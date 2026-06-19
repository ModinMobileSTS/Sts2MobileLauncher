package com.godot.game

import android.text.Html
import com.godot.game.steam.auth.SteamAuthStore
import com.godot.game.steam.auth.SteamLoginCoordinator
import com.godot.game.steam.core.SteamClientIdentity
import com.godot.game.steam.core.SteamNetworkClientFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import top.apricityx.workshop.steam.protocol.STEAM_LANGUAGE_SIMPLIFIED_CHINESE
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileClient
import top.apricityx.workshop.steam.protocol.SteamPublishedFileItem
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQuery
import top.apricityx.workshop.steam.protocol.SteamPublishedFileQueryResult

class SteamWorkshopCatalog(private val context: android.content.Context) {
    enum class SortOption(
        val browseSortValue: String,
        val actualSortValue: String,
        val supportsTimeWindow: Boolean,
    ) {
        MOST_POPULAR("trend", "trend", true),
        MOST_RECENT("mostrecent", "mostrecent", true),
        LAST_UPDATED("lastupdated", "lastupdated", true),
        MOST_SUBSCRIBED("totaluniquesubscribers", "totaluniquesubscribers", true),
    }

    enum class TimeWindow(val daysValue: Int) {
        ONE_WEEK(7),
        THIRTY_DAYS(30),
        THREE_MONTHS(90),
        SIX_MONTHS(180),
        ONE_YEAR(365),
        ALL_TIME(-1),
    }

    data class SearchResult(
        val total: Int,
        val page: Int,
        val items: List<Item>,
    )

    data class Item(
        val appId: Int,
        val publishedFileId: String,
        val title: String,
        val authorName: String,
        val description: String,
        val previewUrl: String,
        val creatorSteamId: Long,
        val fileSizeBytes: Long,
        val subscriptions: Int,
        val views: Int,
        val timeUpdatedEpochSeconds: Long,
    )

    data class Detail(
        val item: Item,
        val description: String,
        val screenshotUrls: List<String>,
        val requiredItems: List<RequiredItem>,
    )

    data class RequiredItem(
        val appId: Int,
        val publishedFileId: String,
        val title: String,
        val description: String,
        val previewUrl: String,
        val creatorSteamId: Long,
        val fileSizeBytes: Long,
        val timeUpdatedEpochSeconds: Long,
        val workshopUrl: String,
    ) {
        fun toItem(): Item =
            Item(
                appId = appId,
                publishedFileId = publishedFileId,
                title = title.ifBlank { "Workshop $publishedFileId" },
                authorName = "",
                description = description,
                previewUrl = previewUrl,
                creatorSteamId = creatorSteamId,
                fileSizeBytes = fileSizeBytes,
                subscriptions = 0,
                views = 0,
                timeUpdatedEpochSeconds = timeUpdatedEpochSeconds,
            )
    }

    fun search(query: String, page: Int, pageSize: Int): SearchResult =
        search(query, page, pageSize, SortOption.MOST_POPULAR, TimeWindow.ONE_WEEK)

    fun search(
        query: String,
        page: Int,
        pageSize: Int,
        sortOption: SortOption,
        timeWindow: TimeWindow,
    ): SearchResult {
        if (query.isBlank() || sortOption != SortOption.MOST_POPULAR || timeWindow != TimeWindow.ONE_WEEK) {
            return searchPublic(query, page, pageSize, true, sortOption, timeWindow)
        }
        return runBlocking {
            searchWithAuthenticatedFallback(query, page, pageSize, sortOption, timeWindow)
        }
    }

