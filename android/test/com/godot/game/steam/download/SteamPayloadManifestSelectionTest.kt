package com.godot.game.steam.download

import `in`.dragonbra.javasteam.types.KeyValue
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SteamPayloadManifestSelectionTest {
    @Test
    fun manifestIdsSupportTheUnsignedBoundaryWithoutAcceptingOverflowOrSigns() {
        val maximum = "18446744073709551615"
        assertEquals(maximum, Sts2SteamPayloadDownloader.Selection.forManifest(" 0$maximum ", "").manifestId)
        for (invalid in listOf("", "0", "-1", "+1", "1.0", "1e3", "18446744073709551616")) {
            assertThrows(IllegalArgumentException::class.java) {
                Sts2SteamPayloadDownloader.Selection.forManifest(invalid, "public")
            }
        }
    }

    @Test
    fun currentListUsesOnlyVisibleManifestsFromTheWindowsDepot() {
        val options = downloader().currentManifestOptions(appInfo("""
            "appinfo" {
                "depots" {
                    "2868840" { "manifests" { "workshop" { "gid" "999" } } }
                    "2868841" {
                        "config" { "oslist" "windows" "osarch" "64" }
                        "manifests" {
                            "public-beta" { "gid" "18446744073709551615" }
                            "public" { "gid" "42" }
                            "broken" { "gid" "0" }
                        }
                        "encryptedmanifests" { "private" { "gid" "73" } }
                    }
                    "2868843" { "manifests" { "linux" { "gid" "888" } } }
                }
            }
        """))
        assertEquals(listOf("public:42", "public-beta:18446744073709551615"), options.map { "${it.branch}:${it.manifestId}" })
    }

    @Test
    fun changedDepotPlatformIsRejectedInsteadOfOfferingIncompatibleFiles() {
        assertThrows(IOException::class.java) {
            downloader().currentManifestOptions(appInfo("""
                "appinfo" { "depots" { "2868841" {
                    "config" { "oslist" "linux" "osarch" "64" }
                    "manifests" { "public" { "gid" "42" } }
                } } }
            """))
        }
    }

    private fun downloader() = Sts2SteamPayloadDownloader(RuntimeEnvironment.getApplication())

    private fun appInfo(text: String) = KeyValue().apply {
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { stream ->
            check(readAsText(stream))
        }
    }
}
