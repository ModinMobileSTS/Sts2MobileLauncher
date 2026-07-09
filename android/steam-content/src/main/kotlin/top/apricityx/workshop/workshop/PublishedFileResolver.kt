package top.apricityx.workshop.workshop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PublishedFileResolver(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: HttpUrl = "https://api.steampowered.com/".toHttpUrl(),
    private val language: String? = null,
) {
    suspend fun resolve(
        appId: UInt,
        publishedFileId: ULong,
        branch: String = "public",
        selectedVariant: WorkshopResolvedVariant? = null,
    ): ResolvedWorkshopItem = withContext(Dispatchers.IO) {
        val details = loadDetails(appId, publishedFileId)
        val title = details.title.takeIf(String::isNotBlank) ?: "Workshop $publishedFileId"
        val filename = details.filename.substringAfterLast('/').ifBlank { "${publishedFileId}.bin" }
        val requestedBranch = normalizeWorkshopBranch(selectedVariant?.branch ?: branch)

        selectedVariant?.manifestId?.let { selectedManifestId ->
            val depotId = selectedVariant.depotId ?: details.depotId(appId)
            val resolution = selectedVariant.toResolution(
                requestedBranch = requestedBranch,
                manifestId = selectedManifestId,
                depotId = depotId,
            )
            return@withContext ResolvedWorkshopItem.UgcManifestItem(
                manifestId = selectedManifestId,
                depotId = depotId,
                title = title,
                metadataJson = buildResolutionMetadata(appId, publishedFileId, details, resolution),
                resolution = resolution,
            )
        }

        if (!details.fileUrl.isNullOrBlank()) {
            val resolution = WorkshopItemResolution(
                requestedBranch = requestedBranch,
                source = selectedVariant?.source?.takeIf(String::isNotBlank) ?: SOURCE_DIRECT_FILE_URL,
                fallbackReason = selectedVariant?.fallbackReason.orEmpty(),
            )
            return@withContext ResolvedWorkshopItem.DirectUrlItem(
                fileName = filename,
                fileUrl = details.fileUrl,
                size = details.fileSize,
                title = title,
                metadataJson = buildResolutionMetadata(appId, publishedFileId, details, resolution),
                resolution = resolution,
            )
        }

        if (details.hcontentFile != null && details.hcontentFile > 0) {
            val manifestId = details.hcontentFile.toULong()
            val depotId = details.depotId(appId)
            val resolution = WorkshopItemResolution(
                requestedBranch = requestedBranch,
                manifestId = manifestId,
                depotId = depotId,
                source = selectedVariant?.source?.takeIf(String::isNotBlank) ?: SOURCE_WEBAPI_HCONTENT_FILE,
                fallbackReason = selectedVariant?.fallbackReason.orEmpty(),
            )
            return@withContext ResolvedWorkshopItem.UgcManifestItem(
                manifestId = manifestId,
                depotId = depotId,
                title = title,
                metadataJson = buildResolutionMetadata(appId, publishedFileId, details, resolution),
                resolution = resolution,
            )
        }

        throw WorkshopDownloadException("Unable to resolve workshop file_url or hcontent_file")
    }

    suspend fun loadDetails(
        appId: UInt,
        publishedFileId: ULong,
    ): PublishedFileWebApiDetails = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("itemcount", "1")
            .add("publishedfileids[0]", publishedFileId.toString())
            .add("includechildren", "true")
            .add("appid", appId.toString())
            .apply {
                language?.takeIf(String::isNotBlank)?.let { add("language", it) }
            }
            .build()

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("ISteamRemoteStorage/GetPublishedFileDetails/v1/").build())
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WorkshopDownloadException("GetPublishedFileDetails failed: ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            val envelope = json.decodeFromString<GetPublishedFileDetailsEnvelope>(payload)
            val details = envelope.response.publishedFileDetails.firstOrNull()
                ?: throw WorkshopDownloadException("Steam did not return workshop details")

            if (details.result != 1) {
                throw WorkshopDownloadException("Steam returned result=${details.result} for published file")
            }

            val fileType = details.fileType ?: WORKSHOP_FILE_TYPE_COMMUNITY
            if (fileType == WORKSHOP_FILE_TYPE_COLLECTION) {
                throw WorkshopDownloadException("Collections are not supported in this demo")
            }
            if (fileType !in supportedFileTypes) {
                throw WorkshopDownloadException("Unsupported workshop file type: $fileType")
            }

            PublishedFileWebApiDetails(
                title = details.title,
                filename = details.filename,
                fileUrl = details.fileUrl,
                fileSize = details.fileSize,
                fileType = fileType,
                hcontentFile = details.hcontentFile,
                consumerAppId = details.consumerAppId,
                rawJson = payload,
            )
        }
    }

    private fun buildResolutionMetadata(
        appId: UInt,
        publishedFileId: ULong,
        details: PublishedFileWebApiDetails,
        resolution: WorkshopItemResolution,
    ): String {
        val webApiDetails = runCatching { json.parseToJsonElement(details.rawJson) }.getOrNull()
        val metadata = buildJsonObject {
            put("schema", 2)
            put("published_file_id", publishedFileId.toString())
            put("app_id", appId.toString())
            put("requested_branch", resolution.requestedBranch)
            put("resolution_source", resolution.source)
            put("resolved_manifest_id", resolution.manifestId?.toString().orEmpty())
            put("resolved_depot_id", resolution.depotId?.toString().orEmpty())
            put("matched_branch_min", resolution.matchedBranchMin)
            put("matched_branch_max", resolution.matchedBranchMax)
            put("fallback_reason", resolution.fallbackReason)
            put("snapshot_timestamp", resolution.timestampEpochSeconds)
            put("webapi_hcontent_file", details.hcontentFile?.toString().orEmpty())
            put("webapi_consumer_app_id", details.consumerAppId?.toString().orEmpty())
            if (webApiDetails != null) {
                put("steam_webapi_details", webApiDetails)
            }
        }
        return json.encodeToString(metadata)
    }

    private fun WorkshopResolvedVariant.toResolution(
        requestedBranch: String,
        manifestId: ULong?,
        depotId: UInt?,
    ): WorkshopItemResolution = WorkshopItemResolution(
        requestedBranch = requestedBranch,
        matchedBranchMin = matchedBranchMin,
        matchedBranchMax = matchedBranchMax,
        manifestId = manifestId,
        depotId = depotId,
        source = source,
        fallbackReason = fallbackReason,
        timestampEpochSeconds = timestampEpochSeconds,
    )

    private companion object {
        const val WORKSHOP_FILE_TYPE_COMMUNITY = 0
        const val WORKSHOP_FILE_TYPE_COLLECTION = 2
        const val SOURCE_DIRECT_FILE_URL = "direct_file_url"
        const val SOURCE_WEBAPI_HCONTENT_FILE = "webapi_hcontent_file"
        val supportedFileTypes = setOf(0, 3, 5, 10, 11, 12)
    }
}

