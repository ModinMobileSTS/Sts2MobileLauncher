using System;
using System.Collections.Generic;
using System.Reflection;
using System.Text.Json;
using Godot;
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
            ApplyCompanionSettingsToRuntimeSave(includeModSettings: true);
            PatchHelper.Log("Applied Android settings bridge values (aspect/vsync/msaa/fullscreen/mod settings). FPS stays controlled by the original in-game setting.");
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
        AndroidSettingsBridge.InvalidateCache();
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

    public static void ApplyCompanionSettingsToRuntimeSave(bool includeModSettings = false)
    {
        var settings = SaveManager.Instance?.SettingsSave;
        if (settings == null)
            return;
        settings.AspectRatioSetting = ParseAspectRatio(AndroidSettingsBridge.GetString("aspect_ratio", "auto"), settings.AspectRatioSetting);
        settings.VSync = ParseVSync(AndroidSettingsBridge.GetString("vsync", "off"), settings.VSync);
        settings.Msaa = AndroidSettingsBridge.GetInt("msaa", settings.Msaa);
        settings.Fullscreen = AndroidSettingsBridge.GetBool("fullscreen", settings.Fullscreen);
        ApplyOptionalSourcePortSettings(settings);
        if (includeModSettings)
            EnsureModSettings(settings);
    }

    private static void ApplyOptionalSourcePortSettings(SettingsSave settings)
    {
        var renderSize = AndroidSettingsBridge.GetSize("fullscreen_render_size");
        SetOptionalProperty(settings, "FullscreenRenderSize", new Vector2I(renderSize.X, renderSize.Y));
        SetOptionalProperty(settings, "GlobalScale", AndroidSettingsBridge.GetFloat("global_scale", GetOptionalProperty(settings, "GlobalScale", 1f)));
        SetOptionalProperty(settings, "UiFontScalePercent", AndroidSettingsBridge.GetInt("ui_font_scale_percent", GetOptionalProperty(settings, "UiFontScalePercent", 100)));
        SetOptionalProperty(settings, "PreloadEnabled", AndroidSettingsBridge.GetBool("preload_enabled", GetOptionalProperty(settings, "PreloadEnabled", true)));
        SetOptionalProperty(settings, "AndroidFlipScreen180", AndroidSettingsBridge.GetBool("android_flip_screen_180", GetOptionalProperty(settings, "AndroidFlipScreen180", false)));
    }

    private static T GetOptionalProperty<T>(object target, string propertyName, T fallback)
    {
        try
        {
            var property = target.GetType().GetProperty(propertyName, BindingFlags.Public | BindingFlags.Instance);
            if (property?.CanRead == true && property.GetValue(target) is T value)
                return value;
        }
        catch
        {
        }
        return fallback;
    }

    private static void SetOptionalProperty(object target, string propertyName, object value)
    {
        try
        {
            var property = target.GetType().GetProperty(propertyName, BindingFlags.Public | BindingFlags.Instance);
            if (property?.CanWrite == true && property.PropertyType.IsInstanceOfType(value))
                property.SetValue(target, value);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to apply optional Android setting {propertyName}: {exception.Message}");
        }
    }

    private static void EnsureModSettings(SettingsSave settings)
    {
        if (!AndroidSettingsBridge.TryGet("mod_settings", out var element) || element.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
            return;

        var modSettings = new ModSettings
        {
            PlayerAgreedToModLoading = element.ValueKind == JsonValueKind.Object && element.TryGetProperty("mods_enabled", out var enabledElement) && ReadBool(enabledElement, false),
        };

        if (element.ValueKind == JsonValueKind.Object)
        {
            ApplyModernModList(modSettings, element);
            ApplyLegacyDisabledMods(modSettings, element);
        }

        settings.ModSettings = modSettings;
        PatchHelper.Log($"Applied Android mod settings: enabled={modSettings.PlayerAgreedToModLoading}, entries={CountConfiguredModEntries(modSettings)}");
        if (modSettings.PlayerAgreedToModLoading && CountConfiguredModEntries(modSettings) == 0)
            PatchHelper.Log("Android mod loading master switch is enabled with no explicit mod entries; all discovered local mods are enabled by default.");
    }

    private static void ApplyModernModList(ModSettings modSettings, JsonElement root)
    {
        if (!root.TryGetProperty("mod_list", out var modListElement) || modListElement.ValueKind != JsonValueKind.Array)
            return;
        foreach (var item in modListElement.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
                continue;
            var id = ReadStringProperty(item, "id");
            if (string.IsNullOrWhiteSpace(id))
                id = ReadStringProperty(item, "name");
            if (string.IsNullOrWhiteSpace(id))
                continue;
            if (!TryParseModSource(ReadStringProperty(item, "source"), out var source))
                continue;
            var isEnabled = !item.TryGetProperty("is_enabled", out var enabledElement) || ReadBool(enabledElement, true);
            UpsertModSetting(modSettings, id, source, isEnabled);
        }
    }

    private static void ApplyLegacyDisabledMods(ModSettings modSettings, JsonElement root)
    {
        if (!root.TryGetProperty("disabled_mods", out var disabledElement) || disabledElement.ValueKind != JsonValueKind.Array)
            return;
        foreach (var item in disabledElement.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object)
                continue;
            var id = ReadStringProperty(item, "name");
            if (string.IsNullOrWhiteSpace(id))
                id = ReadStringProperty(item, "id");
            if (string.IsNullOrWhiteSpace(id))
                continue;
            if (!TryParseModSource(ReadStringProperty(item, "source"), out var source))
                continue;
            UpsertModSetting(modSettings, id, source, false);
        }
    }

    private static bool TryParseModSource(string value, out ModSource source)
    {
        switch ((value ?? string.Empty).Trim().ToLowerInvariant().Replace("-", "_"))
        {
            case "":
            case "mods_directory":
            case "modsdirectory":
            case "local":
                source = ModSource.ModsDirectory;
                return true;
            case "steam_workshop":
            case "steamworkshop":
                source = ModSource.SteamWorkshop;
                PatchHelper.Log("Ignoring Steam Workshop mod setting in no-Steam Android build.");
                return false;
            default:
                source = ModSource.None;
                PatchHelper.Log($"Ignoring Android mod setting with unknown source '{value}'.");
                return false;
        }
    }

    private static void UpsertModSetting(ModSettings modSettings, string id, ModSource source, bool isEnabled)
    {
        id = id.Trim();
        if (TryUpsertModListEntry(modSettings, id, source, isEnabled))
            return;
        UpsertRuntimeDisabledModEntry(modSettings, id, source, isEnabled);
    }

    private static bool TryUpsertModListEntry(ModSettings modSettings, string id, ModSource source, bool isEnabled)
    {
        var property = typeof(ModSettings).GetProperty("ModList", BindingFlags.Public | BindingFlags.Instance);
        if (property == null)
            return false;
        var list = property.GetValue(modSettings) as System.Collections.IList;
        if (list == null)
            return false;
        foreach (var entry in list)
        {
            if (entry == null)
                continue;
            var idProperty = entry.GetType().GetProperty("Id", BindingFlags.Public | BindingFlags.Instance);
            var sourceProperty = entry.GetType().GetProperty("Source", BindingFlags.Public | BindingFlags.Instance);
            if ((string)idProperty?.GetValue(entry) == id && sourceProperty?.GetValue(entry) is ModSource existingSource && existingSource == source)
            {
                entry.GetType().GetProperty("IsEnabled", BindingFlags.Public | BindingFlags.Instance)?.SetValue(entry, isEnabled);
                return true;
            }
        }
        var entryType = list.GetType().IsGenericType ? list.GetType().GetGenericArguments()[0] : null;
        if (entryType == null)
            return false;
        var newEntry = Activator.CreateInstance(entryType);
        entryType.GetProperty("Id", BindingFlags.Public | BindingFlags.Instance)?.SetValue(newEntry, id);
        entryType.GetProperty("Source", BindingFlags.Public | BindingFlags.Instance)?.SetValue(newEntry, source);
        entryType.GetProperty("IsEnabled", BindingFlags.Public | BindingFlags.Instance)?.SetValue(newEntry, isEnabled);
        list.Add(newEntry);
        return true;
    }

    private static void UpsertRuntimeDisabledModEntry(ModSettings modSettings, string id, ModSource source, bool isEnabled)
    {
        var property = typeof(ModSettings).GetProperty("DisabledMods", BindingFlags.Public | BindingFlags.Instance);
        var list = property?.GetValue(modSettings) as System.Collections.IList;
        if (list == null)
        {
            if (isEnabled)
                return;
            PatchHelper.Log("Runtime ModSettings has no ModList/DisabledMods; cannot apply per-mod disabled state.");
            return;
        }
        foreach (var entry in new List<object>(EnumerateList(list)))
        {
            if (entry == null)
                continue;
            var nameProperty = entry.GetType().GetProperty("Name", BindingFlags.Public | BindingFlags.Instance);
            var sourceProperty = entry.GetType().GetProperty("Source", BindingFlags.Public | BindingFlags.Instance);
            if ((string)nameProperty?.GetValue(entry) == id && sourceProperty?.GetValue(entry) is ModSource existingSource && existingSource == source)
            {
                if (isEnabled)
                    list.Remove(entry);
                return;
            }
        }
        if (isEnabled)
            return;
        var entryType = list.GetType().IsGenericType ? list.GetType().GetGenericArguments()[0] : null;
        if (entryType == null)
            return;
        var newEntry = Activator.CreateInstance(entryType);
        entryType.GetProperty("Name", BindingFlags.Public | BindingFlags.Instance)?.SetValue(newEntry, id);
        entryType.GetProperty("Source", BindingFlags.Public | BindingFlags.Instance)?.SetValue(newEntry, source);
        list.Add(newEntry);
    }

    private static IEnumerable<object> EnumerateList(System.Collections.IEnumerable enumerable)
    {
        foreach (var item in enumerable)
            yield return item;
    }

    private static int CountConfiguredModEntries(ModSettings modSettings)
    {
        var modList = typeof(ModSettings).GetProperty("ModList", BindingFlags.Public | BindingFlags.Instance)?.GetValue(modSettings) as System.Collections.ICollection;
        if (modList != null)
            return modList.Count;
        var disabledMods = typeof(ModSettings).GetProperty("DisabledMods", BindingFlags.Public | BindingFlags.Instance)?.GetValue(modSettings) as System.Collections.ICollection;
        return disabledMods?.Count ?? 0;
    }

    private static string ReadStringProperty(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value))
            return string.Empty;
        return value.ValueKind == JsonValueKind.String ? value.GetString() ?? string.Empty : value.ToString();
    }

    private static bool ReadBool(JsonElement element, bool fallback)
    {
        return element.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => element.TryGetInt32(out var number) ? number != 0 : fallback,
            JsonValueKind.String => bool.TryParse(element.GetString(), out var parsed) ? parsed : fallback,
            _ => fallback,
        };
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