    fun runDiagnostics(query: String, page: Int, pageSize: Int): JSONObject {
        val diagnostics = JSONObject()
        diagnostics.put("query", query)
        diagnostics.put("page", page.coerceAtLeast(1))
        diagnostics.put("page_size", pageSize.coerceIn(1, 50))
        diagnostics.put("auth_configured", SteamAuthStore.readAuthMaterial(context.applicationContext) != null)

        diagnostics.put("direct_access_enabled", SteamWorkshopPreferences.isDirectAccessEnabled(context.applicationContext))

        val publicOriginalStep = JSONObject()
        diagnostics.put("public_browse_original", publicOriginalStep)
        val publicOriginalStarted = System.nanoTime()
        val publicOriginalResult = runCatching {
            searchPublic(query, page, pageSize, false, SortOption.MOST_POPULAR, TimeWindow.ONE_WEEK, WorkshopHttpRouteMode.ORIGINAL_ONLY)
        }
        publicOriginalStep.put("elapsed_ms", elapsedMs(publicOriginalStarted))
        publicOriginalStep.put("succeeded", publicOriginalResult.isSuccess)
        publicOriginalResult.onSuccess { result ->
            publicOriginalStep.put("total", result.total)
            publicOriginalStep.put("page", result.page)
            publicOriginalStep.put("item_count", result.items.size)
            publicOriginalStep.put("first_ids", JSONArray().apply {
                result.items.take(5).forEach { put(it.publishedFileId) }
            })
        }.onFailure { error ->
            publicOriginalStep.put("error", describe(error))
        }

        val publicDirectStep = JSONObject()
        diagnostics.put("public_browse_direct", publicDirectStep)
        val publicDirectStarted = System.nanoTime()
        val publicDirectResult = runCatching {
            searchPublic(query, page, pageSize, false, SortOption.MOST_POPULAR, TimeWindow.ONE_WEEK, WorkshopHttpRouteMode.DIRECT_ONLY)
        }
        publicDirectStep.put("elapsed_ms", elapsedMs(publicDirectStarted))
        publicDirectStep.put("succeeded", publicDirectResult.isSuccess)
        publicDirectResult.onSuccess { result ->
            publicDirectStep.put("total", result.total)
            publicDirectStep.put("page", result.page)
            publicDirectStep.put("item_count", result.items.size)
            publicDirectStep.put("first_ids", JSONArray().apply {
                result.items.take(5).forEach { put(it.publishedFileId) }
            })
        }.onFailure { error ->
            publicDirectStep.put("error", describe(error))
        }

        val publicStep = JSONObject()
        diagnostics.put("public_browse", publicStep)
        val publicStarted = System.nanoTime()
        val publicResult = runCatching { searchPublic(query, page, pageSize, false, SortOption.MOST_POPULAR, TimeWindow.ONE_WEEK) }
        publicStep.put("elapsed_ms", elapsedMs(publicStarted))
        publicStep.put("succeeded", publicResult.isSuccess)
        publicResult.onSuccess { result ->
            publicStep.put("total", result.total)
            publicStep.put("page", result.page)
            publicStep.put("item_count", result.items.size)
            publicStep.put("first_ids", JSONArray().apply {
                result.items.take(5).forEach { put(it.publishedFileId) }
            })

            val detailStep = JSONObject()
            diagnostics.put("details", detailStep)
            val detailStarted = System.nanoTime()
            val detailResult = runCatching { loadDetails(result.items.take(10).map { it.publishedFileId }) }
            detailStep.put("elapsed_ms", elapsedMs(detailStarted))
            detailStep.put("succeeded", detailResult.isSuccess)
            detailResult.onSuccess { details ->
                detailStep.put("item_count", details.size)
            }.onFailure { error ->
                detailStep.put("error", describe(error))
            }
        }.onFailure { error ->
            publicStep.put("error", describe(error))
        }

        if (query.isNotBlank()) {
            val authenticatedStep = JSONObject()
            diagnostics.put("authenticated_query", authenticatedStep)
            val authenticatedStarted = System.nanoTime()
            val authenticatedResult = runCatching {
                runBlocking {
                    val appContext = context.applicationContext
                    val auth = SteamAuthStore.readAuthMaterial(appContext) ?: throw IOException("Steam auth is not configured.")
                    val steamId = authSteamIdOrNull(appContext) ?: throw IOException("SteamID64 is missing.")
                    searchAuthenticated(auth.accountName, auth.refreshToken, steamId, query, page, pageSize)
                }
            }
            authenticatedStep.put("elapsed_ms", elapsedMs(authenticatedStarted))
            authenticatedStep.put("succeeded", authenticatedResult.isSuccess)
            authenticatedResult.onSuccess { result ->
                authenticatedStep.put("total", result.total)
                authenticatedStep.put("item_count", result.items.size)
            }.onFailure { error ->
                authenticatedStep.put("error", describe(error))
            }
        }

        val fullStep = JSONObject()
        diagnostics.put("catalog_search", fullStep)
        val fullStarted = System.nanoTime()
        val fullResult = runCatching { search(query, page, pageSize) }
        fullStep.put("elapsed_ms", elapsedMs(fullStarted))
        fullStep.put("succeeded", fullResult.isSuccess)
        fullResult.onSuccess { result ->
            fullStep.put("total", result.total)
            fullStep.put("item_count", result.items.size)
        }.onFailure { error ->
            fullStep.put("error", describe(error))
        }
        return diagnostics
    }

