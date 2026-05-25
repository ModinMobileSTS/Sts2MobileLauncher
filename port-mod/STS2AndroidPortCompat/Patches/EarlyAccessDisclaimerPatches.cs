using System;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Fixes the early access disclaimer layout on non-16:9 screens. The original
// VBoxContainer uses fixed pixel offsets designed for 1680x1080 that misalign
// on wider or narrower viewports. Switches to proportional anchors instead.
public static class EarlyAccessDisclaimerPatches
{
    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var disclaimerType = sts2Asm.GetType(
            "MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NEarlyAccessDisclaimer"
        );
        if (disclaimerType != null)
        {
            PatchHelper.Patch(
                harmony,
                disclaimerType,
                "_Ready",
                postfix: PatchHelper.Method(
                    typeof(EarlyAccessDisclaimerPatches),
                    nameof(ReadyPostfix)
                )
            );
        }
    }

    public static void ReadyPostfix(object __instance)
    {
        try
        {
            var disclaimer = (Control)__instance;
            var image = disclaimer.GetNode<Control>("Image");
            var vbox = image.GetNode<Control>("VBoxContainer");

            // Convert fixed pixel offsets to proportional anchors so the text
            // scales with the banner image on any viewport width.
            float halfWidth = 265f / (1680f - 596f * 2f);
            vbox.AnchorLeft = 0.5f - halfWidth;
            vbox.AnchorRight = 0.5f + halfWidth;
            vbox.OffsetLeft = 0f;
            vbox.OffsetRight = 0f;

            PatchHelper.Log(
                $"[EADisclaimer] Fixed VBoxContainer anchors: L={vbox.AnchorLeft:F3} R={vbox.AnchorRight:F3}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[EADisclaimer] ReadyPostfix failed: {ex.Message}");
        }
    }
}
