package com.godot.game

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

internal enum class WorkshopHttpRouteMode {
    DEFAULT,
    ORIGINAL_ONLY,
    DIRECT_ONLY,
}

internal object SteamWorkshopDirectAccess {
    private const val TAG = "STS2WorkshopDirect"
    private const val MAX_REDIRECTS = 10
    private const val HTTP_METHOD_GET = "GET"
    private const val HTTP_METHOD_HEAD = "HEAD"
    private const val HTTP_TEMP_REDIRECT = 307
    private const val HTTP_PERM_REDIRECT = 308
    private val REDIRECT_RESPONSE_CODES = setOf(300, 301, 302, 303, HTTP_TEMP_REDIRECT, HTTP_PERM_REDIRECT)

    private val COMMUNITY_ROUTE = RouteProfile(
        name = "steam-community",
        logicalHosts = setOf(
            "steamcommunity.com",
            "www.steamcommunity.com",
            "images.steamusercontent.com",
            "steamuserimages-a.akamaihd.net",
            "steamusercontent-a.akamaihd.net",
            "cdn.akamai.steamstatic.com",
            "shared.akamai.steamstatic.com",
            "cdn.cloudflare.steamstatic.com",
            "clan.cloudflare.steamstatic.com",
            "avatars.steamstatic.com",
        ),
        logicalHostSuffixes = setOf(
            ".steamusercontent.com",
            ".steamuserimages-a.akamaihd.net",
            ".steamusercontent-a.akamaihd.net",
            ".steamstatic.com",
        ),
        forwardHost = "steamcommunity.rmbgame.net",
        ignoreSslCertVerification = true,
    )
    private val STORE_ROUTE = RouteProfile(
        name = "steam-store",
        logicalHosts = setOf(
            "api.steampowered.com",
            "store.steampowered.com",
            "help.steampowered.com",
            "login.steampowered.com",
            "checkout.steampowered.com",
        ),
        forwardHost = "steamstore.rmbgame.net",
        ignoreSslCertVerification = true,
    )
    private val ROUTES = listOf(COMMUNITY_ROUTE, STORE_ROUTE)

    fun buildClient(
        context: Context,
        mode: WorkshopHttpRouteMode = WorkshopHttpRouteMode.DEFAULT,
        configure: OkHttpClient.Builder.() -> Unit,
    ): OkHttpClient {
        val appContext = context.applicationContext
        val hostnameVerifier = RouteHostnameVerifier(ROUTES)
        val baseClient = OkHttpClient.Builder()
            .hostnameVerifier(hostnameVerifier)
            .apply(configure)
            .build()
        val directClient = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .hostnameVerifier(hostnameVerifier)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()
        return baseClient.newBuilder()
            .addInterceptor(DirectAccessInterceptor(appContext, mode, directClient))
            .build()
    }