    private suspend fun searchWithAuthenticatedFallback(
        query: String,
        page: Int,
        pageSize: Int,
        sortOption: SortOption,
        timeWindow: TimeWindow,
    ): SearchResult {
        val appContext = context.applicationContext
        val auth = SteamAuthStore.readAuthMaterial(appContext)
        val steamId = auth?.let { authSteamIdOrResolve(appContext) }
        return if (auth != null && steamId != null && steamId > 0L) {
            runCatching {
                searchAuthenticated(
                    accountName = auth.accountName,
                    refreshToken = auth.refreshToken,
                    steamId = steamId,
                    query = query,
                    page = page,
                    pageSize = pageSize,
                )
            }.getOrElse {
                searchPublic(query, page, pageSize, true, sortOption, timeWindow)
            }
        } else {
            searchPublic(query, page, pageSize, true, sortOption, timeWindow)
        }
    }

    fun loadDetails(ids: Collection<String>): Map<String, Item> {
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val client = createWorkshopDetailClient()
        val body = FormBody.Builder()
            .add("itemcount", ids.size.toString())
            .add("appid", SteamWorkshopPreferences.DEFAULT_APP_ID.toString())
            .apply {
                ids.forEachIndexed { index, id ->
                    add("publishedfileids[$index]", id)
                }
            }
            .build()
        val request = Request.Builder()
            .url("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam workshop detail request failed: HTTP ${response.code}")
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val array = root.optJSONObject("response")?.optJSONArray("publishedfiledetails") ?: JSONArray()
            val details = linkedMapOf<String, Item>()
            for (index in 0 until array.length()) {
                val item = parseDetail(array.optJSONObject(index) ?: continue) ?: continue
                details[item.publishedFileId] = item
            }
            return details
        }
    }

    fun loadDetail(item: Item): Detail {
        val apiItem = loadDetails(listOf(item.publishedFileId))[item.publishedFileId] ?: item
        val mergedItem = apiItem.copy(
            authorName = apiItem.authorName.ifBlank { item.authorName },
            description = apiItem.description.ifBlank { item.description },
            previewUrl = apiItem.previewUrl.ifBlank { item.previewUrl },
        )
        val communityDetail = runCatching { loadCommunityDetail(mergedItem) }.getOrDefault(CommunityDetail())
        val description = communityDetail.description.ifBlank {
            mergedItem.description.ifBlank { item.description }
        }
        val screenshots = communityDetail.screenshotUrls
            .ifEmpty { listOf(mergedItem.previewUrl.ifBlank { item.previewUrl }) }
            .filter(String::isNotBlank)
            .distinct()
        val requiredItems = enrichRequiredItems(mergedItem.appId, communityDetail.requiredItems)
        return Detail(
            item = mergedItem.copy(description = description),
            description = description,
            screenshotUrls = screenshots,
            requiredItems = requiredItems,
        )
    }

    private suspend fun searchAuthenticated(
        accountName: String,
        refreshToken: String,
        steamId: Long,
        query: String,
        page: Int,
        pageSize: Int,
    ): SearchResult {
        val appContext = context.applicationContext
        val identity = SteamClientIdentity(appContext)
        val client = SteamNetworkClientFactory.createDefaultClient()
        val account = SteamAccountSession(
            accountName = accountName,
            steamId = steamId,
            refreshToken = refreshToken,
            machineName = identity.machineName,
        )
        val publishedFileClient = SteamPublishedFileClient(
            directoryClient = SteamDirectoryClient(client),
            sessionFactory = { identity.createSession(client) },
        )
        val result = publishedFileClient.queryFiles(
            account = account,
            query = SteamPublishedFileQuery(
                appId = SteamWorkshopPreferences.DEFAULT_APP_ID.toUInt(),
                searchText = query.trim(),
                page = page.coerceAtLeast(1),
                pageSize = pageSize.coerceIn(1, 50),
                language = STEAM_LANGUAGE_SIMPLIFIED_CHINESE,
            ),
        )
        return result.toSearchResult(page.coerceAtLeast(1))
    }

    private fun searchPublic(
        query: String,
        page: Int,
        pageSize: Int,
        enrichDetails: Boolean,
        sortOption: SortOption,
        timeWindow: TimeWindow,
        routeMode: WorkshopHttpRouteMode = WorkshopHttpRouteMode.DEFAULT,
    ): SearchResult {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 50)
        val trimmedQuery = query.trim()
        val url = "https://steamcommunity.com/".toHttpUrl().newBuilder()
            .addPathSegment("workshop")
            .addPathSegment("browse")
            .addQueryParameter("appid", SteamWorkshopPreferences.DEFAULT_APP_ID.toString())
            .addQueryParameter("searchtext", trimmedQuery)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", "schinese")
            .addQueryParameter("browsesort", sortOption.browseSortValue)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", sortOption.actualSortValue)
            .addQueryParameter("p", safePage.toString())
            .addQueryParameter("numperpage", safePageSize.toString())
            .apply {
                if (sortOption.supportsTimeWindow) {
                    addQueryParameter("days", timeWindow.daysValue.toString())
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        val client = createWorkshopBrowseClient(routeMode)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam workshop public browse failed: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            val pageResult = parsePublicBrowsePage(payload, safePage, safePageSize)
            return SearchResult(
                total = pageResult.total,
                page = pageResult.page,
                items = if (enrichDetails) enrichPublicItems(pageResult.items) else pageResult.items,
            )
        }
    }

    private fun parsePublicBrowsePage(payload: String, page: Int, pageSize: Int): PublicBrowsePage {
        val descriptions = hoverRegex.findAll(payload)
            .mapNotNull { match ->
                val fileId = match.groupValues[1].takeIf(String::isNotBlank) ?: return@mapNotNull null
                val description = runCatching {
                    JSONObject(match.groupValues[2]).optString("description", "")
                }.getOrDefault("")
                fileId to stripTagsAndDecode(description)
            }
            .toMap()
        val items = itemBlockRegex.findAll(payload)
            .mapNotNull { match ->
                parseLegacyWorkshopItem(match.groupValues[1], descriptions)
            }
            .toList()
        val hasNextPage = payload.contains("&p=${page + 1}") && payload.contains("pagebtn")
        if (items.isNotEmpty() || hasNextPage) {
            return PublicBrowsePage(
                page = page,
                total = estimatedTotal(page, pageSize, items.size, hasNextPage),
                items = items,
            )
        }
        return parseSsrBrowsePage(payload, page, pageSize)
            ?: PublicBrowsePage(page = page, total = estimatedTotal(page, pageSize, 0, false), items = emptyList())
    }

    private fun parseLegacyWorkshopItem(block: String, descriptions: Map<String, String>): Item? {
        val publishedFileId = legacyPublishedFileIdRegex.find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val appId = legacyAppIdRegex.find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: SteamWorkshopPreferences.DEFAULT_APP_ID
        val previewUrl = itemPreviewRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
        val title = itemTitleRegex.find(block)?.groupValues?.getOrNull(1)?.let(::stripTagsAndDecode).orEmpty()
        val authorName = itemAuthorRegex.find(block)?.groupValues?.getOrNull(1)?.let(::stripTagsAndDecode).orEmpty()
        return Item(
            appId = appId,
            publishedFileId = publishedFileId,
            title = title.ifBlank { "Workshop $publishedFileId" },
            authorName = authorName,
            description = descriptions[publishedFileId].orEmpty(),
            previewUrl = previewUrl,
            creatorSteamId = 0L,
            fileSizeBytes = 0L,
            subscriptions = 0,
            views = 0,
            timeUpdatedEpochSeconds = 0L,
        )
    }

    private fun parseSsrBrowsePage(payload: String, page: Int, pageSize: Int): PublicBrowsePage? {
        val encoded = ssrRenderContextRegex.find(payload)?.groupValues?.getOrNull(1) ?: return null
        val renderContext = decodeJsonStringLiteral(encoded) ?: return null
        val queryData = runCatching {
            JSONObject(renderContext).optString("queryData", "")
        }.getOrDefault("")
        if (queryData.isBlank()) {
            return null
        }
        val queries = runCatching {
            JSONObject(queryData).optJSONArray("queries") ?: JSONArray()
        }.getOrDefault(JSONArray())
        val creatorNames = mutableMapOf<String, String>()
        for (index in 0 until queries.length()) {
            val queryObject = queries.optJSONObject(index) ?: continue
            val queryKey = queryObject.optJSONArray("queryKey") ?: continue
            if (queryKey.optString(0) != "PlayerLinkDetails") {
                continue
            }
            val steamId = queryKey.optString(1, "")
            val personaName = queryObject
                .optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("public_data")
                ?.optString("persona_name", "")
                .orEmpty()
            if (steamId.isNotBlank() && personaName.isNotBlank()) {
                creatorNames[steamId] = personaName
            }
        }
        for (index in 0 until queries.length()) {
            val data = queries.optJSONObject(index)
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?: continue
            val results = data.optJSONArray("results") ?: continue
            if (!data.has("current_page") || !data.has("total_pages")) {
                continue
            }
            val currentPage = data.optInt("current_page", page).coerceAtLeast(1)
            val totalPages = data.optInt("total_pages", currentPage).coerceAtLeast(currentPage)
            val totalCount = data.optInt("total_count", 0)
            val items = mutableListOf<Item>()
            for (resultIndex in 0 until results.length()) {
                parseSsrWorkshopItem(results.optJSONObject(resultIndex) ?: continue, creatorNames)?.let(items::add)
            }
            return PublicBrowsePage(
                page = currentPage,
                total = if (totalCount > 0) totalCount else estimatedTotal(currentPage, pageSize, items.size, currentPage < totalPages),
                items = items,
            )
        }
        return null
    }

    private fun parseSsrWorkshopItem(objectValue: JSONObject, creatorNames: Map<String, String>): Item? {
        val publishedFileId = objectValue.optString("publishedfileid", "").takeIf(String::isNotBlank) ?: return null
        val creator = objectValue.optString("creator", "0")
        return Item(
            appId = objectValue.optInt("consumer_appid", SteamWorkshopPreferences.DEFAULT_APP_ID),
            publishedFileId = publishedFileId,
            title = objectValue.optString("title", "").ifBlank { "Workshop $publishedFileId" },
            authorName = creatorNames[creator].orEmpty(),
            description = stripTagsAndDecode(objectValue.optString("short_description", "")),
            previewUrl = objectValue.optString("preview_url", ""),
            creatorSteamId = creator.toLongOrNull() ?: 0L,
            fileSizeBytes = objectValue.optLong("file_size", 0L),
            subscriptions = objectValue.optInt("subscriptions", 0),
            views = objectValue.optInt("views", 0),
            timeUpdatedEpochSeconds = objectValue.optLong("time_updated", 0L),
        )
    }

    private fun enrichPublicItems(items: List<Item>): List<Item> {
        if (items.isEmpty()) {
            return items
        }
        val details = runCatching { loadDetails(items.map { it.publishedFileId }) }
            .getOrDefault(emptyMap())
        if (details.isEmpty()) {
            return items
        }
        return items.map { item ->
            val detail = details[item.publishedFileId] ?: return@map item
            item.copy(
                appId = if (detail.appId > 0) detail.appId else item.appId,
                title = detail.title.ifBlank { item.title },
                authorName = detail.authorName.ifBlank { item.authorName },
                description = detail.description.ifBlank { item.description },
                previewUrl = detail.previewUrl.ifBlank { item.previewUrl },
                creatorSteamId = if (detail.creatorSteamId > 0L) detail.creatorSteamId else item.creatorSteamId,
                fileSizeBytes = if (detail.fileSizeBytes > 0L) detail.fileSizeBytes else item.fileSizeBytes,
                subscriptions = if (detail.subscriptions > 0) detail.subscriptions else item.subscriptions,
                views = if (detail.views > 0) detail.views else item.views,
                timeUpdatedEpochSeconds = if (detail.timeUpdatedEpochSeconds > 0L) detail.timeUpdatedEpochSeconds else item.timeUpdatedEpochSeconds,
            )
        }
    }

    private fun SteamPublishedFileQueryResult.toSearchResult(page: Int): SearchResult =
        SearchResult(
            total = total,
            page = page,
            items = items.map { item -> item.toCatalogItem() },
        )

    private fun SteamPublishedFileItem.toCatalogItem(): Item =
        Item(
            appId = appId.toInt(),
            publishedFileId = publishedFileId.toString(),
            title = title.ifBlank { "Workshop $publishedFileId" },
            authorName = "",
            description = description,
            previewUrl = previewUrl,
            creatorSteamId = creatorSteamId,
            fileSizeBytes = fileSizeBytes,
            subscriptions = subscriptions,
            views = views,
            timeUpdatedEpochSeconds = timeUpdatedEpochSeconds,
        )

    private fun parseDetail(objectValue: JSONObject): Item? {
        val result = objectValue.optInt("result", 0)
        val publishedFileId = objectValue.optString("publishedfileid", "").toULongOrNull() ?: return null
        if (result != 1) {
            return null
        }
        return Item(
            appId = firstPositiveInt(
                objectValue,
                "consumer_app_id",
                "consumer_appid",
                "appid",
                fallback = SteamWorkshopPreferences.DEFAULT_APP_ID,
            ),
            publishedFileId = publishedFileId.toString(),
            title = objectValue.optString("title", "").ifBlank { "Workshop $publishedFileId" },
            authorName = "",
            description = firstNonBlank(objectValue, "short_description", "description", "file_description"),
            previewUrl = firstNonBlank(objectValue, "preview_url", "previewUrl"),
            creatorSteamId = objectValue.optString("creator", "0").toLongOrNull() ?: 0L,
            fileSizeBytes = firstPositiveLong(objectValue, "file_size", "file_size_bytes", fallback = 0L),
            subscriptions = firstPositiveInt(objectValue, "subscriptions", "lifetime_subscriptions", fallback = 0),
            views = objectValue.optInt("views", 0),
            timeUpdatedEpochSeconds = firstPositiveLong(objectValue, "time_updated", "rtime32_last_modified", fallback = 0L),
        )
    }

    private fun firstNonBlank(objectValue: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = objectValue.optString(key, "")
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun firstPositiveInt(objectValue: JSONObject, vararg keys: String, fallback: Int): Int {
        for (key in keys) {
            val value = objectValue.optInt(key, 0)
            if (value > 0) {
                return value
            }
        }
        return fallback
    }

    private fun firstPositiveLong(objectValue: JSONObject, vararg keys: String, fallback: Long): Long {
        for (key in keys) {
            val value = objectValue.optLong(key, 0L)
            if (value > 0L) {
                return value
            }
        }
        return fallback
    }

    private fun loadCommunityDetail(item: Item): CommunityDetail {
        val request = Request.Builder()
            .url(
                "https://steamcommunity.com/".toHttpUrl().newBuilder()
                    .addPathSegment("sharedfiles")
                    .addPathSegment("filedetails")
                    .addQueryParameter("id", item.publishedFileId)
                    .addQueryParameter("l", "schinese")
                    .build()
            )
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        createWorkshopBrowseClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam workshop detail page failed: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            return CommunityDetail(
                description = extractDescription(payload),
                screenshotUrls = extractScreenshotUrls(payload),
                requiredItems = extractRequiredItems(payload),
            )
        }
    }

    private fun extractDescription(payload: String): String =
        workshopDescriptionRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::stripTagsAndDecode)
            .orEmpty()

    private fun extractScreenshotUrls(payload: String): List<String> {
        val urls = mutableListOf<String>()
        extractFullScreenshotUrlBlock(payload)?.let { block ->
            fullScreenshotUrlRegex.findAll(block).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
            }
        }
        if (urls.isEmpty()) {
            extractScreenshotUrlMapBlock(payload)?.let { block ->
                mappedScreenshotUrlRegex.findAll(block).forEach { match ->
                    match.groupValues.getOrNull(1)
                        ?.let { addScreenshotUrl(urls, it) }
                }
            }
        }
        if (urls.isEmpty()) {
            extractDivInnerHtmlById(payload, "highlight_strip_scroll")?.let { block ->
                screenshotAttributeRegex.findAll(block).forEach { match ->
                    match.groupValues.getOrNull(1)
                        ?.let { addScreenshotUrl(urls, it) }
                }
            }
        }
        if (urls.isEmpty()) {
            enlargedImageRegex.findAll(payload).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
                match.groupValues.getOrNull(2)
                    ?.let { addScreenshotUrl(urls, it) }
            }
            enlargedImageFunctionRegex.findAll(payload).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
            }
            screenshotAttributeRegex.findAll(payload).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
            }
            screenshotSrcsetRegex.findAll(payload).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
            }
            quotedSteamImageUrlRegex.findAll(payload).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.let { addScreenshotUrl(urls, it) }
            }
        }
        if (urls.isEmpty()) {
            previewImageRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { addScreenshotUrl(urls, it) }
            ogImageRegex.find(payload)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { addScreenshotUrl(urls, it) }
        }
        return urls.distinctBy(::canonicalImageIdentity)
    }

    private fun addScreenshotUrl(urls: MutableList<String>, rawValue: String) {
        decodeSteamImageUrl(rawValue)
            .split(',')
            .asSequence()
            .map { it.trim().substringBefore(' ').trim() }
            .map(::normalizeSteamImageUrl)
            .filter(::isSteamImageUrl)
            .forEach(urls::add)
    }

    private fun normalizeSteamImageUrl(value: String): String {
        var normalized = value
            .trim()
            .trim('"', '\'', '`')
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\x26", "&")
            .replace("&amp;", "&")
        if (normalized.startsWith("//")) {
            normalized = "https:$normalized"
        }
        return normalized
    }

    private fun decodeSteamImageUrl(value: String): String {
        val decodedHtml = decodeHtml(value)
        return decodeJsonStringLiteral(decodedHtml)?.takeIf(String::isNotBlank) ?: decodedHtml
    }

    private fun isSteamImageUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return false
        }
        if (lower.contains("/public/css/") ||
            lower.contains("/public/shared/css/") ||
            lower.contains("/public/javascript/") ||
            lower.contains("/javascript/") ||
            lower.endsWith(".css") ||
            lower.contains(".css?") ||
            lower.endsWith(".js") ||
            lower.contains(".js?")
        ) {
            return false
        }
        return lower.contains("images.steamusercontent.com/ugc/") ||
            lower.contains("steamuserimages") ||
            lower.contains("images.akamai.steamusercontent.com/ugc/") ||
            lower.contains("/community_assets/images/apps/") ||
            lower.contains("/store_item_assets/steam/apps/")
    }

    private fun canonicalImageIdentity(value: String): String =
        value.substringBefore('?')
            .lowercase()
            .removeSuffix("/")

    private fun extractFullScreenshotUrlBlock(payload: String): String? =
        fullScreenshotUrlBlockRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)

    private fun extractScreenshotUrlMapBlock(payload: String): String? =
        screenshotUrlMapBlockRegex.find(payload)
            ?.groupValues
            ?.getOrNull(1)

    private fun extractRequiredItems(payload: String): List<ParsedRequiredItem> {
        val container = extractDivInnerHtmlById(payload, "RequiredItems") ?: return emptyList()
        return requiredItemLinkRegex.findAll(container)
            .mapNotNull { match ->
                val publishedFileId = match.groupValues.getOrNull(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val title = match.groupValues.getOrNull(3)?.let(::stripTagsAndDecode).orEmpty()
                ParsedRequiredItem(
                    publishedFileId = publishedFileId,
                    title = title.ifBlank { "Workshop $publishedFileId" },
                    workshopUrl = match.groupValues.getOrNull(1)?.let(::decodeHtml).orEmpty(),
                )
            }
            .distinctBy { it.publishedFileId }
            .toList()
    }

    private fun enrichRequiredItems(fallbackAppId: Int, items: List<ParsedRequiredItem>): List<RequiredItem> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val details = runCatching { loadDetails(items.map { it.publishedFileId }) }
            .getOrDefault(emptyMap())
        return items.map { item ->
            val detail = details[item.publishedFileId]
            RequiredItem(
                appId = detail?.appId?.takeIf { it > 0 } ?: fallbackAppId,
                publishedFileId = item.publishedFileId,
                title = detail?.title?.ifBlank { item.title } ?: item.title,
                description = detail?.description.orEmpty(),
                previewUrl = detail?.previewUrl.orEmpty(),
                creatorSteamId = detail?.creatorSteamId ?: 0L,
                fileSizeBytes = detail?.fileSizeBytes ?: 0L,
                timeUpdatedEpochSeconds = detail?.timeUpdatedEpochSeconds ?: 0L,
                workshopUrl = item.workshopUrl.ifBlank { "https://steamcommunity.com/sharedfiles/filedetails/?id=${item.publishedFileId}" },
            )
        }
    }

    private fun extractDivInnerHtmlById(payload: String, id: String): String? {
        val openingTag = Regex(
            """<div\b[^>]*\bid="${Regex.escape(id)}"[^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(payload) ?: return null
        return extractDivInnerHtml(payload, openingTag.value)
    }

    private fun extractDivInnerHtml(payload: String, openingTag: String): String? {
        val start = payload.indexOf(openingTag)
        if (start < 0) {
            return null
        }
        var cursor = start + openingTag.length
        var depth = 1
        while (cursor < payload.length) {
            val nextOpen = payload.indexOf("<div", cursor, ignoreCase = true).takeIf { it >= 0 }
            val nextClose = payload.indexOf("</div", cursor, ignoreCase = true).takeIf { it >= 0 }
            val nextIndex = listOfNotNull(nextOpen, nextClose).minOrNull() ?: break
            if (nextIndex == nextOpen) {
                depth += 1
                cursor = nextIndex + 4
                continue
            }
            depth -= 1
            if (depth == 0) {
                return payload.substring(start + openingTag.length, nextIndex)
            }
            cursor = nextIndex + 5
        }
        return null
    }

    private fun authSteamIdOrNull(context: android.content.Context): Long? =
        SteamAuthStore.readSnapshot(context).steamId64.trim().toLongOrNull()

    private fun authSteamIdOrResolve(context: android.content.Context): Long? {
        authSteamIdOrNull(context)?.takeIf { it > 0L }?.let { return it }
        return runCatching {
            SteamLoginCoordinator.verifyRefreshToken(context).trim().toLongOrNull()
        }.getOrNull()
    }

    private fun decodeJsonStringLiteral(encoded: String): String? =
        runCatching {
            JSONTokener("\"$encoded\"").nextValue() as? String
        }.getOrNull()

    private fun stripTagsAndDecode(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun estimatedTotal(page: Int, pageSize: Int, count: Int, hasNextPage: Boolean): Int =
        if (hasNextPage) {
            page * pageSize + 1
        } else {
            (page - 1).coerceAtLeast(0) * pageSize + count
        }.coerceAtLeast(count)

    private data class PublicBrowsePage(
        val page: Int,
        val total: Int,
        val items: List<Item>,
    )

    private data class CommunityDetail(
        val description: String = "",
        val screenshotUrls: List<String> = emptyList(),
        val requiredItems: List<ParsedRequiredItem> = emptyList(),
    )

    private data class ParsedRequiredItem(
        val publishedFileId: String,
        val title: String,
        val workshopUrl: String,
    )

    private fun createWorkshopBrowseClient(routeMode: WorkshopHttpRouteMode = WorkshopHttpRouteMode.DEFAULT): OkHttpClient =
        SteamWorkshopDirectAccess.buildClient(context, routeMode) {
            connectTimeout(12, TimeUnit.SECONDS)
            readTimeout(18, TimeUnit.SECONDS)
            writeTimeout(12, TimeUnit.SECONDS)
            callTimeout(25, TimeUnit.SECONDS)
            protocols(listOf(Protocol.HTTP_1_1))
            connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            retryOnConnectionFailure(true)
        }

    private fun createWorkshopDetailClient(): OkHttpClient =
        SteamWorkshopDirectAccess.buildClient(context) {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(8, TimeUnit.SECONDS)
            writeTimeout(8, TimeUnit.SECONDS)
            callTimeout(8, TimeUnit.SECONDS)
            protocols(listOf(Protocol.HTTP_1_1))
            connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            retryOnConnectionFailure(true)
        }

    private fun elapsedMs(startedNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

    private fun describe(error: Throwable): String =
        error::class.java.simpleName + ": " + (error.message ?: error.toString())

    private companion object {
        const val USER_AGENT = "WorkshopOnAndroid/1.0"

        val itemBlockRegex = Regex(
            """<div\b[^>]*class="workshopItem"[^>]*>(.*?<div class="workshopItemAuthorName ellipsis">.*?</div>.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val legacyPublishedFileIdRegex = Regex(
            """(?:\?|&amp;|&)id=(\d+)""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val legacyAppIdRegex = Regex(
            """data-appid="(\d+)"""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val itemPreviewRegex = Regex(
            """class="workshopItemPreviewImage[^"]*"\s+src="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val itemTitleRegex = Regex(
            """class="workshopItemTitle ellipsis">(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val itemAuthorRegex = Regex(
            """class="workshopItemAuthorName ellipsis">.*?<a\b[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val hoverRegex = Regex(
            """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val ssrRenderContextRegex = Regex(
            """window\.SSR\.renderContext=JSON\.parse\("(.+?)"\);""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val workshopDescriptionRegex = Regex(
            """<div\b[^>]*class="workshopItemDescription"[^>]*id="highlightContent"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val enlargedImageRegex = Regex(
            """ShowEnlargedImagePreview\(\s*'([^']+)'\s*\).*?<img\b[^>]*\bsrc="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val fullScreenshotUrlBlockRegex = Regex(
            """var\s+rgFullScreenshotURLs\s*=\s*\[(.*?)\];""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val fullScreenshotUrlRegex = Regex(
            """['"]url['"]\s*:\s*['"]([^'"]+)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val screenshotUrlMapBlockRegex = Regex(
            """var\s+rgScreenshotURLs\s*=\s*\{(.*?)\};""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val mappedScreenshotUrlRegex = Regex(
            """['"]\d+['"]\s*:\s*['"]([^'"]+)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val enlargedImageFunctionRegex = Regex(
            """ShowEnlargedImagePreview\(\s*['"]([^'"]+)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val screenshotAttributeRegex = Regex(
            """\b(?:data-fullsrc|data-screenshot|data-image|data-preview|data-src|src|href)=["']([^"']*(?:steamusercontent|steamuserimages|steamstatic|akamai|akamaihd)[^"']+)["']""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val screenshotSrcsetRegex = Regex(
            """\bsrcset=["']([^"']*(?:steamusercontent|steamuserimages|steamstatic|akamai|akamaihd)[^"']+)["']""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val quotedSteamImageUrlRegex = Regex(
            """['"]((?:https?:)?\\?/\\?/[^'"]*(?:steamusercontent|steamuserimages|steamstatic|akamai|akamaihd)[^'"]*)['"]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val previewImageRegex = Regex(
            """<img\b[^>]*id="previewImage"[^>]*\bsrc="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val ogImageRegex = Regex(
            """<meta\b[^>]*property="og:image"[^>]*content="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val requiredItemLinkRegex = Regex(
            """<a\b[^>]*href="([^"]*filedetails/\?[^"]*\bid=(\d+)[^"]*)"[^>]*>\s*<div\b[^>]*class="requiredItem"[^>]*>(.*?)</div>\s*</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }
}
