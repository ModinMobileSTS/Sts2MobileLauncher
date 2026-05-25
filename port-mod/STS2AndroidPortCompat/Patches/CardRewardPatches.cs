using System;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Fixes a crash when closing the rewards screen caused by a tween race condition.
// Kills the fade tween and stops processing before QueueFree so _Process doesn't
// fire after the node is removed from the tree.
public static class CardRewardPatches
{
    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var rewardsScreenType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.Screens.NRewardsScreen");
        if (rewardsScreenType != null)
        {
            PatchHelper.Patch(
                harmony,
                rewardsScreenType,
                "AfterOverlayClosed",
                prefix: PatchHelper.Method(
                    typeof(CardRewardPatches),
                    nameof(RewardsScreenClosedPrefix)
                )
            );
        }
    }

    public static void RewardsScreenClosedPrefix(object __instance)
    {
        try
        {
            var node = (Node)__instance;
            node.SetProcess(false);

            var field = AccessTools.Field(__instance.GetType(), "_fadeTween");
            var tween = field?.GetValue(__instance) as Tween;
            if (tween != null && tween.IsValid())
            {
                tween.Kill();
                field.SetValue(__instance, null);
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"RewardsScreenClosedPrefix failed: {ex.Message}");
        }
    }
}
