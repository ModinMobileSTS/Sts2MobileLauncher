using System;
using HarmonyLib;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class LanMultiplayerBootstrapPatches
{
    private static Harmony _harmony;
    private static bool _lanApplied;

    public static void Apply(Harmony harmony)
    {
        _harmony = harmony;
        if (!AndroidSettingsBridge.GetBool("lan_multiplayer_enabled", true))
        {
            PatchHelper.Log("LAN multiplayer compatibility is disabled by Android companion settings.");
            return;
        }

        PatchHelper.Log("LAN multiplayer compatibility patches disabled during Android startup stabilization.");
    }

    public static void MainMenuReadyPostfix()
    {
        if (_lanApplied)
            return;
        _lanApplied = true;
        try
        {
            PatchHelper.Log("Applying deferred LAN multiplayer compatibility patches.");
            LanMultiplayerPatches.Apply(_harmony);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Deferred LAN multiplayer patch application failed: {exception}");
        }
    }
}
