using System.Collections.Generic;
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
    }

    public static IEnumerable<CodeInstruction> InitializeTranspiler(IEnumerable<CodeInstruction> instructions)
    {
        var matcher = new CodeMatcher(instructions).MatchStartForward(new CodeMatch(OpCodes.Ldstr, "mods"));
        if (matcher.IsValid)
        {
            matcher.Advance(-1);
            matcher.RemoveInstructions(3);
            matcher.InsertAndAdvance(new CodeInstruction(OpCodes.Ldstr, AppPaths.ModsDir));
            System.IO.Directory.CreateDirectory(AppPaths.ModsDir);
            PatchHelper.Log($"Redirected local mods directory to {AppPaths.ModsDir}");
        }
        else
        {
            PatchHelper.Log("Could not locate local mods path in ModManager.Initialize; external mods may be ignored.");
        }
        return matcher.InstructionEnumeration();
    }

    public static bool ReadSteamModsPrefix() => false;
}
