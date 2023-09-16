/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settings.fingerprint2.domain.interactor

import android.content.Context
import android.content.Intent
import android.hardware.fingerprint.Fingerprint
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.FingerprintManager.CryptoObject
import android.hardware.fingerprint.FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT
import android.os.CancellationSignal
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import com.android.settings.biometrics.GatekeeperPasswordProvider
import com.android.settings.biometrics.fingerprint2.domain.interactor.FingerprintManagerInteractor
import com.android.settings.biometrics.fingerprint2.domain.interactor.FingerprintManagerInteractorImpl
import com.android.settings.biometrics.fingerprint2.shared.model.FingerprintAuthAttemptViewModel
import com.android.settings.biometrics.fingerprint2.shared.model.FingerprintViewModel
import com.android.settings.biometrics.fingerprint2.ui.enrollment.viewmodel.EnrollReason.FindSensor
import com.android.settings.biometrics.fingerprint2.ui.enrollment.viewmodel.FingerEnrollStateViewModel
import com.android.settings.password.ChooseLockSettingsHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.stubbing.OngoingStubbing

@RunWith(MockitoJUnitRunner::class)
class FingerprintManagerInteractorTest {

  @JvmField @Rule var rule = MockitoJUnit.rule()
  private lateinit var underTest: FingerprintManagerInteractor
  private var context: Context = ApplicationProvider.getApplicationContext()
  private var backgroundDispatcher = StandardTestDispatcher()
  @Mock private lateinit var fingerprintManager: FingerprintManager
  @Mock private lateinit var gateKeeperPasswordProvider: GatekeeperPasswordProvider

  private var testScope = TestScope(backgroundDispatcher)
  private var pressToAuthProvider = { true }

  @Before
  fun setup() {
    underTest =
      FingerprintManagerInteractorImpl(
        context,
        backgroundDispatcher,
        fingerprintManager,
        gateKeeperPasswordProvider,
        pressToAuthProvider,
      )
  }

  @Test
  fun testEmptyFingerprints() =
    testScope.runTest {
      whenever(fingerprintManager.getEnrolledFingerprints(anyInt())).thenReturn(emptyList())

      val emptyFingerprintList: List<Fingerprint> = emptyList()
      assertThat(underTest.enrolledFingerprints.last()).isEqualTo(emptyFingerprintList)
    }

  @Test
  fun testOneFingerprint() =
    testScope.runTest {
      val expected = Fingerprint("Finger 1,", 2, 3L)
      val fingerprintList: List<Fingerprint> = listOf(expected)
      whenever(fingerprintManager.getEnrolledFingerprints(anyInt())).thenReturn(fingerprintList)

      val list = underTest.enrolledFingerprints.last()
      assertThat(list.size).isEqualTo(fingerprintList.size)
      val actual = list[0]
      assertThat(actual.name).isEqualTo(expected.name)
      assertThat(actual.fingerId).isEqualTo(expected.biometricId)
      assertThat(actual.deviceId).isEqualTo(expected.deviceId)
    }

  @Test
  fun testCanEnrollFingerprint() =
    testScope.runTest {
      val fingerprintList1: List<Fingerprint> =
        listOf(
          Fingerprint("Finger 1,", 2, 3L),
          Fingerprint("Finger 2,", 3, 3L),
          Fingerprint("Finger 3,", 4, 3L)
        )
      val fingerprintList2: List<Fingerprint> =
        fingerprintList1.plus(
          listOf(Fingerprint("Finger 4,", 5, 3L), Fingerprint("Finger 5,", 6, 3L))
        )
      whenever(fingerprintManager.getEnrolledFingerprints(anyInt()))
        .thenReturn(fingerprintList1)
        .thenReturn(fingerprintList2)

      assertThat(underTest.canEnrollFingerprints.last()).isTrue()
      assertThat(underTest.canEnrollFingerprints.last()).isFalse()
    }

