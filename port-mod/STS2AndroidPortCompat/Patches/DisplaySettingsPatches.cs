using System;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Assets;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.Screens.MainMenu;
using MegaCrit.Sts2.Core.Saves;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class DisplaySettingsPatches
{
    private static readonly StringName[] FontSizeOverrideNames =
    {
        "font_size",
        "normal_font_size",
        "bold_font_size",
        "italics_font_size",
        "bold_italics_font_size",
        "mono_font_size",
    };

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NGame), "ApplyDisplaySettings", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ApplyDisplaySettingsPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "InitializeGraphicsPreferences", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(InitializeGraphicsPreferencesPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "_Notification", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(NotificationPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "_Input", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(GameInputPostfix)));
        PatchHelper.Patch(harmony, typeof(NGlobalUi), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NGlobalUi), "OnWindowChange", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(AutoAspectWindowChangePostfix)));
        PatchHelper.Patch(harmony, typeof(NMainMenu), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NMainMenu), "OnWindowChange", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(AutoAspectWindowChangePostfix)));
    }

    public static void InitializeGraphicsPreferencesPostfix()
    {
        try
        {
            Engine.MaxFps = AndroidSettingsBridge.GetInt("fps_limit", Engine.MaxFps);
            ApplyFontSizeSetting();
            ApplyAndroidScreenOrientationSetting();
            ApplyDisplaySettingsPostfix();
            Callable.From(ApplyDisplaySettingsPostfix).CallDeferred();
            PatchHelper.Log($"Applied Android graphics bridge: fps={Engine.MaxFps}, scale={GetGlobalScale():0.##}, font={GetUiFontScalePercent()}%, render={GetFullscreenRenderSize()}");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"InitializeGraphicsPreferencesPostfix failed: {exception}");
        }
    }

    public static void ApplyDisplaySettingsPostfix()
    {
        try
        {
            AndroidSettingsPatches.ApplyCompanionSettingsToRuntimeSave();
            PreloadManager.Enabled = AndroidSettingsBridge.GetBool("preload_enabled", PreloadManager.Enabled);
            Engine.MaxFps = AndroidSettingsBridge.GetInt("fps_limit", Engine.MaxFps);
            NGame.ApplySyncSetting();
            var window = GetRootWindow();
            if (window == null)
                return;
            var renderSize = GetFullscreenRenderSize();
            var useCustomRenderSize = renderSize.X > 0 && renderSize.Y > 0;
            window.ContentScaleMode = useCustomRenderSize ? Window.ContentScaleModeEnum.Viewport : Window.ContentScaleModeEnum.CanvasItems;
            if (useCustomRenderSize)
            {
                window.ContentScaleAspect = Window.ContentScaleAspectEnum.Expand;
                window.ContentScaleSize = renderSize;
                window.Size = renderSize;
                window.Scaling3DMode = Viewport.Scaling3DModeEnum.Bilinear;
                window.Scaling3DScale = Mathf.Clamp((float)renderSize.X / Mathf.Max(1f, DisplayServer.WindowGetSize().X), 0.1f, 1f);
                DisplayServer.WindowSetSize(renderSize);
                ProjectSettings.SetSetting("display/window/stretch/mode", "viewport");
                ProjectSettings.SetSetting("display/window/stretch/aspect", "keep");
                ProjectSettings.SetSetting("display/window/size/viewport_width", renderSize.X);
                ProjectSettings.SetSetting("display/window/size/viewport_height", renderSize.Y);
                PatchHelper.Log($"[Display] Using Android fullscreen render size: {renderSize}; viewport={window.GetVisibleRect().Size}; surface={DisplayServer.WindowGetSize()}; scale3D={window.Scaling3DScale:0.###}");
            }
            window.ContentScaleFactor = GetGlobalScale();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ApplyDisplaySettingsPostfix failed: {exception}");
        }
    }

    public static void ReadyPostfix()
    {
        try
        {
            var window = GetRootWindow();
            if (window != null)
                ApplyFontSizeOverridesRecursive(window);
            ApplyDisplaySettingsPostfix();
            Callable.From(ApplyDisplaySettingsPostfix).CallDeferred();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ReadyPostfix font/display scaling failed: {exception.Message}");
        }
    }

    public static void GameInputPostfix(InputEvent inputEvent)
    {
        if (inputEvent is InputEventMouseButton { Pressed: true } or InputEventScreenTouch { Pressed: true })
            ApplyDisplaySettingsPostfix();
    }

    public static void AutoAspectWindowChangePostfix()
    {
        if (GetFullscreenRenderSize().X > 0 && GetFullscreenRenderSize().Y > 0)
            ApplyDisplaySettingsPostfix();
    }

    public static void NotificationPostfix(int what)
    {
        try
        {
            switch (what)
            {
                case (int)Node.NotificationWMWindowFocusIn:
                case (int)Node.NotificationApplicationResumed:
                case (int)Node.NotificationApplicationFocusIn:
                    AndroidSettingsBridge.InvalidateCache();
                    ApplyRuntimeDisplaySettings();
                    break;
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"NotificationPostfix failed: {exception.Message}");
        }
    }

    public static void ApplyRuntimeDisplaySettings()
    {
        try
        {
            AndroidSettingsPatches.ApplyCompanionSettingsToRuntimeSave();
            PreloadManager.Enabled = AndroidSettingsBridge.GetBool("preload_enabled", PreloadManager.Enabled);
            Engine.MaxFps = AndroidSettingsBridge.GetInt("fps_limit", Engine.MaxFps);
            ApplyAndroidScreenOrientationSetting();
            ApplyFontSizeSetting();
            ApplyDisplaySettingsPostfix();
            NGame.ApplySyncSetting();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ApplyRuntimeDisplaySettings failed: {exception.Message}");
        }
    }

    private static void ApplyAndroidScreenOrientationSetting()
    {
        if (!OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase))
            return;
        var orientation = AndroidSettingsBridge.GetBool("android_flip_screen_180")
            ? DisplayServer.ScreenOrientation.ReverseLandscape
            : DisplayServer.ScreenOrientation.Landscape;
        DisplayServer.ScreenSetOrientation(orientation);
    }

    private static void ApplyFontSizeSetting()
    {
        var scaleMultiplier = GetUiFontScalePercent() / 100f;
        if (!Mathf.IsEqualApprox(ThemeDB.FallbackBaseScale, scaleMultiplier))
            ThemeDB.FallbackBaseScale = scaleMultiplier;
        var window = GetRootWindow();
        if (window != null)
        {
            ApplyFontSizeOverridesRecursive(window);
            window.PropagateNotification((int)Control.NotificationThemeChanged);
        }
    }

    private static int GetUiFontScalePercent() => Mathf.Clamp(AndroidSettingsBridge.GetInt("ui_font_scale_percent", 100), 50, 200);

    private static float GetGlobalScale() => Mathf.Clamp(AndroidSettingsBridge.GetFloat("global_scale", 1f), 0.5f, 4f);

    private static Vector2I GetFullscreenRenderSize()
    {
        var size = AndroidSettingsBridge.GetSize("fullscreen_render_size");
        return new Vector2I(size.X, size.Y);
    }

    private static Window GetRootWindow()
    {
        if (Engine.GetMainLoop() is SceneTree tree)
            return tree.Root;
        return null;
    }

    private static void ApplyFontSizeOverridesRecursive(Node node)
    {
        if (node is Control control)
            ApplyFontSizeOverrides(control);
        foreach (Node child in node.GetChildren())
            ApplyFontSizeOverridesRecursive(child);
    }

    private static void ApplyFontSizeOverrides(Control control)
    {
        var scaleMultiplier = GetUiFontScalePercent() / 100f;
        foreach (var fontSizeOverrideName in FontSizeOverrideNames)
        {
            if (!control.HasThemeFontSizeOverride(fontSizeOverrideName))
                continue;
            var metaKey = $"__android_port_base_size__{fontSizeOverrideName}";
            var baseSize = control.HasMeta(metaKey)
                ? GetFontBaseSizeFromMeta(control.GetMeta(metaKey))
                : control.GetThemeFontSize(fontSizeOverrideName, string.Empty);
            if (!control.HasMeta(metaKey))
                control.SetMeta(metaKey, baseSize);
            var scaledSize = Mathf.Max(1, Mathf.RoundToInt(baseSize * scaleMultiplier));
            control.AddThemeFontSizeOverride(fontSizeOverrideName, scaledSize);
        }
    }

    private static int GetFontBaseSizeFromMeta(Variant metaValue)
    {
        return metaValue.VariantType switch
        {
            Variant.Type.Int => metaValue.AsInt32(),
            Variant.Type.Float => Mathf.RoundToInt((float)metaValue.AsDouble()),
            Variant.Type.String => int.TryParse(metaValue.AsString(), out var parsed) ? parsed : 0,
            _ => metaValue.Obj is IConvertible convertible ? Convert.ToInt32(convertible) : 0,
        };
    }
}
