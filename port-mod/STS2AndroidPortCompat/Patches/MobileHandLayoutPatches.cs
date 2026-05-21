using System;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Nodes.Cards.Holders;
using MegaCrit.Sts2.Core.Nodes.Combat;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class MobileHandLayoutPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "RefreshLayout", postfix: PatchHelper.Method(typeof(MobileHandLayoutPatches), nameof(RefreshLayoutPostfix)));
    }

    public static void RefreshLayoutPostfix(object __instance)
    {
        try
        {
            var yOffset = GetAdditionalHandCardYOffset();
            if (Mathf.IsZeroApprox(yOffset))
                return;

            var activeHolders = __instance.GetType().GetProperty("ActiveHolders", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(__instance) as System.Collections.IEnumerable;
            if (activeHolders == null)
                return;

            foreach (var item in activeHolders)
            {
                if (item is not NHandCardHolder holder || !GodotObject.IsInstanceValid(holder) || !holder.Visible)
                    continue;
                if (IsFocusedHolder(__instance, holder))
                    continue;
                var target = holder.TargetPosition;
                holder.SetTargetPosition(new Vector2(target.X, target.Y + yOffset));
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Mobile hand layout offset failed: {exception.Message}");
        }
    }

    private static bool IsFocusedHolder(object playerHand, NHandCardHolder holder)
    {
        if (MobileTapPreviewPatches.IsPinned(holder))
            return true;
        var focused = playerHand.GetType().GetProperty("FocusedHolder", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(playerHand);
        return ReferenceEquals(focused, holder);
    }

    private static float GetAdditionalHandCardYOffset()
    {
        if (!OS.HasFeature("mobile"))
            return 0f;
        if (!AndroidSettingsBridge.GetBool("show_more_hand_card_text", true))
            return 0f;
        var percent = Mathf.Clamp(AndroidSettingsBridge.GetInt("show_more_hand_card_text_lift_height_percent", 50), 25, 100);
        return -80f * percent / 100f;
    }
}
