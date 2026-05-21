using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection.Emit;
using HarmonyLib;
using MegaCrit.Sts2.Core.Modding;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class ModLoaderPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(ModManager), "Initialize", transpiler: PatchHelper.Method(typeof(ModLoaderPatches), nameof(InitializeTranspiler)));
        PatchHelper.Patch(harmony, typeof(ModManager), "ReadSteamMods", prefix: PatchHelper.Method(typeof(ModLoaderPatches), nameof(ReadSteamModsPrefix)));
        PatchHelper.Log("Mod loader compatibility patches enabled: local Android mods dir + no Steam Workshop scan.");
    }

    public static IEnumerable<CodeInstruction> InitializeTranspiler(IEnumerable<CodeInstruction> instructions)
    {
        var matcher = new CodeMatcher(instructions).MatchStartForward(new CodeMatch(OpCodes.Ldstr, "mods"));
        if (matcher.IsValid)
        {
            matcher.Advance(-1);
            matcher.RemoveInstructions(3);
            matcher.InsertAndAdvance(new CodeInstruction(OpCodes.Call, AccessTools.Method(typeof(ModLoaderPatches), nameof(GetAndroidModsDir))));
            PatchHelper.Log("Redirected local mods directory to Android app-private mods dir.");
        }
        else
        {
            PatchHelper.Log("Could not locate local mods path in ModManager.Initialize; external mods may be ignored.");
        }
        return matcher.InstructionEnumeration();
    }

    public static string GetAndroidModsDir()
    {
        var path = AppPaths.ModsDir;
        try
        {
            Directory.CreateDirectory(path);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to create Android mods directory '{path}': {exception.Message}");
        }
        return path;
    }

    public static bool ReadSteamModsPrefix() => false;
}
