package com.godot.game.steam.auth

import android.content.Intent
import android.os.Looper
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import top.apricityx.workshop.steam.proto.CAuthentication_AllowedConfirmation
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Request
import top.apricityx.workshop.steam.proto.CAuthentication_BeginAuthSessionViaCredentials_Response
import top.apricityx.workshop.steam.proto.CAuthentication_GetPasswordRSAPublicKey_Response
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Response
import top.apricityx.workshop.steam.proto.CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response
import top.apricityx.workshop.steam.proto.EAuthSessionGuardType
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamAuthTransactionHandle
import top.apricityx.workshop.steam.protocol.SteamAuthenticationClient
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
import top.apricityx.workshop.steam.protocol.SteamServiceMethodException

/** Exercises the actual binder, manager and protocol, replacing only HTTP/CM transports. */
internal class SteamAuthTestFixture : AutoCloseable {
    private val controller = Robolectric.buildService(SteamAuthForegroundService::class.java).create()
    val service: SteamAuthForegroundService = controller.get()
    val binder = service.onBind(Intent()) as SteamAuthForegroundService.LocalBinder
    val sessions = CopyOnWriteArrayList<FixtureSession>()
    var challengeType = SteamGuardChallengeType.DeviceCode
    var beginFailure: Int? = null
    private val worker = getField(service, "worker") as ScheduledExecutorService
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        // Never proceed: even an accidental URL change cannot contact a real account/server.
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Synthetic directory")
            .body("""{"response":{"serverlist":[{"endpoint":"cm.invalid:443","type":"websockets"}]}}""".toResponseBody())
            .build()
    }.build()

    init {
        val manager = getField(service, "manager") as SteamAuthTransactionManager
        setField(manager, "protocolClient", SteamAuthenticationClient(SteamDirectoryClient(http)) {
            FixtureSession(challengeType, beginFailure).also(sessions::add)
        })
    }

    fun begin(account: String = "pending-account"): SteamAuthTransactionHandle {
        binder.begin(account, "synthetic-password")
        drain()
        assertEquals(binder.getSnapshot().message, SteamAuthForegroundService.Stage.WAITING_CODE, binder.getSnapshot().stage)
        val handle = SteamAuthStore.readPendingAuthTransaction(service)
        assertNotNull(handle)
        return handle!!
    }

    fun complete(account: String) {
        val handle = begin(account)
        binder.submitGuardCode(handle.transactionId, handle.selectedChallengeType!!, "12345")
        drain()
        assertEquals(SteamAuthForegroundService.Stage.SUCCESS, binder.getSnapshot().stage)
    }

    fun resume() {
        SteamAuthForegroundService.resumePending(service)
        service.onStartCommand(shadowOf(service.application).nextStartedService, 0, 1)
        drain()
    }

    fun drain() {
        // Begin/submit may enqueue an immediate poll behind the first barrier.
        repeat(2) { worker.submit {}.get(10, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    override fun close() {
        controller.destroy()
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    class FixtureSession(
        private val challengeType: SteamGuardChallengeType,
        private val beginFailure: Int?,
    ) : SteamCmSession {
        override val currentSession: StateFlow<SessionContext?> = MutableStateFlow(null)
        @Volatile var closed = false
            private set
        var onClose: (() -> Unit)? = null
        private var accountName = "pending-account"

        override suspend fun connect(servers: List<CmServer>) {
            check(servers.single().endpoint == "cm.invalid:443")
        }

        override suspend fun <T : MessageLite> callServiceMethod(
            methodName: String,
            request: MessageLite,
            parser: Parser<T>,
        ): T {
            val response = when (methodName) {
                "Authentication.GetPasswordRSAPublicKey#1" ->
                    CAuthentication_GetPasswordRSAPublicKey_Response.newBuilder()
                        .setPublickeyMod(publicKey.modulus.toString(16))
                        .setPublickeyExp(publicKey.publicExponent.toString(16).padStart(6, '0'))
                        .setTimestamp(1L)
                        .build()
                "Authentication.BeginAuthSessionViaCredentials#1" -> {
                    beginFailure?.let { throw SteamServiceMethodException(methodName, it, "Synthetic failure") }
                    accountName = (request as CAuthentication_BeginAuthSessionViaCredentials_Request).accountName
                    val guard = if (challengeType == SteamGuardChallengeType.EmailCode) {
                        EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                    } else {
                        EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                    }
                    CAuthentication_BeginAuthSessionViaCredentials_Response.newBuilder()
                        .setClientId(11L)
                        .setSteamid(22L)
                        .setRequestId(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
                        .setInterval(1f)
                        .addAllowedConfirmations(CAuthentication_AllowedConfirmation.newBuilder().setConfirmationType(guard))
                        .build()
                }
                "Authentication.UpdateAuthSessionWithSteamGuardCode#1" ->
                    CAuthentication_UpdateAuthSessionWithSteamGuardCode_Response.getDefaultInstance()
                "Authentication.PollAuthSessionStatus#1" ->
                    CAuthentication_PollAuthSessionStatus_Response.newBuilder()
                        .setAccountName(accountName)
                        .setRefreshToken("synthetic-token-$accountName")
                        .setAccessToken("synthetic-access-token")
                        .build()
                else -> error("Unexpected protocol request: $methodName")
            }
            return parser.parseFrom(response.toByteArray())
        }

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext = error("Not an anonymous login")
        override suspend fun connectWithRefreshToken(servers: List<CmServer>, account: SteamAccountSession): SessionContext =
            error("No real refresh-token connection is allowed")
        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray = error("Not a download")
        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = error("Not a catalog request")
        override fun close() {
            closed = true
            onClose?.invoke()
        }
    }

    companion object {
        private val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().public as RSAPublicKey

        fun getField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

        fun setField(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
    }
}
