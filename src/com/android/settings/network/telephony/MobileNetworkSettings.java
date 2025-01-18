/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Changes from Qualcomm Innovation Center, Inc. are provided under the following license:
 * Copyright (c) 2022-2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.settings.network.telephony;

import static com.android.settings.network.MobileNetworkListFragment.collectAirplaneModeAndFinishIfOn;

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.ims.feature.ImsFeature.FEATURE_MMTEL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;

import static com.qti.extphone.ExtPhoneCallbackListener.EVENT_ON_CIWLAN_CONFIG_CHANGE;

import android.app.Activity;
import android.app.settings.SettingsEnums;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.Settings.MobileNetworkActivity;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.datausage.BillingCyclePreferenceController;
import com.android.settings.datausage.DataUsageSummaryPreferenceController;
import com.android.settings.network.CarrierWifiTogglePreferenceController;
import com.android.settings.network.MobileNetworkRepository;
import com.android.settings.network.SubscriptionUtil;
import com.android.settings.network.telephony.cdma.CdmaSubscriptionPreferenceController;
import com.android.settings.network.telephony.cdma.CdmaSystemSelectPreferenceController;
import com.android.settings.network.telephony.gsm.AutoSelectPreferenceController;
import com.android.settings.network.telephony.gsm.OpenNetworkSelectPagePreferenceController;
import com.android.settings.network.telephony.gsm.SelectNetworkPreferenceController;
import com.android.settings.network.telephony.wificalling.CrossSimCallingViewModel;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.wifi.WifiPickerTrackerHelper;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.mobile.dataservice.MobileNetworkInfoEntity;
import com.android.settingslib.mobile.dataservice.SubscriptionInfoEntity;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.utils.ThreadUtils;

import com.qti.extphone.CiwlanConfig;
import com.qti.extphone.Client;
import com.qti.extphone.ExtPhoneCallbackListener;
import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.ServiceCallback;

