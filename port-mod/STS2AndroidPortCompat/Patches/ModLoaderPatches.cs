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
        // Startup first: avoid AppPaths/DataDir and Harmony transpiler execution during
        // the native GodotSharp bootstrap, which is currently sensitive to early Godot
        // API / StringName initialization on Android.
        PatchHelper.Log("Mod loader compatibility patches disabled during Android startup stabilization.");
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
