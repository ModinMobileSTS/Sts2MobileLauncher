using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection.Emit;
using System.Text.Json;
using HarmonyLib;
using MegaCrit.Sts2.Core.Modding;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

// Mirrors the reference launcher startup path: rewrite the game's own
// `Path.Combine(directoryName, "mods")` during ModManager.Initialize so the
// built-in recursive scanner / dependency sorter / per-mod enable checks do all
// the loading work.  This is intentionally not a post-scan reflection shim.
public static class ModLoaderPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(ModManager), "Initialize", transpiler: PatchHelper.Method(typeof(ModLoaderPatches), nameof(InitializeTranspiler)));
        PatchHelper.Patch(harmony, typeof(ModManager), "ReadSteamMods", prefix: PatchHelper.Method(typeof(ModLoaderPatches), nameof(ReadSteamModsPrefix)));
        PatchHelper.Log("Mod loader compatibility patches enabled: reference launcher local-mod scan + no Steam Workshop scan.");
    }

    public static IEnumerable<CodeInstruction> InitializeTranspiler(IEnumerable<CodeInstruction> instructions)
    {
        var matcher = new CodeMatcher(instructions).MatchStartForward(new CodeMatch(OpCodes.Ldstr, "mods"));
        if (matcher.IsValid)
        {
            // Reference pattern is: ldloc directoryName, ldstr "mods", call Path.Combine.
            // Drop all three instructions and push our resolved Android mods directory.
            matcher.Advance(-1);
            matcher.RemoveInstructions(3);
            matcher.InsertAndAdvance(new CodeInstruction(OpCodes.Call, AccessTools.Method(typeof(ModLoaderPatches), nameof(GetAndroidModsDir))));
            PatchHelper.Log($"[Mods] Redirected ModManager.Initialize to {AppPaths.ModsDir}");
        }
        else
        {
            PatchHelper.Log("[Mods] Warning: could not locate \"mods\" ldstr in ModManager.Initialize; Android mods will be ignored.");
        }
        return matcher.InstructionEnumeration();
    }

    public static string GetAndroidModsDir()
    {
        var path = AppPaths.ModsDir;
        try
        {
            Directory.CreateDirectory(path);
            NormalizeManifestAliases(path);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"[Mods] Failed to prepare Android mods directory '{path}': {exception.Message}");
        }
        return path;
    }

    private static void NormalizeManifestAliases(string modsRoot)
    {
        if (!Directory.Exists(modsRoot))
            return;
        foreach (var manifestPath in Directory.EnumerateFiles(modsRoot, "mod_manifest.json", SearchOption.AllDirectories))
        {
            TryCopyManifestAlias(manifestPath);
        }
    }

    private static void TryCopyManifestAlias(string manifestPath)
    {
        try
        {
            var dir = Path.GetDirectoryName(manifestPath);
            if (string.IsNullOrEmpty(dir))
                return;
            var modId = ReadManifestId(manifestPath);
            if (string.IsNullOrWhiteSpace(modId))
                modId = Path.GetFileName(dir);
            if (string.IsNullOrWhiteSpace(modId))
                return;
            var alias = Path.Combine(dir, modId + ".json");
            if (Path.GetFullPath(alias).Equals(Path.GetFullPath(manifestPath), StringComparison.OrdinalIgnoreCase))
                return;
            if (!File.Exists(alias))
            {
                WriteManifestAlias(manifestPath, alias);
                PatchHelper.Log($"[Mods] Added runtime manifest alias {Path.GetFileName(alias)} for reference scanner.");
            }
            if (File.Exists(alias) && string.Equals(ReadManifestId(alias), modId, StringComparison.Ordinal) && IsGeneratedManifestAlias(alias))
            {
                File.Delete(manifestPath);
                PatchHelper.Log($"[Mods] Removed duplicate mod_manifest.json after aliasing {Path.GetFileName(alias)}.");
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"[Mods] Manifest alias normalization failed for {manifestPath}: {exception.Message}");
        }
    }

    private static void WriteManifestAlias(string sourcePath, string aliasPath)
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(sourcePath));
            using var stream = File.Create(aliasPath);
            using var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true });
            writer.WriteStartObject();
            foreach (var property in document.RootElement.EnumerateObject())
                property.WriteTo(writer);
            writer.WriteBoolean("android_generated_manifest_alias", true);
            writer.WriteEndObject();
        }
        catch
        {
            File.Copy(sourcePath, aliasPath, overwrite: true);
        }
    }

    private static bool IsGeneratedManifestAlias(string manifestPath)
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(manifestPath));
            return document.RootElement.TryGetProperty("android_generated_manifest_alias", out var generated)
                && generated.ValueKind == JsonValueKind.True;
        }
        catch
        {
            return false;
        }
    }

    private static string ReadManifestId(string manifestPath)
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(manifestPath));
            var root = document.RootElement;
            if (root.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String)
                return id.GetString();
            if (root.TryGetProperty("mod_id", out var modId) && modId.ValueKind == JsonValueKind.String)
                return modId.GetString();
            if (root.TryGetProperty("modId", out var camelModId) && camelModId.ValueKind == JsonValueKind.String)
                return camelModId.GetString();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"[Mods] Failed to read manifest id from {manifestPath}: {exception.Message}");
        }
        return null;
    }

    public static bool ReadSteamModsPrefix() => false;
}
