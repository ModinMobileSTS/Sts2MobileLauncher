using System;
using System.Collections.Generic;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Assets;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.Combat;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.GodotExtensions;
using MegaCrit.Sts2.Core.Nodes.Screens.Settings;
using MegaCrit.Sts2.Core.Saves;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

/// <summary>
/// Recreates the Android/mobile port settings inside the original PC settings screen.
/// The imported payload does not contain the old Android settings scene/classes, so this
/// patch injects a lightweight extra tab at runtime and writes to the companion JSON file
/// that the rest of the compat layer already consumes.
/// </summary>
public static class AndroidInGameSettingsPatches
{
    private const string MobileTabName = "AndroidMobile";
    private const string MobilePanelName = "AndroidMobileSettings";
    private static readonly Color DividerColor = new Color(0.909804f, 0.862745f, 0.745098f, 0.25098f);
    private static Theme _lineTheme;
    private static Font _regularFont;
    private static Font _boldFont;
    private static Control _buttonTemplate;
    private static Texture2D _buttonTexture;
    private static Material _buttonMaterial;
    private static Font _buttonFont;

    public static void Apply(Harmony harmony)
    {
        var settingsScreenType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NSettingsScreen");
        if (settingsScreenType != null)
            PatchHelper.Patch(harmony, settingsScreenType, "_Ready", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsScreenReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NSettingsTabManager), "SwitchTabTo", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsTabManagerSwitchTabPostfix)));
        PatchHelper.Patch(harmony, typeof(NSettingsTabManager), "ResetTabs", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsTabManagerResetTabsPostfix)));
    }

    public static void SettingsScreenOriginalTabsReadyPostfix(Node __instance)
    {
        try
        {
            var graphicsContent = GetSettingsContent(__instance, "%GraphicsSettings");
            if (graphicsContent == null || graphicsContent.GetNodeOrNull("AndroidUiFontScale") != null)
                return;

            var row = new HBoxContainer
            {
                Name = "AndroidUiFontScale",
                SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
                CustomMinimumSize = new Vector2(0, 64),
                MouseFilter = Control.MouseFilterEnum.Pass,
            };
            row.AddThemeConstantOverride("separation", 12);

            var label = new Label
            {
                Name = "Label",
                Text = T("字体大小", "Font size"),
                VerticalAlignment = VerticalAlignment.Center,
                SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
                MouseFilter = Control.MouseFilterEnum.Ignore,
            };
            label.AddThemeFontSizeOverride("font_size", 28);
            row.AddChild(label);

            var valueLabel = new Label
            {
                Name = "ValueLabel",
                Text = $"{DisplaySettingsPatches.GetUiFontScalePercent()}%",
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Right,
                CustomMinimumSize = new Vector2(86, 0),
                MouseFilter = Control.MouseFilterEnum.Ignore,
            };
            valueLabel.AddThemeFontSizeOverride("font_size", 24);

            var slider = new HSlider
            {
                Name = "Slider",
                MinValue = 50,
                MaxValue = 200,
                Step = 5,
                Value = DisplaySettingsPatches.GetUiFontScalePercent(),
                CustomMinimumSize = new Vector2(340, 0),
                SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
                FocusMode = Control.FocusModeEnum.All,
            };
            slider.Connect(Godot.Range.SignalName.ValueChanged, Callable.From<double>(value =>
            {
                int percent = Mathf.Clamp(Mathf.RoundToInt((float)value), 50, 200);
                valueLabel.Text = $"{percent}%";
                AndroidSettingsBridge.SetInt("ui_font_scale_percent", percent);
                DisplaySettingsPatches.ApplyRuntimeDisplaySettings();
                AndroidFontCoveragePatches.RegisterTreePostfix(__instance);
            }));
            row.AddChild(slider);
            row.AddChild(valueLabel);
            graphicsContent.AddChild(row);
            AndroidFontCoveragePatches.RegisterTreePostfix(row);
            PatchHelper.Log("Added Android font-size slider to original Graphics settings tab.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android original-tab settings injection failed: {exception.Message}");
        }
    }

    private static Control GetSettingsContent(Node settingsScreen, string panelPath)
    {
        var panel = settingsScreen.GetNodeOrNull<Node>(panelPath);
        if (panel == null)
            return null;
        try
        {
            var property = panel.GetType().GetProperty("Content", BindingFlags.Public | BindingFlags.Instance);
            if (property?.GetValue(panel) is Control control)
                return control;
        }
        catch
        {
        }
        return panel.GetNodeOrNull<Control>("Content");
    }

    public static void SettingsScreenReadyPostfix(Node __instance)
    {
        try
        {
            if (!OS.HasFeature("mobile") && !OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase))
                return;
            var tabManager = __instance.GetNodeOrNull<NSettingsTabManager>("%SettingsTabManager");
            if (tabManager == null || tabManager.GetNodeOrNull(MobileTabName) != null)
                return;
            var panel = CreateMobilePanel(__instance);
            if (panel == null)
                return;
            var tab = CreateMobileTab(tabManager, panel);
            if (tab == null)
                return;
            tab.Connect(NClickableControl.SignalName.Released, Callable.From<NButton>(_ => ShowMobilePanel(tabManager, tab, panel)));
            PatchHelper.Log("Added Android/mobile settings tab to in-game settings screen.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android in-game settings injection failed: {exception}");
        }
    }

    public static void SettingsTabManagerSwitchTabPostfix(NSettingsTabManager __instance)
    {
        HideMobilePanel(__instance, deselectTab: true);
    }

    public static void SettingsTabManagerResetTabsPostfix(NSettingsTabManager __instance)
    {
        HideMobilePanel(__instance, deselectTab: true);
    }

    private static NSettingsTab CreateMobileTab(NSettingsTabManager tabManager, Control panel)
    {
        var template = tabManager.GetNodeOrNull<NSettingsTab>("Input")
            ?? tabManager.GetNodeOrNull<NSettingsTab>("Sound")
            ?? tabManager.GetNodeOrNull<NSettingsTab>("Graphics")
            ?? tabManager.GetNodeOrNull<NSettingsTab>("General");
        if (template == null)
            return null;
        var tab = template.Duplicate((int)(Node.DuplicateFlags.Groups | Node.DuplicateFlags.Scripts)) as NSettingsTab;
        if (tab == null)
            return null;
        tab.Name = MobileTabName;
        tab.FocusMode = Control.FocusModeEnum.All;
        if (tab.GetNodeOrNull<Label>("Label") is { } label)
            label.Text = T("移动端", "Mobile");
        var rightTrigger = tabManager.GetNodeOrNull<TextureRect>("RightTriggerIcon");
        tabManager.AddChild(tab);
        tab.SetLabel(T("移动端", "Mobile"));
        if (rightTrigger != null)
            tabManager.MoveChild(tab, Math.Max(0, rightTrigger.GetIndex()));
        tab.SetMeta("sts2mobile_panel", panel.GetPath());
        return tab;
    }

    private static Control CreateMobilePanel(Node settingsScreen)
    {
        var general = settingsScreen.GetNodeOrNull<Control>("%GeneralSettings");
        var parent = general?.GetParent();
        if (parent == null)
            return null;
        if (parent.GetNodeOrNull<Control>(MobilePanelName) is { } existing)
            return existing;

        CaptureOfficialLineStyle(general);
        var panel = new VBoxContainer
        {
            Name = MobilePanelName,
            UniqueNameInOwner = true,
            Visible = false,
            CustomMinimumSize = new Vector2(1012f, 0f),
            SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
        };
        panel.AddThemeConstantOverride("separation", 8);
        CopyPanelLayout(general, panel);
        BuildRows(panel);
        parent.AddChild(panel);
        return panel;
    }

    private static void CopyPanelLayout(Control source, Control target)
    {
        target.LayoutMode = source.LayoutMode;
        target.AnchorLeft = source.AnchorLeft;
        target.AnchorTop = source.AnchorTop;
        target.AnchorRight = source.AnchorRight;
        target.AnchorBottom = source.AnchorBottom;
        target.OffsetLeft = source.OffsetLeft;
        target.OffsetTop = source.OffsetTop;
        target.OffsetRight = source.OffsetRight;
        target.OffsetBottom = source.OffsetBottom;
        target.GrowHorizontal = source.GrowHorizontal;
        target.GrowVertical = source.GrowVertical;
        target.MouseFilter = Control.MouseFilterEnum.Pass;
    }

    private static void BuildRows(VBoxContainer content)
    {
        AddHeader(content, T("移动端移植设置", "Mobile Port Settings"));
        AddButtonRow(content, T("安卓外部设置", "Android companion settings"), T("打开", "Open"), () =>
        {
            if (!ExternalSettingsPatches.OpenCompanionSettings())
                PatchHelper.Log("Java settings-shell bridge returned false.");
        });

        AddHeader(content, T("显示 / 图形", "Display / Graphics"));
        AddSwitchRow(content, "shader_compatibility_mode", T("着色器兼容模式", "Shader compatibility"), false, _ => PatchHelper.Log("Shader compatibility setting changed; already-loaded materials may require a restart."));
        AddSwitchRow(content, "android_flip_screen_180", T("屏幕旋转 180°", "Rotate screen 180°"), false, _ => DisplaySettingsPatches.ApplyRuntimeDisplaySettings());
        AddSizePaginatorRow(content, "fullscreen_render_size", T("渲染分辨率", "Render resolution"), GetResolutionOptions(), (0, 0), _ => DisplaySettingsPatches.ApplyRuntimeDisplaySettings());
        AddIntPaginatorRow(content, "ui_font_scale_percent", T("字体大小", "Font size"), Range(50, 200, 5), 100, v => $"{v}%", _ => DisplaySettingsPatches.ApplyRuntimeDisplaySettings());
        AddIntPaginatorRow(content, "global_scale", T("游戏缩放", "Game scale"), Range(50, 200, 5), 100, v => $"{v}%", v =>
        {
            AndroidSettingsBridge.SetFloat("global_scale", v / 100f);
            DisplaySettingsPatches.ApplyRuntimeDisplaySettings();
        }, readCurrent: () => Mathf.RoundToInt(AndroidSettingsBridge.GetFloat("global_scale", 1f) * 100f));

        AddHeader(content, T("触摸 / 手牌", "Touch / Hand"));
        AddSwitchRow(content, "show_more_hand_card_text", T("显示更多手牌文字", "Show more hand-card text"), true, _ => RefreshHandLayout());
        AddIntPaginatorRow(content, "show_more_hand_card_text_lift_height_percent", T("手牌抬起高度", "Hand-card lift height"), Range(25, 100, 5), 50, v => $"{v}%", _ => RefreshHandLayout());
        AddSwitchRow(content, "touch_lift_preview", T("抬起触摸时预览", "Touch-lift preview"), true, enabled =>
        {
            if (!enabled)
                MobileTapPreviewPatches.ClearAllPinned();
        });
        AddStringPaginatorRow(content, "touch_lift_retap_action", T("抬起后二次点击操作", "Touch-lift re-tap action"),
            new[]
            {
                ("put_down", T("放下卡牌", "Put down card")),
                ("play", T("打出卡牌", "Play card")),
                ("none", T("无", "None")),
            }, "put_down", null);
        AddSwitchRow(content, "mobile_selection_confirmation", T("手机选择二次确认", "Mobile selection confirmation"), true, null);
        AddSwitchRow(content, "mobile_two_finger_inspect", T("双指查看", "Two-finger inspect"), true, null);

        AddHeader(content, T("系统 / 兼容", "System / Compatibility"));
        AddSwitchRow(content, "preload_enabled", T("启用预加载", "Enable preload"), true, enabled => PreloadManager.Enabled = enabled);
        AddSwitchRow(content, "audio_compatibility_mode", T("声音兼容模式", "Audio compatibility mode"), false, null);
        AddSwitchRow(content, "android_volume_up_soft_keyboard", T("音量上键打开软键盘", "Volume up opens keyboard"), false, null);
        AddSwitchRow(content, "show_mobile_emoji_button", T("显示联机表情按钮", "Show multiplayer emoji button"), true, null);
        AddSwitchRow(content, "quick_sl_enabled", T("启用快速 SL / 重打按钮", "Enable quick retry button"), true, null);
        AddSwitchRow(content, "max_multiplayer_enabled", T("启用自定义最大联机人数", "Enable custom max multiplayer players"), true, null);
        AddIntPaginatorRow(content, "max_multiplayer_players", T("最大联机人数", "Max multiplayer players"), Range(1, 128, 1), 4, v => v.ToString(), null);
        AddSwitchRow(content, "lan_multiplayer_enabled", T("启用 LAN 兼容", "Enable LAN compatibility"), true, null);
        AddTextRow(content, "lan_custom_player_id", T("LAN 自定义玩家 ID", "LAN custom player ID"), "", null);
    }

    private static void CaptureOfficialLineStyle(Control panel)
    {
        try
        {
            var label = FindFirstChildOfType<RichTextLabel>(panel);
            _lineTheme = label?.Theme;
            _regularFont = label?.GetThemeFont("normal_font");
            _boldFont = label?.GetThemeFont("bold_font");
            _buttonTemplate = FindFirstChildOfType<NSettingsButton>(panel);
            if (_buttonTemplate != null)
            {
                _buttonTexture = _buttonTemplate.GetNodeOrNull<TextureRect>("Image")?.Texture;
                _buttonMaterial = _buttonTemplate.GetNodeOrNull<TextureRect>("Image")?.Material;
                _buttonFont = _buttonTemplate.GetNodeOrNull<Label>("Label")?.GetThemeFont("font");
            }
        }
        catch
        {
        }
    }

    private static T FindFirstChildOfType<T>(Node node) where T : Node
    {
        foreach (Node child in node.GetChildren())
        {
            if (child is T typed)
                return typed;
            var nested = FindFirstChildOfType<T>(child);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static NSettingsTickbox CreateOfficialTickbox(bool initialValue, Action<bool> onChanged)
    {
        var tickbox = new AndroidSettingsTickbox
        {
            InitialValue = initialValue,
            Changed = onChanged,
            Name = "SettingsTickbox",
            CustomMinimumSize = new Vector2(320, 64),
            SizeFlagsHorizontal = Control.SizeFlags.ShrinkEnd,
            FocusMode = Control.FocusModeEnum.All,
            MouseFilter = Control.MouseFilterEnum.Pass,
        };
        try
        {
            var packed = ResourceLoader.Load<PackedScene>("res://scenes/screens/settings_tickbox.tscn");
            var template = packed?.Instantiate<Control>();
            if (template == null)
                throw new InvalidOperationException("settings_tickbox.tscn returned null");
            MoveVisualChildren(template, tickbox);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Falling back to programmatic settings tickbox visuals: {exception.Message}");
            var visuals = new Control { Name = "TickboxVisuals", UniqueNameInOwner = true, CustomMinimumSize = new Vector2(64, 64), Material = new ShaderMaterial() };
            visuals.AddChild(new ColorRect { Name = "Ticked", Color = new Color(0.95f, 0.72f, 0.12f), UniqueNameInOwner = true, AnchorRight = 1f, AnchorBottom = 1f });
            visuals.AddChild(new ColorRect { Name = "NotTicked", Color = new Color(0.22f, 0.17f, 0.12f), UniqueNameInOwner = true, AnchorRight = 1f, AnchorBottom = 1f });
            tickbox.AddChild(visuals);
            tickbox.AddChild(new NSelectionReticle { Name = "SelectionReticle" });
        }
        return tickbox;
    }

    private static void MoveVisualChildren(Control source, Control target, Action<Node> beforeAdd = null)
    {
        target.CustomMinimumSize = source.CustomMinimumSize;
        target.SizeFlagsHorizontal = source.SizeFlagsHorizontal;
        target.SizeFlagsVertical = source.SizeFlagsVertical;
        target.MouseFilter = source.MouseFilter;
        while (source.GetChildCount() > 0)
        {
            var child = source.GetChild(0);
            source.RemoveChild(child);
            beforeAdd?.Invoke(child);
            target.AddChild(child);
            SetOwnerRecursive(child, target);
        }
        source.Free();
    }

    private static void SetOwnerRecursive(Node node, Node owner)
    {
        node.Owner = owner;
        foreach (Node child in node.GetChildren())
            SetOwnerRecursive(child, owner);
    }

    private static void AddHeader(VBoxContainer content, string text)
    {
        var divider = new ColorRect
        {
            CustomMinimumSize = new Vector2(0, 2),
            Color = DividerColor,
            MouseFilter = Control.MouseFilterEnum.Ignore,
        };
        content.AddChild(divider);
        var label = new Label
        {
            Text = text,
            CustomMinimumSize = new Vector2(0, 50),
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Left,
            Modulate = new Color(1f, 0.965f, 0.886f),
        };
        label.AddThemeFontSizeOverride("font_size", 30);
        content.AddChild(label);
    }

    private static HBoxContainer AddBaseRow(VBoxContainer content, string labelText)
    {
        var margin = new MarginContainer
        {
            CustomMinimumSize = new Vector2(0, 64),
            MouseFilter = Control.MouseFilterEnum.Pass,
        };
        margin.AddThemeConstantOverride("margin_left", 12);
        margin.AddThemeConstantOverride("margin_right", 12);
        var row = new HBoxContainer
        {
            SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
            MouseFilter = Control.MouseFilterEnum.Pass,
        };
        row.AddThemeConstantOverride("separation", 12);
        var label = new RichTextLabel
        {
            Text = labelText,
            BbcodeEnabled = true,
            ScrollActive = false,
            FitContent = true,
            SizeFlagsHorizontal = Control.SizeFlags.ExpandFill,
            CustomMinimumSize = new Vector2(0, 64),
            VerticalAlignment = VerticalAlignment.Center,
            MouseFilter = Control.MouseFilterEnum.Ignore,
            Theme = _lineTheme,
        };
        if (_regularFont != null)
            label.AddThemeFontOverride("normal_font", _regularFont);
        if (_boldFont != null)
            label.AddThemeFontOverride("bold_font", _boldFont);
        label.AddThemeFontSizeOverride("normal_font_size", 28);
        label.AddThemeFontSizeOverride("bold_font_size", 28);
        label.AddThemeFontSizeOverride("bold_italics_font_size", 28);
        label.AddThemeFontSizeOverride("italics_font_size", 28);
        label.AddThemeFontSizeOverride("mono_font_size", 28);
        row.AddChild(label);
        margin.AddChild(row);
        content.AddChild(margin);
        return row;
    }

    private static void AddButtonRow(VBoxContainer content, string label, string buttonText, Action onPressed)
    {
        var row = AddBaseRow(content, label);
        var button = CreateOfficialButton(buttonText, onPressed);
        row.AddChild(button);
    }

    private static AndroidSettingsButton CreateOfficialButton(string text, Action onPressed)
    {
        var button = new AndroidSettingsButton
        {
            Name = "AndroidSettingsButton",
            ButtonText = text,
            PressedAction = onPressed,
            CustomMinimumSize = new Vector2(220, 64),
            SizeFlagsHorizontal = Control.SizeFlags.ShrinkEnd,
            FocusMode = Control.FocusModeEnum.All,
        };
        if (_buttonTemplate != null && GodotObject.IsInstanceValid(_buttonTemplate))
        {
            try
            {
                var duplicate = _buttonTemplate.Duplicate((int)(Node.DuplicateFlags.Groups | Node.DuplicateFlags.Scripts)) as Control;
                if (duplicate != null)
                {
                    button.CustomMinimumSize = duplicate.CustomMinimumSize;
                    button.SizeFlagsHorizontal = duplicate.SizeFlagsHorizontal;
                    button.MouseFilter = duplicate.MouseFilter;
                    MoveVisualChildren(duplicate, button);
                    if (button.GetNodeOrNull<Label>("Label") is { } officialLabel)
                        officialLabel.Text = text;
                    return button;
                }
            }
            catch (Exception exception)
            {
                PatchHelper.Log($"Falling back to programmatic settings button visuals: {exception.Message}");
            }
        }
        try
        {
            var packed = ResourceLoader.Load<PackedScene>("res://scenes/ui/selection_reticle.tscn");
            var reticle = packed?.Instantiate<Control>() ?? new NSelectionReticle();
            reticle.Name = "SelectionReticle";
            button.AddChild(reticle);
        }
        catch
        {
            button.AddChild(new NSelectionReticle { Name = "SelectionReticle" });
        }
        if (_buttonTexture != null)
        {
            button.AddChild(new TextureRect
            {
                Name = "Image",
                Texture = _buttonTexture,
                Material = _buttonMaterial,
                CustomMinimumSize = new Vector2(64, 64),
                LayoutMode = 1,
                AnchorRight = 1f,
                AnchorBottom = 1f,
                GrowHorizontal = Control.GrowDirection.Both,
                GrowVertical = Control.GrowDirection.Both,
                PivotOffset = new Vector2(160, 32),
                MouseFilter = Control.MouseFilterEnum.Ignore,
                ExpandMode = TextureRect.ExpandModeEnum.IgnoreSize,
                StretchMode = TextureRect.StretchModeEnum.KeepAspectCentered,
            });
        }
        else
        {
            button.AddChild(new ColorRect
            {
                Name = "Image",
                Color = new Color(0.18f, 0.11f, 0.08f, 0.85f),
                LayoutMode = 1,
                AnchorRight = 1f,
                AnchorBottom = 1f,
                MouseFilter = Control.MouseFilterEnum.Ignore,
            });
        }
        var label = new Label
        {
            Name = "Label",
            Text = text,
            LayoutMode = 1,
            AnchorRight = 1f,
            AnchorBottom = 1f,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            MouseFilter = Control.MouseFilterEnum.Ignore,
        };
        label.AddThemeColorOverride("font_color", new Color(0.91f, 0.86359f, 0.7462f));
        label.AddThemeColorOverride("font_shadow_color", new Color(0f, 0f, 0f, 0.25098f));
        label.AddThemeColorOverride("font_outline_color", new Color(0.29f, 0.14703f, 0.1421f));
        label.AddThemeConstantOverride("shadow_offset_x", 4);
        label.AddThemeConstantOverride("shadow_offset_y", 3);
        label.AddThemeConstantOverride("outline_size", 12);
        if (_buttonFont != null)
            label.AddThemeFontOverride("font", _buttonFont);
        label.AddThemeFontSizeOverride("font_size", 28);
        button.AddChild(label);
        return button;
    }

    private static void AddSwitchRow(VBoxContainer content, string key, string label, bool fallback, Action<bool> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var check = CreateOfficialTickbox(AndroidSettingsBridge.GetBool(key, fallback), pressed =>
        {
            AndroidSettingsBridge.SetBool(key, pressed);
            afterChanged?.Invoke(pressed);
        });
        row.AddChild(check);
    }

    private static void AddIntPaginatorRow(VBoxContainer content, string key, string label, int[] values, int fallback, Func<int, string> format, Action<int> afterChanged, Func<int> readCurrent = null)
    {
        var row = AddBaseRow(content, label);
        var current = readCurrent?.Invoke() ?? AndroidSettingsBridge.GetInt(key, fallback);
        var paginator = CreateOfficialPaginator(values, current, format, value =>
        {
            AndroidSettingsBridge.SetInt(key, value);
            afterChanged?.Invoke(value);
        });
        row.AddChild(paginator);
    }

    private static void AddStringPaginatorRow(VBoxContainer content, string key, string label, (string Value, string Label)[] options, string fallback, Action<string> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var current = AndroidSettingsBridge.GetString(key, fallback);
        var labels = new string[options.Length];
        var currentIndex = 0;
        for (var i = 0; i < options.Length; i++)
        {
            labels[i] = options[i].Label;
            if (string.Equals(options[i].Value, current, StringComparison.OrdinalIgnoreCase))
                currentIndex = i;
        }
        var paginator = CreateOfficialPaginator(labels, currentIndex, index =>
        {
            var value = options[Mathf.Clamp(index, 0, options.Length - 1)].Value;
            AndroidSettingsBridge.SetString(key, value);
            afterChanged?.Invoke(value);
        });
        row.AddChild(paginator);
    }

    private static void AddSizePaginatorRow(VBoxContainer content, string key, string label, (int X, int Y)[] values, (int X, int Y) fallback, Action<(int X, int Y)> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var current = AndroidSettingsBridge.GetSize(key, fallback.X, fallback.Y);
        var currentIndex = 0;
        var labels = new string[values.Length];
        for (var i = 0; i < values.Length; i++)
        {
            labels[i] = values[i].X <= 0 || values[i].Y <= 0 ? T("自动", "Auto") : $"{values[i].X}x{values[i].Y}";
            if (values[i].X == current.X && values[i].Y == current.Y)
                currentIndex = i;
        }
        var paginator = CreateOfficialPaginator(labels, currentIndex, index =>
        {
            var value = values[Mathf.Clamp(index, 0, values.Length - 1)];
            AndroidSettingsBridge.SetSize(key, value.X, value.Y);
            afterChanged?.Invoke(value);
        });
        row.AddChild(paginator);
    }

    private static AndroidSettingsPaginator CreateOfficialPaginator(int[] values, int current, Func<int, string> format, Action<int> onChanged)
    {
        var labels = new string[values.Length];
        var currentIndex = 0;
        for (var i = 0; i < values.Length; i++)
        {
            labels[i] = format(values[i]);
            if (values[i] == current)
                currentIndex = i;
        }
        return CreateOfficialPaginator(labels, currentIndex, index => onChanged(values[Mathf.Clamp(index, 0, values.Length - 1)]));
    }

    private static AndroidSettingsPaginator CreateOfficialPaginator(string[] labels, int currentIndex, Action<int> onChanged)
    {
        var paginator = new AndroidSettingsPaginator
        {
            Name = "Paginator",
            Labels = labels,
            InitialIndex = Mathf.Clamp(currentIndex, 0, Math.Max(0, labels.Length - 1)),
            Changed = onChanged,
            CustomMinimumSize = new Vector2(420, 64),
            SizeFlagsHorizontal = Control.SizeFlags.Fill,
            FocusMode = Control.FocusModeEnum.All,
            MouseFilter = Control.MouseFilterEnum.Ignore,
        };
        try
        {
            var packed = ResourceLoader.Load<PackedScene>("res://scenes/screens/paginator.tscn");
            var template = packed?.Instantiate<Control>();
            if (template == null)
                throw new InvalidOperationException("paginator.tscn returned null");
            var templateMin = template.CustomMinimumSize;
            MoveVisualChildren(template, paginator, AdjustPaginatorChildLayout);
            paginator.CustomMinimumSize = new Vector2(Math.Max(420f, templateMin.X), Math.Max(64f, templateMin.Y));
            paginator.SizeFlagsHorizontal = Control.SizeFlags.Fill;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Falling back to programmatic settings paginator visuals: {exception.Message}");
            AddProgrammaticPaginatorFallback(paginator, labels.Length > 0 ? labels[paginator.InitialIndex] : string.Empty);
        }
        return paginator;
    }

    private static void AdjustPaginatorChildLayout(Node child)
    {
        if (child is Control control)
        {
            switch (child.Name.ToString())
            {
                case "LeftArrow":
                    control.CustomMinimumSize = new Vector2(64, 64);
                    control.AnchorLeft = 0f;
                    control.AnchorRight = 0f;
                    control.OffsetLeft = 0f;
                    control.OffsetRight = 64f;
                    break;
                case "LabelContainer":
                    control.CustomMinimumSize = new Vector2(292, 64);
                    control.AnchorLeft = 0f;
                    control.AnchorRight = 1f;
                    control.OffsetLeft = 64f;
                    control.OffsetRight = -64f;
                    break;
                case "RightArrow":
                    control.CustomMinimumSize = new Vector2(64, 64);
                    control.AnchorLeft = 1f;
                    control.AnchorRight = 1f;
                    control.OffsetLeft = -64f;
                    control.OffsetRight = 0f;
                    break;
            }
        }
    }

    private static void AddProgrammaticPaginatorFallback(AndroidSettingsPaginator paginator, string text)
    {
        paginator.MouseFilter = Control.MouseFilterEnum.Pass;
        var left = new AndroidPaginatorArrow { Name = "LeftArrow", PageLeft = true, CustomMinimumSize = new Vector2(64, 64), MouseFilter = Control.MouseFilterEnum.Pass };
        left.AddChild(new Label { Text = "‹", HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, LayoutMode = 1, AnchorRight = 1f, AnchorBottom = 1f, MouseFilter = Control.MouseFilterEnum.Ignore });
        var labelContainer = new Control { Name = "LabelContainer", CustomMinimumSize = new Vector2(260, 64), SizeFlagsHorizontal = Control.SizeFlags.ExpandFill, MouseFilter = Control.MouseFilterEnum.Ignore };
        var label = new MegaCrit.Sts2.addons.mega_text.MegaLabel { Name = "Label", UniqueNameInOwner = true, Text = text, LayoutMode = 1, AnchorRight = 1f, AnchorBottom = 1f, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MouseFilter = Control.MouseFilterEnum.Ignore, MinFontSize = 12, MaxFontSize = 28 };
        var vfxLabel = new MegaCrit.Sts2.addons.mega_text.MegaLabel { Name = "VfxLabel", UniqueNameInOwner = true, Text = text, Visible = false, LayoutMode = 1, AnchorRight = 1f, AnchorBottom = 1f, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MouseFilter = Control.MouseFilterEnum.Ignore, MinFontSize = 12, MaxFontSize = 28 };
        ApplyMegaLabelFontOverride(label);
        ApplyMegaLabelFontOverride(vfxLabel);
        labelContainer.AddChild(label);
        labelContainer.AddChild(vfxLabel);
        var right = new AndroidPaginatorArrow { Name = "RightArrow", PageLeft = false, CustomMinimumSize = new Vector2(64, 64), MouseFilter = Control.MouseFilterEnum.Pass };
        right.AddChild(new Label { Text = "›", HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, LayoutMode = 1, AnchorRight = 1f, AnchorBottom = 1f, MouseFilter = Control.MouseFilterEnum.Ignore });
        var box = new HBoxContainer { Name = "FallbackBox", SizeFlagsHorizontal = Control.SizeFlags.ExpandFill, MouseFilter = Control.MouseFilterEnum.Ignore };
        box.AddChild(left);
        box.AddChild(labelContainer);
        box.AddChild(right);
        paginator.AddChild(box);
        paginator.AddChild(new NSelectionReticle { Name = "SelectionReticle" });
    }

    private static void ApplyMegaLabelFontOverride(MegaCrit.Sts2.addons.mega_text.MegaLabel label)
    {
        Font font = _buttonFont ?? _regularFont ?? ThemeDB.FallbackFont;
        if (font != null)
            label.AddThemeFontOverride("font", font);
    }

    private static int[] Range(int min, int max, int step)
    {
        var values = new List<int>();
        for (var value = min; value <= max; value += Math.Max(1, step))
            values.Add(value);
        return values.ToArray();
    }

    private static (int X, int Y)[] GetResolutionOptions() => new[]
    {
        (0, 0),
        (1280, 720),
        (1600, 900),
        (1920, 1080),
        (2560, 1440),
        (3200, 1800),
    };

    private static void AddSliderRow(VBoxContainer content, string key, string label, int min, int max, int step, int fallback, Func<int, string> format, Func<int, object> convert, Action<int> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var valueLabel = new Label
        {
            CustomMinimumSize = new Vector2(90, 54),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        valueLabel.AddThemeFontSizeOverride("font_size", 24);
        var slider = new HSlider
        {
            MinValue = min,
            MaxValue = max,
            Step = step,
            Value = key == "global_scale" ? Mathf.RoundToInt(AndroidSettingsBridge.GetFloat(key, fallback / 100f) * 100f) : AndroidSettingsBridge.GetInt(key, fallback),
            CustomMinimumSize = new Vector2(320, 54),
            FocusMode = Control.FocusModeEnum.All,
        };
        valueLabel.Text = format(Mathf.RoundToInt((float)slider.Value));
        slider.ValueChanged += value =>
        {
            var intValue = Mathf.RoundToInt((float)value);
            valueLabel.Text = format(intValue);
            AndroidSettingsBridge.SetValue(key, convert(intValue));
            afterChanged?.Invoke(intValue);
        };
        row.AddChild(slider);
        row.AddChild(valueLabel);
    }

    private static void AddOptionRow(VBoxContainer content, string key, string label, (string Value, string Label)[] options, string fallback, Action<string> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var option = new OptionButton
        {
            CustomMinimumSize = new Vector2(280, 54),
            FocusMode = Control.FocusModeEnum.All,
        };
        var current = AndroidSettingsBridge.GetString(key, fallback);
        var selected = 0;
        for (var i = 0; i < options.Length; i++)
        {
            option.AddItem(options[i].Label, i);
            if (string.Equals(options[i].Value, current, StringComparison.OrdinalIgnoreCase))
                selected = i;
        }
        option.Selected = selected;
        option.ItemSelected += index =>
        {
            var value = options[Mathf.Clamp((int)index, 0, options.Length - 1)].Value;
            AndroidSettingsBridge.SetString(key, value);
            afterChanged?.Invoke(value);
        };
        row.AddChild(option);
    }

    private static void AddIntRow(VBoxContainer content, string key, string label, int fallback, int min, int max, Action<int> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var input = new LineEdit
        {
            Text = AndroidSettingsBridge.GetInt(key, fallback).ToString(),
            CustomMinimumSize = new Vector2(220, 54),
            FocusMode = Control.FocusModeEnum.All,
            VirtualKeyboardType = LineEdit.VirtualKeyboardTypeEnum.Number,
        };
        void Persist()
        {
            var value = fallback;
            int.TryParse(input.Text.Trim(), out value);
            value = Mathf.Clamp(value, min, max);
            input.Text = value.ToString();
            AndroidSettingsBridge.SetInt(key, value);
            afterChanged?.Invoke(value);
        }
        input.TextSubmitted += _ => Persist();
        input.FocusExited += Persist;
        row.AddChild(input);
    }

    private static void AddTextRow(VBoxContainer content, string key, string label, string fallback, Action<string> afterChanged)
    {
        var row = AddBaseRow(content, label);
        var input = new LineEdit
        {
            Text = AndroidSettingsBridge.GetString(key, fallback),
            CustomMinimumSize = new Vector2(320, 54),
            FocusMode = Control.FocusModeEnum.All,
        };
        void Persist()
        {
            var value = input.Text?.Trim() ?? string.Empty;
            AndroidSettingsBridge.SetString(key, value);
            afterChanged?.Invoke(value);
        }
        input.TextSubmitted += _ => Persist();
        input.FocusExited += Persist;
        row.AddChild(input);
    }

    private static void ShowMobilePanel(NSettingsTabManager tabManager, NSettingsTab tab, Control panel)
    {
        try
        {
            foreach (Node child in tabManager.GetChildren())
            {
                if (child is NSettingsTab settingsTab)
                    settingsTab.Deselect();
            }
            typeof(NSettingsTabManager).GetField("_currentTab", BindingFlags.NonPublic | BindingFlags.Instance)?.SetValue(tabManager, null);
            tab.Select();
            HideVanillaPanels(tabManager);
            panel.Visible = true;
            var scrollContainer = tabManager.GetNodeOrNull<NScrollableContainer>("%ScrollContainer");
            if (scrollContainer != null)
            {
                scrollContainer.SetContent(panel, 20f, 30f);
                scrollContainer.InstantlyScrollToTop();
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Show mobile settings panel failed: {exception.Message}");
        }
    }

    private static void HideMobilePanel(NSettingsTabManager tabManager, bool deselectTab)
    {
        try
        {
            var panel = GetMobilePanel(tabManager);
            if (panel != null)
                panel.Visible = false;
            if (deselectTab && tabManager.GetNodeOrNull<NSettingsTab>(MobileTabName) is { } tab)
                tab.Deselect();
        }
        catch
        {
        }
    }

    private static Control GetMobilePanel(NSettingsTabManager tabManager)
    {
        var tab = tabManager.GetNodeOrNull<NSettingsTab>(MobileTabName);
        if (tab != null && tab.HasMeta("sts2mobile_panel"))
        {
            var path = tab.GetMeta("sts2mobile_panel").AsNodePath();
            return tab.GetNodeOrNull<Control>(path);
        }
        var screen = tabManager.GetParent();
        return screen?.GetNodeOrNull<Control>($"%{MobilePanelName}");
    }

    private static void HideVanillaPanels(NSettingsTabManager tabManager)
    {
        var names = new[] { "%GeneralSettings", "%GraphicsSettings", "%SoundSettings", "%InputSettings" };
        foreach (var name in names)
        {
            var panel = tabManager.GetNodeOrNull<Control>(name);
            if (panel != null)
                panel.Visible = false;
        }
    }

    private static void RefreshHandLayout()
    {
        try
        {
            NPlayerHand.Instance?.ForceRefreshCardIndices();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Refresh hand layout after Android setting failed: {exception.Message}");
        }
    }

    private static string T(string zh, string en) => IsZh() ? zh : en;

    private static bool IsZh()
    {
        try
        {
            var locManagerType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Localization.LocManager");
            var instance = locManagerType?.GetProperty("Instance", BindingFlags.Public | BindingFlags.Static)?.GetValue(null);
            var language = locManagerType?.GetProperty("Language", BindingFlags.Public | BindingFlags.Instance)?.GetValue(instance) as string ?? string.Empty;
            return language.StartsWith("zh", StringComparison.OrdinalIgnoreCase) || language.StartsWith("zhs", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}

public partial class AndroidSettingsTickbox : NSettingsTickbox
{
    public bool InitialValue { get; set; }
    public Action<bool> Changed { get; set; }

    public override void _Ready()
    {
        ConnectSignals();
        IsTicked = InitialValue;
    }

    protected override void OnTick()
    {
        Changed?.Invoke(true);
    }

    protected override void OnUntick()
    {
        Changed?.Invoke(false);
    }
}

public partial class AndroidSettingsPaginator : NPaginator
{
    public string[] Labels { get; set; } = Array.Empty<string>();
    public int InitialIndex { get; set; }
    public Action<int> Changed { get; set; }

    public override void _Ready()
    {
        ConnectSignals();
        _options.Clear();
        _options.AddRange(Labels.Length == 0 ? new[] { string.Empty } : Labels);
        _currentIndex = Mathf.Clamp(InitialIndex, 0, _options.Count - 1);
        UpdateLabel();
    }

    protected override void OnIndexChanged(int index)
    {
        _currentIndex = Mathf.Clamp(index, 0, _options.Count - 1);
        UpdateLabel();
        Changed?.Invoke(_currentIndex);
    }

    private void UpdateLabel()
    {
        if (_label != null)
            _label.SetTextAutoSize(_options[_currentIndex]);
    }
}

public partial class AndroidSettingsButton : NSettingsButton
{
    public string ButtonText { get; set; } = string.Empty;
    public Action PressedAction { get; set; }

    public override void _Ready()
    {
        ConnectSignals();
        if (GetNodeOrNull<Label>("Label") is { } label)
            label.Text = ButtonText;
        Connect(NClickableControl.SignalName.Released, Callable.From<NButton>(_ => PressedAction?.Invoke()));
    }
}

public partial class AndroidPaginatorArrow : NClickableControl
{
    public bool PageLeft { get; set; }

    public override void _Ready()
    {
        ConnectSignals();
    }

    protected override void OnRelease()
    {
        base.OnRelease();
        if (GetParent()?.GetParent() is AndroidSettingsPaginator paginator)
        {
            if (PageLeft)
                paginator.PageLeft();
            else
                paginator.PageRight();
        }
        else if (GetParent() is AndroidSettingsPaginator directPaginator)
        {
            if (PageLeft)
                directPaginator.PageLeft();
            else
                directPaginator.PageRight();
        }
    }
}
