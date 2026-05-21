using System;
using System.Collections.Generic;
using System.Reflection;
using Godot;
using HarmonyLib;
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

    public static void Apply(Harmony harmony)
    {
        var settingsScreenType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.Screens.Settings.NSettingsScreen");
        if (settingsScreenType != null)
            PatchHelper.Patch(harmony, settingsScreenType, "_Ready", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsScreenReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NSettingsTabManager), "SwitchTabTo", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsTabManagerSwitchTabPostfix)));
        PatchHelper.Patch(harmony, typeof(NSettingsTabManager), "ResetTabs", postfix: PatchHelper.Method(typeof(AndroidInGameSettingsPatches), nameof(SettingsTabManagerResetTabsPostfix)));
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
        AddSliderRow(content, "ui_font_scale_percent", T("字体大小", "Font size"), 50, 200, 5, 100, v => $"{v}%", v => v, _ => DisplaySettingsPatches.ApplyRuntimeDisplaySettings());
        AddSliderRow(content, "global_scale", T("游戏缩放", "Game scale"), 50, 200, 5, 100, v => $"{v}%", v => v / 100f, _ => DisplaySettingsPatches.ApplyRuntimeDisplaySettings());

        AddHeader(content, T("触摸 / 手牌", "Touch / Hand"));
        AddSwitchRow(content, "show_more_hand_card_text", T("显示更多手牌文字", "Show more hand-card text"), true, _ => RefreshHandLayout());
        AddSliderRow(content, "show_more_hand_card_text_lift_height_percent", T("手牌抬起高度", "Hand-card lift height"), 25, 100, 5, 50, v => $"{v}%", v => v, _ => RefreshHandLayout());
        AddSwitchRow(content, "touch_lift_preview", T("抬起触摸时预览", "Touch-lift preview"), true, enabled =>
        {
            if (!enabled)
                MobileTapPreviewPatches.ClearAllPinned();
        });
        AddOptionRow(content, "touch_lift_retap_action", T("抬起后二次点击操作", "Touch-lift re-tap action"),
            new[]
            {
                ("put_down", T("放下卡牌", "Put down card")),
                ("play", T("打出卡牌", "Play card")),
                ("none", T("无", "None")),
            }, "put_down", null);
        AddSwitchRow(content, "mobile_selection_confirmation", T("手机选择二次确认", "Mobile selection confirmation"), true, null);
        AddSwitchRow(content, "mobile_two_finger_inspect", T("双指查看", "Two-finger inspect"), true, null);

        AddHeader(content, T("系统 / 兼容", "System / Compatibility"));
        AddSwitchRow(content, "preload_enabled", T("启用预加载", "Enable preload"), true, null);
        AddSwitchRow(content, "audio_compatibility_mode", T("声音兼容模式", "Audio compatibility mode"), false, null);
        AddSwitchRow(content, "android_volume_up_soft_keyboard", T("音量上键打开软键盘", "Volume up opens keyboard"), false, null);
        AddSwitchRow(content, "show_mobile_emoji_button", T("显示联机表情按钮", "Show multiplayer emoji button"), true, null);
        AddSwitchRow(content, "quick_sl_enabled", T("启用快速 SL / 重打按钮", "Enable quick retry button"), true, null);
        AddSwitchRow(content, "max_multiplayer_enabled", T("启用自定义最大联机人数", "Enable custom max multiplayer players"), true, null);
        AddIntRow(content, "max_multiplayer_players", T("最大联机人数", "Max multiplayer players"), 4, 1, 16, null);
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
            if (template != null)
            {
                tickbox.CustomMinimumSize = template.CustomMinimumSize;
                tickbox.SizeFlagsHorizontal = template.SizeFlagsHorizontal;
                tickbox.MouseFilter = template.MouseFilter;
                while (template.GetChildCount() > 0)
                {
                    var child = template.GetChild(0);
                    template.RemoveChild(child);
                    tickbox.AddChild(child);
                    SetOwnerRecursive(child, tickbox);
                }
                template.QueueFree();
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Falling back to programmatic settings tickbox visuals: {exception.Message}");
            tickbox.AddChild(new Control { Name = "TickboxVisuals", UniqueNameInOwner = true, CustomMinimumSize = new Vector2(64, 64) });
            tickbox.AddChild(new Control { Name = "SelectionReticle" });
        }
        return tickbox;
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
        row.AddThemeConstantOverride("separation", 16);
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
        var button = new Button
        {
            Text = buttonText,
            CustomMinimumSize = new Vector2(240, 54),
            FocusMode = Control.FocusModeEnum.All,
        };
        button.Pressed += onPressed;
        row.AddChild(button);
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
