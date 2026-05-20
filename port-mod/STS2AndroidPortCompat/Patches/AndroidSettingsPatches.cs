using System;
using HarmonyLib;
using MegaCrit.Sts2.Core.Modding;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class AndroidSettingsPatches
{
    private static bool _appliedOnce;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(SaveManager), "InitSettingsData", postfix: PatchHelper.Method(typeof(AndroidSettingsPatches), nameof(InitSettingsDataPostfix)));
        var settingsSaveManagerType = typeof(SettingsSave).Assembly.GetType("MegaCrit.Sts2.Core.Saves.Managers.SettingsSaveManager");
        if (settingsSaveManagerType != null)
            PatchHelper.Patch(
                harmony,
                settingsSaveManagerType,
                "SaveSettings",
                prefix: PatchHelper.Method(typeof(AndroidSettingsPatches), nameof(SaveSettingsPrefix)),
                postfix: PatchHelper.Method(typeof(AndroidSettingsPatches), nameof(SaveSettingsPostfix)));

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
            settings.AspectRatioSetting = ParseAspectRatio(AndroidSettingsBridge.GetString("aspect_ratio", "auto"), settings.AspectRatioSetting);
            settings.VSync = ParseVSync(AndroidSettingsBridge.GetString("vsync", "off"), settings.VSync);
            settings.Msaa = AndroidSettingsBridge.GetInt("msaa", settings.Msaa);
            settings.FpsLimit = AndroidSettingsBridge.GetInt("fps_limit", settings.FpsLimit);
            settings.Fullscreen = AndroidSettingsBridge.GetBool("fullscreen", settings.Fullscreen);
            EnsureModSettings(settings);
            PatchHelper.Log("Applied Android settings bridge values (aspect/vsync/msaa/fps/fullscreen/mod settings). Mobile-only fields stay in companion JSON.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android settings bridge failed: {exception}");
        }
    }

    public static bool SaveSettingsPrefix(object __instance)
    {
        try
        {
            if (AndroidSettingsBridge.TryReadRaw(out var beforeJson))
                AndroidSettingsMerge.PendingBeforeSaveJson = beforeJson;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"PreserveAndroidOnlySettingsOnSave failed: {exception}");
        }
        return true;
    }

    public static void SaveSettingsPostfix()
    {
        var beforeJson = AndroidSettingsMerge.PendingBeforeSaveJson;
        AndroidSettingsMerge.PendingBeforeSaveJson = null;
        if (!string.IsNullOrWhiteSpace(beforeJson))
            AndroidSettingsMerge.MergeBackAndroidOnlyFields(beforeJson);
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

    private static void EnsureModSettings(SettingsSave settings)
    {
        if (settings.ModSettings != null)
            return;
        if (!AndroidSettingsBridge.TryGet("mod_settings", out var element) || element.ValueKind is System.Text.Json.JsonValueKind.Null or System.Text.Json.JsonValueKind.Undefined)
            return;
        settings.ModSettings = new ModSettings { PlayerAgreedToModLoading = true };
    }

    private static VSyncType ParseVSync(string value, VSyncType fallback)
    {
        return (value ?? string.Empty).Trim().ToLowerInvariant() switch
        {
            "off" or "disabled" or "0" => VSyncType.Off,
            "on" or "enabled" or "1" => VSyncType.On,
            "adaptive" or "2" or "3" => VSyncType.Adaptive,
            _ => fallback,
        };
    }

    private static AspectRatioSetting ParseAspectRatio(string value, AspectRatioSetting fallback)
    {
        return (value ?? string.Empty).Trim().ToLowerInvariant().Replace("-", "_") switch
        {
            "auto" => AspectRatioSetting.Auto,
            "4_3" or "four_by_three" => AspectRatioSetting.FourByThree,
            "16_10" or "sixteen_by_ten" => AspectRatioSetting.SixteenByTen,
            "16_9" or "sixteen_by_nine" => AspectRatioSetting.SixteenByNine,
            "21_9" or "twenty_one_by_nine" => AspectRatioSetting.TwentyOneByNine,
            _ => fallback,
        };
    }

}
