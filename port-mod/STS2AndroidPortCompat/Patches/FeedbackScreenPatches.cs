using System;
using System.Linq;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Suppresses MegaCrit's beta-build FeedbackScreen, which auto-opens on game
// start and currently locks input on Android: NFeedbackCategoryDropdown.
// _Ready throws NullReferenceException out of LocString.GetFormattedText
// (missing localization rows in the public-beta build), and a "Sending"
// overlay never dismisses, so the user can't tap Return to Game.
//
// Strategy: hide the screen and skip its _Ready body so its UI never wires up.
// We can't QueueFree it — ScreenContext keeps a reference and would later hit
// ObjectDisposedException polling .Visible from NMainMenu / NEventRoom.
// Reflection-based type lookup so we don't depend on exact class name.
public static class FeedbackScreenPatches
{
    private const string TargetNamespace = "MegaCrit.Sts2.Core.Nodes.Screens.FeedbackScreen";

    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        Type[] types;
        try
        {
            types = sts2Asm.GetTypes();
        }
        catch (System.Reflection.ReflectionTypeLoadException ex)
        {
            // Some types may fail to load; use what we can.
            types = ex.Types.Where(t => t != null).ToArray();
        }

        var candidates = types
            .Where(t =>
                t != null
                && t.Namespace == TargetNamespace
                && typeof(Node).IsAssignableFrom(t)
                && (t.Name.Contains("Screen") || t.Name.Contains("Popup"))
                && !t.Name.Contains("Dropdown")
                && !t.Name.Contains("Category")
                && !t.Name.Contains("Emoji")
            )
            .ToArray();

        if (candidates.Length == 0)
        {
            PatchHelper.Log(
                $"[FeedbackScreen] No screen-like types found in {TargetNamespace}; skipping suppression."
            );
            return;
        }

        foreach (var t in candidates)
        {
            PatchHelper.Patch(
                harmony,
                t,
                "_Ready",
                prefix: PatchHelper.Method(typeof(FeedbackScreenPatches), nameof(ReadyPrefix))
            );
        }
    }

    // Returns false so the original _Ready never runs (so the dropdown's NRE
    // chain doesn't fire) and forces Visible=false so ScreenContext skips this
    // screen when iterating the modal stack. Keep the node alive so disposing
    // doesn't cascade into NMainMenu / NEventRoom polling its .Visible.
    public static bool ReadyPrefix(object __instance)
    {
        try
        {
            if (__instance is CanvasItem ci)
            {
                ci.Visible = false;
            }
            if (__instance is Node node)
            {
                node.ProcessMode = Node.ProcessModeEnum.Disabled;
                PatchHelper.Log(
                    $"[FeedbackScreen] Hid {node.GetType().FullName} and skipped _Ready"
                );
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[FeedbackScreen] ReadyPrefix failed: {ex.Message}");
        }
        return false;
    }
}
