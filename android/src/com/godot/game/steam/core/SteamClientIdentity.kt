package com.godot.game.steam.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.DEFAULT_MACHINE_NAME
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.buildSteamMachineId

class SteamClientIdentity(context: Context) {
    private val appContext = context.applicationContext
    val machineName: String = "STS2 Android Launcher"
    private val machineId: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val installationId = resolveInstallationId()
        val baseId = resolveAndroidId() ?: installationId
        val hardwareSummary = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.BOARD,
        ).joinToString("|") { it?.takeIf(String::isNotBlank) ?: "unknown" }
        val storageSummary = listOf(
            Build.FINGERPRINT,
            Build.PRODUCT,
            Build.HARDWARE,
            Build.SUPPORTED_ABIS.joinToString(","),
        ).joinToString("|") { it?.takeIf(String::isNotBlank) ?: "unknown" }
        buildSteamMachineId(
            machineGuidSource = "$baseId|${appContext.packageName}".toByteArray(Charsets.UTF_8),
            macAddressSource = "$baseId|$hardwareSummary".toByteArray(Charsets.UTF_8),
            diskIdSource = "$baseId|$storageSummary".toByteArray(Charsets.UTF_8),
        )
    }

    fun createSession(client: OkHttpClient): OkHttpSteamCmSession = OkHttpSteamCmSession(
        client = client,
        machineName = machineName.ifBlank { DEFAULT_MACHINE_NAME },
        machineId = machineId,
    )

    private fun resolveAndroidId(): String? = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID,
    )?.trim()?.takeUnless { it.isBlank() || it == INVALID_ANDROID_ID }

    private fun resolveInstallationId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALLATION_ID, null)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    private companion object {
        private const val PREFS_NAME = "sts2_steam_client_identity"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val INVALID_ANDROID_ID = "9774d56d682e549c"
    }
}
