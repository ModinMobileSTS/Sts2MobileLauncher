package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CAuthentication_AccessToken_GenerateForApp_Request
import top.apricityx.workshop.steam.proto.CAuthentication_AccessToken_GenerateForApp_Response
import top.apricityx.workshop.steam.proto.CAuthentication_AllowedConfirmation
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Request
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Response
import top.apricityx.workshop.steam.proto.CAuthentication_DeviceDetails
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Request
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Response
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Request
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Response
import top.apricityx.workshop.steam.proto.CAuthentication_RefreshToken_Revoke_Request
import top.apricityx.workshop.steam.proto.CAuthentication_RefreshToken_Revoke_Response
import top.apricityx.workshop.steam.proto.CAuthentication_UpdateAuthSessionWithSteamGuardCode_Request
import top.apricityx.workshop.steam.proto.CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response
import top.apricityx.workshop.steam.proto.EAuthSessionGuardType
import top.apricityx.workshop.steam.proto.EAuthTokenPlatformType
import top.apricityx.workshop.steam.proto.ESessionPersistence
import top.apricityx.workshop.steam.proto.ETokenRenewalType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher

class SteamAuthenticationClient(
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
) {
    suspend fun beginAuthSession(
        details: SteamAuthSessionDetails,
        debugLogger: ((String) -> Unit)? = null,
    ): SteamCredentialAuthSession {
        val cmServers = directoryClient.loadServers()
        val session = sessionFactory()
        try {
            debugLogger.log("Protocol: loaded ${cmServers.size} CM server candidate(s) for credential auth.")
            session.connect(cmServers)
            debugLogger.log("Protocol: connected to Steam CM for credential auth.")
            debugLogger.log("Protocol: requesting RSA public key account=${details.username.maskForLog()}.")
            val publicKey = session.callServiceMethod(
                methodName = "Authentication.GetPasswordRSAPublicKey#1",
                request = CAuthentication_GetPasswordRSAPublicKey_Request.newBuilder()
                    .setAccountName(details.username)
                    .build(),
                parser = CAuthentication_GetPasswordRSAPublicKey_Response.parser(),
            )
            debugLogger.log(
                "Protocol: received RSA public key timestamp=${publicKey.timestamp} modulusBytes=${publicKey.publickeyMod.length / 2}.",
            )
            val encryptedPassword = encryptPassword(details.password, publicKey)
            debugLogger.log("Protocol: encrypted password length=${encryptedPassword.length}.")

            debugLogger.log(
                "Protocol: beginning auth session websiteId=${details.websiteId} guardDataPresent=${!details.guardData.isNullOrBlank()} deviceName=${details.deviceFriendlyName}.",
            )
            val beginResponse = session.callServiceMethod(
                methodName = "Authentication.BeginAuthSessionViaCredentials#1",
                request = buildBeginAuthSessionRequest(
                    details = details,
                    encryptedPassword = encryptedPassword,
                    encryptionTimestamp = publicKey.timestamp,
                ),
                parser = CAuthentication_BeginAuthSessionViaCredentials_Response.parser(),
            )

            val challenges = beginResponse.allowedConfirmationsList
                .map(::mapChallenge)
                .sortedBy(SteamGuardChallenge::sortOrder)
            debugLogger.log(
                "Protocol: auth session started steamId=${beginResponse.steamid} clientId=${beginResponse.clientId} intervalSeconds=${beginResponse.interval} challenges=${challenges.summaryForLog()}.",
            )

            return SteamCredentialAuthSession(
                session = session,
                handle = SteamAuthTransactionHandle.create(
                    accountName = details.username,
                    guardData = details.guardData,
                    steamId = beginResponse.steamid,
                    clientId = beginResponse.clientId,
                    requestId = beginResponse.requestId.toByteArray(),
                    pollingIntervalMillis = (beginResponse.interval * 1_000f).toLong().coerceAtLeast(1_000L),
                    challenges = challenges,
                ),
                connectionFactory = { connectFreshSession(debugLogger) },
                debugLogger = debugLogger,
            )
        } catch (error: Throwable) {
            debugLogger.log("Protocol: credential auth failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            session.close()
            throw error.asAuthenticationStartException("Steam 登录失败")
        }
    }

    /**
     * Rebuilds an in-flight credentials transaction on a fresh, unauthenticated CM connection.
     * The handle contains no password and is sufficient for PollAuthSessionStatus and guard-code submission.
     */
    suspend fun resumeAuthSession(
        handle: SteamAuthTransactionHandle,
        debugLogger: ((String) -> Unit)? = null,
    ): SteamCredentialAuthSession {
        if (handle.isExpired()) {
            throw SteamAuthTransactionExpiredException(handle.transactionId, handle.expiresAtEpochMillis)
        }
        val session = connectFreshSession(debugLogger)
        debugLogger.log(
            "Protocol: resumed auth transaction id=${handle.transactionId} steamId=${handle.steamId} " +
                "clientId=${handle.effectiveClientId} phase=${handle.phase.name}.",
        )
        return SteamCredentialAuthSession(
            session = session,
            handle = handle,
            connectionFactory = { connectFreshSession(debugLogger) },
            debugLogger = debugLogger,
        )
    }

    suspend fun generateAccessTokenForApp(
        account: SteamAccountSession,
        allowRenewal: Boolean,
        debugLogger: ((String) -> Unit)? = null,
    ): SteamWebAccessTokens {
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                debugLogger.log(
                    "Protocol: generating access token steamId=${account.steamId} account=${account.accountName.maskForLog()} allowRenewal=$allowRenewal cmServers=${cmServers.size}.",
                )
                session.connectWithRefreshToken(cmServers, account)
                debugLogger.log("Protocol: connected to Steam CM with refresh token.")
                val response = session.callServiceMethod(
                    methodName = "Authentication.GenerateAccessTokenForApp#1",
                    request = CAuthentication_AccessToken_GenerateForApp_Request.newBuilder()
                        .setRefreshToken(account.refreshToken)
                        .setSteamid(account.steamId)
                        .setRenewalType(
                            if (allowRenewal) {
                                ETokenRenewalType.k_ETokenRenewalType_Allow
                            } else {
                                ETokenRenewalType.k_ETokenRenewalType_None
                            },
                        )
                        .build(),
                    parser = CAuthentication_AccessToken_GenerateForApp_Response.parser(),
                )
                debugLogger.log(
                    "Protocol: generated access token accessLength=${response.accessToken.length} refreshUpdated=${response.refreshToken.isNotBlank()}.",
                )
                SteamWebAccessTokens(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken.takeIf(String::isNotBlank),
                )
            } catch (error: Throwable) {
                debugLogger.log("Protocol: GenerateAccessTokenForApp failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
                throw error.asAuthenticationException("生成 Steam Web Token 失败")
            }
        }
    }

    suspend fun revokeRefreshToken(
        account: SteamAccountSession,
        tokenId: ULong,
        debugLogger: ((String) -> Unit)? = null,
    ) {
        val cmServers = directoryClient.loadServers()
        sessionFactory().use { session ->
            try {
                debugLogger.log(
                    "Protocol: revoking refresh token tokenId=$tokenId steamId=${account.steamId} cmServers=${cmServers.size}.",
                )
                session.connectWithRefreshToken(cmServers, account)
                debugLogger.log("Protocol: connected to Steam CM for refresh-token revocation.")
                session.callServiceMethod(
                    methodName = "Authentication.RevokeRefreshToken#1",
                    request = CAuthentication_RefreshToken_Revoke_Request.newBuilder()
                        .setTokenId(tokenId.toLong())
                        .setSteamid(account.steamId)
                        .build(),
                    parser = CAuthentication_RefreshToken_Revoke_Response.parser(),
                )
                debugLogger.log("Protocol: refresh token revoked successfully.")
            } catch (error: Throwable) {
                debugLogger.log("Protocol: RevokeRefreshToken failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
                throw error.asAuthenticationException("撤销 Steam Refresh Token 失败")
            }
        }
    }

    private suspend fun connectFreshSession(
        debugLogger: ((String) -> Unit)?,
    ): SteamCmSession {
        val cmServers = directoryClient.loadServers()
        val session = sessionFactory()
        try {
            debugLogger.log("Protocol: opening fresh unauthenticated CM connection candidates=${cmServers.size}.")
            session.connect(cmServers)
            return session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }
}

class SteamCredentialAuthSession internal constructor(
    session: SteamCmSession,
    handle: SteamAuthTransactionHandle,
    private val connectionFactory: suspend () -> SteamCmSession,
    private val debugLogger: ((String) -> Unit)? = null,
) : Closeable {
    private val operationMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var session: SteamCmSession = session

    @Volatile
    private var handle: SteamAuthTransactionHandle = handle

    @Volatile
    private var closed = false

    val steamId: Long
        get() = exportHandle().steamId

    val pollingIntervalMillis: Long
        get() = exportHandle().pollingIntervalMillis

    val challenges: List<SteamGuardChallenge>
        get() = exportHandle().challenges.map(SteamAuthChallengeSnapshot::toChallenge)

    /** Returns a persistence-safe snapshot. It intentionally never contains the account password. */
    fun exportHandle(): SteamAuthTransactionHandle = synchronized(stateLock) { handle }

    /**
     * Records which challenge the caller selected before prompting or starting remote-confirmation polling.
     * Persist the returned handle so a recreated UI/service can restore the correct phase.
     */
    fun selectChallenge(type: SteamGuardChallengeType): SteamAuthTransactionHandle = synchronized(stateLock) {
        ensureOpenLocked()
        val available = handle.challenges.any { it.type == type }
        require(available) { "Steam Guard challenge ${type.name} is not available for this transaction" }
        handle = handle.withSelectedChallenge(type)
        handle
    }

    suspend fun submitGuardCode(
        type: SteamGuardChallengeType,
        code: String,
    ): SteamAuthTransactionHandle = operationMutex.withLock {
        requireActiveTransaction()
        require(type == SteamGuardChallengeType.DeviceCode || type == SteamGuardChallengeType.EmailCode) {
            "Challenge ${type.name} does not accept a Steam Guard code"
        }
        if (exportHandle().selectedChallengeType != type) {
            selectChallenge(type)
        }
        try {
            debugLogger.log("Protocol: submitting Steam Guard code type=${type.name} codeLength=${code.length}.")
            withConnectionRetries("UpdateAuthSessionWithSteamGuardCode") { activeSession ->
                val current = exportHandle()
                activeSession.callServiceMethod(
                    methodName = "Authentication.UpdateAuthSessionWithSteamGuardCode#1",
                    request = CAuthentication_UpdateAuthSessionWithSteamGuardCode_Request.newBuilder()
                        .setClientId(current.effectiveClientId)
                        .setSteamid(current.steamId)
                        .setCode(code)
                        .setCodeType(type.toProto())
                        .build(),
                    parser = CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response.parser(),
                )
            }
            synchronized(stateLock) {
                handle = handle.withPhase(SteamAuthTransactionPhase.POLLING)
            }
            debugLogger.log("Protocol: Steam Guard code accepted by service.")
            exportHandle()
        } catch (error: Throwable) {
            if (error is SteamServiceMethodException && error.resultCode == STEAM_RESULT_DUPLICATE_REQUEST) {
                // Steam returns DuplicateRequest when the mobile approval wins the race with a code.
                // The transaction is still valid and the next status poll will complete it.
                synchronized(stateLock) {
                    handle = handle.withPhase(SteamAuthTransactionPhase.POLLING)
                }
                debugLogger.log("Protocol: Steam Guard code raced with remote approval; continuing to poll.")
                return@withLock exportHandle()
            }
            debugLogger.log("Protocol: UpdateAuthSessionWithSteamGuardCode failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            throw error.asAuthenticationOperationException("提交 Steam Guard 验证码失败", exportHandle())
        }
    }

    suspend fun pollStatus(): SteamAuthPollResult? = operationMutex.withLock {
        requireActiveTransaction()
        try {
            val beforePoll = exportHandle()
            debugLogger.log("Protocol: polling auth session status clientId=${beforePoll.effectiveClientId}.")
            val response = withConnectionRetries("PollAuthSessionStatus") { activeSession ->
                val current = exportHandle()
                activeSession.callServiceMethod(
                    methodName = "Authentication.PollAuthSessionStatus#1",
                    request = CAuthentication_PollAuthSessionStatus_Request.newBuilder()
                        .setClientId(current.effectiveClientId)
                        .setRequestId(com.google.protobuf.ByteString.copyFrom(current.requestId))
                        .build(),
                    parser = CAuthentication_PollAuthSessionStatus_Response.parser(),
                )
            }
            if (response.newClientId != 0L) {
                synchronized(stateLock) {
                    handle = handle.withNewClientId(response.newClientId)
                }
                debugLogger.log("Protocol: auth poll rotated clientId to ${response.newClientId}.")
            }
            if (response.refreshToken.isBlank()) {
                debugLogger.log("Protocol: auth session still pending.")
                return null
            }
            debugLogger.log(
                "Protocol: auth session completed account=${response.accountName.maskForLog()} refreshLength=${response.refreshToken.length} accessLength=${response.accessToken.length} guardDataUpdated=${response.newGuardData.isNotBlank()}.",
            )
            return SteamAuthPollResult(
                steamId = exportHandle().steamId,
                accountName = response.accountName,
                refreshToken = response.refreshToken,
                accessToken = response.accessToken,
                newGuardData = response.newGuardData.takeIf(String::isNotBlank),
            )
        } catch (error: Throwable) {
            debugLogger.log("Protocol: PollAuthSessionStatus failed ${error::class.java.simpleName}: ${error.message.orEmpty()}")
            throw error.asAuthenticationOperationException("轮询 Steam 登录状态失败", exportHandle())
        }
    }

    suspend fun awaitResult(): SteamAuthPollResult {
        debugLogger.log("Protocol: waiting for auth result pollIntervalMs=$pollingIntervalMillis.")
        var attempts = 0
        while (true) {
            attempts += 1
            debugLogger.log("Protocol: auth poll attempt=$attempts.")
            pollStatus()?.let { result ->
                debugLogger.log("Protocol: auth result received after $attempts poll attempt(s).")
                return result
            }
            delay(pollingIntervalMillis)
        }
    }

    override fun close() {
        val toClose = synchronized(stateLock) {
            if (closed) {
                null
            } else {
                closed = true
                session
            }
        }
        toClose?.close()
    }

    private fun requireActiveTransaction() {
        val current = synchronized(stateLock) {
            ensureOpenLocked()
            handle
        }
        if (current.isExpired()) {
            close()
            throw SteamAuthTransactionExpiredException(current.transactionId, current.expiresAtEpochMillis)
        }
    }

    private fun ensureOpenLocked() {
        check(!closed) { "Steam auth transaction is closed" }
    }

    private suspend fun <T> withConnectionRetries(
        operation: String,
        block: suspend (SteamCmSession) -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(MAX_CONNECTION_RECOVERY_ATTEMPTS + 1) { attempt ->
            requireActiveTransaction()
            try {
                return block(session)
            } catch (error: Throwable) {
                if (!error.isRecoverableAuthTransportFailure()) {
                    throw error
                }
                lastError = error
                if (attempt >= MAX_CONNECTION_RECOVERY_ATTEMPTS) {
                    return@repeat
                }
                debugLogger.log(
                    "Protocol: $operation transport failure; rebuilding CM connection attempt=${attempt + 1}/$MAX_CONNECTION_RECOVERY_ATTEMPTS " +
                        "error=${error::class.java.simpleName}: ${error.message.orEmpty()}.",
                )
                delay(CONNECTION_RECOVERY_BACKOFF_MILLIS * (attempt + 1L))
                try {
                    val replacement = connectionFactory()
                    try {
                        replaceSession(replacement)
                    } catch (replaceError: Throwable) {
                        replacement.close()
                        throw replaceError
                    }
                } catch (connectError: Throwable) {
                    if (!connectError.isRecoverableAuthTransportFailure()) {
                        throw connectError
                    }
                    lastError = connectError
                }
            }
        }
        throw SteamAuthTransientException(
            "$operation failed after ${MAX_CONNECTION_RECOVERY_ATTEMPTS + 1} connection attempt(s)",
            lastError,
        )
    }

    private fun replaceSession(replacement: SteamCmSession) {
        val previous = synchronized(stateLock) {
            ensureOpenLocked()
            val old = session
            session = replacement
            old
        }
        previous.close()
    }

    private companion object {
        private const val MAX_CONNECTION_RECOVERY_ATTEMPTS = 2
        private const val CONNECTION_RECOVERY_BACKOFF_MILLIS = 500L
    }
}

/** A persistence-safe description of one allowed Steam Guard route. */
class SteamAuthChallengeSnapshot @JvmOverloads constructor(
    val type: SteamGuardChallengeType,
    val message: String? = null,
) {
    internal fun toChallenge(): SteamGuardChallenge = SteamGuardChallenge(type, message)
}

enum class SteamAuthTransactionPhase {
    STARTED,
    AWAITING_GUARD_CODE,
    AWAITING_REMOTE_CONFIRMATION,
    POLLING,
}

/**
 * Short-lived, serializable auth transaction handle. It contains routing identifiers and optional
 * remembered guard data, but never the account password or encrypted password.
 */
class SteamAuthTransactionHandle private constructor(
    val transactionId: String,
    val accountName: String,
    val guardData: String?,
    val clientId: Long,
    val requestIdBase64: String,
    val steamId: Long,
    val pollingIntervalMillis: Long,
    challenges: List<SteamAuthChallengeSnapshot>,
    val newClientId: Long?,
    val selectedChallengeType: SteamGuardChallengeType?,
    val phase: SteamAuthTransactionPhase,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    val challenges: List<SteamAuthChallengeSnapshot> = challenges.toList()

    val effectiveClientId: Long
        get() = newClientId?.takeIf { it != 0L } ?: clientId

    val requestId: ByteArray
        get() = Base64.getDecoder().decode(requestIdBase64)

    val preferredChallenge: SteamAuthChallengeSnapshot?
        get() = challenges.firstOrNull()

    @JvmOverloads
    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        nowEpochMillis >= expiresAtEpochMillis

    @JvmOverloads
    fun remainingLifetimeMillis(nowEpochMillis: Long = System.currentTimeMillis()): Long =
        (expiresAtEpochMillis - nowEpochMillis).coerceAtLeast(0L)

    fun toJson(): String = HANDLE_JSON.encodeToString(
        PersistedSteamAuthTransaction.serializer(),
        PersistedSteamAuthTransaction(
            schemaVersion = HANDLE_SCHEMA_VERSION,
            transactionId = transactionId,
            accountName = accountName,
            guardData = guardData,
            clientId = clientId,
            requestIdBase64 = requestIdBase64,
            steamId = steamId,
            pollingIntervalMillis = pollingIntervalMillis,
            challenges = challenges.map {
                PersistedSteamAuthChallenge(type = it.type.name, message = it.message)
            },
            newClientId = newClientId,
            selectedChallengeType = selectedChallengeType?.name,
            phase = phase.name,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        ),
    )

    internal fun withSelectedChallenge(type: SteamGuardChallengeType): SteamAuthTransactionHandle = copy(
        selectedChallengeType = type,
        phase = when (type) {
            SteamGuardChallengeType.DeviceCode,
            SteamGuardChallengeType.EmailCode,
            -> SteamAuthTransactionPhase.AWAITING_GUARD_CODE

            SteamGuardChallengeType.DeviceConfirmation,
            SteamGuardChallengeType.EmailConfirmation,
            -> SteamAuthTransactionPhase.AWAITING_REMOTE_CONFIRMATION

            else -> SteamAuthTransactionPhase.POLLING
        },
    )

    internal fun withPhase(value: SteamAuthTransactionPhase): SteamAuthTransactionHandle = copy(phase = value)

    internal fun withNewClientId(value: Long): SteamAuthTransactionHandle = copy(newClientId = value)

    private fun copy(
        newClientId: Long? = this.newClientId,
        selectedChallengeType: SteamGuardChallengeType? = this.selectedChallengeType,
        phase: SteamAuthTransactionPhase = this.phase,
    ): SteamAuthTransactionHandle = SteamAuthTransactionHandle(
        transactionId = transactionId,
        accountName = accountName,
        guardData = guardData,
        clientId = clientId,
        requestIdBase64 = requestIdBase64,
        steamId = steamId,
        pollingIntervalMillis = pollingIntervalMillis,
        challenges = challenges,
        newClientId = newClientId,
        selectedChallengeType = selectedChallengeType,
        phase = phase,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    override fun toString(): String =
        "SteamAuthTransactionHandle(transactionId=$transactionId, account=${accountName.maskForLog()}, " +
            "steamId=$steamId, clientId=$clientId, newClientId=$newClientId, phase=$phase, " +
            "challenge=$selectedChallengeType, expiresAtEpochMillis=$expiresAtEpochMillis)"

    companion object {
        const val DEFAULT_LIFETIME_MILLIS: Long = 4L * 60L * 1_000L
        private const val HANDLE_SCHEMA_VERSION = 1
        private val HANDLE_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @JvmStatic
        fun fromJson(value: String): SteamAuthTransactionHandle {
            val persisted = HANDLE_JSON.decodeFromString(PersistedSteamAuthTransaction.serializer(), value)
            require(persisted.schemaVersion == HANDLE_SCHEMA_VERSION) {
                "Unsupported Steam auth transaction schema ${persisted.schemaVersion}"
            }
            val challenges = persisted.challenges.map { challenge ->
                SteamAuthChallengeSnapshot(
                    type = enumValueOrUnknown(challenge.type),
                    message = challenge.message,
                )
            }
            val selected = persisted.selectedChallengeType?.let(::enumValueOrUnknown)
            val phase = runCatching { SteamAuthTransactionPhase.valueOf(persisted.phase) }
                .getOrDefault(SteamAuthTransactionPhase.STARTED)
            val decodedRequestId = Base64.getDecoder().decode(persisted.requestIdBase64)
            require(decodedRequestId.isNotEmpty()) { "Steam auth request ID is empty" }
            require(persisted.transactionId.isNotBlank()) { "Steam auth transaction ID is empty" }
            require(persisted.accountName.isNotBlank()) { "Steam auth account name is empty" }
            require(persisted.clientId != 0L) { "Steam auth client ID is empty" }
            require(persisted.steamId != 0L) { "Steam auth SteamID is empty" }
            require(persisted.pollingIntervalMillis > 0L) { "Steam auth poll interval is invalid" }
            require(persisted.expiresAtEpochMillis > persisted.createdAtEpochMillis) {
                "Steam auth transaction deadline is invalid"
            }
            return SteamAuthTransactionHandle(
                transactionId = persisted.transactionId,
                accountName = persisted.accountName,
                guardData = persisted.guardData?.takeIf(String::isNotBlank),
                clientId = persisted.clientId,
                requestIdBase64 = persisted.requestIdBase64,
                steamId = persisted.steamId,
                pollingIntervalMillis = persisted.pollingIntervalMillis,
                challenges = challenges,
                newClientId = persisted.newClientId?.takeIf { it != 0L },
                selectedChallengeType = selected,
                phase = phase,
                createdAtEpochMillis = persisted.createdAtEpochMillis,
                expiresAtEpochMillis = persisted.expiresAtEpochMillis,
            )
        }

        internal fun create(
            accountName: String,
            guardData: String?,
            steamId: Long,
            clientId: Long,
            requestId: ByteArray,
            pollingIntervalMillis: Long,
            challenges: List<SteamGuardChallenge>,
            nowEpochMillis: Long = System.currentTimeMillis(),
        ): SteamAuthTransactionHandle {
            require(requestId.isNotEmpty()) { "Steam auth request ID is empty" }
            require(accountName.isNotBlank()) { "Steam auth account name is empty" }
            require(clientId != 0L) { "Steam auth client ID is empty" }
            require(steamId != 0L) { "Steam auth SteamID is empty" }
            return SteamAuthTransactionHandle(
                transactionId = UUID.randomUUID().toString(),
                accountName = accountName.trim(),
                guardData = guardData?.takeIf(String::isNotBlank),
                clientId = clientId,
                requestIdBase64 = Base64.getEncoder().encodeToString(requestId),
                steamId = steamId,
                pollingIntervalMillis = pollingIntervalMillis.coerceAtLeast(1_000L),
                challenges = challenges.map { SteamAuthChallengeSnapshot(it.type, it.message) },
                newClientId = null,
                selectedChallengeType = null,
                phase = SteamAuthTransactionPhase.STARTED,
                createdAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = nowEpochMillis + DEFAULT_LIFETIME_MILLIS,
            )
        }

        private fun enumValueOrUnknown(value: String): SteamGuardChallengeType =
            runCatching { SteamGuardChallengeType.valueOf(value) }.getOrDefault(SteamGuardChallengeType.Unknown)
    }
}

class SteamAuthTransactionExpiredException(
    val transactionId: String,
    val deadlineEpochMillis: Long,
) : SteamProtocolException("Steam auth transaction expired at $deadlineEpochMillis")

class SteamAuthTransientException(
    message: String,
    cause: Throwable? = null,
) : SteamProtocolException(message, cause)

@Serializable
private data class PersistedSteamAuthTransaction(
    val schemaVersion: Int,
    val transactionId: String,
    val accountName: String,
    val guardData: String? = null,
    val clientId: Long,
    val requestIdBase64: String,
    val steamId: Long,
    val pollingIntervalMillis: Long,
    val challenges: List<PersistedSteamAuthChallenge>,
    val newClientId: Long? = null,
    val selectedChallengeType: String? = null,
    val phase: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
private data class PersistedSteamAuthChallenge(
    val type: String,
    val message: String? = null,
)

private fun SteamGuardChallenge.sortOrder(): Int =
    when (type) {
        SteamGuardChallengeType.None -> 0
        SteamGuardChallengeType.DeviceConfirmation -> 1
        SteamGuardChallengeType.DeviceCode -> 2
        SteamGuardChallengeType.EmailCode -> 3
        SteamGuardChallengeType.EmailConfirmation -> 4
        SteamGuardChallengeType.MachineToken -> 5
        SteamGuardChallengeType.LegacyMachineAuth -> 6
        SteamGuardChallengeType.Unknown -> 7
    }

private fun mapChallenge(source: CAuthentication_AllowedConfirmation): SteamGuardChallenge =
    SteamGuardChallenge(
        type = when (source.confirmationType) {
            EAuthSessionGuardType.k_EAuthSessionGuardType_None -> SteamGuardChallengeType.None
            EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode -> SteamGuardChallengeType.EmailCode
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode -> SteamGuardChallengeType.DeviceCode
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation -> SteamGuardChallengeType.DeviceConfirmation
            EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation -> SteamGuardChallengeType.EmailConfirmation
            EAuthSessionGuardType.k_EAuthSessionGuardType_MachineToken -> SteamGuardChallengeType.MachineToken
            EAuthSessionGuardType.k_EAuthSessionGuardType_LegacyMachineAuth -> SteamGuardChallengeType.LegacyMachineAuth
            else -> SteamGuardChallengeType.Unknown
        },
        message = source.associatedMessage.takeIf(String::isNotBlank),
    )

private fun SteamGuardChallengeType.toProto(): EAuthSessionGuardType =
    when (this) {
        SteamGuardChallengeType.None -> EAuthSessionGuardType.k_EAuthSessionGuardType_None
        SteamGuardChallengeType.EmailCode -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
        SteamGuardChallengeType.DeviceCode -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
        SteamGuardChallengeType.DeviceConfirmation -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation
        SteamGuardChallengeType.EmailConfirmation -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation
        SteamGuardChallengeType.MachineToken -> EAuthSessionGuardType.k_EAuthSessionGuardType_MachineToken
        SteamGuardChallengeType.LegacyMachineAuth -> EAuthSessionGuardType.k_EAuthSessionGuardType_LegacyMachineAuth
        SteamGuardChallengeType.Unknown -> EAuthSessionGuardType.k_EAuthSessionGuardType_Unknown
    }

private fun encryptPassword(
    password: String,
    publicKey: CAuthentication_GetPasswordRSAPublicKey_Response,
): String {
    val modulus = BigInteger(1, decodeHex(publicKey.publickeyMod))
    val exponent = BigInteger(1, decodeHex(publicKey.publickeyExp))
    val keySpec = RSAPublicKeySpec(modulus, exponent)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(keySpec))
    return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
}

private fun decodeHex(value: String): ByteArray {
    require(value.length % 2 == 0) { "Invalid hex input length" }
    return ByteArray(value.length / 2) { index ->
        val offset = index * 2
        value.substring(offset, offset + 2).toInt(16).toByte()
    }
}

private fun List<SteamGuardChallenge>.summaryForLog(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(separator = ",") { challenge ->
            buildString {
                append(challenge.type.name)
                challenge.message
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        append("(message)")
                    }
            }
        }
    }

private fun String?.maskForLog(): String =
    this
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            when {
                value.length <= 2 -> "*".repeat(value.length)
                else -> "${value.first()}***${value.last()}"
            }
        }
        ?: "-"

private fun ((String) -> Unit)?.log(line: String) {
    this?.invoke(line)
}

internal fun buildBeginAuthSessionRequest(
    details: SteamAuthSessionDetails,
    encryptedPassword: String,
    encryptionTimestamp: Long,
): CAuthentication_BeginAuthSessionViaCredentials_Request {
    val builder = CAuthentication_BeginAuthSessionViaCredentials_Request.newBuilder()
        .setAccountName(details.username)
        .setEncryptedPassword(encryptedPassword)
        .setEncryptionTimestamp(encryptionTimestamp)
        .setPersistence(
            if (details.isPersistentSession) {
                ESessionPersistence.k_ESessionPersistence_Persistent
            } else {
                ESessionPersistence.k_ESessionPersistence_Ephemeral
            },
        )
        .setWebsiteId(details.websiteId)
        .setDeviceDetails(
            CAuthentication_DeviceDetails.newBuilder()
                .setDeviceFriendlyName(details.deviceFriendlyName)
                .setPlatformType(EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient)
                .setOsType(details.clientOsType)
                .build(),
        )

    details.guardData
        ?.takeIf(String::isNotBlank)
        ?.let(builder::setGuardData)

    return builder.build()
}

private fun Throwable.asAuthenticationException(prefix: String): SteamAuthenticationException =
    when (this) {
        is SteamAuthenticationException -> this
        is SteamServiceMethodException -> SteamAuthenticationException(
            resultCode = resultCode,
            message = buildSteamAuthenticationErrorMessage(
                prefix = prefix,
                resultCode = resultCode,
                detail = steamMessage,
            ),
            cause = this,
        )

        else -> SteamAuthenticationException(
            resultCode = 2,
            message = listOfNotNull(prefix, message).joinToString(": "),
            cause = this,
        )
    }

private fun Throwable.asAuthenticationStartException(prefix: String): Throwable =
    if (isRecoverableAuthTransportFailure()) {
        SteamAuthTransientException(
            message = listOfNotNull(prefix, message).joinToString(": "),
            cause = this,
        )
    } else {
        asAuthenticationException(prefix)
    }

private fun Throwable.asAuthenticationOperationException(
    prefix: String,
    handle: SteamAuthTransactionHandle,
): Throwable =
    when (this) {
        is SteamAuthTransactionExpiredException -> this
        is SteamAuthTransientException -> this
        is SteamAuthenticationException -> this
        is SteamServiceMethodException -> {
            if (resultCode == STEAM_RESULT_EXPIRED) {
                SteamAuthTransactionExpiredException(handle.transactionId, handle.expiresAtEpochMillis)
            } else {
                SteamAuthenticationException(
                    resultCode = resultCode,
                    message = buildSteamAuthenticationErrorMessage(
                        prefix = prefix,
                        resultCode = resultCode,
                        detail = steamMessage,
                    ),
                    cause = this,
                )
            }
        }

        else -> SteamAuthenticationException(
            resultCode = 2,
            message = listOfNotNull(prefix, message).joinToString(": "),
            cause = this,
        )
    }

private fun Throwable.isRecoverableAuthTransportFailure(): Boolean =
    when (this) {
        is SteamServiceMethodException -> false
        is SteamAuthenticationException -> false
        is SteamAuthTransactionExpiredException -> false
        is TimeoutCancellationException -> true
        is IOException -> true
        is SteamProtocolException -> cause?.isRecoverableAuthTransportFailure() ?: true
        else -> cause?.isRecoverableAuthTransportFailure() ?: false
    }

private const val STEAM_RESULT_EXPIRED = 27
private const val STEAM_RESULT_DUPLICATE_REQUEST = 29
