package com.godot.game.steam.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.godot.game.R
import com.godot.game.SteamAccountActivity
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import top.apricityx.workshop.steam.protocol.SteamAuthChallengeSnapshot
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionPhase
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType

/**
 * Owns an in-flight Steam credential authentication transaction independently of the Activity.
 *
 * Passwords and Steam Guard codes are accepted only through the local binder and live only in the
 * worker call that consumes them. Intents and persistent storage contain no credentials. Once Steam
 * returns a resumable handle, the encrypted [SteamAuthStore] becomes the source of truth and this
 * service can rebuild the CM connection after an Activity or process restart.
 */
class SteamAuthForegroundService : Service() {
    enum class Stage {
        IDLE,
        PREPARING,
        STARTING,
        RESUMING,
        WAITING_CONFIRMATION,
        WAITING_CODE,
        POLLING,
        RECONNECTING,
        SUBMITTING_CODE,
        SUCCESS,
        FAILED,
        CANCELLED,
        EXPIRED,
        NEEDS_CREDENTIALS,
    }

    class Snapshot internal constructor(
        val revision: Long,
        val stage: Stage,
        val transactionId: String?,
        val challengeType: SteamGuardChallengeType?,
        val challengeMessage: String?,
        val deadlineEpochMillis: Long,
        val message: String,
        val previousCodeRejected: Boolean,
    ) {
        val isActive: Boolean
            get() = stage in ACTIVE_STAGES

        companion object {
            private val ACTIVE_STAGES = setOf(
                Stage.PREPARING,
                Stage.STARTING,
                Stage.RESUMING,
                Stage.WAITING_CONFIRMATION,
                Stage.WAITING_CODE,
                Stage.POLLING,
                Stage.RECONNECTING,
                Stage.SUBMITTING_CODE,
            )
        }
    }

    fun interface Listener {
        fun onAuthStateChanged(snapshot: Snapshot)
    }

