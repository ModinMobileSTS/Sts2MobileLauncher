using System;
using System.Reflection;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Replaces the desktop resolution dropdown with a UI scale selector for mobile.
// Persists the scale percentage to user://ui_scale.cfg and applies it by adjusting
// the window's ContentScaleSize. Also intercepts window change handlers to maintain
// the correct scale when the viewport resizes.
public static class UiScalePatches
{
    public static int UiScalePercent { get; private set; } = 100;
    public static event Action UiScaleChanged;
    private static bool _uiScaleLoaded = false;

    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var resDropdownType = sts2Asm.GetType(
            "MegaCrit.Sts2.Core.Nodes.Screens.Settings.NResolutionDropdown"
        );
        if (resDropdownType != null)
        {
            PatchHelper.Patch(
                harmony,
                resDropdownType,
                "RefreshEnabled",
                prefix: PatchHelper.Method(typeof(UiScalePatches), nameof(RefreshEnabledPrefix))
            );

            PatchHelper.Patch(
                harmony,
                resDropdownType,
                "PopulateDropdownItems",
                prefix: PatchHelper.Method(typeof(UiScalePatches), nameof(PopulateScaleItemsPrefix))
            );

            PatchHelper.Patch(
                harmony,
                resDropdownType,
                "RefreshCurrentlySelectedResolution",
                prefix: PatchHelper.Method(typeof(UiScalePatches), nameof(RefreshScaleLabelPrefix))
            );

            PatchHelper.Patch(
                harmony,
                resDropdownType,
                "OnDropdownItemSelected",
                prefix: PatchHelper.Method(typeof(UiScalePatches), nameof(ScaleItemSelectedPrefix))
            );
        }

        var resItemType = sts2Asm.GetType(
            "MegaCrit.Sts2.Core.Nodes.Screens.Settings.NResolutionDropdownItem"
        );
        if (resItemType != null)
        {
            PatchHelper.Patch(
                harmony,
                resItemType,
                "Init",
                prefix: PatchHelper.Method(typeof(UiScalePatches), nameof(ResolutionItemInitPrefix))
            );
        }

        var settingsScreenType = sts2Asm.GetType(
            "MegaCrit.Sts2.Core.Nodes.Screens.Settings.NSettingsScreen"
        );
        if (settingsScreenType != null)
        {
            PatchHelper.Patch(
                harmony,
                settingsScreenType,
                "LocalizeLabels",
                postfix: PatchHelper.Method(typeof(UiScalePatches), nameof(LocalizeLabelsPostfix))
            );
        }

        var globalUiType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.CommonUi.NGlobalUi");
        if (globalUiType != null)
        {
            PatchHelper.Patch(
                harmony,
                globalUiType,
                "OnWindowChange",
                prefix: PatchHelper.Method(
                    typeof(UiScalePatches),
                    nameof(GlobalUiWindowChangePrefix)
                )
            );
        }

