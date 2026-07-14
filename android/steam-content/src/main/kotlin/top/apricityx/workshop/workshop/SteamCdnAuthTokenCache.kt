package top.apricityx.workshop.workshop

import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.apricityx.workshop.steam.protocol.CdnAuthToken

/**
 * Keeps Steam CDN tokens until shortly before expiry and coalesces refreshes per host.
 *
 * When [rejectedToken] is supplied after a 403, the cached value is reused only when
 * another coroutine has already replaced the rejected token. This prevents a stale
 * cached token from being returned by the refresh path while avoiding duplicate CM
 * requests from concurrent chunk workers.
 */
internal class SteamCdnAuthTokenCache(
    private val now: () -> Instant = Instant::now,
    private val refreshSkew: Duration = Duration.ofSeconds(DEFAULT_REFRESH_SKEW_SECONDS),
) {
    private val tokens = ConcurrentHashMap<String, CdnAuthToken>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    internal fun cached(host: String): String? = usableToken(tokens[normalizeHost(host)], rejectedToken = null)

    suspend fun resolve(
        host: String,
        rejectedToken: String? = null,
        fetchToken: suspend (String) -> CdnAuthToken,
    ): String {
        val key = normalizeHost(host)
        val rejected = normalizeToken(rejectedToken)
        usableToken(tokens[key], rejected)?.let { return it }

        return refreshLocks.computeIfAbsent(key) { Mutex() }.withLock {
            usableToken(tokens[key], rejected)?.let { return@withLock it }

            val fetched = fetchToken(host)
            val normalized = fetched.copy(token = normalizeToken(fetched.token).orEmpty())
            if (normalized.token.isBlank()) {
                throw WorkshopDownloadException("Steam CDN returned an empty auth token")
            }
            tokens[key] = normalized
            normalized.token
        }
    }

    internal fun invalidate(host: String, rejectedToken: String? = null) {
        val key = normalizeHost(host)
        val rejected = normalizeToken(rejectedToken)
        if (rejected == null) {
            tokens.remove(key)
        } else {
            tokens.computeIfPresent(key) { _, current ->
                current.takeUnless { normalizeToken(it.token) == rejected }
            }
        }
    }

    private fun usableToken(token: CdnAuthToken?, rejectedToken: String?): String? {
        token ?: return null
        val normalized = normalizeToken(token.token) ?: return null
        if (rejectedToken != null && normalized == rejectedToken) return null
        if (!token.expiration.isAfter(now().plus(refreshSkew))) return null
        return normalized
    }

    private fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT)

    private fun normalizeToken(token: String?): String? = token
        ?.trim()
        ?.removePrefix("?")
        ?.takeIf(String::isNotBlank)

    private companion object {
        const val DEFAULT_REFRESH_SKEW_SECONDS = 30L
    }
}
