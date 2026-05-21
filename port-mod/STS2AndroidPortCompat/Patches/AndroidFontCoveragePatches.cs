using System;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Nodes;

namespace STS2Mobile.Patches;

/// <summary>
/// Applies the Android font-size setting to UI created after the main menu is
/// ready (submenus, modals, cards, hover tips, compendium screens, etc.).  This
/// intentionally avoids patching Godot engine methods or MegaLabel private
/// methods during startup, because those Harmony wrappers can trip GodotSharp
/// StringName static-initialization bugs on Android.
/// </summary>
public static class AndroidFontCoveragePatches
{
    public static void Apply(Harmony harmony)
    {
        // Patch only Godot's generic AddChild path.  Patching many concrete UI
        // _Ready methods here forces their static constructors to run before
        // localization is initialized (for example NTopBarDeckButton), which can
        // poison the type and crash startup.  AddChild catches both scene-created
        // and programmatically-created UI without touching those types early.
        PatchHelper.Patch(harmony, typeof(Node), "AddChild", postfix: PatchHelper.Method(typeof(AndroidFontCoveragePatches), nameof(NodeAddChildPostfix)));
        var addChildSafely = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Helpers.GodotTreeExtensions")?.GetMethod("AddChildSafely", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static);
        if (addChildSafely != null)
            harmony.Patch(addChildSafely, postfix: new HarmonyMethod(PatchHelper.Method(typeof(AndroidFontCoveragePatches), nameof(AddChildSafelyPostfix))));
    }

    public static void NodeAddChildPostfix(Node node) => RegisterTreePostfix(node);

    public static void AddChildSafelyPostfix(Node child) => RegisterTreePostfix(child);

    public static void RegisterTreePostfix(Node __instance)
    {
        try
        {
            if (__instance is not Control control)
                return;
            DisplaySettingsPatches.ApplyFontSizeOverridesRecursive(control);
            Callable.From(() =>
            {
                try
                {
                    if (GodotObject.IsInstanceValid(control))
                        DisplaySettingsPatches.ApplyFontSizeOverridesRecursive(control);
                }
                catch
                {
                }
            }).CallDeferred();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android font coverage scan failed on {__instance?.GetType().Name}: {exception.Message}");
        }
    }
}
