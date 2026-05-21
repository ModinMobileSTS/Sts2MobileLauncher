using System;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.addons.mega_text;
using MegaCrit.Sts2.Core.Assets;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.Screens.MainMenu;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;
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

    private static bool? _lastUseCustomRenderSize;
    private static Vector2I _lastRenderSize = new(-1, -1);
    private static float _lastScaleFactor = -1f;
    private static Window.ContentScaleAspectEnum? _lastScaleAspect;
    private static AspectRatioSetting? _lastAspect;
    private static bool? _hasSourcePortMegaTextScaling;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NGame), "ApplyDisplaySettings", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ApplyDisplaySettingsPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "InitializeGraphicsPreferences", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(InitializeGraphicsPreferencesPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "_Notification", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(NotificationPostfix)));
        PatchHelper.Patch(harmony, typeof(NGame), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NGlobalUi), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NMainMenu), "_Ready", postfix: PatchHelper.Method(typeof(DisplaySettingsPatches), nameof(ReadyPostfix)));
    }

    public static void InitializeGraphicsPreferencesPostfix()
    {
        try
        {
            ApplyFontSizeSetting();
            ApplyAndroidScreenOrientationSetting();
            ApplyDisplaySettingsPostfix();
            Callable.From(ApplyDisplaySettingsPostfix).CallDeferred();
            PatchHelper.Log($"Applied Android graphics bridge: fps(original)={Engine.MaxFps}, scale={GetGlobalScale():0.##}, font={GetUiFontScalePercent()}%, render={GetFullscreenRenderSize()}");
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
            var settings = SaveManager.Instance?.SettingsSave;
            if (settings != null)
                NGame.ApplySyncSetting();
            var window = GetRootWindow();
            if (window == null)
                return;

            var renderSize = GetFullscreenRenderSize();
            var useCustomRenderSize = (settings?.Fullscreen ?? true) && renderSize.X > 0 && renderSize.Y > 0;
            var aspect = settings?.AspectRatioSetting ?? AspectRatioSetting.Auto;
            var scaleFactor = GetGlobalScale();

            var previousUseCustomRenderSize = _lastUseCustomRenderSize;
            var previousAspect = _lastAspect;

            SetContentScaleModeIfChanged(window, useCustomRenderSize ? Window.ContentScaleModeEnum.Viewport : Window.ContentScaleModeEnum.CanvasItems);
            if (useCustomRenderSize)
            {
                if (aspect != AspectRatioSetting.Auto)
                    SetContentScaleAspectIfChanged(window, Window.ContentScaleAspectEnum.Keep);
                SetContentScaleSizeIfChanged(window, renderSize);
            }
            else if (aspect != AspectRatioSetting.Auto)
            {
                SetContentScaleAspectIfChanged(window, Window.ContentScaleAspectEnum.Keep);
                ApplyAspectContentScaleSize(window, aspect);
            }
            else if (previousUseCustomRenderSize == true || (previousAspect.HasValue && previousAspect.Value != AspectRatioSetting.Auto))
            {
                ApplyAutoAspectContentScaleSize(window);
            }
            SetContentScaleFactorIfChanged(window, scaleFactor);

            if (_lastUseCustomRenderSize != useCustomRenderSize || _lastRenderSize != renderSize || !Mathf.IsEqualApprox(_lastScaleFactor, scaleFactor) || _lastScaleAspect != window.ContentScaleAspect || _lastAspect != aspect)
            {
                _lastUseCustomRenderSize = useCustomRenderSize;
                _lastRenderSize = renderSize;
                _lastScaleFactor = scaleFactor;
                _lastScaleAspect = window.ContentScaleAspect;
                _lastAspect = aspect;
                PatchHelper.Log($"[Display] Android source-port scaling applied: custom={useCustomRenderSize}, render={renderSize}, mode={window.ContentScaleMode}, aspect={window.ContentScaleAspect}, scale={scaleFactor:0.##}, surface={DisplayServer.WindowGetSize()} (no runtime WindowSetSize)");
            }
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
        var scaleMultiplier = GetUiFontScaleMultiplier();
        if (!Mathf.IsEqualApprox(ThemeDB.FallbackBaseScale, scaleMultiplier))
            ThemeDB.FallbackBaseScale = scaleMultiplier;
        var window = GetRootWindow();
        if (window != null)
        {
            ApplyFontSizeOverridesRecursive(window);
            window.PropagateNotification((int)Control.NotificationThemeChanged);
        }
    }

    private static void ApplyAspectContentScaleSize(Window window, AspectRatioSetting aspect)
    {
        switch (aspect)
        {
            case AspectRatioSetting.FourByThree:
                SetContentScaleSizeIfChanged(window, new Vector2I(1680, 1260));
                break;
            case AspectRatioSetting.SixteenByTen:
                SetContentScaleSizeIfChanged(window, new Vector2I(1920, 1200));
                break;
            case AspectRatioSetting.SixteenByNine:
                SetContentScaleSizeIfChanged(window, new Vector2I(1920, 1080));
                break;
            case AspectRatioSetting.TwentyOneByNine:
                SetContentScaleSizeIfChanged(window, new Vector2I(2580, 1080));
                break;
        }
    }

    private static void ApplyAutoAspectContentScaleSize(Window window)
    {
        if (window.Size.Y <= 0)
            return;
        float ratio = (float)window.Size.X / window.Size.Y;
        if (ratio > 2.3888888f)
        {
            SetContentScaleAspectIfChanged(window, Window.ContentScaleAspectEnum.KeepWidth);
            SetContentScaleSizeIfChanged(window, new Vector2I(2580, 1080));
        }
        else if (ratio < 1.3333334f)
        {
            SetContentScaleAspectIfChanged(window, Window.ContentScaleAspectEnum.KeepHeight);
            SetContentScaleSizeIfChanged(window, new Vector2I(1680, 1260));
        }
        else
        {
            SetContentScaleAspectIfChanged(window, Window.ContentScaleAspectEnum.Expand);
            SetContentScaleSizeIfChanged(window, new Vector2I(1680, 1080));
        }
    }

    private static void SetContentScaleModeIfChanged(Window window, Window.ContentScaleModeEnum mode)
    {
        if (window.ContentScaleMode != mode)
            window.ContentScaleMode = mode;
    }

    private static void SetContentScaleAspectIfChanged(Window window, Window.ContentScaleAspectEnum aspect)
    {
        if (window.ContentScaleAspect != aspect)
            window.ContentScaleAspect = aspect;
    }

    private static void SetContentScaleSizeIfChanged(Window window, Vector2I size)
    {
        if (window.ContentScaleSize != size)
            window.ContentScaleSize = size;
    }

    private static void SetContentScaleFactorIfChanged(Window window, float factor)
    {
        if (!Mathf.IsEqualApprox(window.ContentScaleFactor, factor))
            window.ContentScaleFactor = factor;
    }

    internal static int GetUiFontScalePercent() => Mathf.Clamp(AndroidSettingsBridge.GetInt("ui_font_scale_percent", 100), 50, 200);

    internal static float GetUiFontScaleMultiplier() => GetUiFontScalePercent() / 100f;

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

    internal static void ApplyFontSizeOverridesRecursive(Node node)
    {
        if (node is Control control)
            ApplyFontSizeOverrides(control);
        foreach (Node child in node.GetChildren())
            ApplyFontSizeOverridesRecursive(child);
    }

    private static void ApplyFontSizeOverrides(Control control)
    {
        if (ShouldSkipFontOverrideScaling(control))
            return;
        var scaleMultiplier = GetUiFontScaleMultiplier();
        foreach (var fontSizeOverrideName in FontSizeOverrideNames)
        {
            if (!TryGetFontBaseSize(control, fontSizeOverrideName, out var baseSize))
                continue;
            var scaledSize = Mathf.Max(1, Mathf.RoundToInt(baseSize * scaleMultiplier));
            control.AddThemeFontSizeOverride(fontSizeOverrideName, scaledSize);
        }
    }

    private static bool TryGetFontBaseSize(Control control, StringName fontSizeOverrideName, out int baseSize)
    {
        var metaKey = $"__android_port_base_size__{fontSizeOverrideName}";
        if (control.HasMeta(metaKey))
        {
            baseSize = GetFontBaseSizeFromMeta(control.GetMeta(metaKey));
            return baseSize > 0;
        }

        if (control.HasThemeFontSizeOverride(fontSizeOverrideName))
        {
            baseSize = control.GetThemeFontSize(fontSizeOverrideName);
        }
        else if (ShouldSeedDefaultFontSize(control, fontSizeOverrideName))
        {
            var themeType = control.GetClass();
            baseSize = string.IsNullOrWhiteSpace(themeType)
                ? control.GetThemeFontSize(fontSizeOverrideName)
                : control.GetThemeFontSize(fontSizeOverrideName, themeType);
            if (baseSize <= 0)
                baseSize = control.GetThemeFontSize(fontSizeOverrideName);
        }
        else
        {
            baseSize = 0;
        }

        if (baseSize <= 0)
            return false;
        control.SetMeta(metaKey, baseSize);
        return true;
    }

    private static bool ShouldSeedDefaultFontSize(Control control, StringName fontSizeOverrideName)
    {
        if (fontSizeOverrideName == "font_size")
            return control is Label or Button or LineEdit or TextEdit;
        if (control is RichTextLabel)
        {
            return fontSizeOverrideName == "normal_font_size"
                || fontSizeOverrideName == "bold_font_size"
                || fontSizeOverrideName == "italics_font_size"
                || fontSizeOverrideName == "bold_italics_font_size"
                || fontSizeOverrideName == "mono_font_size";
        }
        return false;
    }

    private static bool ShouldSkipFontOverrideScaling(Control control)
    {
        if (control is MegaLabel { AutoSizeEnabled: true } megaLabel)
        {
            if (!HasSourcePortMegaTextScaling())
                ApplyAutoSizeFontScaling(megaLabel);
            return true;
        }
        if (control is MegaRichTextLabel { AutoSizeEnabled: true } megaRichTextLabel)
        {
            if (!HasSourcePortMegaTextScaling())
                ApplyAutoSizeFontScaling(megaRichTextLabel);
            return true;
        }
        return false;
    }

    private static void ApplyAutoSizeFontScaling(MegaLabel label)
    {
        if (!label.HasThemeFontOverride("font"))
            return;
        int baseMin = GetOrStoreIntMeta(label, "__android_port_base_min_font_size", label.MinFontSize);
        int baseMax = GetOrStoreIntMeta(label, "__android_port_base_max_font_size", label.MaxFontSize);
        float scale = GetUiFontScaleMultiplier();
        label.MinFontSize = Mathf.Max(1, Mathf.RoundToInt(baseMin * scale));
        label.MaxFontSize = Mathf.Max(label.MinFontSize, Mathf.RoundToInt(baseMax * scale));
        InvokeAdjustFontSize(label);
    }

    private static void ApplyAutoSizeFontScaling(MegaRichTextLabel label)
    {
        if (!label.HasThemeFontOverride("normal_font"))
            return;
        int baseMin = GetOrStoreIntMeta(label, "__android_port_base_min_font_size", label.MinFontSize);
        int baseMax = GetOrStoreIntMeta(label, "__android_port_base_max_font_size", label.MaxFontSize);
        float scale = GetUiFontScaleMultiplier();
        label.MinFontSize = Mathf.Max(1, Mathf.RoundToInt(baseMin * scale));
        label.MaxFontSize = Mathf.Max(label.MinFontSize, Mathf.RoundToInt(baseMax * scale));
        SetPrivateField(label, "_needsResize", true);
        InvokeAdjustFontSize(label);
    }

    private static bool HasSourcePortMegaTextScaling()
    {
        if (!_hasSourcePortMegaTextScaling.HasValue)
        {
            const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
            _hasSourcePortMegaTextScaling = typeof(MegaLabel).GetField("_lastAppliedScaledFontSize", flags) != null
                || typeof(MegaRichTextLabel).GetField("_sourceText", flags) != null;
        }
        return _hasSourcePortMegaTextScaling.Value;
    }

    private static int GetOrStoreIntMeta(GodotObject obj, string metaKey, int currentValue)
    {
        if (obj.HasMeta(metaKey))
            return GetFontBaseSizeFromMeta(obj.GetMeta(metaKey));
        obj.SetMeta(metaKey, currentValue);
        return currentValue;
    }

    private static void SetPrivateField(object target, string fieldName, object value)
    {
        try
        {
            target.GetType().GetField(fieldName, BindingFlags.Instance | BindingFlags.NonPublic)?.SetValue(target, value);
        }
        catch
        {
        }
    }

    private static void InvokeAdjustFontSize(object target)
    {
        try
        {
            target.GetType().GetMethod("AdjustFontSize", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(target, null);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Auto-size font scaling failed on {target.GetType().Name}: {exception.Message}");
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
