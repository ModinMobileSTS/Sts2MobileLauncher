package top.apricityx.workshop.workshop

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import top.apricityx.workshop.steam.protocol.CdnAuthToken
import kotlin.test.assertEquals

class SteamCdnAuthTokenCacheTest {
    @Test
    fun refreshesInsideThirtySecondSkew() = runBlocking {
        var now = Instant.ofEpochSecond(1_000L)
        val cache = SteamCdnAuthTokenCache(now = { now })
        val fetches = AtomicInteger()

        val first = cache.resolve("cdn.example") {
            CdnAuthToken("first", now.plusSeconds(31L)).also { fetches.incrementAndGet() }
        }
        assertEquals("first", cache.cached("CDN.EXAMPLE"))
        now = now.plusSeconds(2L)
        assertEquals(null, cache.cached("cdn.example"))
        val second = cache.resolve("cdn.example") {
            CdnAuthToken("second", now.plusSeconds(300L)).also { fetches.incrementAndGet() }
        }

        assertEquals("first", first)
        assertEquals("second", second)
        assertEquals(2, fetches.get())
    }

    @Test
    fun rejectedTokenForcesOneSharedRefresh() = runBlocking {
        val now = Instant.ofEpochSecond(1_000L)
        val cache = SteamCdnAuthTokenCache(now = { now })
        val fetches = AtomicInteger()
        val fetch: suspend (String) -> CdnAuthToken = {
            val sequence = fetches.incrementAndGet()
            delay(25L)
            CdnAuthToken("token-$sequence", now.plusSeconds(300L))
        }
        assertEquals("token-1", cache.resolve("CDN.EXAMPLE", fetchToken = fetch))

        val refreshed = List(8) {
            async { cache.resolve("cdn.example", rejectedToken = "?token-1", fetchToken = fetch) }
        }.awaitAll()

        assertEquals(List(8) { "token-2" }, refreshed)
        assertEquals(2, fetches.get())
    }
}