    private class DirectAccessInterceptor(
        private val context: Context,
        private val mode: WorkshopHttpRouteMode,
        private val directClient: OkHttpClient,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (mode == WorkshopHttpRouteMode.ORIGINAL_ONLY || !supports(request.url.host)) {
                return chain.proceed(request)
            }
            if (mode == WorkshopHttpRouteMode.DEFAULT && !SteamWorkshopPreferences.isDirectAccessEnabled(context)) {
                return chain.proceed(request)
            }
            return try {
                executeDirectAccessRequest(request, directClient)
            } catch (error: IOException) {
                if (mode == WorkshopHttpRouteMode.DIRECT_ONLY) {
                    throw error
                }
                Log.w(TAG, "Workshop direct-access route failed; falling back to original Steam host: ${error.message}", error)
                chain.proceed(request)
            }
        }
    }

    private fun executeDirectAccessRequest(
        initialLogicalRequest: Request,
        directClient: OkHttpClient,
    ): Response {
        var logicalRequest = initialLogicalRequest
        var followUpCount = 0
        while (true) {
            val networkRequest = buildNetworkRequest(logicalRequest)
            val response = directClient.newCall(networkRequest).execute()
            val redirectTarget = response.redirectTarget(logicalRequest.url)
            if (redirectTarget == null) {
                return response.newBuilder()
                    .request(logicalRequest)
                    .build()
            }
            val responseCode = response.code
            response.close()
            if (followUpCount >= MAX_REDIRECTS) {
                throw ProtocolException("Too many Workshop direct-access redirects: $MAX_REDIRECTS")
            }
            logicalRequest = buildRedirectRequest(logicalRequest, redirectTarget, responseCode)
            followUpCount++
        }
    }

    private fun buildNetworkRequest(logicalRequest: Request): Request {
        val logicalUrl = normalizeForwardedUrl(
            url = logicalRequest.url,
            fallbackLogicalHost = logicalRequest.url.host,
        )
        val route = routeForLogicalHost(logicalUrl.host)
        if (route == null) {
            return logicalRequest.newBuilder()
                .url(logicalUrl)
                .removeHeader("Host")
                .build()
        }
        val networkUrl = logicalUrl.newBuilder()
            .host(route.forwardHost)
            .build()
        Log.i(TAG, "Workshop direct-access ${route.name}: ${logicalUrl.host}${logicalUrl.encodedPath} -> ${route.forwardHost}${networkUrl.encodedPath}")
        return logicalRequest.newBuilder()
            .url(networkUrl)
            .header("Host", logicalUrl.host)
            .build()
    }

    private fun buildRedirectRequest(
        previousLogicalRequest: Request,
        redirectUrl: HttpUrl,
        responseCode: Int,
    ): Request {
        val preserveBody = responseCode == HTTP_TEMP_REDIRECT || responseCode == HTTP_PERM_REDIRECT
        val originalMethod = previousLogicalRequest.method
        val redirectMethod = when {
            preserveBody -> originalMethod
            originalMethod == HTTP_METHOD_GET || originalMethod == HTTP_METHOD_HEAD -> originalMethod
            else -> HTTP_METHOD_GET
        }
        val redirectBody: RequestBody? = if (redirectMethod == originalMethod) previousLogicalRequest.body else null
        return previousLogicalRequest.newBuilder()
            .url(redirectUrl)
            .method(redirectMethod, redirectBody)
            .apply {
                if (redirectBody == null) {
                    removeHeader("Transfer-Encoding")
                    removeHeader("Content-Length")
                    removeHeader("Content-Type")
                }
            }
            .build()
    }

    private fun Response.redirectTarget(logicalUrl: HttpUrl): HttpUrl? {
        if (code !in REDIRECT_RESPONSE_CODES) {
            return null
        }
        val location = header("Location")?.trim().orEmpty()
        if (location.isBlank()) {
            return null
        }
        val resolved = logicalUrl.resolve(location) ?: return null
        return normalizeForwardedUrl(
            url = resolved,
            fallbackLogicalHost = logicalUrl.host,
        )
    }

    private fun normalizeForwardedUrl(
        url: HttpUrl,
        fallbackLogicalHost: String,
    ): HttpUrl {
        val route = routeForForwardHost(url.host) ?: return url
        return url.newBuilder()
            .host(fallbackLogicalHost.takeIf { route.supports(it) } ?: route.primaryLogicalHost)
            .build()
    }

    private fun supports(host: String): Boolean =
        routeForLogicalHost(host) != null

    private fun routeForLogicalHost(host: String): RouteProfile? {
        val normalized = host.lowercase()
        return ROUTES.firstOrNull { route -> route.supports(normalized) }
    }

    private fun routeForForwardHost(host: String): RouteProfile? {
        val normalized = host.lowercase()
        return ROUTES.firstOrNull { route -> normalized == route.forwardHost }
    }

    private data class RouteProfile(
        val name: String,
        val logicalHosts: Set<String>,
        val logicalHostSuffixes: Set<String> = emptySet(),
        val forwardHost: String,
        val ignoreSslCertVerification: Boolean,
    ) {
        val primaryLogicalHost: String = logicalHosts.first()

        fun supports(host: String): Boolean =
            host.lowercase().let { normalized ->
                normalized in logicalHosts || logicalHostSuffixes.any(normalized::endsWith)
            }
    }

    private class RouteHostnameVerifier(
        private val routes: List<RouteProfile>,
        private val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    ) : HostnameVerifier {
        override fun verify(hostname: String, session: SSLSession): Boolean {
            if (defaultVerifier.verify(hostname, session)) {
                return true
            }
            val route = routes.firstOrNull { candidate ->
                candidate.ignoreSslCertVerification && candidate.forwardHost == hostname.lowercase()
            }
            if (route != null) {
                Log.w(TAG, "Bypassed Workshop direct-access hostname verification for ${route.forwardHost}.")
                return true
            }
            return false
        }
    }
}