  @Test
  fun testGenerateChallenge() =
    testScope.runTest {
      val byteArray = byteArrayOf(5, 3, 2)
      val challenge = 100L
      val intent = Intent()
      intent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_GK_PW_HANDLE, challenge)
      whenever(
          gateKeeperPasswordProvider.requestGatekeeperHat(
            any(Intent::class.java),
            anyLong(),
            anyInt()
          )
        )
        .thenReturn(byteArray)

      val generateChallengeCallback: ArgumentCaptor<FingerprintManager.GenerateChallengeCallback> =
        argumentCaptor()

      var result: Pair<Long, ByteArray?>? = null
      val job = testScope.launch { result = underTest.generateChallenge(1L) }
      runCurrent()

      verify(fingerprintManager).generateChallenge(anyInt(), capture(generateChallengeCallback))
      generateChallengeCallback.value.onChallengeGenerated(1, 2, challenge)

      runCurrent()
      job.cancelAndJoin()

      assertThat(result?.first).isEqualTo(challenge)
      assertThat(result?.second).isEqualTo(byteArray)
    }

  @Test
  fun testRemoveFingerprint_succeeds() =
    testScope.runTest {
      val fingerprintViewModelToRemove = FingerprintViewModel("Finger 2", 1, 2L)
      val fingerprintToRemove = Fingerprint("Finger 2", 1, 2L)

      val removalCallback: ArgumentCaptor<FingerprintManager.RemovalCallback> = argumentCaptor()

      var result: Boolean? = null
      val job =
        testScope.launch { result = underTest.removeFingerprint(fingerprintViewModelToRemove) }
      runCurrent()

      verify(fingerprintManager)
        .remove(any(Fingerprint::class.java), anyInt(), capture(removalCallback))
      removalCallback.value.onRemovalSucceeded(fingerprintToRemove, 1)

      runCurrent()
      job.cancelAndJoin()

      assertThat(result).isTrue()
    }

  @Test
  fun testRemoveFingerprint_fails() =
    testScope.runTest {
      val fingerprintViewModelToRemove = FingerprintViewModel("Finger 2", 1, 2L)
      val fingerprintToRemove = Fingerprint("Finger 2", 1, 2L)

      val removalCallback: ArgumentCaptor<FingerprintManager.RemovalCallback> = argumentCaptor()

      var result: Boolean? = null
      val job =
        testScope.launch { result = underTest.removeFingerprint(fingerprintViewModelToRemove) }
      runCurrent()

      verify(fingerprintManager)
        .remove(any(Fingerprint::class.java), anyInt(), capture(removalCallback))
      removalCallback.value.onRemovalError(
        fingerprintToRemove,
        100,
        "Oh no, we couldn't find that one"
      )

      runCurrent()
      job.cancelAndJoin()

      assertThat(result).isFalse()
    }

  @Test
  fun testRenameFingerprint_succeeds() =
    testScope.runTest {
      val fingerprintToRename = FingerprintViewModel("Finger 2", 1, 2L)

      underTest.renameFingerprint(fingerprintToRename, "Woo")

      verify(fingerprintManager).rename(eq(fingerprintToRename.fingerId), anyInt(), safeEq("Woo"))
    }

  @Test
  fun testAuth_succeeds() =
    testScope.runTest {
      val fingerprint = Fingerprint("Woooo", 100, 101L)

      var result: FingerprintAuthAttemptViewModel? = null
      val job = launch { result = underTest.authenticate() }

      val authCallback: ArgumentCaptor<FingerprintManager.AuthenticationCallback> = argumentCaptor()

      runCurrent()

      verify(fingerprintManager)
        .authenticate(
          nullable(CryptoObject::class.java),
          any(CancellationSignal::class.java),
          capture(authCallback),
          nullable(Handler::class.java),
          anyInt()
        )
      authCallback.value.onAuthenticationSucceeded(
        FingerprintManager.AuthenticationResult(null, fingerprint, 1, false)
      )

      runCurrent()
      job.cancelAndJoin()
      assertThat(result).isEqualTo(FingerprintAuthAttemptViewModel.Success(fingerprint.biometricId))
    }

  @Test
  fun testAuth_lockout() =
    testScope.runTest {
      var result: FingerprintAuthAttemptViewModel? = null
      val job = launch { result = underTest.authenticate() }

      val authCallback: ArgumentCaptor<FingerprintManager.AuthenticationCallback> = argumentCaptor()

      runCurrent()

      verify(fingerprintManager)
        .authenticate(
          nullable(CryptoObject::class.java),
          any(CancellationSignal::class.java),
          capture(authCallback),
          nullable(Handler::class.java),
          anyInt()
        )
      authCallback.value.onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "Lockout!!")

      runCurrent()
      job.cancelAndJoin()
      assertThat(result)
        .isEqualTo(
          FingerprintAuthAttemptViewModel.Error(FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "Lockout!!")
        )
    }

  @Test
  fun testEnroll_progress() =
    testScope.runTest {
      val token = byteArrayOf(5, 3, 2)
      var result: FingerEnrollStateViewModel? = null
      val job = launch { underTest.enroll(token, FindSensor).collect { result = it } }
      val enrollCallback: ArgumentCaptor<FingerprintManager.EnrollmentCallback> = argumentCaptor()
      runCurrent()

      verify(fingerprintManager)
        .enroll(
          eq(token),
          any(CancellationSignal::class.java),
          anyInt(),
          capture(enrollCallback),
          eq(FingerprintManager.ENROLL_FIND_SENSOR)
        )
      enrollCallback.value.onEnrollmentProgress(1)
      runCurrent()
      job.cancelAndJoin()

      assertThat(result).isEqualTo(FingerEnrollStateViewModel.EnrollProgress(1))
    }

  @Test
  fun testEnroll_help() =
    testScope.runTest {
      val token = byteArrayOf(5, 3, 2)
      var result: FingerEnrollStateViewModel? = null
      val job = launch { underTest.enroll(token, FindSensor).collect { result = it } }
      val enrollCallback: ArgumentCaptor<FingerprintManager.EnrollmentCallback> = argumentCaptor()
      runCurrent()

      verify(fingerprintManager)
        .enroll(
          eq(token),
          any(CancellationSignal::class.java),
          anyInt(),
          capture(enrollCallback),
          eq(FingerprintManager.ENROLL_FIND_SENSOR)
        )
      enrollCallback.value.onEnrollmentHelp(-1, "help")
      runCurrent()
      job.cancelAndJoin()

      assertThat(result).isEqualTo(FingerEnrollStateViewModel.EnrollHelp(-1, "help"))
    }

  @Test
  fun testEnroll_error() =
    testScope.runTest {
      val token = byteArrayOf(5, 3, 2)
      var result: FingerEnrollStateViewModel? = null
      val job = launch { underTest.enroll(token, FindSensor).collect { result = it } }
      val enrollCallback: ArgumentCaptor<FingerprintManager.EnrollmentCallback> = argumentCaptor()
      runCurrent()

      verify(fingerprintManager)
        .enroll(
          eq(token),
          any(CancellationSignal::class.java),
          anyInt(),
          capture(enrollCallback),
          eq(FingerprintManager.ENROLL_FIND_SENSOR)
        )
      enrollCallback.value.onEnrollmentError(-2, "error")
      runCurrent()
      job.cancelAndJoin()

      assertThat(result).isEqualTo(FingerEnrollStateViewModel.EnrollError(-2, "error"))
    }

  private fun <T : Any> safeEq(value: T): T = eq(value) ?: value
  private fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
  private fun <T> any(type: Class<T>): T = Mockito.any<T>(type)
  private fun <T> whenever(methodCall: T): OngoingStubbing<T> = `when`(methodCall)
  inline fun <reified T : Any> argumentCaptor(): ArgumentCaptor<T> =
    ArgumentCaptor.forClass(T::class.java)
}
