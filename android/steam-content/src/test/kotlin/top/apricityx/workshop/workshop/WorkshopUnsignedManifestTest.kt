package top.apricityx.workshop.workshop

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import top.apricityx.workshop.steam.proto.CPublishedFile_GetChangeHistory_Response
import top.apricityx.workshop.steam.proto.CPublishedFile_GetItemInfo_Response
import top.apricityx.workshop.steam.proto.PublishedFileAuthorSnapshot
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkshopUnsignedManifestTest {
    private val highIds = listOf(9223372036854775808uL, 18446744073709551614uL)

    @Test
    fun authorSnapshotWireValuesReachSelectedVariantWithoutFallingBack() = runBlocking {
        val response = CPublishedFile_GetItemInfo_Response.newBuilder()
            .addWorkshopItems(
                CPublishedFile_GetItemInfo_Response.WorkshopItemInfo.newBuilder()
                    .setPublishedFileId(7L)
                    .setManifestId(9L)
                    .apply {
                        (highIds + 0uL).forEachIndexed { index, id ->
                            addAuthorSnapshots(
                                PublishedFileAuthorSnapshot.newBuilder()
                                    .setManifestId(id.toLong())
                                    .setTimestamp(100 + index)
                                    .setGameBranchMin("branch-$index")
                                    .setGameBranchMax("branch-$index")
                                    .build(),
                            )
                        }
                    }
                    .build(),
            )
            .build()
        val http = http("\"hcontent_file\":0")
        val protocol = protocol(http, response)
        val webApi = PublishedFileResolver(http)
        var historyCalls = 0
        val candidates = WorkshopVariantResolver(
            webApi,
            PublishedFileItemInfoProvider { appId, id -> protocol.getItemInfo(null, appId, listOf(id)).single() },
            PublishedFileChangeHistoryProvider { historyCalls++; emptyList() },
        ).resolveCandidates(2868840u, 7uL)

        val snapshots = candidates.filter { it.source == WorkshopVariantResolver.SOURCE_AUTHOR_SNAPSHOT }
        assertEquals(highIds.toSet(), snapshots.map { it.manifestId }.toSet())
        assertEquals(0, historyCalls)
        for (candidate in snapshots) {
            val expected = highIds[candidate.branch.removePrefix("branch-").toInt()]
            val resolved = assertIs<ResolvedWorkshopItem.UgcManifestItem>(
                webApi.resolve(2868840u, 7uL, selectedVariant = candidate.toResolvedVariant()),
            )
            assertEquals(expected, resolved.manifestId)
            assertEquals(candidate.branch, resolved.resolution.requestedBranch)
            assertEquals(expected.toString(), metadata(resolved, "resolved_manifest_id"))
        }
    }

    @Test
    fun savedHistoryWireValuesReachVariantResolverWhenAuthorSnapshotsAreAbsent() = runBlocking {
        val response = CPublishedFile_GetChangeHistory_Response.newBuilder().apply {
            (highIds + 0uL).forEachIndexed { index, id ->
                addChanges(
                    CPublishedFile_GetChangeHistory_Response.ChangeLog.newBuilder()
                        .setManifestId(id.toLong())
                        .setSavedSnapshot(true)
                        .setTimestamp(100 + index)
                        .setSnapshotGameBranchMin("branch-$index")
                        .setSnapshotGameBranchMax("branch-$index")
                        .build(),
                )
            }
            addChanges(CPublishedFile_GetChangeHistory_Response.ChangeLog.newBuilder().setManifestId(12L).setSavedSnapshot(false).build())
        }.build()
        val http = http("\"hcontent_file\":0")
        val protocol = protocol(http, response)
        val webApi = PublishedFileResolver(http)
        val candidates = WorkshopVariantResolver(
            webApi,
            changeHistoryProvider = PublishedFileChangeHistoryProvider { id -> protocol.getChangeHistory(null, id) },
        ).resolveCandidates(2868840u, 7uL)

        assertEquals(highIds.toSet(), candidates.map { it.manifestId }.toSet())
        assertEquals(setOf(WorkshopVariantResolver.SOURCE_CHANGE_HISTORY_SNAPSHOT), candidates.map { it.source }.toSet())
        for (candidate in candidates) {
            val expected = highIds[candidate.branch.removePrefix("branch-").toInt()]
            val resolved = assertIs<ResolvedWorkshopItem.UgcManifestItem>(
                webApi.resolve(2868840u, 7uL, selectedVariant = candidate.toResolvedVariant()),
            )
            assertEquals(expected, resolved.manifestId)
            assertEquals(expected.toString(), metadata(resolved, "resolved_manifest_id"))
        }
    }

    @Test
    fun webApiQuotedAndNumericUint64ValuesRemainExactThroughResolution() = runBlocking {
        for (expected in highIds) {
            for (token in listOf(expected.toString(), "\"$expected\"")) {
                val resolver = PublishedFileResolver(http("\"hcontent_file\":$token"))
                val candidate = WorkshopVariantResolver(resolver).resolveCandidates(2868840u, 7uL).single()
                assertEquals(expected, candidate.manifestId)
                assertEquals(WorkshopVariantResolver.SOURCE_WEBAPI_HCONTENT_FILE, candidate.source)
                val resolved = assertIs<ResolvedWorkshopItem.UgcManifestItem>(resolver.resolve(2868840u, 7uL))
                assertEquals(expected, resolved.manifestId)
                assertEquals(expected.toString(), metadata(resolved, "webapi_hcontent_file"))
                assertEquals(expected.toString(), metadata(resolved, "resolved_manifest_id"))
            }
        }
    }

    @Test
    fun zeroNullAndMissingWebApiHandlesDoNotProduceManifestCandidates() = runBlocking {
        for (field in listOf("", "\"hcontent_file\":null,", "\"hcontent_file\":0,", "\"hcontent_file\":\"0\",")) {
            val resolver = PublishedFileResolver(http("${field}\"file_url\":\"https://fixture.invalid/item.bin\""))
            val candidate = WorkshopVariantResolver(resolver).resolveCandidates(2868840u, 7uL).single()
            assertEquals(null, candidate.manifestId)
            assertEquals(WorkshopVariantResolver.SOURCE_DIRECT_FILE_URL, candidate.source)
            assertIs<ResolvedWorkshopItem.DirectUrlItem>(resolver.resolve(2868840u, 7uL))
        }
    }

    private fun metadata(item: ResolvedWorkshopItem, key: String): String =
        Json.parseToJsonElement(item.metadataJson).jsonObject.getValue(key).jsonPrimitive.content

    private fun http(fields: String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val payload = when (chain.request().url.encodedPath) {
            "/ISteamDirectory/GetCMListForConnect/v1/" -> """{"response":{"serverlist":[]}}"""
            "/ISteamRemoteStorage/GetPublishedFileDetails/v1/" ->
                """{"response":{"publishedfiledetails":[{"result":1,"title":"Synthetic fixture","consumer_app_id":2868840,$fields}]}}"""
            else -> error("Unexpected request: ${chain.request().url}")
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private fun protocol(http: OkHttpClient, response: MessageLite): SteamPublishedFileClient =
        SteamPublishedFileClient(SteamDirectoryClient(client = http)) { WireSession(response.toByteArray()) }

    private class WireSession(private val responseBytes: ByteArray) : SteamCmSession {
        override val currentSession: StateFlow<SessionContext?> = MutableStateFlow(null)
        override suspend fun connect(servers: List<CmServer>) = Unit
        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext = SessionContext(1, 0L, 0u, 30)
        override suspend fun connectWithRefreshToken(servers: List<CmServer>, account: SteamAccountSession): SessionContext = error("No account fixture")
        override suspend fun <T : MessageLite> callServiceMethod(methodName: String, request: MessageLite, parser: Parser<T>): T =
            parser.parseFrom(responseBytes)
        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray = error("No content fixture")
        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = error("No appinfo fixture")
        override fun close() = Unit
    }
}