    inner class LocalBinder : Binder() {
        fun getSnapshot(): Snapshot = snapshot

        fun registerListener(listener: Listener) {
            listeners.add(listener)
            mainHandler.post {
                if (listeners.contains(listener)) {
                    listener.onAuthStateChanged(snapshot)
                }
            }
        }

        fun unregisterListener(listener: Listener) {
            listeners.remove(listener)
        }

        /** Replaces any older pending generation. The credentials never enter an Intent. */
        fun begin(accountName: String, password: String) {
            beginFromBinder(accountName, password)
        }

        fun submitGuardCode(transactionId: String, type: SteamGuardChallengeType, code: String) {
            submitCodeFromBinder(transactionId, type, code)
        }

        fun cancel() {
            cancelAuthentication()
        }
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong(0L)
    private val generation = AtomicLong(0L)
    private lateinit var worker: ScheduledExecutorService
    private lateinit var manager: SteamAuthTransactionManager

    @Volatile
    private var snapshot = Snapshot(
        revision = 0L,
        stage = Stage.IDLE,
        transactionId = null,
        challengeType = null,
        challengeMessage = null,
        deadlineEpochMillis = 0L,
        message = "",
        previousCodeRejected = false,
    )

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        worker = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sts2-steam-auth").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        manager = SteamAuthTransactionManager(this) { line -> Log.d(TAG, line) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action ?: ACTION_RESUME) {
            ACTION_CANCEL -> cancelAuthentication(intent?.getStringExtra(EXTRA_TRANSACTION_ID))
            ACTION_PREPARE -> {
                val pending = SteamAuthStore.readPendingAuthTransaction(this)
                when {
                    pending != null -> resumePendingTransaction(pending)
                    flags and START_FLAG_REDELIVERY != 0 -> finishNeedsCredentials()
                    snapshot.isActive && snapshot.stage != Stage.PREPARING -> Unit
                    else -> {
                        publish(
                            stage = Stage.PREPARING,
                            message = getString(R.string.steam_status_auth_preparing),
                        )
                        scheduleMissingCredentialsGuard(generation.get())
                    }
                }
            }
            ACTION_RESUME -> {
                val pending = SteamAuthStore.readPendingAuthTransaction(this)
                if (pending == null) {
                    finishNeedsCredentials()
                } else {
                    resumePendingTransaction(pending)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        listeners.clear()
        if (::manager.isInitialized) {
            manager.close()
        }
        if (::worker.isInitialized) {
            worker.shutdownNow()
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val oldGeneration = generation.incrementAndGet()
        SteamAuthStore.clearPendingAuthTransaction(this)
        publish(
            stage = Stage.EXPIRED,
            message = getString(R.string.steam_error_session_expired),
        )
        worker.execute {
            if (generation.get() == oldGeneration) {
                manager.cancel()
                stopForegroundAndSelf(oldGeneration)
            }
        }
    }

    private fun beginFromBinder(accountName: String, password: String) {
        val normalizedAccount = accountName.trim()
        if (normalizedAccount.isEmpty() || password.isEmpty()) {
            finishNeedsCredentials()
            return
        }

        ensureForeground()
        val operationGeneration = generation.incrementAndGet()
        // Invalidate the old generation synchronously. A poll already returning on the worker can
        // no longer atomically commit after this point.
        SteamAuthStore.clearPendingAuthTransaction(this)
        publish(
            stage = Stage.STARTING,
            message = getString(R.string.steam_status_auth_starting),
        )
        worker.execute {
            manager.cancel()
            var attempts = 0
            while (isCurrent(operationGeneration)) {
                attempts += 1
                try {
                    val handle = manager.begin(normalizedAccount, password)
                    if (!isCurrent(operationGeneration)) {
                        manager.cancel()
                        return@execute
                    }
                    restoreChallengeAndContinue(operationGeneration, handle)
                    return@execute
                } catch (error: Throwable) {
                    if (!isCurrent(operationGeneration)) {
                        return@execute
                    }
                    if (isTransient(error) && attempts < BEGIN_MAX_ATTEMPTS) {
                        publishForHandle(
                            stage = Stage.RECONNECTING,
                            handle = manager.getPendingHandle(),
                            message = getString(R.string.steam_status_auth_reconnecting),
                        )
                        sleepBackoff(attempts)
                        continue
                    }
                    finishFatal(operationGeneration, error)
                    return@execute
                }
            }
        }
    }

    private fun resumePendingTransaction(pending: SteamAuthTransactionHandle) {
        if (pending.isExpired()) {
            finishExpired(pending.transactionId)
            return
        }
        val current = snapshot
        if (current.isActive && current.transactionId == pending.transactionId) {
            return
        }
        ensureForeground()
        val operationGeneration = generation.incrementAndGet()
        publishForHandle(
            stage = Stage.RESUMING,
            handle = pending,
            message = getString(R.string.steam_status_auth_resuming),
        )
        worker.execute { resumeAndContinue(operationGeneration, pending.transactionId, 0) }
    }

    private fun resumeAndContinue(operationGeneration: Long, transactionId: String, attempt: Int) {
        if (!isCurrent(operationGeneration)) {
            return
        }
        val pending = SteamAuthStore.readPendingAuthTransaction(this)
        if (pending == null || pending.transactionId != transactionId) {
            finishSupersededOrMissing(operationGeneration)
            return
        }
        if (pending.isExpired()) {
            finishExpired(transactionId, operationGeneration)
            return
        }
        try {
            manager.close()
            val handle = manager.resume()
            if (handle == null) {
                finishSupersededOrMissing(operationGeneration)
                return
            }
            restoreChallengeAndContinue(operationGeneration, handle)
        } catch (error: Throwable) {
            if (!isCurrent(operationGeneration)) {
                return
            }
            when {
                SteamAuthTransactionManager.isExpiredFailure(error) -> finishExpired(transactionId, operationGeneration)
                isTransient(error) -> {
                    publishForHandle(
                        stage = Stage.RECONNECTING,
                        handle = pending,
                        message = getString(R.string.steam_status_auth_reconnecting),
                    )
                    scheduleResume(operationGeneration, transactionId, attempt + 1)
                }
                else -> finishFatal(operationGeneration, error)
            }
        }
    }

    private fun restoreChallengeAndContinue(
        operationGeneration: Long,
        initialHandle: SteamAuthTransactionHandle,
    ) {
        if (!isCurrent(operationGeneration)) {
            return
        }
        var handle = initialHandle
        var selected = handle.selectedChallengeType
        if (selected == null) {
            selected = chooseChallenge(handle.challenges)
            if (selected != null) {
                handle = manager.selectChallenge(selected)
            }
        }

        when {
            handle.phase == SteamAuthTransactionPhase.AWAITING_GUARD_CODE &&
                (selected == SteamGuardChallengeType.DeviceCode || selected == SteamGuardChallengeType.EmailCode) -> {
                publishCodeRequired(handle, selected, previousCodeRejected = false)
            }
            selected == SteamGuardChallengeType.MachineToken ||
                selected == SteamGuardChallengeType.LegacyMachineAuth ||
                selected == SteamGuardChallengeType.Unknown -> {
                finishFatal(
                    operationGeneration,
                    IllegalStateException(getString(R.string.steam_auth_error_unsupported_challenge)),
                )
            }
            selected == SteamGuardChallengeType.DeviceConfirmation ||
                selected == SteamGuardChallengeType.EmailConfirmation -> {
                publishForHandle(
                    stage = Stage.WAITING_CONFIRMATION,
                    handle = handle,
                    message = getString(R.string.steam_guard_confirmation_message),
                    challengeType = selected,
                    challengeMessage = findChallengeMessage(handle, selected),
                )
                schedulePoll(operationGeneration, handle.transactionId, 0L)
            }
            else -> {
                publishForHandle(
                    stage = Stage.POLLING,
                    handle = handle,
                    message = getString(R.string.steam_status_auth_polling),
                    challengeType = selected,
                    challengeMessage = selected?.let { findChallengeMessage(handle, it) },
                )
                schedulePoll(operationGeneration, handle.transactionId, 0L)
            }
        }
    }

    private fun submitCodeFromBinder(
        transactionId: String,
        type: SteamGuardChallengeType,
        code: String,
    ) {
        val normalizedCode = code.trim()
        val handle = manager.getPendingHandle()
        if (handle == null) {
            finishNeedsCredentials()
            return
        }
        if (
            normalizedCode.isEmpty() ||
            handle.transactionId != transactionId ||
            handle.selectedChallengeType != type ||
            handle.phase != SteamAuthTransactionPhase.AWAITING_GUARD_CODE
        ) {
            // Ignore a late dialog submission rather than applying a code to a newer transaction.
            return
        }
        ensureForeground()
        val operationGeneration = generation.get()
        publishForHandle(
            stage = Stage.SUBMITTING_CODE,
            handle = handle,
            message = getString(R.string.steam_status_auth_submitting_code),
            challengeType = type,
            challengeMessage = findChallengeMessage(handle, type),
        )
        worker.execute { submitCode(operationGeneration, handle.transactionId, type, normalizedCode, 0) }
    }

    private fun submitCode(
        operationGeneration: Long,
        transactionId: String,
        type: SteamGuardChallengeType,
        code: String,
        attempt: Int,
    ) {
        if (!isCurrent(operationGeneration)) {
            return
        }
        try {
            val updated = manager.submitGuardCode(type, code)
            if (!isCurrent(operationGeneration)) {
                return
            }
            publishForHandle(
                stage = Stage.POLLING,
                handle = updated,
                message = getString(R.string.steam_status_auth_polling),
                challengeType = type,
                challengeMessage = findChallengeMessage(updated, type),
            )
            schedulePoll(operationGeneration, transactionId, 0L)
        } catch (error: Throwable) {
            if (!isCurrent(operationGeneration)) {
                return
            }
            val authError = error.findCause<SteamAuthenticationException>()
            when {
                authError?.resultCode == RESULT_INVALID_EMAIL_CODE ||
                    authError?.resultCode == RESULT_INVALID_DEVICE_CODE -> {
                    val pending = manager.getPendingHandle()
                    if (pending == null) {
                        finishSupersededOrMissing(operationGeneration)
                    } else {
                        publishCodeRequired(pending, type, previousCodeRejected = true)
                    }
                }
                SteamAuthTransactionManager.isExpiredFailure(error) -> finishExpired(transactionId, operationGeneration)
                isTransient(error) -> {
                    val pending = manager.getPendingHandle()
                    publishForHandle(
                        stage = Stage.RECONNECTING,
                        handle = pending,
                        message = getString(R.string.steam_status_auth_reconnecting),
                        challengeType = type,
                    )
                    sleepBackoff(attempt + 1)
                    try {
                        manager.close()
                        manager.resume()
                    } catch (resumeError: Throwable) {
                        if (SteamAuthTransactionManager.isExpiredFailure(resumeError)) {
                            finishExpired(transactionId, operationGeneration)
                            return
                        }
                        if (!isTransient(resumeError)) {
                            finishFatal(operationGeneration, resumeError)
                            return
                        }
                    }
                    submitCode(operationGeneration, transactionId, type, code, attempt + 1)
                }
                else -> finishFatal(operationGeneration, error)
            }
        }
    }

    private fun pollOnce(operationGeneration: Long, transactionId: String) {
        if (!isCurrent(operationGeneration)) {
            return
        }
        val pending = manager.getPendingHandle()
        if (pending == null || pending.transactionId != transactionId) {
            finishSupersededOrMissing(operationGeneration)
            return
        }
        if (pending.isExpired()) {
            finishExpired(transactionId, operationGeneration)
            return
        }
        try {
            val completion = manager.pollOnce()
            if (!isCurrent(operationGeneration)) {
                return
            }
            if (completion != null) {
                if (!generation.compareAndSet(operationGeneration, operationGeneration + 1L)) {
                    return
                }
                publish(
                    stage = Stage.SUCCESS,
                    message = getString(
                        R.string.steam_login_done,
                        completion.accountName,
                        completion.steamId64,
                    ),
                )
                stopForegroundAndSelf(operationGeneration + 1L)
                return
            }
            val updated = manager.getCurrentHandle() ?: pending
            val selected = updated.selectedChallengeType
            val stage = if (
                selected == SteamGuardChallengeType.DeviceConfirmation ||
                selected == SteamGuardChallengeType.EmailConfirmation
            ) {
                Stage.WAITING_CONFIRMATION
            } else {
                Stage.POLLING
            }
            publishForHandle(
                stage = stage,
                handle = updated,
                message = if (stage == Stage.WAITING_CONFIRMATION) {
                    getString(R.string.steam_guard_confirmation_message)
                } else {
                    getString(R.string.steam_status_auth_polling)
                },
                challengeType = selected,
                challengeMessage = selected?.let { findChallengeMessage(updated, it) },
            )
            schedulePoll(operationGeneration, transactionId, updated.pollingIntervalMillis)
        } catch (error: Throwable) {
            if (!isCurrent(operationGeneration)) {
                return
            }
            when {
                SteamAuthTransactionManager.isExpiredFailure(error) -> finishExpired(transactionId, operationGeneration)
                isTransient(error) -> {
                    publishForHandle(
                        stage = Stage.RECONNECTING,
                        handle = pending,
                        message = getString(R.string.steam_status_auth_reconnecting),
                        challengeType = pending.selectedChallengeType,
                    )
                    scheduleResume(operationGeneration, transactionId, 1)
                }
                else -> finishFatal(operationGeneration, error)
            }
        }
    }

    private fun schedulePoll(operationGeneration: Long, transactionId: String, delayMillis: Long) {
        if (!isCurrent(operationGeneration) || worker.isShutdown) {
            return
        }
        worker.schedule(
            { pollOnce(operationGeneration, transactionId) },
            delayMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleResume(operationGeneration: Long, transactionId: String, attempt: Int) {
        if (!isCurrent(operationGeneration) || worker.isShutdown) {
            return
        }
        val delay = reconnectBackoffMillis(attempt)
        worker.schedule(
            { resumeAndContinue(operationGeneration, transactionId, attempt) },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun publishCodeRequired(
        handle: SteamAuthTransactionHandle,
        type: SteamGuardChallengeType,
        previousCodeRejected: Boolean,
    ) {
        val associatedMessage = findChallengeMessage(handle, type).orEmpty()
        val message = when (type) {
            SteamGuardChallengeType.EmailCode -> getString(
                if (previousCodeRejected) R.string.steam_guard_email_code_retry else R.string.steam_guard_email_code,
                associatedMessage,
            )
            else -> getString(
                if (previousCodeRejected) R.string.steam_guard_device_code_retry else R.string.steam_guard_device_code,
            )
        }
        publishForHandle(
            stage = Stage.WAITING_CODE,
            handle = handle,
            message = message,
            challengeType = type,
            challengeMessage = associatedMessage,
            previousCodeRejected = previousCodeRejected,
        )
    }

    private fun finishExpired(transactionId: String, operationGeneration: Long? = null) {
        if (!claimTerminalGeneration(operationGeneration)) {
            return
        }
        val terminalGeneration = generation.get()
        SteamAuthStore.clearPendingAuthTransaction(this, transactionId)
        manager.close()
        publish(
            stage = Stage.EXPIRED,
            message = getString(R.string.steam_error_session_expired),
        )
        stopForegroundAndSelf(terminalGeneration)
    }

    private fun finishFatal(operationGeneration: Long, error: Throwable) {
        if (!claimTerminalGeneration(operationGeneration)) {
            return
        }
        val terminalGeneration = generation.get()
        val message = describeFatalError(error)
        manager.cancel()
        SteamAuthStore.recordFailure(this, message)
        publish(stage = Stage.FAILED, message = message)
        stopForegroundAndSelf(terminalGeneration)
    }

    private fun finishSupersededOrMissing(operationGeneration: Long) {
        if (!claimTerminalGeneration(operationGeneration)) {
            return
        }
        val terminalGeneration = generation.get()
        manager.close()
        publish(
            stage = Stage.NEEDS_CREDENTIALS,
            message = getString(R.string.steam_auth_error_missing_credentials),
        )
        stopForegroundAndSelf(terminalGeneration)
    }

    private fun finishNeedsCredentials() {
        val terminalGeneration = generation.incrementAndGet()
        if (::manager.isInitialized) {
            manager.close()
        }
        publish(
            stage = Stage.NEEDS_CREDENTIALS,
            message = getString(R.string.steam_auth_error_missing_credentials),
        )
        stopForegroundAndSelf(terminalGeneration)
    }

    private fun cancelAuthentication(expectedTransactionId: String? = null) {
        val expected = expectedTransactionId?.trim()?.takeIf(String::isNotEmpty)
        val persisted = SteamAuthStore.readPendingAuthTransaction(this)
        if (expected != null && persisted != null && persisted.transactionId != expected) {
            // A delayed notification action from an older generation must not cancel the new one.
            return
        }
        val targetTransactionId = expected
            ?: snapshot.transactionId
            ?: persisted?.transactionId
        if (targetTransactionId != null) {
            // This lock/CAS is the cancellation linearization point. If success committed first,
            // let the worker publish SUCCESS instead of reporting a misleading cancellation.
            if (!SteamAuthStore.clearPendingAuthTransaction(this, targetTransactionId)) {
                return
            }
        } else {
            // Before BeginAuth has produced a handle, invalidating the in-memory generation is the
            // only possible cancellation mechanism.
            SteamAuthStore.clearPendingAuthTransaction(this)
        }
        val cancelledGeneration = generation.incrementAndGet()
        publish(
            stage = Stage.CANCELLED,
            message = getString(R.string.steam_login_cancelled),
        )
        if (::worker.isInitialized && !worker.isShutdown) {
            worker.execute {
                if (generation.get() == cancelledGeneration) {
                    manager.cancel()
                    stopForegroundAndSelf(cancelledGeneration)
                }
            }
        } else {
            stopForegroundAndSelf(cancelledGeneration)
        }
    }

    private fun scheduleMissingCredentialsGuard(operationGeneration: Long) {
        worker.schedule({
            if (
                generation.get() == operationGeneration &&
                snapshot.stage == Stage.PREPARING &&
                SteamAuthStore.readPendingAuthTransaction(this) == null
            ) {
                finishNeedsCredentials()
            }
        }, PREPARE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun publishForHandle(
        stage: Stage,
        handle: SteamAuthTransactionHandle?,
        message: String,
        challengeType: SteamGuardChallengeType? = handle?.selectedChallengeType,
        challengeMessage: String? = null,
        previousCodeRejected: Boolean = false,
    ) {
        publish(
            stage = stage,
            transactionId = handle?.transactionId,
            challengeType = challengeType,
            challengeMessage = challengeMessage,
            deadlineEpochMillis = handle?.expiresAtEpochMillis ?: 0L,
            message = message,
            previousCodeRejected = previousCodeRejected,
        )
    }

    private fun publish(
        stage: Stage,
        transactionId: String? = null,
        challengeType: SteamGuardChallengeType? = null,
        challengeMessage: String? = null,
        deadlineEpochMillis: Long = 0L,
        message: String,
        previousCodeRejected: Boolean = false,
    ) {
        val next = Snapshot(
            revision = revision.incrementAndGet(),
            stage = stage,
            transactionId = transactionId,
            challengeType = challengeType,
            challengeMessage = challengeMessage,
            deadlineEpochMillis = deadlineEpochMillis,
            message = message,
            previousCodeRejected = previousCodeRejected,
        )
        snapshot = next
        if (foregroundStarted && next.isActive) {
            updateForegroundNotification(next)
        }
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener.onAuthStateChanged(next) }
                    .onFailure { Log.w(TAG, "Steam auth listener failed.", it) }
            }
        }
    }

    private fun ensureForeground() {
        val notification = buildNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateForegroundNotification(value: Snapshot) {
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        notifications.notify(NOTIFICATION_ID, buildNotification(value))
    }

    private fun buildNotification(value: Snapshot): Notification {
        val openIntent = Intent(this, SteamAccountActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = Intent(this, SteamAuthForegroundService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TRANSACTION_ID, value.transactionId.orEmpty())
        val cancelPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val body = when (value.stage) {
            Stage.WAITING_CONFIRMATION -> getString(R.string.steam_auth_notification_waiting_confirmation)
            Stage.WAITING_CODE -> getString(R.string.steam_auth_notification_waiting_code)
            Stage.RECONNECTING, Stage.RESUMING -> getString(R.string.steam_auth_notification_reconnecting)
            else -> getString(R.string.steam_auth_notification_preparing)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_steam_24)
            .setContentTitle(getString(R.string.steam_auth_notification_title))
            .setContentText(body)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_close_24,
                    getString(R.string.steam_auth_notification_cancel),
                    cancelPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.steam_auth_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notifications.createNotificationChannel(channel)
    }

    private fun stopForegroundAndSelf(expectedGeneration: Long) {
        mainHandler.post {
            if (generation.get() != expectedGeneration) {
                return@post
            }
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            stopSelf()
        }
    }

    private fun chooseChallenge(challenges: List<SteamAuthChallengeSnapshot>): SteamGuardChallengeType? {
        val types = challenges.map { it.type }.toSet()
        return CHALLENGE_PREFERENCE.firstOrNull(types::contains)
    }

    private fun findChallengeMessage(
        handle: SteamAuthTransactionHandle,
        type: SteamGuardChallengeType,
    ): String? = handle.challenges.firstOrNull { it.type == type }?.message

    private fun isCurrent(operationGeneration: Long): Boolean =
        generation.get() == operationGeneration && !worker.isShutdown

    private fun claimTerminalGeneration(operationGeneration: Long?): Boolean =
        if (operationGeneration == null) {
            generation.incrementAndGet()
            true
        } else {
            generation.compareAndSet(operationGeneration, operationGeneration + 1L)
        }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(reconnectBackoffMillis(attempt))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun reconnectBackoffMillis(attempt: Int): Long =
        when (attempt.coerceAtLeast(1)) {
            1 -> 1_000L
            2 -> 2_000L
            else -> 5_000L
        }

    private fun isTransient(error: Throwable): Boolean =
        SteamAuthTransactionManager.isTransientFailure(error) ||
            error.findCause<IOException>() != null ||
            error.anyCauseNameContains("Timeout") ||
            error.anyCauseNameContains("NoConnection") ||
            error.anyCauseNameContains("RemoteDisconnect")

    private fun describeFatalError(error: Throwable): String {
        val auth = error.findCause<SteamAuthenticationException>()
        return when (auth?.resultCode) {
            5 -> getString(R.string.steam_error_invalid_password)
            20 -> getString(R.string.steam_error_service_busy)
            63, 65, 85, 88 -> getString(R.string.steam_error_guard_code)
            84, 87 -> getString(R.string.steam_error_rate_limited)
            RESULT_EXPIRED -> getString(R.string.steam_error_session_expired)
            else -> error.message
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_ERROR_MESSAGE_LENGTH)
                ?: getString(R.string.steam_error_unknown)
        }
    }

    companion object {
        private const val TAG = "Sts2SteamAuthService"
        private const val NOTIFICATION_CHANNEL_ID = "steam_auth"
        private const val NOTIFICATION_ID = 0x5354
        private const val PREPARE_BIND_TIMEOUT_SECONDS = 20L
        private const val BEGIN_MAX_ATTEMPTS = 3
        private const val RESULT_INVALID_EMAIL_CODE = 65
        private const val RESULT_EXPIRED = 27
        private const val RESULT_INVALID_DEVICE_CODE = 88
        private const val MAX_ERROR_MESSAGE_LENGTH = 320
        private const val ACTION_PREPARE = "com.godot.game.steam.auth.PREPARE"
        private const val ACTION_RESUME = "com.godot.game.steam.auth.RESUME"
        private const val ACTION_CANCEL = "com.godot.game.steam.auth.CANCEL"
        private const val EXTRA_TRANSACTION_ID = "steam_auth_transaction_id"

        private val CHALLENGE_PREFERENCE = listOf(
            SteamGuardChallengeType.None,
            SteamGuardChallengeType.DeviceConfirmation,
            SteamGuardChallengeType.DeviceCode,
            SteamGuardChallengeType.EmailCode,
            SteamGuardChallengeType.EmailConfirmation,
            SteamGuardChallengeType.MachineToken,
            SteamGuardChallengeType.LegacyMachineAuth,
            SteamGuardChallengeType.Unknown,
        )

        @JvmStatic
        fun prepare(context: Context) {
            start(context, ACTION_PREPARE)
        }

        @JvmStatic
        fun resumePending(context: Context) {
            start(context, ACTION_RESUME)
        }

        @JvmStatic
        fun cancelPending(context: Context) {
            start(context, ACTION_CANCEL)
        }

        private fun start(context: Context, action: String) {
            val app = context.applicationContext
            val intent = Intent(app, SteamAuthForegroundService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
    }
}

private inline fun <reified T : Throwable> Throwable?.findCause(): T? {
    var current = this
    var depth = 0
    while (current != null && depth < 20) {
        if (current is T) {
            return current
        }
        val next = current.cause
        if (next === current) {
            return null
        }
        current = next
        depth += 1
    }
    return null
}

private fun Throwable.anyCauseNameContains(value: String): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 20) {
        if (current::class.java.simpleName.contains(value, ignoreCase = true)) {
            return true
        }
        val next = current.cause
        if (next === current) {
            return false
        }
        current = next
        depth += 1
    }
    return false
}
