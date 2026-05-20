using System;
using System.Reflection;
using System.Runtime.CompilerServices;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Nodes.Combat;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class TouchInputPatches
{
    private static readonly ConditionalWeakTable<object, TouchState> States = new ConditionalWeakTable<object, TouchState>();

    public static void Apply(Harmony harmony)
    {
        var mouseCardPlayType = typeof(NMouseCardPlay);
        PatchHelper.Patch(harmony, mouseCardPlayType, "_Input", postfix: PatchHelper.Method(typeof(TouchInputPatches), nameof(MouseCardPlayInputPostfix)));
        PatchHelper.Patch(harmony, mouseCardPlayType, "Start", postfix: PatchHelper.Method(typeof(TouchInputPatches), nameof(MouseCardPlayStartPostfix)));
        PatchHelper.Patch(harmony, mouseCardPlayType, "OnCancelPlayCard", postfix: PatchHelper.Method(typeof(TouchInputPatches), nameof(MouseCardPlayCancelPostfix)));

        var targetManagerType = typeof(NTargetManager);
        PatchHelper.Patch(harmony, targetManagerType, "StartTargeting", postfix: PatchHelper.Method(typeof(TouchInputPatches), nameof(TargetManagerStartPostfix)));
        PatchHelper.Patch(harmony, targetManagerType, "FinishTargeting", prefix: PatchHelper.Method(typeof(TouchInputPatches), nameof(TargetManagerFinishPrefix)));
    }

    public static void MouseCardPlayStartPostfix(object __instance)
    {
        var state = States.GetOrCreateValue(__instance);
        state.KeepHolderFocusedAfterCancel = false;
    }

    public static void MouseCardPlayInputPostfix(object __instance, InputEvent inputEvent)
    {
        try
        {
            if (!IsTouchOptimized() || inputEvent is not InputEventMouseButton mouseButton || mouseButton.ButtonIndex != MouseButton.Left || !mouseButton.IsReleased())
                return;

            if (!IsCardInPlayZone(__instance))
            {
                var cancelMethod = __instance.GetType().GetMethod("CancelPlayCard", BindingFlags.Public | BindingFlags.Instance);
                cancelMethod?.Invoke(__instance, null);
                PatchHelper.Log("Touch input cancelled card play: released outside play zone.");
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"MouseCardPlayInputPostfix failed: {exception.Message}");
        }
    }

    public static void MouseCardPlayCancelPostfix(object __instance)
    {
        try
        {
            var targetManager = NTargetManager.Instance;
            if (targetManager != null && ConsumeCancelledByUntargetedRelease(targetManager))
                States.GetOrCreateValue(__instance).KeepHolderFocusedAfterCancel = true;
            if (!States.TryGetValue(__instance, out var state) || !state.KeepHolderFocusedAfterCancel)
                return;
            state.KeepHolderFocusedAfterCancel = false;
            var holder = GetField(__instance, "Holder") ?? __instance.GetType().BaseType?.GetField("Holder", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(__instance);
            var hitbox = holder?.GetType().GetProperty("Hitbox", BindingFlags.Public | BindingFlags.Instance)?.GetValue(holder) as Control;
            if (hitbox != null && GodotObject.IsInstanceValid(hitbox) && hitbox.IsInsideTree())
                Callable.From(hitbox.GrabFocus).CallDeferred();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"MouseCardPlayCancelPostfix failed: {exception.Message}");
        }
    }

    public static void TargetManagerStartPostfix(object __instance)
    {
        SetField(__instance, "_androidPortCancelledByUntargetedRelease", false);
    }

    public static void TargetManagerFinishPrefix(object __instance, bool cancel)
    {
        try
        {
            if (!IsTouchOptimized() || !cancel)
                return;
            var hoveredNode = GetProperty(__instance, "HoveredNode");
            var targetMode = GetField(__instance, "_targetMode");
            if (hoveredNode == null && targetMode != null && targetMode.ToString() == "ReleaseMouseToTarget")
            {
                SetField(__instance, "_androidPortCancelledByUntargetedRelease", true);
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"TargetManagerFinishPrefix failed: {exception.Message}");
        }
    }

    public static bool ConsumeCancelledByUntargetedRelease(object targetManager)
    {
        var value = GetField(targetManager, "_androidPortCancelledByUntargetedRelease") is true;
        SetField(targetManager, "_androidPortCancelledByUntargetedRelease", false);
        return value;
    }

    private static bool IsTouchOptimized()
    {
        return AndroidSettingsBridge.GetBool("touch_lift_preview", true)
            || AndroidSettingsBridge.GetBool("mobile_selection_confirmation", true);
    }

    private static bool IsCardInPlayZone(object mouseCardPlay)
    {
        var method = mouseCardPlay.GetType().GetMethod("IsCardInPlayZone", BindingFlags.NonPublic | BindingFlags.Instance);
        return method != null && (bool)method.Invoke(mouseCardPlay, null);
    }

    private static object GetField(object target, string name)
    {
        return target.GetType().GetField(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(target);
    }

    private static object GetProperty(object target, string name)
    {
        return target.GetType().GetProperty(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(target);
    }

    private static void SetField(object target, string name, object value)
    {
        var field = target.GetType().GetField(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        field?.SetValue(target, value);
    }

    private sealed class TouchState
    {
        public bool KeepHolderFocusedAfterCancel;
    }
}