        var mainMenuType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NMainMenu");
        if (mainMenuType != null)
        {
            PatchHelper.Patch(
                harmony,
                mainMenuType,
                "OnWindowChange",
                prefix: PatchHelper.Method(
                    typeof(UiScalePatches),
                    nameof(MainMenuWindowChangePrefix)
                )
            );
        }
    }

    public static void EnsureUiScaleLoaded()
    {
        if (_uiScaleLoaded)
            return;
        _uiScaleLoaded = true;
        try
        {
            var path = ProjectSettings.GlobalizePath("user://ui_scale.cfg");
            if (System.IO.File.Exists(path))
            {
                if (
                    int.TryParse(System.IO.File.ReadAllText(path).Trim(), out int val)
                    && val >= 100
                    && val <= 200
                )
                    UiScalePercent = val;
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
    }

    private static void SaveUiScale()
    {
        try
        {
            var path = ProjectSettings.GlobalizePath("user://ui_scale.cfg");
            System.IO.File.WriteAllText(path, UiScalePercent.ToString());
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
    }

    public static void ApplyScaledContentSize(Window window)
    {
        float scale = UiScalePercent / 100f;

        // Expand mode fills any screen ratio including near-square foldable displays.
        window.ContentScaleAspect = Window.ContentScaleAspectEnum.Expand;
        window.ContentScaleSize = new Vector2I(
            (int)Math.Round(1680.0 / scale),
            (int)Math.Round(1080.0 / scale)
        );
    }

    public static void ApplyUiScale()
    {
        EnsureUiScaleLoaded();
        try
        {
            var window = ((SceneTree)Engine.GetMainLoop()).Root;
            ApplyScaledContentSize(window);
            PatchHelper.Log(
                $"UI Scale: {UiScalePercent}% -> ContentScaleSize {window.ContentScaleSize}"
            );
            UiScaleChanged?.Invoke();
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"Failed to apply UI scale: {ex.Message}");
        }
    }

    // Always enable the dropdown since mobile has no windowed/fullscreen toggle.
    public static bool RefreshEnabledPrefix(object __instance)
    {
        try
        {
            var enableMethod = AccessTools.Method(__instance.GetType(), "Enable");
            enableMethod?.Invoke(__instance, null);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }

    // Replaces resolution entries with scale percentage options (100% to 150%).
    public static bool PopulateScaleItemsPrefix(object __instance)
    {
        try
        {
            var instType = __instance.GetType();
            AccessTools.Method(instType, "ClearDropdownItems").Invoke(__instance, null);

            var dropdownItems = (Node)
                AccessTools.Field(instType, "_dropdownItems").GetValue(__instance);
            var scene = (PackedScene)
                AccessTools.Field(instType, "_dropdownItemScene").GetValue(__instance);

            int[] scales = { 100, 110, 120, 130, 140, 150 };
            foreach (int scale in scales)
            {
                var item = scene.Instantiate(PackedScene.GenEditState.Disabled);
                dropdownItems.AddChild(item);
                item.Connect(
                    "Selected",
                    new Callable((GodotObject)__instance, "OnDropdownItemSelected")
                );
                item.Call("Init", new Vector2I(scale, 0));
            }

            dropdownItems.GetParent().Call("RefreshLayout");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"PopulateScaleItems failed: {ex.Message}");
        }
        return false;
    }

    // Shows the current scale percentage in the dropdown label.
    public static bool RefreshScaleLabelPrefix(object __instance)
    {
        EnsureUiScaleLoaded();
        try
        {
            var label = (GodotObject)
                AccessTools.Field(__instance.GetType(), "_currentOptionLabel").GetValue(__instance);
            label.Call("SetTextAutoSize", $"{UiScalePercent}%");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }

    // Applies the selected scale, saves it, and updates the label.
    public static bool ScaleItemSelectedPrefix(object __instance, object nDropdownItem)
    {
        try
        {
            var resField = AccessTools.Field(nDropdownItem.GetType(), "resolution");
            var resolution = (Vector2I)resField.GetValue(nDropdownItem);
            if (resolution.Y != 0)
                return true;

            int newScale = resolution.X;
            if (newScale == UiScalePercent)
                return false;

            AccessTools.Method(__instance.GetType(), "CloseDropdown").Invoke(__instance, null);

            UiScalePercent = newScale;
            SaveUiScale();
            ApplyUiScale();

            var label = (GodotObject)
                AccessTools.Field(__instance.GetType(), "_currentOptionLabel").GetValue(__instance);
            label.Call("SetTextAutoSize", $"{UiScalePercent}%");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"ScaleItemSelected failed: {ex.Message}");
        }
        return false;
    }

    // Initializes dropdown items with scale percentage text instead of resolution.
    public static bool ResolutionItemInitPrefix(object __instance, Vector2I setResolution)
    {
        if (setResolution.Y != 0)
            return true;

        try
        {
            AccessTools
                .Field(__instance.GetType(), "resolution")
                .SetValue(__instance, setResolution);
            var label = (GodotObject)
                AccessTools.Field(__instance.GetType(), "_label").GetValue(__instance);
            label.Call("SetTextAutoSize", $"{setResolution.X}%");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }

    // Renames the "Resolution" label to "UI Scale" in the settings screen.
    public static void LocalizeLabelsPostfix(object __instance)
    {
        try
        {
            var screen = (Node)__instance;
            var graphicsPanel = screen.GetNode("%GraphicsSettings");
            var content = (Node)((GodotObject)graphicsPanel).Get("Content");
            var resNode = content.GetNode("WindowedResolution");
            var label = (GodotObject)resNode.GetNode("Label");
            label.Set("text", "UI Scale");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"LocalizeLabels postfix failed: {ex.Message}");
        }
    }

    // Reapplies the scaled content size when the window changes (e.g. rotation).
    public static bool GlobalUiWindowChangePrefix(object __instance)
    {
        if (
            MegaCrit.Sts2.Core.Saves.SaveManager.Instance.SettingsSave.AspectRatioSetting
            != MegaCrit.Sts2.Core.Settings.AspectRatioSetting.Auto
        )
            return true; // let the original handle non-Auto settings

        EnsureUiScaleLoaded();
        try
        {
            var window = (Window)
                AccessTools.Field(__instance.GetType(), "_window").GetValue(__instance);
            ApplyScaledContentSize(window);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }

    // Handles window change on the main menu screen specifically.
    public static bool MainMenuWindowChangePrefix(object __instance, bool isAspectRatioAuto)
    {
        if (!isAspectRatioAuto)
            return false;
        EnsureUiScaleLoaded();
        try
        {
            var window = (Window)
                AccessTools.Field(__instance.GetType(), "_window").GetValue(__instance);
            ApplyScaledContentSize(window);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }
}
