package top.apricityx.workshop.workshop

import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import top.apricityx.workshop.steam.proto.ContentManifestMetadata
import top.apricityx.workshop.steam.proto.ContentManifestPayload

class SpireSupplyStationClientTest {
    @Test
    fun downloadsEncryptedContentWithoutSteamAndRefreshesRejectedToken() = runBlocking {
        val fixture = Fixture()
        val requests = mutableListOf<Request>()
        var tokenCalls = 0
        val client = client { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/descriptor") ->
                    response(request, fixture.descriptor())
                request.url.encodedPath.endsWith("/cdn/token") -> {
                    tokenCalls++
                    response(
                        request,
                        """{"origin":"https://cdn.example","query":"token=credential-$tokenCalls&expiration_time=4102444800"}""",
                    )
                }
                request.url.encodedPath.contains("/manifest/") -> {
                    assertTrue(request.url.encodedPath.endsWith("/18446744073709551614"))
                    if (request.url.queryParameter("token") == "credential-1")
                        response(request, fixture.manifest)
                    else response(request, "denied", 403)
                }
                request.url.encodedPath.contains("/chunk/") -> {
                    if (request.url.queryParameter("token") == "credential-2")
                        response(request, fixture.chunk)
                    else response(request, "denied", 403)
                }
                else -> error("Unexpected network request")
            }
        }
        withOutput { output ->
            val events = engine(client).download(request(output)).toList()
            assertTrue(events.last() is DownloadEvent.Completed, events.toString())
            assertContentEquals(fixture.content, File(output, "Fixture.json").readBytes())
            val resolution = events.filterIsInstance<DownloadEvent.Resolved>().single().resolution
            assertEquals(SpireSupplyStationClient.BRANCH, resolution.requestedBranch)
            assertTrue(
                resolution.matchedBranchMin.isEmpty() && resolution.matchedBranchMax.isEmpty()
            )
            val persisted =
                File(output, "metadata.json").readText() + File(output, "download.log").readText()
            assertFalse(persisted.contains("credential-"))
            assertFalse(persisted.contains(fixture.keyBase64))
            assertFalse(persisted.contains("18446744073709551614"))
        }
        assertEquals(2, tokenCalls)
        assertEquals(
            "true",
            requests
                .last { it.url.encodedPath.endsWith("/cdn/token") }
                .url
                .queryParameter("refresh"),
        )
        assertTrue(
            requests.all { it.header("Authorization") == null && it.header("Cookie") == null }
        )
    }

    @Test
    fun rejectsDescriptorForAnotherItemBeforeFetchingContent() = runBlocking {
        val fixture = Fixture()
        val client = client { request ->
            assertTrue(request.url.encodedPath.endsWith("/descriptor"))
            response(request, fixture.descriptor(publishedFileId = "999"))
        }
        withOutput { output ->
            val events = engine(client).download(request(output)).toList()
            assertTrue(events.last() is DownloadEvent.Failed)
            assertFalse(File(output, "Fixture.json").exists())
        }
    }

    @Test
    fun rejectsContentChangeWhileRefreshingManifestAuthorization() = runBlocking {
        val fixture = Fixture()
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/descriptor") ->
                    response(
                        request,
                        fixture.descriptor(
                            manifestId =
                                if (request.url.queryParameter("refresh") == "true") "43" else "42"
                        ),
                    )
                request.url.encodedPath.endsWith("/cdn/token") ->
                    response(
                        request,
                        """{"origin":"https://cdn.example","query":"token=rejected&expiration_time=4102444800"}""",
                    )
                request.url.encodedPath.contains("/manifest/") -> response(request, "denied", 403)
                else -> error("Changed content must not be downloaded")
            }
        }
        withOutput { output ->
            val events = engine(client).download(request(output)).toList()
            assertTrue(events.last() is DownloadEvent.Failed)
            assertFalse(File(output, "Fixture.json").exists())
        }
    }

    @Test
    fun keepsSteamPathWhenSupplyStationIsDisabled() = runBlocking {
        val content = "original Steam download"
        val client = client { request ->
            when (request.url.host) {
                "api.steampowered.com" ->
                    response(
                        request,
                        """{"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"7","consumer_app_id":2868840,"title":"fixture","filename":"fixture.bin","file_url":"https://steamcontent.example/file","file_size":${content.length}}]}}""",
                    )
                "steamcontent.example" -> response(request, content)
                else -> error("Disabled source must never contact the supply station")
            }
        }
        withOutput { output ->
            val events =
                engine(client).download(WorkshopDownloadRequest(2868840u, 7uL, output)).toList()
            assertTrue(events.last() is DownloadEvent.Completed, events.toString())
            assertEquals(content, File(output, "fixture.bin").readText())
        }
    }

    @Test
    fun preservesNestedAuthorizationErrorsWithoutMisclassifyingLocalPermissions() {
        val noLicense =
            IOException(
                "Unable to download UGC manifest",
                IOException("Depot request failed: NoLicense"),
            )
        val rejected =
            IOException("Failed to download chunk", IOException("Steam CDN request failed: 403"))
        assertTrue(isSteamAuthorizationFailure(noLicense.userVisibleDownloadFailureMessage()))
        assertTrue(isSteamAuthorizationFailure(rejected.userVisibleDownloadFailureMessage()))
        assertFalse(isSteamAuthorizationFailure("Permission denied writing MOD directory"))
        assertFalse(isSteamAuthorizationFailure("Network timeout"))
    }

    private fun engine(client: OkHttpClient) =
        WorkshopDownloadEngine.createDefault(
            client = client,
            sessionFactory = { error("Anonymous station downloads must not open Steam CM") },
        )

    private fun request(output: File) =
        WorkshopDownloadRequest(
            2868840u,
            7uL,
            output,
            branch = "public-beta",
            selectedVariant =
                WorkshopResolvedVariant("public-beta", 999uL, 2868840u, "steam_snapshot"),
            useSupplyStation = true,
        )

    private suspend fun withOutput(block: suspend (File) -> Unit) {
        val output = Files.createTempDirectory("supply-station-test").toFile()
        try {
            block(output)
        } finally {
            output.deleteRecursively()
        }
    }

    private fun client(handler: (Request) -> Response) =
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()

    private fun response(request: Request, body: String, status: Int = 200) =
        response(request, body.toByteArray(), status)

    private fun response(request: Request, body: ByteArray, status: Int = 200) =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fixture")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()

    private class Fixture {
        val content =
            """{"id":"Fixture","version":"1.0","has_dll":false,"has_pck":false}""".toByteArray()
        private val key = ByteArray(32) { it.toByte() }
        val keyBase64: String = Base64.getEncoder().encodeToString(key)
        private val sha = MessageDigest.getInstance("SHA-1").digest(content)
        val chunk = encrypt(zip(content))
        val manifest: ByteArray

        init {
            val mapping =
                ContentManifestPayload.FileMapping.newBuilder()
                    .setFilename(
                        Base64.getEncoder()
                            .encodeToString(encrypt("Fixture.json\u0000".toByteArray()))
                    )
                    .setSize(content.size.toLong())
                    .setShaContent(ByteString.copyFrom(sha))
                    .addChunks(
                        ContentManifestPayload.FileMapping.ChunkData.newBuilder()
                            .setSha(ByteString.copyFrom(sha))
                            .setCrc(steamAdler32(content).toInt())
                            .setOffset(0)
                            .setCbOriginal(content.size)
                            .setCbCompressed(chunk.size)
                    )
            val payload =
                ContentManifestPayload.newBuilder().addMappings(mapping).build().toByteArray()
            val metadata =
                ContentManifestMetadata.newBuilder()
                    .setDepotId(2868840)
                    .setGidManifest(42)
                    .setCreationTime(1)
                    .setFilenamesEncrypted(true)
                    .build()
                    .toByteArray()
            val bytes = ByteArrayOutputStream()
            bytes.write(intBytes(0x71F617D0))
            bytes.write(intBytes(payload.size))
            bytes.write(payload)
            bytes.write(intBytes(0x1F4812BE))
            bytes.write(intBytes(metadata.size))
            bytes.write(metadata)
            bytes.write(intBytes(0x32C415AB))
            manifest = zip(bytes.toByteArray())
        }

        fun descriptor(publishedFileId: String = "7", manifestId: String = "42") =
            """{
            "mode":"UGC","appId":2868840,"publishedFileId":"$publishedFileId","title":"fixture",
            "fileName":"fixture.zip","fileSizeBytes":${content.size},"contentVersion":"fixture-v1",
            "manifestId":"$manifestId","depotId":2868840,"requestCode":"18446744073709551614",
            "endpoints":[{"origin":"https://cdn.example","query":null}],"depotKeyBase64":"$keyBase64",
            "expiresAt":"2100-01-01T00:00:00Z"} """

        private fun encrypt(clear: ByteArray): ByteArray {
            val iv = ByteArray(16) { 3 }
            val keySpec = SecretKeySpec(key, "AES")
            val ecb =
                Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keySpec) }
            val cbc =
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
                }
            return ecb.doFinal(iv) + cbc.doFinal(clear)
        }

        private fun zip(bytes: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use {
                it.putNextEntry(ZipEntry("payload"))
                it.write(bytes)
                it.closeEntry()
            }
            return output.toByteArray()
        }

        private fun intBytes(value: Int) =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
