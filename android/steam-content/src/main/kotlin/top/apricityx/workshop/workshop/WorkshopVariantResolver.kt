package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.SteamPublishedFileAuthorSnapshot
import top.apricityx.workshop.steam.protocol.SteamPublishedFileChangeLog
import top.apricityx.workshop.steam.protocol.SteamPublishedFileItemInfo

fun interface PublishedFileItemInfoProvider {
    suspend fun getItemInfo(appId: UInt, publishedFileId: ULong): SteamPublishedFileItemInfo?
}

fun interface PublishedFileChangeHistoryProvider {
    suspend fun getChangeHistory(publishedFileId: ULong): List<SteamPublishedFileChangeLog>
}

class WorkshopVariantResolver(
    private val publishedFileResolver: PublishedFileResolver,
    private val itemInfoProvider: PublishedFileItemInfoProvider? = null,
    private val changeHistoryProvider: PublishedFileChangeHistoryProvider? = null,
) {
    suspend fun resolveCandidates(
        appId: UInt,
        publishedFileId: ULong,
    ): List<WorkshopVariantCandidate> {
        val webApiDetails = publishedFileResolver.loadDetails(appId, publishedFileId)
        val title = webApiDetails.title.ifBlank { "Workshop $publishedFileId" }
        val depotId = webApiDetails.depotId(appId)
        val candidates = LinkedHashMap<String, WorkshopVariantCandidate>()

        val itemInfoResult = runCatching { itemInfoProvider?.getItemInfo(appId, publishedFileId) }
        val itemInfo = itemInfoResult.getOrNull()
        if (itemInfo != null) {
            itemInfo.authorSnapshots
                .filter { it.manifestId > 0uL }
                .sortedWith(compareByDescending<SteamPublishedFileAuthorSnapshot> { it.timestampEpochSeconds }
                    .thenBy { branchLabel(it.gameBranchMin, it.gameBranchMax) })
                .forEach { snapshot ->
                    val candidate = WorkshopVariantCandidate(
                        branch = branchForSnapshot(snapshot),
                        manifestId = snapshot.manifestId,
                        depotId = depotId,
                        title = title,
                        source = SOURCE_AUTHOR_SNAPSHOT,
                        matchedBranchMin = snapshot.gameBranchMin,
                        matchedBranchMax = snapshot.gameBranchMax,
                        timestampEpochSeconds = snapshot.timestampEpochSeconds,
                        fileSizeBytes = webApiDetails.fileSize,
                    )
                    candidates.putIfAbsent(candidateKey(candidate), candidate)
                }

            if (itemInfo.manifestId > 0uL) {
                val candidate = WorkshopVariantCandidate(
                    branch = "public",
                    manifestId = itemInfo.manifestId,
                    depotId = depotId,
                    title = title,
                    source = SOURCE_CM_MANIFEST_ID,
                    fallbackReason = if (itemInfo.authorSnapshots.isEmpty()) {
                        "cm_get_item_info_returned_no_author_snapshots"
                    } else {
                        "cm_get_item_info_manifest_id_fallback"
                    },
                    timestampEpochSeconds = itemInfo.timeUpdatedEpochSeconds,
                    fileSizeBytes = webApiDetails.fileSize,
                )
                candidates.putIfAbsent(candidateKey(candidate), candidate)
            }
        }

        val changeHistoryResult = if (candidates.values.none { it.source == SOURCE_AUTHOR_SNAPSHOT }) {
            runCatching { changeHistoryProvider?.getChangeHistory(publishedFileId).orEmpty() }
        } else {
            Result.success(emptyList())
        }
        changeHistoryResult.getOrNull()
            .orEmpty()
            .filter { it.savedSnapshot && it.manifestId > 0uL }
            .sortedWith(compareByDescending<SteamPublishedFileChangeLog> { it.timestampEpochSeconds }
                .thenBy { branchLabel(it.gameBranchMin, it.gameBranchMax) })
            .forEach { change ->
                val candidate = WorkshopVariantCandidate(
                    branch = branchForChangeLog(change),
                    manifestId = change.manifestId,
                    depotId = depotId,
                    title = title,
                    source = SOURCE_CHANGE_HISTORY_SNAPSHOT,
                    matchedBranchMin = change.gameBranchMin,
                    matchedBranchMax = change.gameBranchMax,
                    timestampEpochSeconds = change.timestampEpochSeconds,
                    fileSizeBytes = webApiDetails.fileSize,
                )
                candidates.putIfAbsent(candidateKey(candidate), candidate)
            }

        val cmFailure = listOf(
            itemInfoResult.exceptionOrNull()?.message?.trim().orEmpty(),
            changeHistoryResult.exceptionOrNull()?.message?.trim().orEmpty(),
        ).filter(String::isNotBlank).joinToString("; ")
        if (webApiDetails.hcontentFile != null && webApiDetails.hcontentFile > 0) {
            val fallbackReason = when {
                cmFailure.isNotBlank() -> "cm_get_item_info_failed: $cmFailure"
                itemInfo == null -> "cm_get_item_info_unavailable"
                candidates.isEmpty() -> "no_cm_manifest_candidate"
                else -> "webapi_hcontent_file_fallback"
            }
            val candidate = WorkshopVariantCandidate(
                branch = "public",
                manifestId = webApiDetails.hcontentFile.toULong(),
                depotId = depotId,
                title = title,
                source = SOURCE_WEBAPI_HCONTENT_FILE,
                fallbackReason = fallbackReason,
                fileSizeBytes = webApiDetails.fileSize,
            )
            candidates.putIfAbsent(candidateKey(candidate), candidate)
        }

        if (!webApiDetails.fileUrl.isNullOrBlank()) {
            val fallbackReason = when {
                cmFailure.isNotBlank() -> "cm_get_item_info_failed: $cmFailure"
                else -> "direct_file_url_fallback"
            }
            val candidate = WorkshopVariantCandidate(
                branch = "public",
                manifestId = null,
                depotId = null,
                title = title,
                source = SOURCE_DIRECT_FILE_URL,
                fallbackReason = fallbackReason,
                fileSizeBytes = webApiDetails.fileSize,
            )
            candidates.putIfAbsent(candidateKey(candidate), candidate)
        }

        return candidates.values.sortedWith(
            compareBy<WorkshopVariantCandidate> { sourceRank(it.source) }
                .thenBy { branchSortKey(it.branch) }
                .thenByDescending { it.timestampEpochSeconds }
                .thenBy { it.manifestId?.toString().orEmpty() },
        )
    }

    private fun branchForSnapshot(snapshot: SteamPublishedFileAuthorSnapshot): String =
        branchFromRange(snapshot.gameBranchMin, snapshot.gameBranchMax)

    private fun branchForChangeLog(change: SteamPublishedFileChangeLog): String =
        branchFromRange(change.gameBranchMin, change.gameBranchMax)

    private fun branchFromRange(minBranch: String, maxBranch: String): String {
        val min = normalizeWorkshopBranch(minBranch).takeIf { it != "public" || minBranch.isNotBlank() }
        val max = normalizeWorkshopBranch(maxBranch).takeIf { it != "public" || maxBranch.isNotBlank() }
        return when {
            !min.isNullOrBlank() && min == max -> min
            !min.isNullOrBlank() -> min
            !max.isNullOrBlank() -> max
            else -> "public"
        }
    }

    private fun branchLabel(min: String, max: String): String = listOf(min, max)
        .map { normalizeWorkshopBranch(it) }
        .distinct()
        .joinToString("..")

    private fun candidateKey(candidate: WorkshopVariantCandidate): String = listOf(
        candidate.branch,
        candidate.manifestId?.toString().orEmpty(),
        candidate.source,
        candidate.matchedBranchMin,
        candidate.matchedBranchMax,
    ).joinToString("|")

    private fun sourceRank(source: String): Int = when (source) {
        SOURCE_AUTHOR_SNAPSHOT -> 0
        SOURCE_CHANGE_HISTORY_SNAPSHOT -> 1
        SOURCE_CM_MANIFEST_ID -> 2
        SOURCE_WEBAPI_HCONTENT_FILE -> 3
        SOURCE_DIRECT_FILE_URL -> 4
        else -> 9
    }

    private fun branchSortKey(branch: String): Int = when (normalizeWorkshopBranch(branch)) {
        "public" -> 0
        "public-beta" -> 1
        else -> 2
    }

    companion object {
        const val SOURCE_AUTHOR_SNAPSHOT = "cm_get_item_info_author_snapshot"
        const val SOURCE_CHANGE_HISTORY_SNAPSHOT = "cm_get_change_history_snapshot"
        const val SOURCE_CM_MANIFEST_ID = "cm_get_item_info_manifest_id"
        const val SOURCE_WEBAPI_HCONTENT_FILE = "webapi_hcontent_file"
        const val SOURCE_DIRECT_FILE_URL = "direct_file_url"
    }
}
