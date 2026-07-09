package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CPublishedFile_GetChangeHistory_Request
import top.apricityx.workshop.steam.proto.CPublishedFile_GetChangeHistory_Response
import top.apricityx.workshop.steam.proto.CPublishedFile_GetItemInfo_Request
import top.apricityx.workshop.steam.proto.CPublishedFile_GetItemInfo_Response
import top.apricityx.workshop.steam.proto.CPublishedFile_QueryFiles_Request
import top.apricityx.workshop.steam.proto.CPublishedFile_QueryFiles_Response
import top.apricityx.workshop.steam.proto.EPublishedFileRevision

data class SteamPublishedFileQuery(
    val appId: UInt,
    val searchText: String,
    val page: Int = 1,
    val pageSize: Int = 30,
    val queryType: Int = STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH,
    val language: Int = STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
)

data class SteamPublishedFileQueryResult(
    val total: Int,
    val items: List<SteamPublishedFileItem>,
    val nextCursor: String? = null,
)

data class SteamPublishedFileItem(
    val publishedFileId: ULong,
    val appId: UInt,
    val title: String,
    val description: String,
    val previewUrl: String,
    val creatorSteamId: Long,
    val fileSizeBytes: Long,
    val subscriptions: Int,
    val lifetimeSubscriptions: Int,
    val views: Int,
    val timeCreatedEpochSeconds: Long,
    val timeUpdatedEpochSeconds: Long,
)

data class SteamPublishedFileItemInfo(
    val publishedFileId: ULong,
    val timeUpdatedEpochSeconds: Long,
    val manifestId: ULong,
    val revision: Int,
    val authorSnapshots: List<SteamPublishedFileAuthorSnapshot>,
)

data class SteamPublishedFileAuthorSnapshot(
    val timestampEpochSeconds: Long,
    val gameBranchMin: String,
    val gameBranchMax: String,
    val manifestId: ULong,
)

data class SteamPublishedFileChangeLog(
    val timestampEpochSeconds: Long,
    val savedSnapshot: Boolean,
    val gameBranchMin: String,
    val gameBranchMax: String,
    val manifestId: ULong,
)

