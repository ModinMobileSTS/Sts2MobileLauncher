package com.godot.game.steam.auth

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE, sdk = [35], application = Application::class,
    shadows = [SteamAuthPreferencesShadow::class, com.godot.game.AndroidLinuxTestShadow::class],
    instrumentedPackages = ["com.godot.game.steam.auth", "top.apricityx.workshop.steam.protocol"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class SteamAuthDeadlineTest {
    private lateinit var fixture: SteamAuthTestFixture

    @Before fun setUp() {
        SteamAuthStore.clear(RuntimeEnvironment.getApplication())
        SteamAuthTestFixture().use { it.complete("saved-account") }
        fixture = SteamAuthTestFixture()
    }

    @After fun tearDown() {
        fixture.close()
        SteamAuthStore.clear(RuntimeEnvironment.getApplication())
    }

    @Test fun deviceCodeWaitExpiresWithoutAnyUserOrStoreRead() {
        assertCodeWaitExpires(SteamGuardChallengeType.DeviceCode)
    }

    @Test fun emailCodeWaitExpiresWithoutAnyUserOrStoreRead() {
        assertCodeWaitExpires(SteamGuardChallengeType.EmailCode)
    }

    @Test fun cancelAfterDeadlineTerminatesEvenWhenItsReadRemovesTheHandle() {
        fixture.begin()
        ShadowSystemClock.advanceBy(Duration.ofMillis(SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS))
        // Do not dispatch the main-looper deadline yet: the notification/dialog action wins first.
        fixture.binder.cancel()
        fixture.drain()
        assertTerminated(SteamAuthForegroundService.Stage.CANCELLED)
    }

    @Test fun cancelAfterAnotherReaderRemovedExpiredPendingStillTerminates() {
        val pending = fixture.begin()
        ShadowSystemClock.advanceBy(Duration.ofMillis(SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS))
        assertEquals(pending.transactionId, SteamAuthStore.readPendingAuthTransaction(fixture.service)?.transactionId)
        assertNull(SteamAuthStore.readPendingAuthTransaction(fixture.service))

        fixture.binder.cancel()
        fixture.drain()

        assertTerminated(SteamAuthForegroundService.Stage.CANCELLED)
    }

    @Test fun deadlineStillTerminatesAfterAnotherReaderRemovedExpiredPending() {
        fixture.begin()
        ShadowSystemClock.advanceBy(Duration.ofMillis(SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS))
        assertNotNull(SteamAuthStore.readPendingAuthTransaction(fixture.service))
        assertNull(SteamAuthStore.readPendingAuthTransaction(fixture.service))

        shadowOf(Looper.getMainLooper()).idle()

        assertTerminated(SteamAuthForegroundService.Stage.EXPIRED)
    }

    @Test fun cancelledTransactionsDeadlineCannotExpireItsReplacement() {
        val old = fixture.begin("old-attempt")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        fixture.binder.cancel()
        fixture.drain()
        val current = fixture.begin("new-attempt")
        assertNotEquals(old.transactionId, current.transactionId)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))

        assertEquals(SteamAuthForegroundService.Stage.WAITING_CODE, fixture.binder.getSnapshot().stage)
        assertEquals(current.transactionId, SteamAuthStore.readPendingAuthTransaction(fixture.service)?.transactionId)
        assertFalse(fixture.sessions.last().closed)
        assertSavedAccount()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTerminated(SteamAuthForegroundService.Stage.EXPIRED)
    }

    @Test fun serviceRecreationResumesTheOriginalDeadlineRatherThanExtendingIt() {
        val original = fixture.begin()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        fixture.close()
        fixture = SteamAuthTestFixture()
        fixture.resume()
        assertEquals(SteamAuthForegroundService.Stage.WAITING_CODE, fixture.binder.getSnapshot().stage)
        assertEquals(original.transactionId, fixture.binder.getSnapshot().transactionId)
        assertEquals(original.expiresAtEpochMillis, fixture.binder.getSnapshot().deadlineEpochMillis)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(179_999L))
        assertEquals(SteamAuthForegroundService.Stage.WAITING_CODE, fixture.binder.getSnapshot().stage)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
        assertTerminated(SteamAuthForegroundService.Stage.EXPIRED)
    }

    @Test fun successfulTokenCommitWinsCancelAndDeadlineBeforeSuccessPublication() {
        val handle = fixture.begin("completed-account")
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Manager closes the session after the store CAS, before returning the completion to Service.
        fixture.sessions.last().onClose = {
            committed.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            fixture.binder.submitGuardCode(handle.transactionId, SteamGuardChallengeType.DeviceCode, "12345")
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            assertEquals("completed-account", SteamAuthStore.readAuthMaterial(fixture.service)?.accountName)
            assertNull(SteamAuthStore.readPendingAuthTransaction(fixture.service))

            fixture.binder.cancel()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        } finally {
            release.countDown()
        }
        fixture.drain()

        assertEquals(SteamAuthForegroundService.Stage.SUCCESS, fixture.binder.getSnapshot().stage)
        assertEquals("synthetic-token-completed-account", SteamAuthStore.readAuthMaterial(fixture.service)?.refreshToken)
        assertFalse(fixture.binder.getSnapshot().isActive)
        assertTrue(fixture.sessions.last().closed)
        assertNotificationRemoved()
    }

    @Test fun completedTransactionsDeadlineCannotChangeSuccess() {
        fixture.complete("completed-account")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertEquals(SteamAuthForegroundService.Stage.SUCCESS, fixture.binder.getSnapshot().stage)
        assertEquals("completed-account", SteamAuthStore.readAuthMaterial(fixture.service)?.accountName)
        assertNotificationRemoved()
    }

    private fun assertCodeWaitExpires(type: SteamGuardChallengeType) {
        fixture.challengeType = type
        val handle = fixture.begin()
        assertEquals(SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS, handle.expiresAtEpochMillis - handle.createdAtEpochMillis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS - 1L))
        assertEquals(SteamAuthForegroundService.Stage.WAITING_CODE, fixture.binder.getSnapshot().stage)
        assertFalse(fixture.sessions.last().closed)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))

        assertTerminated(SteamAuthForegroundService.Stage.EXPIRED)
    }

    private fun assertTerminated(stage: SteamAuthForegroundService.Stage) {
        assertEquals(stage, fixture.binder.getSnapshot().stage)
        assertFalse(fixture.binder.getSnapshot().isActive)
        assertNull(SteamAuthStore.readPendingAuthTransaction(fixture.service))
        assertTrue(fixture.sessions.last().closed)
        assertTrue(shadowOf(fixture.service).isStoppedBySelf)
        assertNotificationRemoved()
        assertSavedAccount()
    }

    private fun assertNotificationRemoved() {
        assertTrue(fixture.service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertTrue(shadowOf(fixture.service).isForegroundStopped)
    }

    private fun assertSavedAccount() {
        val saved = SteamAuthStore.readAuthMaterial(fixture.service)
        assertEquals("saved-account", saved?.accountName)
        assertEquals("synthetic-token-saved-account", saved?.refreshToken)
        assertEquals("22", SteamAuthStore.readSnapshot(fixture.service).steamId64)
    }
}
