package com.godot.game

import android.text.Html
import com.godot.game.steam.auth.SteamAuthStore
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
    data class SearchResult(
        val total: Int,
        val page: Int,
        val items: List<Item>,
    )

    data class Item(
        val appId: Int,
        val publishedFileId: String,
        val title: String,
        val description: String,
        val previewUrl: String,
        val creatorSteamId: Long,
        val fileSizeBytes: Long,
        val subscriptions: Int,
        val views: Int,
        val timeUpdatedEpochSeconds: Long,
    )

    fun search(query: String, page: Int, pageSize: Int): SearchResult {
        if (query.isBlank()) {
            return searchPublic(query, page, pageSize, true)
        }
        return runBlocking {
            searchWithAuthenticatedFallback(query, page, pageSize)
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
            searchPublic(query, page, pageSize, false, WorkshopHttpRouteMode.ORIGINAL_ONLY)
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
            searchPublic(query, page, pageSize, false, WorkshopHttpRouteMode.DIRECT_ONLY)
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
        val publicResult = runCatching { searchPublic(query, page, pageSize, false) }
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

    private suspend fun searchWithAuthenticatedFallback(query: String, page: Int, pageSize: Int): SearchResult {
        val appContext = context.applicationContext
        val auth = SteamAuthStore.readAuthMaterial(appContext)
        val steamId = authSteamIdOrNull(appContext)
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
                searchPublic(query, page, pageSize, true)
            }
        } else {
            searchPublic(query, page, pageSize, true)
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
        routeMode: WorkshopHttpRouteMode = WorkshopHttpRouteMode.DEFAULT,
    ): SearchResult {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 50)
        val trimmedQuery = query.trim()
        val sort = "trend"
        val url = "https://steamcommunity.com/".toHttpUrl().newBuilder()
            .addPathSegment("workshop")
            .addPathSegment("browse")
            .addQueryParameter("appid", SteamWorkshopPreferences.DEFAULT_APP_ID.toString())
            .addQueryParameter("searchtext", trimmedQuery)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", "schinese")
            .addQueryParameter("browsesort", sort)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("actualsort", sort)
            .addQueryParameter("p", safePage.toString())
            .addQueryParameter("numperpage", safePageSize.toString())
            .apply {
                if (sort == "trend") {
                    addQueryParameter("days", "7")
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
        return Item(
            appId = appId,
            publishedFileId = publishedFileId,
            title = title.ifBlank { "Workshop $publishedFileId" },
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
                parseSsrWorkshopItem(results.optJSONObject(resultIndex) ?: continue)?.let(items::add)
            }
            return PublicBrowsePage(
                page = currentPage,
                total = if (totalCount > 0) totalCount else estimatedTotal(currentPage, pageSize, items.size, currentPage < totalPages),
                items = items,
            )
        }
        return null
    }

    private fun parseSsrWorkshopItem(objectValue: JSONObject): Item? {
        val publishedFileId = objectValue.optString("publishedfileid", "").takeIf(String::isNotBlank) ?: return null
        return Item(
            appId = objectValue.optInt("consumer_appid", SteamWorkshopPreferences.DEFAULT_APP_ID),
            publishedFileId = publishedFileId,
            title = objectValue.optString("title", "").ifBlank { "Workshop $publishedFileId" },
            description = stripTagsAndDecode(objectValue.optString("short_description", "")),
            previewUrl = objectValue.optString("preview_url", ""),
            creatorSteamId = objectValue.optString("creator", "0").toLongOrNull() ?: 0L,
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
            appId = objectValue.optInt("consumer_app_id", SteamWorkshopPreferences.DEFAULT_APP_ID),
            publishedFileId = publishedFileId.toString(),
            title = objectValue.optString("title", "").ifBlank { "Workshop $publishedFileId" },
            description = objectValue.optString("description", ""),
            previewUrl = objectValue.optString("preview_url", ""),
            creatorSteamId = objectValue.optString("creator", "0").toLongOrNull() ?: 0L,
            fileSizeBytes = objectValue.optLong("file_size", 0L),
            subscriptions = objectValue.optInt("subscriptions", 0),
            views = objectValue.optInt("views", 0),
            timeUpdatedEpochSeconds = objectValue.optLong("time_updated", 0L),
        )
    }

    private fun authSteamIdOrNull(context: android.content.Context): Long? =
        SteamAuthStore.readSnapshot(context).steamId64.trim().toLongOrNull()

    private fun decodeJsonStringLiteral(encoded: String): String? =
        runCatching {
            JSONTokener("\"$encoded\"").nextValue() as? String
        }.getOrNull()

    private fun stripTagsAndDecode(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()

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
        val hoverRegex = Regex(
            """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val ssrRenderContextRegex = Regex(
            """window\.SSR\.renderContext=JSON\.parse\("(.+?)"\);""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