data class PublishedFileWebApiDetails(
    val title: String,
    val filename: String,
    val fileUrl: String?,
    val fileSize: Long?,
    val fileType: Int,
    val hcontentFile: Long?,
    val consumerAppId: Long?,
    val rawJson: String,
) {
    fun depotId(fallbackAppId: UInt): UInt = (consumerAppId?.takeIf { it > 0 } ?: fallbackAppId.toLong()).toUInt()
}

fun normalizeWorkshopBranch(branch: String?): String {
    val normalized = branch?.trim().orEmpty()
    return if (normalized.isEmpty()) "public" else normalized
}

@Serializable
private data class GetPublishedFileDetailsEnvelope(
    val response: GetPublishedFileDetailsResponse,
)

@Serializable
private data class GetPublishedFileDetailsResponse(
    @SerialName("publishedfiledetails")
    val publishedFileDetails: List<PublishedFileDetailsDto> = emptyList(),
)

@Serializable
private data class PublishedFileDetailsDto(
    val result: Int = 0,
    val title: String = "",
    val filename: String = "",
    @SerialName("file_url")
    val fileUrl: String? = null,
    @SerialName("file_size")
    val fileSize: Long? = null,
    @SerialName("file_type")
    val fileType: Int? = null,
    @SerialName("hcontent_file")
    val hcontentFile: Long? = null,
    @SerialName("consumer_app_id")
    val consumerAppId: Long? = null,
)
