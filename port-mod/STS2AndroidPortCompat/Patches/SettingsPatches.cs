using System;
using System.IO;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

// Mirrors the reference launcher's first-launch mobile defaults and VSync label
// fix, but uses this port's Java companion settings file as the marker location
// so it coexists with the Android shell.
public static class SettingsPatches
{
    private static bool _mobileDefaultsChecked;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(SaveManager), "InitSettingsData", postfix: PatchHelper.Method(typeof(SettingsPatches), nameof(InitSettingsDataPostfix)));

        var vsyncPaginatorType = typeof(SaveManager).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NVSyncPaginator");
        if (vsyncPaginatorType != null)
            PatchHelper.Patch(harmony, vsyncPaginatorType, "GetVSyncString", prefix: PatchHelper.Method(typeof(SettingsPatches), nameof(GetVSyncStringPrefix)));
    }

    public static void InitSettingsDataPostfix()
    {
        if (_mobileDefaultsChecked)
            return;
        _mobileDefaultsChecked = true;

        var markerPath = Path.Combine(AppPaths.AccountRoot, ".mobile_defaults_applied");
        if (File.Exists(markerPath))
            return;

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(markerPath) ?? AppPaths.AccountRoot);
            var settings = SaveManager.Instance.SettingsSave;
            settings.VSync = VSyncType.On;
            settings.AspectRatioSetting = AspectRatioSetting.Auto;
            settings.Msaa = 0;
            SaveManager.Instance.SaveSettings();
            File.WriteAllText(markerPath, "1");
            PatchHelper.Log("Applied reference mobile defaults (first launch): VSync=On, AspectRatio=Auto, Msaa=None.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to apply reference mobile defaults: {exception.Message}");
        }
    }

    public static bool GetVSyncStringPrefix(object vsyncType, ref string __result)
    {
        try
        {
            int value = (int)vsyncType;
            var sts2Assembly = typeof(SaveManager).Assembly;
            var locStringType = sts2Assembly.GetType("MegaCrit.Sts2.Core.Localization.LocString");
            var constructor = locStringType?.GetConstructor(new[] { typeof(string), typeof(string) });
            var getTextMethod = locStringType?.GetMethod("GetFormattedText", Type.EmptyTypes);

            string key = value switch
            {
                1 => "VSYNC_OFF",
                2 => "VSYNC_ON",
                3 => "VSYNC_ADAPTIVE",
                _ => "VSYNC_ADAPTIVE",
            };

            if (constructor != null && getTextMethod != null)
            {
                var locString = constructor.Invoke(new object[] { "settings_ui", key });
                __result = (string)getTextMethod.Invoke(locString, null);
            }
            else
            {
                __result = value switch
                {
                    1 => "Off",
                    2 => "On",
                    _ => "Adaptive",
                };
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"GetVSyncStringPrefix failed: {exception.Message}");
            __result = "On";
        }
        return false;
    }
}