import java.lang.Runnable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SearchIndexable(forTarget = SearchIndexable.ALL & ~SearchIndexable.ARC)
public class MobileNetworkSettings extends AbstractMobileNetworkSettings implements
        MobileNetworkRepository.MobileNetworkCallback {

    private static final String LOG_TAG = "NetworkSettings";
    public static final int REQUEST_CODE_EXIT_ECM = 17;
    public static final int REQUEST_CODE_DELETE_SUBSCRIPTION = 18;
    @VisibleForTesting
    static final String KEY_CLICKED_PREF = "key_clicked_pref";

    private static final String KEY_ROAMING_PREF = "button_roaming_key";
    private static final String KEY_DATA_PREF = "data_preference";
    private static final String KEY_CALLS_PREF = "calls_preference";
    private static final String KEY_SMS_PREF = "sms_preference";
    private static final String KEY_MOBILE_DATA_PREF = "mobile_data_enable";
    private static final String KEY_CONVERT_TO_ESIM_PREF = "convert_to_esim";
    private static final String KEY_EID_KEY = "network_mode_eid_info";

    // UICC provisioning status
    public static final int CARD_NOT_PROVISIONED = 0;
    public static final int CARD_PROVISIONED = 1;

    //String keys for preference lookup
    private static final String BUTTON_CDMA_SYSTEM_SELECT_KEY = "cdma_system_select_key";
    private static final String BUTTON_CDMA_SUBSCRIPTION_KEY = "cdma_subscription_key";

    private static final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private static TelephonyManager mTelephonyManager;
    private static int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private CdmaSystemSelectPreferenceController mCdmaSystemSelectPreferenceController;
    private CdmaSubscriptionPreferenceController mCdmaSubscriptionPreferenceController;

    private UserManager mUserManager;
    private String mClickedPrefKey;

    private MobileNetworkRepository mMobileNetworkRepository;
    private List<SubscriptionInfoEntity> mSubInfoEntityList = new ArrayList<>();
    @Nullable
    private SubscriptionInfoEntity mSubscriptionInfoEntity;
    private MobileNetworkInfoEntity mMobileNetworkInfoEntity;

    private static ImsManager sImsMgr;
    private static String sPackageName;
    private static SparseArray<CiwlanConfig> sCiwlanConfig = new SparseArray();
    private static boolean sExtTelServiceConnected = false;
    private static Client sClient;
    private static ExtTelephonyManager sExtTelephonyManager;
    private static SubscriptionManager sSubscriptionManager;
    private static boolean sIsMsimCiwlanSupported = false;
    private static int sInstanceCounter = 0;
    private static final ServiceCallback mExtTelServiceCallback = new ServiceCallback() {
        @Override
        public void onConnected() {
            Log.d(LOG_TAG, "ExtTelephony service connected");
            sExtTelServiceConnected = true;
            int[] events = new int[] {EVENT_ON_CIWLAN_CONFIG_CHANGE};
            sClient = sExtTelephonyManager.registerCallbackWithEvents(sPackageName,
                    mExtPhoneCallbackListener, events);
            sIsMsimCiwlanSupported = sExtTelephonyManager.isFeatureSupported(
                    ExtTelephonyManager.FEATURE_CIWLAN_MODE_PREFERENCE);
            Log.d(LOG_TAG, "Client = " + sClient);
            getCiwlanConfig();
        }

        @Override
        public void onDisconnected() {
            Log.d(LOG_TAG, "ExtTelephony service disconnected");
            sExtTelephonyManager.unregisterCallback(mExtPhoneCallbackListener);
            sExtTelServiceConnected = false;
            sClient = null;
        }
    };

    private static ExtPhoneCallbackListener mExtPhoneCallbackListener =
            new ExtPhoneCallbackListener() {
        @Override
        public void onCiwlanConfigChange(int slotId, CiwlanConfig ciwlanConfig) {
           Log.d(LOG_TAG, "onCiwlanConfigChange: slotId = " + slotId + ", config = " +
                   ciwlanConfig);
           int subId = SubscriptionManager.getSubscriptionId(slotId);
           sCiwlanConfig.put(subId, ciwlanConfig);
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED
                    .equals(intent.getAction())) {
                ThreadUtils.postOnMainThread(() -> {
                    redrawPreferenceControllers();
                });
            }
        }
    };

    private static CiwlanConfig getCiwlanConfig(int... subscriptionId) {
        // If subscriptionId is passed in, return the config belonging to that subId. Otherwise,
        // query the config for all active subscriptions.
        if (subscriptionId.length != 0 && sCiwlanConfig != null) {
            return sCiwlanConfig.get(subscriptionId[0]);
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // Query the C_IWLAN config of all active subscriptions
                int[] activeSubIdList = sSubscriptionManager.getActiveSubscriptionIdList();
                for (int i = 0; i < activeSubIdList.length; i++) {
                    try {
                        int subId = activeSubIdList[i];
                        sCiwlanConfig.put(subId, sExtTelephonyManager.getCiwlanConfig(
                                SubscriptionManager.getSlotIndex(subId)));
                    } catch (RemoteException ex) {
                        Log.e(LOG_TAG, "getCiwlanConfig exception", ex);
                    }
                }
            }
        });
        return null;
    }

    static boolean isCiwlanEnabled(int subId) {
        ImsMmTelManager imsMmTelMgr = getImsMmTelManager(subId);
        if (imsMmTelMgr == null) {
            return false;
        }
        try {
            return imsMmTelMgr.isCrossSimCallingEnabled();
        } catch (ImsException exception) {
            Log.e(LOG_TAG, "Failed to get C_IWLAN toggle status", exception);
        }
        return false;
    }

    private static ImsMmTelManager getImsMmTelManager(int subId) {
        if (!SubscriptionManager.isUsableSubscriptionId(subId)) {
            Log.d(LOG_TAG, "getImsMmTelManager: subId unusable");
            return null;
        }
        if (sImsMgr == null) {
            Log.d(LOG_TAG, "getImsMmTelManager: ImsManager null");
            return null;
        }
        return sImsMgr.getImsMmTelManager(subId);
    }

    static boolean isInCiwlanOnlyMode(int subId) {
        if (sCiwlanConfig == null) {
            Log.d(LOG_TAG, "isInCiwlanOnlyMode: C_IWLAN config map null");
            return false;
        }
        CiwlanConfig config = sCiwlanConfig.get(subId);
        if (config != null) {
            if (isRoaming(subId)) {
                return config.isCiwlanOnlyInRoam();
            }
            return config.isCiwlanOnlyInHome();
        } else {
            Log.d(LOG_TAG, "isInCiwlanOnlyMode: C_IWLAN config null for subId " + subId);
            return false;
        }
    }

    static boolean isCiwlanModeSupported(int subId) {
        if (sCiwlanConfig == null) {
            Log.d(LOG_TAG, "isCiwlanModeSupported: C_IWLAN config map null");
            return false;
        }
        CiwlanConfig config = sCiwlanConfig.get(subId);
        if (config != null) {
            return config.isCiwlanModeSupported();
        } else {
            Log.d(LOG_TAG, "isCiwlanModeSupported: C_IWLAN config null for subId " + subId);
            return false;
        }
    }

    static boolean isImsRegisteredOnCiwlan(int subId) {
        if (mTelephonyManager == null) {
            Log.d(LOG_TAG, "isImsRegisteredOnCiwlan: TelephonyManager null");
            return false;
        }
        TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
        IImsRegistration imsRegistrationImpl = tm.getImsRegistration(
                SubscriptionManager.getSlotIndex(subId), FEATURE_MMTEL);
        if (imsRegistrationImpl != null) {
            try {
                return imsRegistrationImpl.getRegistrationTechnology() ==
                        REGISTRATION_TECH_CROSS_SIM;
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, "getRegistrationTechnology failed", ex);
            }
        }
        return false;
    }

    static boolean isMsimCiwlanSupported() {
        Log.i(LOG_TAG, "isMsimCiwlanSupported = " + sIsMsimCiwlanSupported);
        return sIsMsimCiwlanSupported;
    }

    static boolean isRoaming(int subId) {
        if (mTelephonyManager == null) {
            Log.d(LOG_TAG, "isRoaming: TelephonyManager null");
            return false;
        }
        TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
        boolean nriRoaming = false;
        ServiceState serviceState = tm.getServiceState();
        if (serviceState != null) {
            NetworkRegistrationInfo nri =
                    serviceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN);
            if (nri != null) {
                nriRoaming = nri.isNetworkRoaming();
            } else {
                Log.d(LOG_TAG, "isRoaming: network registration info null");
            }
        } else {
            Log.d(LOG_TAG, "isRoaming: service state null");
        }
        return nriRoaming;
    }

    static int getNonDefaultDataSub() {
        final int DDS = SubscriptionManager.getDefaultDataSubscriptionId();
        int nDDS = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int[] activeSubIdList = sSubscriptionManager.getActiveSubscriptionIdList();
        for (int i = 0; i < activeSubIdList.length; i++) {
            if (activeSubIdList[i] != DDS) {
                nDDS = activeSubIdList[i];
            }
        }
        return nDDS;
    }

    public MobileNetworkSettings() {
        super(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.MOBILE_NETWORK;
    }

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (super.onPreferenceTreeClick(preference)) {
            return true;
        }
        final String key = preference.getKey();

        if (mTelephonyManager == null) {
            return false;
        }
        if (TextUtils.equals(key, BUTTON_CDMA_SYSTEM_SELECT_KEY)
                || TextUtils.equals(key, BUTTON_CDMA_SUBSCRIPTION_KEY)) {
            if (mTelephonyManager.getEmergencyCallbackMode()) {
                startActivityForResult(
                        new Intent(TelephonyManager.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null)
                                .setPackage(Utils.PHONE_PACKAGE_NAME),
                        REQUEST_CODE_EXIT_ECM);
                mClickedPrefKey = key;
            }
            return true;
        }

        return false;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        if (!SubscriptionUtil.isSimHardwareVisible(context)) {
            finish();
            return Arrays.asList();
        }
        if (getArguments() == null) {
            Intent intent = getIntent();
            if (intent != null) {
                mSubId = intent.getIntExtra(Settings.EXTRA_SUB_ID,
                        MobileNetworkUtils.getSearchableSubscriptionId(context));
                Log.d(LOG_TAG, "display subId from intent: " + mSubId);
            } else {
                Log.d(LOG_TAG, "intent is null, can not get subId " + mSubId + " from intent.");
            }
        } else {
            mSubId = getArguments().getInt(Settings.EXTRA_SUB_ID,
                    MobileNetworkUtils.getSearchableSubscriptionId(context));
            Log.d(LOG_TAG, "display subId from getArguments(): " + mSubId);
        }
        Log.i(LOG_TAG, "display subId: " + mSubId);

        mMobileNetworkRepository = MobileNetworkRepository.getInstance(context);
        mExecutor.execute(() -> {
            mSubscriptionInfoEntity = mMobileNetworkRepository.getSubInfoById(
                    String.valueOf(mSubId));
            mMobileNetworkInfoEntity =
                    mMobileNetworkRepository.queryMobileNetworkInfoBySubId(
                            String.valueOf(mSubId));
        });

        MobileNetworkEidPreferenceController eid = new MobileNetworkEidPreferenceController(context,
                KEY_EID_KEY);
        eid.init(this, mSubId);

        return Arrays.asList(
                new DataUsageSummaryPreferenceController(context, mSubId),
                new RoamingPreferenceController(context, KEY_ROAMING_PREF, getSettingsLifecycle(),
                        this, mSubId),
                new DataDefaultSubscriptionController(context, KEY_DATA_PREF,
                        getSettingsLifecycle(), this),
                new CallsDefaultSubscriptionController(context, KEY_CALLS_PREF,
                        getSettingsLifecycle(), this),
                new SmsDefaultSubscriptionController(context, KEY_SMS_PREF, getSettingsLifecycle(),
                        this),
                new MobileDataPreferenceController(context, KEY_MOBILE_DATA_PREF,
                        getSettingsLifecycle(), this, mSubId),
                new ConvertToEsimPreferenceController(context, KEY_CONVERT_TO_ESIM_PREF,
                        getSettingsLifecycle(), this, mSubId), eid);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.d(LOG_TAG, "Invalid subId, get the default subscription to show.");
            SubscriptionInfo info = SubscriptionUtil.getSubscriptionOrDefault(context, mSubId);
            if (info == null) {
                Log.d(LOG_TAG, "Invalid subId request " + mSubId);
                return;
            }
            mSubId = info.getSubscriptionId();
            Log.d(LOG_TAG, "Show NetworkSettings fragment for subId" + mSubId);
        }

        sImsMgr = context.getSystemService(ImsManager.class);

        // Connect TelephonyUtils to ExtTelephonyService
        TelephonyUtils.connectExtTelephonyService(context);

        Intent intent = getIntent();
        if (intent != null) {
            int updateSubscriptionIndex = intent.getIntExtra(Settings.EXTRA_SUB_ID,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);

            if (updateSubscriptionIndex != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                int oldSubId = mSubId;
                mSubId = updateSubscriptionIndex;
                // If the subscription has changed or the new intent does not contain the opt in
                // action,
                // remove the old discovery dialog. If the activity is being recreated, we will see
                // onCreate -> onNewIntent, so the dialog will first be recreated for the old
                // subscription
                // and then removed.
                if (updateSubscriptionIndex != oldSubId
                        || !MobileNetworkActivity.doesIntentContainOptInAction(intent)) {
                    removeContactDiscoveryDialog(oldSubId);
                }

                // evaluate showing the new discovery dialog if this intent contains an action to
                // show the
                // opt-in.
                if (MobileNetworkActivity.doesIntentContainOptInAction(intent)) {
                    showContactDiscoveryDialog();
                }
            }

        }

        use(MobileNetworkSwitchController.class).init(mSubId);
        use(CarrierSettingsVersionPreferenceController.class).init(mSubId);
        use(BillingCyclePreferenceController.class).init(mSubId);
        use(MmsMessagePreferenceController.class).init(mSubId);
        use(DataDuringCallsPreferenceController.class).init(mSubId);
        final var crossSimCallingViewModel =
                new ViewModelProvider(this).get(CrossSimCallingViewModel.class);
        use(AutoDataSwitchPreferenceController.class).init(mSubId, crossSimCallingViewModel);
        use(DisabledSubscriptionController.class).init(mSubId);
        use(DeleteSimProfilePreferenceController.class).init(mSubId);
        use(DisableSimFooterPreferenceController.class).init(mSubId);
        use(NrDisabledInDsdsFooterPreferenceController.class).init(mSubId);

        use(MobileNetworkSpnPreferenceController.class).init(this, mSubId);
        use(MobileNetworkPhoneNumberPreferenceController.class).init(this, mSubId);
        use(MobileNetworkImeiPreferenceController.class).init(this, mSubId);

        final MobileDataPreferenceController mobileDataPreferenceController =
                use(MobileDataPreferenceController.class);
        if (mobileDataPreferenceController != null) {
            mobileDataPreferenceController.init(getFragmentManager(), mSubId,
                    mSubscriptionInfoEntity, mMobileNetworkInfoEntity);
            mobileDataPreferenceController.setWifiPickerTrackerHelper(
                    new WifiPickerTrackerHelper(getSettingsLifecycle(), context,
                            null /* WifiPickerTrackerCallback */));
        }

        final RoamingPreferenceController roamingPreferenceController =
                use(RoamingPreferenceController.class);
        if (roamingPreferenceController != null) {
            roamingPreferenceController.init(getFragmentManager(), mSubId,
                    mMobileNetworkInfoEntity);
        }
        final SatelliteSettingPreferenceController satelliteSettingPreferenceController = use(
                SatelliteSettingPreferenceController.class);
        if (satelliteSettingPreferenceController != null) {
            satelliteSettingPreferenceController.init(mSubId);
        }
        use(ApnPreferenceController.class).init(mSubId);
        use(UserPLMNPreferenceController.class).init(mSubId);
        use(CarrierPreferenceController.class).init(mSubId);
        use(DataUsagePreferenceController.class).init(mSubId);
        use(PreferredNetworkModePreferenceController.class).init(getLifecycle(), mSubId);
        use(EnabledNetworkModePreferenceController.class).init(mSubId, getParentFragmentManager());
        use(DataServiceSetupPreferenceController.class).init(mSubId);
        use(Enable2gPreferenceController.class).init(mSubId);
        use(CarrierWifiTogglePreferenceController.class).init(getLifecycle(), mSubId);

        final CallingPreferenceCategoryController callingPreferenceCategoryController =
                use(CallingPreferenceCategoryController.class);
        use(WifiCallingPreferenceController.class)
                .init(mSubId, callingPreferenceCategoryController);

        final OpenNetworkSelectPagePreferenceController openNetworkSelectPagePreferenceController =
                use(OpenNetworkSelectPagePreferenceController.class).init(mSubId);
        final AutoSelectPreferenceController autoSelectPreferenceController =
                use(AutoSelectPreferenceController.class)
                        .init(mSubId)
                        .addListener(openNetworkSelectPagePreferenceController);

        final SelectNetworkPreferenceController selectNetworkPreferenceController =
                use(SelectNetworkPreferenceController.class)
                        .init(mSubId)
                        .addListener(autoSelectPreferenceController);

        use(NetworkPreferenceCategoryController.class).init(mSubId)
                .setChildren(Arrays.asList(autoSelectPreferenceController));
        mCdmaSystemSelectPreferenceController = use(CdmaSystemSelectPreferenceController.class);
        mCdmaSystemSelectPreferenceController.init(getPreferenceManager(), mSubId);
        mCdmaSubscriptionPreferenceController = use(CdmaSubscriptionPreferenceController.class);
        mCdmaSubscriptionPreferenceController.init(getPreferenceManager(), mSubId);

        final VideoCallingPreferenceController videoCallingPreferenceController =
                use(VideoCallingPreferenceController.class)
                        .init(mSubId, callingPreferenceCategoryController);
        final BackupCallingPreferenceController crossSimCallingPreferenceController =
                use(BackupCallingPreferenceController.class)
                        .init(getFragmentManager(), mSubId, callingPreferenceCategoryController);
        use(Enabled5GPreferenceController.class).init(mSubId);
        use(Enhanced4gLtePreferenceController.class).init(mSubId)
                .addListener(videoCallingPreferenceController);
        use(Enhanced4gCallingPreferenceController.class).init(mSubId)
                .addListener(videoCallingPreferenceController);
        use(Enhanced4gAdvancedCallingPreferenceController.class).init(mSubId)
                .addListener(videoCallingPreferenceController);
        use(ContactDiscoveryPreferenceController.class).init(getParentFragmentManager(), mSubId);
        use(NrAdvancedCallingPreferenceController.class).init(mSubId);
        use(TransferEsimPreferenceController.class).init(mSubId, mSubscriptionInfoEntity);
        final ConvertToEsimPreferenceController convertToEsimPreferenceController =
                use(ConvertToEsimPreferenceController.class);
        if (convertToEsimPreferenceController != null) {
            convertToEsimPreferenceController.init(mSubId, mSubscriptionInfoEntity);
        }

        List<AbstractSubscriptionPreferenceController> subscriptionPreferenceControllers =
                useAll(AbstractSubscriptionPreferenceController.class);
        for (AbstractSubscriptionPreferenceController controller :
                subscriptionPreferenceControllers) {
            controller.init(mSubId);
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        Log.i(LOG_TAG, "onCreate:+");

        final TelephonyStatusControlSession session =
                setTelephonyAvailabilityStatus(getPreferenceControllersAsList());

        super.onCreate(icicle);
        if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            Log.i(LOG_TAG, "onCreate: invalid subId. finish");
            session.close();
            finish();
            return;
        }
        final Context context = getContext();
        sPackageName = this.getClass().getPackage().toString();
        sSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mTelephonyManager = context.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId);
        sExtTelephonyManager = ExtTelephonyManager.getInstance(context);
        sExtTelephonyManager.connectService(mExtTelServiceCallback);
        sInstanceCounter++;

        session.close();

        onRestoreInstance(icicle);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        collectAirplaneModeAndFinishIfOn(this);
    }

    @Override
    public void onResume() {
        Log.i(LOG_TAG, "onResume:+");
        super.onResume();
        mMobileNetworkRepository.addRegister(this, this, mSubId);
        mMobileNetworkRepository.updateEntity();
        // TODO: remove log after fixing b/182326102
        Log.d(LOG_TAG, "onResume() subId=" + mSubId);
        getActivity().registerReceiver(mBroadcastReceiver,
                new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    private void onSubscriptionDetailChanged() {
        final SubscriptionInfoEntity subscriptionInfoEntity = mSubscriptionInfoEntity;
        if (subscriptionInfoEntity == null) {
            return;
        }
        ThreadUtils.postOnMainThread(() -> {
            if (getActivity() instanceof SettingsActivity activity && !activity.isFinishing()) {
                // Update the title when SIM stats got changed
                activity.setTitle(subscriptionInfoEntity.uniqueName);
            }
            redrawPreferenceControllers();
        });
    }

    @Override
    public void onPause() {
        mMobileNetworkRepository.removeRegister(this);
        super.onPause();
        getActivity().unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "onDestroy: sExtTelServiceConnected = " + sExtTelServiceConnected
                + " , sInstanceCounter = " + sInstanceCounter);
        if (sInstanceCounter > 0) {
            sInstanceCounter--;
        }
        if ((sInstanceCounter == 0) && (sExtTelephonyManager != null) && sExtTelServiceConnected) {
            Log.i(LOG_TAG, "onDestroy");
            sExtTelephonyManager.disconnectService(mExtTelServiceCallback);
            sExtTelephonyManager = null;
        }
        super.onDestroy();
    }

    @VisibleForTesting
    void onRestoreInstance(Bundle icicle) {
        if (icicle != null) {
            mClickedPrefKey = icicle.getString(KEY_CLICKED_PREF);
        }
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.mobile_network_settings;
    }

    @Override
    protected String getLogTag() {
        return LOG_TAG;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_CLICKED_PREF, mClickedPrefKey);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_EXIT_ECM:
                if (resultCode != Activity.RESULT_CANCELED) {
                    // If the phone exits from ECM mode, show the CDMA
                    final Preference preference = getPreferenceScreen()
                            .findPreference(mClickedPrefKey);
                    if (preference != null) {
                        preference.performClick();
                    }
                }
                break;

            case REQUEST_CODE_DELETE_SUBSCRIPTION:
                if (resultCode != Activity.RESULT_CANCELED) {
                    final Activity activity = getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.finish();
                    }
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            final MenuItem item = menu.add(Menu.NONE, R.id.edit_sim_name, Menu.NONE,
                    R.string.mobile_network_sim_name);
            item.setIcon(com.android.internal.R.drawable.ic_mode_edit);
            item.setIconTintList(ColorStateList.valueOf(
                com.android.settingslib.Utils.getColorAttrDefaultColor(getContext(),
                    android.R.attr.colorControlNormal)));
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (menuItem.getItemId() == R.id.edit_sim_name) {
                RenameMobileNetworkDialogFragment.newInstance(mSubId).show(
                        getFragmentManager(), RenameMobileNetworkDialogFragment.TAG);
                return true;
            }
        }
        return super.onOptionsItemSelected(menuItem);
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.mobile_network_settings) {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                        boolean enabled) {
                    return super.getXmlResourcesToIndex(context, enabled);
                }

                /** suppress full page if user is not admin */
                @Override
                protected boolean isPageSearchEnabled(Context context) {
                    boolean isAirplaneOff = Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.AIRPLANE_MODE_ON, 0) == 0;
                    return isAirplaneOff && SubscriptionUtil.isSimHardwareVisible(context)
                            && context.getSystemService(UserManager.class).isAdminUser();
                }
            };

    private ContactDiscoveryDialogFragment getContactDiscoveryFragment(int subId) {
        // In the case that we are rebuilding this activity after it has been destroyed and
        // recreated, look up the dialog in the fragment manager.
        return (ContactDiscoveryDialogFragment) getChildFragmentManager()
                .findFragmentByTag(ContactDiscoveryDialogFragment.getFragmentTag(subId));
    }


    private void removeContactDiscoveryDialog(int subId) {
        ContactDiscoveryDialogFragment fragment = getContactDiscoveryFragment(subId);
        if (fragment != null) {
            fragment.dismiss();
        }
    }

    private void showContactDiscoveryDialog() {
        ContactDiscoveryDialogFragment fragment = getContactDiscoveryFragment(mSubId);

        if (mSubscriptionInfoEntity == null) {
            Log.d(LOG_TAG, "showContactDiscoveryDialog, Invalid subId request " + mSubId);
            onDestroy();
            return;
        }

        if (fragment == null) {
            fragment = ContactDiscoveryDialogFragment.newInstance(mSubId,
                    mSubscriptionInfoEntity.uniqueName);
        }
        // Only try to show the dialog if it has not already been added, otherwise we may
        // accidentally add it multiple times, causing multiple dialogs.
        if (!fragment.isAdded()) {
            fragment.show(getChildFragmentManager(),
                    ContactDiscoveryDialogFragment.getFragmentTag(mSubId));
        }
    }

    @Override
    public void onAvailableSubInfoChanged(List<SubscriptionInfoEntity> subInfoEntityList) {
        mSubInfoEntityList = subInfoEntityList;
        SubscriptionInfoEntity[] entityArray = mSubInfoEntityList.toArray(
                new SubscriptionInfoEntity[0]);
        mSubscriptionInfoEntity = null;
        for (SubscriptionInfoEntity entity : entityArray) {
            int subId = Integer.parseInt(entity.subId);
            if (subId == mSubId) {
                mSubscriptionInfoEntity = entity;
                Log.d(LOG_TAG, "Set subInfo for subId " + mSubId);
                break;
            } else if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && entity.isDefaultSubscriptionSelection) {
                mSubscriptionInfoEntity = entity;
                Log.d(LOG_TAG, "Set subInfo to default subInfo.");
            }
        }
        if (mSubscriptionInfoEntity == null && getActivity() != null) {
            // If the current subId is not existed, finish it.
            finishFragment();
            return;
        }
        onSubscriptionDetailChanged();
    }
}
