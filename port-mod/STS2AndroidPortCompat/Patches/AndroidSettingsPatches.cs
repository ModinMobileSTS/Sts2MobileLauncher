using System;
using HarmonyLib;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;
using STS2AndroidPortCompat.Android;

namespace STS2AndroidPortCompat.Patches;

public static class AndroidSettingsPatches
{
    private static bool _appliedOnce;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(SaveManager), "InitSettingsData", postfix: PatchHelper.Method(typeof(AndroidSettingsPatches), nameof(InitSettingsDataPostfix)));
        var vsyncPaginatorType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NVSyncPaginator");
        if (vsyncPaginatorType != null)
            PatchHelper.Patch(harmony, vsyncPaginatorType, "GetVSyncString", prefix: PatchHelper.Method(typeof(AndroidSettingsPatches), nameof(GetVSyncStringPrefix)));
    }

    public static void InitSettingsDataPostfix()
    {
        if (_appliedOnce)
            return;
        _appliedOnce = true;
        try
        {
            var settings = SaveManager.Instance.SettingsSave;
            settings.VSync = AndroidSettingsBridge.GetString("vsync", "off") switch
            {
                "on" => VSyncType.On,
                "adaptive" => VSyncType.Adaptive,
                _ => VSyncType.Off,
            };
            settings.Msaa = AndroidSettingsBridge.GetInt("msaa", settings.Msaa);
            SaveManager.Instance.SaveSettings();
            PatchHelper.Log("Applied initial Android settings bridge values (vsync/msaa). Full mobile fields are TODO in M6.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android settings bridge failed: {exception}");
        }
    }

    public static bool GetVSyncStringPrefix(object vsyncType, ref string __result)
    {
        var value = (int)vsyncType;
        __result = value switch
        {
            1 => "Off",
            2 => "On",
            3 => "Adaptive",
            _ => "Adaptive",
        };
        return false;
    }
}
