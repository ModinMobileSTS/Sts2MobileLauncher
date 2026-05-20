using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class AndroidSettingsMerge
{
    public static string PendingBeforeSaveJson;

    private static readonly string[] AndroidOnlyKeys =
    {
        "shader_compatibility_mode",
        "preload_enabled",
        "global_scale",
        "ui_font_size",
        "ui_font_scale_percent",
        "show_more_hand_card_text",
        "show_more_hand_card_text_lift_height_percent",
        "touch_lift_preview",
        "touch_lift_retap_action",
        "mobile_selection_confirmation",
        "mobile_two_finger_inspect",
        "show_mobile_emoji_button",
        "lan_multiplayer_enabled",
        "max_multiplayer_players",
        "max_multiplayer_enabled",
        "quick_sl_enabled",
        "android_volume_up_soft_keyboard",
        "android_flip_screen_180",
        "fullscreen_render_size",
        "audio_compatibility_mode",
        "lan_player_id",
        "lan_use_custom_player_id",
        "lan_use_custom_platform_player_id",
        "lan_custom_player_id",
        "lan_compatibility_mod_names",
        "lan_join_host",
        "lan_join_port",
        "android_graphics_preset",
        "android_display_preset",
    };

    public static void MergeBackAndroidOnlyFields(string beforeJson)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(beforeJson) || !AndroidSettingsBridge.TryReadRaw(out var afterJson))
                return;
            var before = JsonNode.Parse(beforeJson)?.AsObject();
            var after = JsonNode.Parse(afterJson)?.AsObject();
            if (before == null || after == null)
                return;

            var changed = false;
            foreach (var key in AndroidOnlyKeys)
            {
                if (!before.TryGetPropertyValue(key, out var value))
                    continue;
                after[key] = value?.DeepClone();
                changed = true;
            }

            if (changed)
            {
                AndroidSettingsBridge.TryWriteRaw(after.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
                PatchHelper.Log("Merged Android-only settings back after PC SettingsSave serialization.");
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android-only settings merge failed: {exception}");
        }
    }
}