class SteamPublishedFileClient(
    private val directoryClient: SteamDirectoryClient,
    private val sessionFactory: () -> SteamCmSession,
) {
    suspend fun queryFiles(
        account: SteamAccountSession,
        query: SteamPublishedFileQuery,
    ): SteamPublishedFileQueryResult {
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                session.connectWithRefreshToken(cmServers, account)
                val response = session.callServiceMethod(
                    methodName = "PublishedFile.QueryFiles#1",
                    request = CPublishedFile_QueryFiles_Request.newBuilder()
                        .setQueryType(query.queryType)
                        .setPage(query.page.coerceAtLeast(1))
                        .setNumperpage(query.pageSize.coerceIn(1, 50))
                        .setAppid(query.appId.toInt())
                        .setSearchText(query.searchText)
                        .setLanguage(query.language)
                        .setReturnDetails(true)
                        .setReturnShortDescription(true)
                        .setStripDescriptionBbcode(true)
                        .build(),
                    parser = CPublishedFile_QueryFiles_Response.parser(),
                )
                SteamPublishedFileQueryResult(
                    total = response.total,
                    items = response.publishedfiledetailsList.mapNotNull(::toPublishedFileItem),
                    nextCursor = response.nextCursor.takeIf(String::isNotBlank),
                )
            } catch (error: Throwable) {
                throw when (error) {
                    is SteamProtocolException -> error
                    else -> SteamProtocolException("Failed to query Steam published files", error)
                }
            }
        }
    }

    suspend fun getItemInfo(
        account: SteamAccountSession?,
        appId: UInt,
        publishedFileIds: List<ULong>,
        desiredRevision: EPublishedFileRevision = EPublishedFileRevision.k_EPublishedFileRevision_AuthorSnapshot,
    ): List<SteamPublishedFileItemInfo> {
        val ids = publishedFileIds
            .filter { it > 0uL }
            .distinct()
            .take(100)
        if (ids.isEmpty()) {
            return emptyList()
        }
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                if (account != null && account.steamId > 0L && account.refreshToken.isNotBlank()) {
                    session.connectWithRefreshToken(cmServers, account)
                } else {
                    session.connectAnonymous(cmServers)
                }
                val request = CPublishedFile_GetItemInfo_Request.newBuilder()
                    .setAppid(appId.toInt())
                    .setDesiredRevision(desiredRevision)
                    .apply {
                        ids.forEach { id ->
                            addWorkshopItems(
                                CPublishedFile_GetItemInfo_Request.WorkshopItem.newBuilder()
                                    .setPublishedFileId(id.toLong())
                                    .setDesiredRevision(desiredRevision)
                                    .build(),
                            )
                        }
                    }
                    .build()
                val response = session.callServiceMethod(
                    methodName = "PublishedFile.GetItemInfo#1",
                    request = request,
                    parser = CPublishedFile_GetItemInfo_Response.parser(),
                )
                response.workshopItemsList.mapNotNull(::toPublishedFileItemInfo)
            } catch (error: Throwable) {
                throw when (error) {
                    is SteamProtocolException -> error
                    else -> SteamProtocolException("Failed to get Steam published file item info", error)
                }
            }
        }
    }

    private fun toPublishedFileItemInfo(
        item: CPublishedFile_GetItemInfo_Response.WorkshopItemInfo,
    ): SteamPublishedFileItemInfo? =
        item.publishedFileId.takeIf { it > 0L }?.toULong()?.let { publishedFileId ->
            SteamPublishedFileItemInfo(
                publishedFileId = publishedFileId,
                timeUpdatedEpochSeconds = item.timeUpdated.toLong(),
                manifestId = item.manifestId.toULong(),
                revision = item.revision.number,
                authorSnapshots = item.authorSnapshotsList.mapNotNull { snapshot ->
                    snapshot.manifestId.takeIf { it > 0L }?.toULong()?.let { manifestId ->
                        SteamPublishedFileAuthorSnapshot(
                            timestampEpochSeconds = snapshot.timestamp.toLong(),
                            gameBranchMin = snapshot.gameBranchMin,
                            gameBranchMax = snapshot.gameBranchMax,
                            manifestId = manifestId,
                        )
                    }
                },
            )
        }

    suspend fun getChangeHistory(
        account: SteamAccountSession?,
        publishedFileId: ULong,
        count: Int = 50,
        language: Int = STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
    ): List<SteamPublishedFileChangeLog> {
        if (publishedFileId <= 0uL) {
            return emptyList()
        }
        val cmServers = directoryClient.loadServers()
        return sessionFactory().use { session ->
            try {
                if (account != null && account.steamId > 0L && account.refreshToken.isNotBlank()) {
                    session.connectWithRefreshToken(cmServers, account)
                } else {
                    session.connectAnonymous(cmServers)
                }
                val request = CPublishedFile_GetChangeHistory_Request.newBuilder()
                    .setPublishedfileid(publishedFileId.toLong())
                    .setStartindex(0)
                    .setCount(count.coerceIn(1, 100))
                    .setLanguage(language)
                    .build()
                val response = session.callServiceMethod(
                    methodName = "PublishedFile.GetChangeHistory#1",
                    request = request,
                    parser = CPublishedFile_GetChangeHistory_Response.parser(),
                )
                response.changesList.mapNotNull(::toPublishedFileChangeLog)
            } catch (error: Throwable) {
                throw when (error) {
                    is SteamProtocolException -> error
                    else -> SteamProtocolException("Failed to get Steam published file change history", error)
                }
            }
        }
    }

    private fun toPublishedFileChangeLog(
        change: CPublishedFile_GetChangeHistory_Response.ChangeLog,
    ): SteamPublishedFileChangeLog? =
        change.manifestId.takeIf { it > 0L }?.toULong()?.let { manifestId ->
            SteamPublishedFileChangeLog(
                timestampEpochSeconds = change.timestamp.toLong(),
                savedSnapshot = change.savedSnapshot,
                gameBranchMin = change.snapshotGameBranchMin,
                gameBranchMax = change.snapshotGameBranchMax,
                manifestId = manifestId,
            )
        }

    private fun toPublishedFileItem(
        detail: top.apricityx.workshop.steam.proto.PublishedFileDetails,
    ): SteamPublishedFileItem? =
        detail.publishedfileid.takeIf { it > 0L }?.toULong()?.let { publishedFileId ->
            SteamPublishedFileItem(
                publishedFileId = publishedFileId,
                appId = detail.consumerAppid.toUInt(),
                title = detail.title,
                description = detail.shortDescription.takeIf(String::isNotBlank)
                    ?: detail.fileDescription,
                previewUrl = detail.previewUrl,
                creatorSteamId = detail.creator,
                fileSizeBytes = detail.fileSize,
                subscriptions = detail.subscriptions,
                lifetimeSubscriptions = detail.lifetimeSubscriptions,
                views = detail.views,
                timeCreatedEpochSeconds = detail.timeCreated.toLong(),
                timeUpdatedEpochSeconds = detail.timeUpdated.toLong(),
            )
        }
}

const val STEAM_LANGUAGE_ENGLISH = 0
const val STEAM_LANGUAGE_SIMPLIFIED_CHINESE = 6
const val STEAM_PUBLISHED_FILE_QUERY_TYPE_RANKED_BY_TEXT_SEARCH = 12
