using System;
using System.Threading.Tasks;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Adjusts the merchant shop open animation for shorter viewports. When UI scale
// reduces the effective viewport height below 1080px, the inventory panel's
// target position is shifted up so it remains fully visible.
public static class MerchantLayoutPatches
{
    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var merchantInvType = sts2Asm.GetType(
            "MegaCrit.Sts2.Core.Nodes.Screens.Shops.NMerchantInventory"
        );
        if (merchantInvType != null)
        {
            PatchHelper.Patch(
                harmony,
                merchantInvType,
                "DoOpenAnimation",
                prefix: PatchHelper.Method(
                    typeof(MerchantLayoutPatches),
                    nameof(MerchantOpenPrefix)
                )
            );
        }
    }

    public static bool MerchantOpenPrefix(object __instance, ref Task __result)
    {
        UiScalePatches.EnsureUiScaleLoaded();
        try
        {
            var node = (Node)__instance;
            var window = node.GetTree().Root;
            float scaledHeight = (float)window.ContentScaleSize.Y;

            if (scaledHeight >= 1080f)
                return true;

            var instType = __instance.GetType();
            var slotsContainer = (Control)
                AccessTools.Field(instType, "_slotsContainer").GetValue(__instance);
            var backstop = (Node)AccessTools.Field(instType, "_backstop").GetValue(__instance);

            float lostHeight = 1080f - scaledHeight;
            float scaledOpenPos = 80f - lostHeight * 0.5f;

            var existingTween =
                AccessTools.Field(instType, "_inventoryTween")?.GetValue(__instance) as Tween;
            existingTween?.Kill();

            var tween = ((Node)__instance).CreateTween().SetParallel();
            tween
                .TweenProperty(backstop, "modulate:a", 0.8f, 1.0)
                .SetEase(Tween.EaseType.InOut)
                .SetTrans(Tween.TransitionType.Sine)
                .FromCurrent();
            tween
                .TweenProperty(slotsContainer, "position:y", scaledOpenPos, 0.7)
                .SetEase(Tween.EaseType.Out)
                .SetTrans(Tween.TransitionType.Quint)
                .FromCurrent();

            AccessTools.Field(instType, "_inventoryTween")?.SetValue(__instance, tween);

            PatchHelper.Log($"Merchant open: y={scaledOpenPos} (viewport height: {scaledHeight})");

            __result = Task.CompletedTask;
            return false;
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"MerchantOpenPrefix failed: {ex.Message}");
            return true;
        }
    }
}
