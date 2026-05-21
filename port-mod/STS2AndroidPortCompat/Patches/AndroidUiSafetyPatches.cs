using System;
using System.Threading.Tasks;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.Screens.Settings;

namespace STS2Mobile.Patches;

/// <summary>
/// Android can deliver viewport/window-change callbacks while settings controls
/// are still entering/leaving the tree.  The PC callbacks assume every exported
/// child and SceneTree is already valid, which creates noisy NullReference
/// exceptions during startup/resolution changes.  These prefixes only skip the
/// unsafe early/late callback; normal ready controls still run the original code.
/// </summary>
public static class AndroidUiSafetyPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NDisplayDropdown), "OnWindowChange", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(SettingsControlWindowChangePrefix)));
        PatchHelper.Patch(harmony, typeof(NResolutionDropdown), "OnWindowChange", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(SettingsControlWindowChangePrefix)));
        PatchHelper.Patch(harmony, typeof(NWindowResizeTickbox), "OnWindowChange", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(SettingsControlWindowChangePrefix)));
        PatchHelper.Patch(harmony, typeof(NFullscreenTickbox), "OnWindowChange", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(SettingsControlWindowChangePrefix)));
        PatchHelper.Patch(harmony, typeof(NBackButton), "OnWindowChange", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(BackButtonWindowChangePrefix)));
        PatchHelper.Patch(harmony, typeof(NInputSettingsPanel), "RefreshSize", prefix: PatchHelper.Method(typeof(AndroidUiSafetyPatches), nameof(InputSettingsRefreshSizePrefix)));
    }

    public static bool SettingsControlWindowChangePrefix(Control __instance)
    {
        return IsReadyInTree(__instance) && HasNode(__instance, "%Label");
    }

    public static bool BackButtonWindowChangePrefix(NBackButton __instance)
    {
        return IsReadyInTree(__instance) && __instance.GetWindow() != null;
    }

    public static bool InputSettingsRefreshSizePrefix(NInputSettingsPanel __instance, ref Task __result)
    {
        if (IsReadyInTree(__instance) && __instance.GetParent() is Control && __instance.Content != null)
            return true;
        __result = Task.CompletedTask;
        return false;
    }

    private static bool IsReadyInTree(Node node)
    {
        try
        {
            // Do not require IsNodeReady(): several original _Ready() methods
            // call their window-change handler once after ConnectSignals(), but
            // Godot may not report the node as fully ready until _Ready returns.
            return node != null && node.IsInsideTree() && node.GetTree() != null;
        }
        catch
        {
            return false;
        }
    }

    private static bool HasNode(Node node, NodePath path)
    {
        try
        {
            return node.GetNodeOrNull(path) != null;
        }
        catch
        {
            return false;
        }
    }
}
