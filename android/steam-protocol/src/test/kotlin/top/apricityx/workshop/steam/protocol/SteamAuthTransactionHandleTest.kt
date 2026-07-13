package top.apricityx.workshop.steam.protocol

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Request
import top.apricityx.workshop.steam.proto.CAuthentication_PollAuthSessionStatus_Response
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamAuthTransactionHandleTest {
    @Test
    fun jsonRoundTripPreservesResumeMaterialWithoutPassword() {
        val handle = createHandle(nowEpochMillis = 1_000L)
            .withSelectedChallenge(SteamGuardChallengeType.DeviceConfirmation)
            .withNewClientId(44L)

        val encoded = handle.toJson()
        val decoded = SteamAuthTransactionHandle.fromJson(encoded)

        assertEquals(handle.transactionId, decoded.transactionId)
        assertEquals("test-account", decoded.accountName)
        assertEquals("remembered-guard", decoded.guardData)
        assertEquals(11L, decoded.clientId)
        assertEquals(44L, decoded.newClientId)
        assertEquals(44L, decoded.effectiveClientId)
        assertEquals(22L, decoded.steamId)
        assertEquals(1_500L, decoded.pollingIntervalMillis)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.requestId)
        assertEquals(SteamGuardChallengeType.DeviceConfirmation, decoded.selectedChallengeType)
        assertEquals(SteamAuthTransactionPhase.AWAITING_REMOTE_CONFIRMATION, decoded.phase)
        assertEquals(SteamGuardChallengeType.DeviceConfirmation, decoded.preferredChallenge?.type)
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(decoded.toString().contains("remembered-guard"))
    }

    @Test
    fun deadlineAndEmptyChallengeListRemainRecoverable() {
        val createdAt = 10_000L
        val empty = SteamAuthTransactionHandle.create(
            accountName = "test-account",
            guardData = null,
            steamId = 22L,
            clientId = 11L,
            requestId = byteArrayOf(9),
            pollingIntervalMillis = 1_000L,
            challenges = emptyList(),
            nowEpochMillis = createdAt,
        )
        val deadline = createdAt + SteamAuthTransactionHandle.DEFAULT_LIFETIME_MILLIS

        assertTrue(empty.challenges.isEmpty())
        assertFalse(empty.isExpired(deadline - 1L))
        assertTrue(empty.isExpired(deadline))
        assertEquals(1L, empty.remainingLifetimeMillis(deadline - 1L))
        assertEquals(0L, empty.remainingLifetimeMillis(deadline))
        assertTrue(SteamAuthTransactionHandle.fromJson(empty.toJson()).challenges.isEmpty())
    }

    @Test
    fun selectedChallengeControlsPersistedPhase() {
        val handle = createHandle()

        assertEquals(
            SteamAuthTransactionPhase.AWAITING_GUARD_CODE,
            handle.withSelectedChallenge(SteamGuardChallengeType.DeviceCode).phase,
        )
        assertEquals(
            SteamAuthTransactionPhase.AWAITING_REMOTE_CONFIRMATION,
            handle.withSelectedChallenge(SteamGuardChallengeType.DeviceConfirmation).phase,
        )
        assertEquals(
            SteamAuthTransactionPhase.POLLING,
            handle.withSelectedChallenge(SteamGuardChallengeType.None).phase,
        )
    }

    @Test
    fun pollPersistsServerRotatedClientIdAcrossExport() = runBlocking {
        val response = CAuthentication_PollAuthSessionStatus_Response.newBuilder()
            .setNewClientId(77L)
            .build()
        val cmSession = ResponseSession(response)
        val authSession = SteamCredentialAuthSession(
            session = cmSession,
            handle = createHandle(),
            connectionFactory = { cmSession },
        )

        assertNull(authSession.pollStatus())
        assertEquals(11L, cmSession.lastPollClientId)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), cmSession.lastPollRequestId)
        assertEquals(77L, authSession.exportHandle().newClientId)
        assertEquals(77L, authSession.exportHandle().effectiveClientId)
        assertEquals(77L, SteamAuthTransactionHandle.fromJson(authSession.exportHandle().toJson()).effectiveClientId)
        assertNull(authSession.pollStatus())
        assertEquals(77L, cmSession.lastPollClientId)
        authSession.close()
        assertTrue(cmSession.closed)
    }

    @Test
    fun pollRebuildsConnectionAfterTransientTransportFailure() = runBlocking {
        val initial = FailingSession()
        val replacement = ResponseSession(CAuthentication_PollAuthSessionStatus_Response.getDefaultInstance())
        var reconnects = 0
        val authSession = SteamCredentialAuthSession(
            session = initial,
            handle = createHandle(),
            connectionFactory = {
                reconnects += 1
                replacement
            },
        )

        assertNull(authSession.pollStatus())
        assertEquals(1, reconnects)
        assertTrue(initial.closed)
        assertEquals(11L, replacement.lastPollClientId)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), replacement.lastPollRequestId)
        authSession.close()
        assertTrue(replacement.closed)
    }

    private fun createHandle(nowEpochMillis: Long = System.currentTimeMillis()): SteamAuthTransactionHandle =
        SteamAuthTransactionHandle.create(
            accountName = "test-account",
            guardData = "remembered-guard",
            steamId = 22L,
            clientId = 11L,
            requestId = byteArrayOf(1, 2, 3, 4),
            pollingIntervalMillis = 1_500L,
            challenges = listOf(
                SteamGuardChallenge(SteamGuardChallengeType.DeviceConfirmation),
                SteamGuardChallenge(SteamGuardChallengeType.DeviceCode),
                SteamGuardChallenge(SteamGuardChallengeType.None),
            ),
            nowEpochMillis = nowEpochMillis,
        )

    private class ResponseSession(
        private val pollResponse: CAuthentication_PollAuthSessionStatus_Response,
    ) : StubSession() {
        var lastPollClientId: Long? = null
            private set
        var lastPollRequestId: ByteArray? = null
            private set

        override suspend fun <T : MessageLite> callServiceMethod(
            methodName: String,
            request: MessageLite,
            parser: Parser<T>,
        ): T {
            assertEquals("Authentication.PollAuthSessionStatus#1", methodName)
            val poll = request as CAuthentication_PollAuthSessionStatus_Request
            lastPollClientId = poll.clientId
            lastPollRequestId = poll.requestId.toByteArray()
            @Suppress("UNCHECKED_CAST")
            return pollResponse as T
        }
    }

    private class FailingSession : StubSession() {
        override suspend fun <T : MessageLite> callServiceMethod(
            methodName: String,
            request: MessageLite,
            parser: Parser<T>,
        ): T = throw IOException("disconnected for test")
    }

    private abstract class StubSession : SteamCmSession {
        private val state = MutableStateFlow<SessionContext?>(null)
        var closed: Boolean = false
            private set

        override val currentSession: StateFlow<SessionContext?> = state

        override suspend fun connect(servers: List<CmServer>) = Unit

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext =
            error("Not used by auth transaction tests")

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = error("Not used by auth transaction tests")

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray =
            error("Not used by auth transaction tests")

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo =
            error("Not used by auth transaction tests")

        override fun close() {
            closed = true
        }
    }
}
