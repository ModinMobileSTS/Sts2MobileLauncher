package com.godot.game.steam.auth

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.godot.game.R
import com.godot.game.SteamAccountActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE, sdk = [35], application = Application::class,
    shadows = [SteamAuthPreferencesShadow::class, com.godot.game.AndroidLinuxTestShadow::class],
    instrumentedPackages = ["com.godot.game.steam.auth"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class SteamAuthTerminalActivityTest {
    private lateinit var controller: ActivityController<SteamAccountActivity>
    private lateinit var activity: SteamAccountActivity
    private lateinit var profileName: TextView
    private lateinit var fixture: SteamAuthTestFixture
    private lateinit var connection: ServiceConnection
    private lateinit var listener: SteamAuthForegroundService.Listener
    private var connected = false

    @Before fun setUp() {
        SteamAuthStore.clear(RuntimeEnvironment.getApplication())
        // Use the actual Activity's binding callbacks and terminal rendering with real Views, but
        // omit onCreate's unrelated account/download/cloud setup and safety-notice dialog.
        controller = Robolectric.buildActivity(SteamAccountActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.Theme_Sts2ExtraSettings)
        val content = FrameLayout(activity)
        profileName = TextView(activity)
        content.addView(profileName)
        activity.setContentView(content)
        SteamAuthTestFixture.setField(activity, "profileNameView", profileName)
        connection = SteamAuthTestFixture.getField(activity, "steamAuthConnection") as ServiceConnection
        listener = SteamAuthTestFixture.getField(activity, "steamAuthListener") as SteamAuthForegroundService.Listener
        controller.visible()
        fixture = SteamAuthTestFixture()
    }

    @After fun tearDown() {
        disconnect()
        fixture.close()
        // onCreate was deliberately not run; detach the window without destroying an uncreated FragmentManager.
        activity.windowManager.removeViewImmediate(activity.window.decorView)
        SteamAuthStore.clear(RuntimeEnvironment.getApplication())
    }

    @Test fun differentServiceFailuresWithEqualRevisionBothShowTheirResult() {
        failLogin()
        val firstRevision = fixture.binder.getSnapshot().revision
        connect()
        assertMessage(fixture.binder.getSnapshot().message)
        expireMessage()
        replaceService()
        failLogin()
        assertEquals(firstRevision, fixture.binder.getSnapshot().revision)

        connect()

        assertMessage(fixture.binder.getSnapshot().message)
    }

    @Test fun differentServiceSuccessesWithEqualRevisionRefreshTheDisplayedAccount() {
        fixture.complete("first-account")
        val firstRevision = fixture.binder.getSnapshot().revision
        connect()
        assertEquals("first-account", profileName.text.toString())
        assertMessage(fixture.binder.getSnapshot().message)
        expireMessage()
        replaceService()
        fixture.complete("second-account")
        assertEquals(firstRevision, fixture.binder.getSnapshot().revision)
        // onStart refresh is deliberately not involved: the new terminal callback itself must
        // update the existing Activity, even when this local revision was seen on the old binder.
        assertEquals("first-account", profileName.text.toString())

        connect()

        assertEquals("second-account", profileName.text.toString())
        assertMessage(fixture.binder.getSnapshot().message)
    }

    @Test fun sameServiceRebindDoesNotRepeatAnAlreadyHandledTerminalMessage() {
        failLogin()
        connect()
        assertMessage(fixture.binder.getSnapshot().message)
        expireMessage()
        disconnect()

        connect()

        assertNull(activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text))
        // A genuinely later terminal on this same binder is still delivered.
        failLogin()
        layoutSurface()
        assertMessage(fixture.binder.getSnapshot().message)
    }

    private fun failLogin() {
        fixture.beginFailure = 5
        fixture.binder.begin("synthetic-failure", "synthetic-password")
        fixture.drain()
        assertEquals(SteamAuthForegroundService.Stage.FAILED, fixture.binder.getSnapshot().stage)
    }

    private fun connect() {
        SteamAuthTestFixture.setField(activity, "steamAuthServiceBound", true)
        connection.onServiceConnected(ComponentName(activity, SteamAuthForegroundService::class.java), fixture.binder)
        connected = true
        shadowOf(Looper.getMainLooper()).idle()
        layoutSurface()
    }

    private fun disconnect() {
        if (!connected) return
        fixture.binder.unregisterListener(listener)
        connection.onServiceDisconnected(ComponentName(activity, SteamAuthForegroundService::class.java))
        SteamAuthTestFixture.setField(activity, "steamAuthServiceBound", false)
        connected = false
    }

    private fun replaceService() {
        disconnect()
        fixture.close()
        fixture = SteamAuthTestFixture()
    }

    private fun assertMessage(expected: String) {
        val message = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        assertNotNull("A new service's terminal result must reach the visible Snackbar", message)
        assertEquals(expected, message.text.toString())
    }

    private fun expireMessage() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        layoutSurface()
        assertNull(activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text))
    }

    private fun layoutSurface() {
        activity.window.decorView.apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1080, 1920)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400L))
    }
}
