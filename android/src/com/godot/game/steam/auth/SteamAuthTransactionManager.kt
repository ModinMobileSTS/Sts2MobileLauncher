package com.godot.game.steam.auth

import android.content.Context
import android.util.Log
import com.godot.game.steam.core.SteamClientIdentity
import com.godot.game.steam.core.SteamNetworkClientFactory
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import top.apricityx.workshop.steam.protocol.SteamAuthPollResult
import top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionExpiredException
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle
import top.apricityx.workshop.steam.protocol.SteamAuthTransientException
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamAuthenticationException
import top.apricityx.workshop.steam.protocol.SteamCredentialAuthSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType

/**
 * Java-friendly, blocking facade around the resumable protocol transaction. Call its network
 * methods from a worker/service thread. Closing releases the current CM connection but deliberately
 * preserves the encrypted pending handle; only [cancel] clears it.
 */
class SteamAuthTransactionManager @JvmOverloads constructor(
    context: Context,
    private val debugLogger: DebugLogger? = null,
) : Closeable {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val httpClient = SteamNetworkClientFactory.createDefaultClient()
    private val identity = SteamClientIdentity(appContext)
    private val protocolClient = SteamAuthenticationClient(
        directoryClient = SteamDirectoryClient(httpClient),
        sessionFactory = { identity.createSession(httpClient) },
    )

    @Volatile
    private var activeSession: SteamCredentialAuthSession? = null

    @Volatile
    private var localGeneration: Long = 0L

    fun begin(username: String?, password: String?): SteamAuthTransactionHandle {
        val normalizedUsername = username.orEmpty().trim()
        require(normalizedUsername.isNotEmpty()) { "Steam account name is required." }
        require(!password.isNullOrEmpty()) { "Steam password is required." }

        val generation = synchronized(stateLock) {
            localGeneration += 1L
            activeSession?.close()
            activeSession = null
            localGeneration
        }
        // Starting a new credentials request is an explicit replacement of any older transaction.
        SteamAuthStore.clearPendingAuthTransaction(appContext)

        val saved = SteamAuthStore.readAuthMaterial(appContext)
        val matchingGuardData = saved
            ?.takeIf { it.accountName.equals(normalizedUsername, ignoreCase = true) }
            ?.guardData
            ?.takeIf(String::isNotBlank)

        val created = runBlocking {
            protocolClient.beginAuthSession(
                details = SteamAuthSessionDetails(
                    username = normalizedUsername,
                    password = password,
                    guardData = matchingGuardData,
                    deviceFriendlyName = identity.machineName,
                ),
                debugLogger = ::logProtocol,
            )
        }
        val handle = created.exportHandle()
        synchronized(stateLock) {
            if (generation != localGeneration) {
                created.close()
                throw SteamAuthTransactionSupersededException(handle.transactionId)
            }
            SteamAuthStore.savePendingAuthTransaction(appContext, handle)
            activeSession = created
        }
        return handle
    }

    /** Recreates the protocol session on a fresh CM connection, or returns null when none is pending. */
    fun resume(): SteamAuthTransactionHandle? {
        val persisted = SteamAuthStore.readPendingAuthTransaction(appContext) ?: return null
        synchronized(stateLock) {
            activeSession
                ?.takeIf {
                    val active = it.exportHandle()
                    active.transactionId == persisted.transactionId &&
                        active.effectiveClientId == persisted.effectiveClientId
                }
                ?.let { return it.exportHandle() }
        }

        val generation = synchronized(stateLock) { localGeneration }
        val resumed = try {
            runBlocking { protocolClient.resumeAuthSession(persisted, ::logProtocol) }
        } catch (error: SteamAuthTransactionExpiredException) {
            SteamAuthStore.clearPendingAuthTransaction(appContext, persisted.transactionId)
            throw error
        }

        synchronized(stateLock) {
            val stillPending = SteamAuthStore.readPendingAuthTransaction(appContext)
            if (
                generation != localGeneration ||
                stillPending == null ||
                stillPending.transactionId != persisted.transactionId
            ) {
                resumed.close()
                throw SteamAuthTransactionSupersededException(persisted.transactionId)
            }
            activeSession?.close()
            activeSession = resumed
        }
        return resumed.exportHandle()
    }

    /** Persists the selected challenge and resumable phase before UI leaves the foreground. */
    fun selectChallenge(type: SteamGuardChallengeType): SteamAuthTransactionHandle {
        val generation = synchronized(stateLock) { localGeneration }
        val session = requireActiveSession()
        val updated = session.selectChallenge(type)
        persistUpdatedGeneration(updated, generation)
        return updated
    }

    /** Submits a device/email code and persists the transition to POLLING. */
    fun submitGuardCode(type: SteamGuardChallengeType, code: String?): SteamAuthTransactionHandle {
        val normalizedCode = code.orEmpty().trim()
        require(normalizedCode.isNotEmpty()) { "Steam Guard code is required." }
        val generation = synchronized(stateLock) { localGeneration }
        val session = requireActiveSession()
        return try {
            val updated = runBlocking { session.submitGuardCode(type, normalizedCode) }
            persistUpdatedGeneration(updated, generation)
            updated
        } catch (error: SteamAuthTransactionExpiredException) {
            expireGeneration(session.exportHandle())
            throw error
        } catch (error: Throwable) {
            // Preserve AWAITING_GUARD_CODE after a rejected code so a recreated UI can retry.
            persistUpdatedGeneration(session.exportHandle(), generation)
            throw error
        }
    }

    /**
     * Polls once. A null result means the transaction is still pending. The method persists any
     * server-supplied new_client_id before returning, so callers do not need to save it separately.
     */
    fun pollOnce(): Completion? {
        val generation = synchronized(stateLock) { localGeneration }
        val session = requireActiveSession()
        val result = try {
            runBlocking { session.pollStatus() }
        } catch (error: SteamAuthTransactionExpiredException) {
            expireGeneration(session.exportHandle())
            throw error
        }
        val updated = session.exportHandle()
        if (result == null) {
            persistUpdatedGeneration(updated, generation)
            return null
        }
        return commitCompletion(updated, result, generation)
    }

    fun getPendingHandle(): SteamAuthTransactionHandle? = SteamAuthStore.readPendingAuthTransaction(appContext)

    fun getCurrentHandle(): SteamAuthTransactionHandle? =
        activeSession?.exportHandle() ?: getPendingHandle()

    /** Explicit user cancellation. This never deletes an already saved refresh token. */
    fun cancel() {
        val session = synchronized(stateLock) {
            localGeneration += 1L
            val current = activeSession
            activeSession = null
            current
        }
        val handle = session?.exportHandle() ?: SteamAuthStore.readPendingAuthTransaction(appContext)
        if (handle == null) {
            SteamAuthStore.clearPendingAuthTransaction(appContext)
        } else {
            SteamAuthStore.clearPendingAuthTransaction(appContext, handle.transactionId)
        }
        session?.close()
    }

    /** Releases sockets while keeping the pending transaction available to a new service/UI owner. */
    override fun close() {
        val session = synchronized(stateLock) {
            val current = activeSession
            activeSession = null
            current
        }
        session?.close()
    }

    private fun requireActiveSession(): SteamCredentialAuthSession {
        activeSession?.let { session ->
            val persisted = SteamAuthStore.readPendingAuthTransaction(appContext)
            if (persisted != null && persisted.transactionId == session.exportHandle().transactionId) {
                return session
            }
            session.close()
            synchronized(stateLock) {
                if (activeSession === session) {
                    activeSession = null
                }
            }
        }
        resume()
        return activeSession ?: throw IllegalStateException("No resumable Steam authentication transaction is pending.")
    }

    private fun persistUpdatedGeneration(handle: SteamAuthTransactionHandle, expectedLocalGeneration: Long) {
        if (handle.isExpired()) {
            expireGeneration(handle)
            throw SteamAuthTransactionExpiredException(handle.transactionId, handle.expiresAtEpochMillis)
        }
        if (synchronized(stateLock) { localGeneration != expectedLocalGeneration }) {
            throw SteamAuthTransactionSupersededException(handle.transactionId)
        }
        if (!SteamAuthStore.updatePendingAuthTransaction(appContext, handle)) {
            closeGeneration(handle.transactionId)
            throw SteamAuthTransactionSupersededException(handle.transactionId)
        }
    }

    private fun commitCompletion(
        handle: SteamAuthTransactionHandle,
        result: SteamAuthPollResult,
        expectedLocalGeneration: Long,
    ): Completion {
        if (handle.isExpired()) {
            expireGeneration(handle)
            throw SteamAuthTransactionExpiredException(handle.transactionId, handle.expiresAtEpochMillis)
        }
        if (synchronized(stateLock) { localGeneration != expectedLocalGeneration }) {
            closeGeneration(handle.transactionId)
            throw SteamAuthTransactionSupersededException(handle.transactionId)
        }
        val effectiveGuardData = result.newGuardData ?: handle.guardData.orEmpty()
        val steamId64 = result.steamId.toString()
        val committed = SteamAuthStore.recordAuthSuccessIfPendingMatches(
            appContext,
            handle.transactionId,
            result.accountName,
            result.refreshToken,
            effectiveGuardData,
            steamId64,
        )
        closeGeneration(handle.transactionId)
        if (!committed) {
            throw SteamAuthTransactionSupersededException(handle.transactionId)
        }
        return Completion(
            accountName = result.accountName,
            steamId64 = steamId64,
            guardDataUpdated = result.newGuardData != null,
        )
    }

    private fun expireGeneration(handle: SteamAuthTransactionHandle) {
        SteamAuthStore.clearPendingAuthTransaction(appContext, handle.transactionId)
        closeGeneration(handle.transactionId)
    }

    private fun closeGeneration(transactionId: String) {
        val session = synchronized(stateLock) {
            val current = activeSession
            if (current?.exportHandle()?.transactionId == transactionId) {
                activeSession = null
                current
            } else {
                null
            }
        }
        session?.close()
    }

    private fun logProtocol(line: String) {
        debugLogger?.log(line) ?: Log.d(TAG, line)
    }

    fun interface DebugLogger {
        fun log(line: String)
    }

    class Completion internal constructor(
        val accountName: String,
        val steamId64: String,
        val guardDataUpdated: Boolean,
    )

    companion object {
        private const val TAG = "Sts2SteamAuthTxn"

        @JvmStatic
        fun isExpiredFailure(error: Throwable?): Boolean =
            error.findCause<SteamAuthTransactionExpiredException>() != null

        @JvmStatic
        fun isTransientFailure(error: Throwable?): Boolean {
            if (error.findCause<SteamAuthTransientException>() != null) {
                return true
            }
            val authentication = error.findCause<SteamAuthenticationException>() ?: return false
            return authentication.resultCode == 20
        }

        @JvmStatic
        fun isSupersededFailure(error: Throwable?): Boolean =
            error.findCause<SteamAuthTransactionSupersededException>() != null
    }
}

class SteamAuthTransactionSupersededException(
    val transactionId: String,
) : IllegalStateException("Steam auth transaction was cancelled or replaced: $transactionId")

private inline fun <reified T : Throwable> Throwable?.findCause(): T? {
    var current = this
    while (current != null) {
        if (current is T) {
            return current
        }
        val next = current.cause
        if (next === current) {
            return null
        }
        current = next
    }
    return null
}
