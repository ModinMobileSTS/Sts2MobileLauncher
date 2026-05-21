using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.ControllerInput;
using MegaCrit.Sts2.Core.Models;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.Cards.Holders;
using MegaCrit.Sts2.Core.Nodes.Combat;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.GodotExtensions;
using MegaCrit.Sts2.Core.Nodes.Screens.Shops;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class AndroidInputCompatPatches
{
    private const float TriggerPressThreshold = 0.55f;
    private const float TriggerReleaseThreshold = 0.35f;
    private static readonly StringName MobileBackAction = "back_button";
    private static readonly Dictionary<(int Device, StringName Action), bool> TriggerStates = new();
    private static readonly Dictionary<int, TouchPointState> ActiveTouchPoints = new();
    private static readonly HashSet<int> SuppressedSecondaryTouchIndices = new();
    private static long _lastBackDispatchFrame = -1;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NInputManager), "_Input", prefix: PatchHelper.Method(typeof(AndroidInputCompatPatches), nameof(InputPrefix)));
        PatchHelper.Patch(harmony, typeof(NInputManager), "_UnhandledInput", prefix: PatchHelper.Method(typeof(AndroidInputCompatPatches), nameof(UnhandledInputPrefix)));
    }

    public static bool InputPrefix(NInputManager __instance, InputEvent inputEvent)
    {
        try
        {
            if (!OS.HasFeature("mobile"))
                return true;
            if (TryHandleMobileBackInput(__instance, inputEvent))
                return false;
            if (!AndroidSettingsBridge.GetBool("mobile_two_finger_inspect", true))
            {
                ClearMobileRightClickGestureState();
                return true;
            }
            ProcessMobileRightClickGesture(__instance, inputEvent);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android input _Input compat failed: {exception.Message}");
        }
        return true;
    }

    public static bool UnhandledInputPrefix(NInputManager __instance, InputEvent inputEvent)
    {
        try
        {
            if (!OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase))
                return true;
            foreach (var mapped in EnumerateCompatibleControllerEvents(inputEvent))
                DispatchMappedControllerInput(__instance, mapped.Action, mapped.Pressed);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android controller compat dispatch failed: {exception.Message}");
        }
        return true;
    }

    private static bool TryHandleMobileBackInput(NInputManager inputManager, InputEvent inputEvent)
    {
        if (!inputEvent.IsActionPressed(MobileBackAction))
            return false;
        if (NGame.Instance.Transition.InTransition)
            return true;
        var frame = inputManager.GetTree().GetFrame();
        if (_lastBackDispatchFrame == frame)
            return true;
        _lastBackDispatchFrame = frame;
        DispatchMegaInput(MegaInput.cancel, true);
        DispatchMegaInput(MegaInput.pauseAndBack, true);
        DispatchMegaInput(MegaInput.cancel, false);
        DispatchMegaInput(MegaInput.pauseAndBack, false);
        inputManager.GetViewport()?.SetInputAsHandled();
        return true;
    }

    private static IEnumerable<(StringName Action, bool Pressed)> EnumerateCompatibleControllerEvents(InputEvent inputEvent)
    {
        if (inputEvent is InputEventJoypadMotion motion)
        {
            if (motion.Axis == JoyAxis.TriggerLeft || motion.Axis == JoyAxis.TriggerRight)
            {
                var action = motion.Axis == JoyAxis.TriggerLeft ? Controller.leftTrigger : Controller.rightTrigger;
                var pressed = GetTriggerState(motion.Device, action) ? motion.AxisValue >= TriggerReleaseThreshold : motion.AxisValue >= TriggerPressThreshold;
                if (UpdateTriggerState(motion.Device, action, pressed))
                    yield return (action, pressed);
            }
            else if (motion.Axis == (JoyAxis)2 || motion.Axis == (JoyAxis)3)
            {
                var leftPressed = GetTriggerState(motion.Device, Controller.leftTrigger) ? motion.AxisValue <= -TriggerReleaseThreshold : motion.AxisValue <= -TriggerPressThreshold;
                var rightPressed = GetTriggerState(motion.Device, Controller.rightTrigger) ? motion.AxisValue >= TriggerReleaseThreshold : motion.AxisValue >= TriggerPressThreshold;
                if (Mathf.Abs(motion.AxisValue) < TriggerReleaseThreshold)
                {
                    leftPressed = false;
                    rightPressed = false;
                }
                if (UpdateTriggerState(motion.Device, Controller.leftTrigger, leftPressed))
                    yield return (Controller.leftTrigger, leftPressed);
                if (UpdateTriggerState(motion.Device, Controller.rightTrigger, rightPressed))
                    yield return (Controller.rightTrigger, rightPressed);
            }
        }
    }

    private static void DispatchMappedControllerInput(NInputManager inputManager, StringName controllerInput, bool pressed)
    {
        foreach (var item in GetControllerInputMap(inputManager))
        {
            if (item.Value == controllerInput)
                DispatchMegaInput(item.Key, pressed);
        }
    }

    private static Dictionary<StringName, StringName> GetControllerInputMap(NInputManager inputManager)
    {
        return typeof(NInputManager).GetField("_controllerInputMap", BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(inputManager) as Dictionary<StringName, StringName>
            ?? new Dictionary<StringName, StringName>();
    }

    private static bool GetTriggerState(int device, StringName action)
    {
        return TriggerStates.TryGetValue((device, action), out var value) && value;
    }

    private static bool UpdateTriggerState(int device, StringName action, bool nextPressed)
    {
        var current = GetTriggerState(device, action);
        if (current == nextPressed)
            return false;
        TriggerStates[(device, action)] = nextPressed;
        GD.Print($"[AndroidController] Compatible trigger device={device} action={action} pressed={nextPressed}");
        return true;
    }

    private static void DispatchMegaInput(StringName action, bool pressed)
    {
        Input.ParseInputEvent(new InputEventAction { Action = action, Pressed = pressed });
    }

    private static void ClearMobileRightClickGestureState()
    {
        ActiveTouchPoints.Clear();
        SuppressedSecondaryTouchIndices.Clear();
    }

    private static void ProcessMobileRightClickGesture(NInputManager inputManager, InputEvent inputEvent)
    {
        if (inputEvent is InputEventScreenTouch touch)
        {
            if (SuppressedSecondaryTouchIndices.Contains(touch.Index))
            {
                if (!touch.Pressed)
                {
                    SuppressedSecondaryTouchIndices.Remove(touch.Index);
                    ActiveTouchPoints.Remove(touch.Index);
                }
                inputManager.GetViewport()?.SetInputAsHandled();
                return;
            }
            if (touch.Pressed)
            {
                if (TryGetSingleUnsuppressedTouch(out var firstTouch))
                {
                    ActiveTouchPoints[touch.Index] = new TouchPointState { StartPosition = touch.Position };
                    SuppressedSecondaryTouchIndices.Add(touch.Index);
                    var position = firstTouch.StartPosition;
                    Callable.From(() =>
                    {
                        if (!TryDispatchDirectMobileRightClick(position))
                            DispatchSyntheticRightClick(position);
                    }).CallDeferred();
                    inputManager.GetViewport()?.SetInputAsHandled();
                    return;
                }
                ActiveTouchPoints[touch.Index] = new TouchPointState { StartPosition = touch.Position };
            }
            else
            {
                ActiveTouchPoints.Remove(touch.Index);
                SuppressedSecondaryTouchIndices.Remove(touch.Index);
            }
        }
        else if (inputEvent is InputEventScreenDrag drag && SuppressedSecondaryTouchIndices.Contains(drag.Index))
        {
            inputManager.GetViewport()?.SetInputAsHandled();
        }
        if (ActiveTouchPoints.Count == 0)
            SuppressedSecondaryTouchIndices.Clear();
    }

    private static bool TryGetSingleUnsuppressedTouch(out TouchPointState touchPointState)
    {
        TouchPointState? found = null;
        foreach (var item in ActiveTouchPoints)
        {
            if (SuppressedSecondaryTouchIndices.Contains(item.Key))
                continue;
            if (found.HasValue)
            {
                touchPointState = default;
                return false;
            }
            found = item.Value;
        }
        if (!found.HasValue)
        {
            touchPointState = default;
            return false;
        }
        touchPointState = found.Value;
        return true;
    }

    private static bool TryDispatchDirectMobileRightClick(Vector2 position)
    {
        try
        {
            if (NTargetManager.Instance?.IsInSelection == true)
            {
                GD.Print("[MobileRightClick] direct target cancel");
                NTargetManager.Instance.CancelTargeting();
                return true;
            }
            if (NPlayerHand.Instance?.InCardPlay == true)
            {
                GD.Print("[MobileRightClick] direct card play cancel");
                NPlayerHand.Instance.CancelAllCardPlay();
                return true;
            }
            var merchantSlot = FindTopmostMerchantSlotAtPosition(NGame.Instance, position);
            if (merchantSlot != null)
            {
                GD.Print($"[MobileRightClick] direct merchant preview slot={merchantSlot.GetType().Name} pos={position}");
                PreviewMerchantSlot(merchantSlot);
                return true;
            }
            var cardHolder = FindTopmostCardHolderAtPosition(NGame.Instance, position);
            if (cardHolder == null)
            {
                GD.Print($"[MobileRightClick] no direct hit at pos={position}");
                return false;
            }
            if (cardHolder is NHandCardHolder handCardHolder && TryPreviewHandCard(handCardHolder))
            {
                GD.Print($"[MobileRightClick] direct hand inspect holder={handCardHolder.GetType().Name} pos={position}");
                return true;
            }
            GD.Print($"[MobileRightClick] direct card alt press holder={cardHolder.GetType().Name} pos={position}");
            cardHolder.EmitSignal(NCardHolder.SignalName.AltPressed, cardHolder);
            return true;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Direct mobile right-click dispatch failed: {exception.Message}");
            return false;
        }
    }

    private static bool TryPreviewHandCard(NHandCardHolder holder)
    {
        if (holder.CardModel == null || NPlayerHand.Instance == null)
            return false;
        var cards = NPlayerHand.Instance.ActiveHolders
            .Select(h => h.CardModel)
            .Where(card => card != null)
            .Cast<CardModel>()
            .ToList();
        var index = cards.IndexOf(holder.CardModel);
        if (index < 0)
        {
            cards = new List<CardModel> { holder.CardModel };
            index = 0;
        }
        NGame.Instance.GetInspectCardScreen().Open(cards, index);
        return true;
    }

    private static NCardHolder FindTopmostCardHolderAtPosition(Node node, Vector2 position)
    {
        if (node == null || !GodotObject.IsInstanceValid(node))
            return null;
        for (var i = node.GetChildCount() - 1; i >= 0; i--)
        {
            var found = FindTopmostCardHolderAtPosition(node.GetChild(i), position);
            if (found != null)
                return found;
        }
        return node is NCardHolder holder && IsCardHolderHit(holder, position) ? holder : null;
    }

    private static NMerchantSlot FindTopmostMerchantSlotAtPosition(Node node, Vector2 position)
    {
        if (node == null || !GodotObject.IsInstanceValid(node))
            return null;
        for (var i = node.GetChildCount() - 1; i >= 0; i--)
        {
            var found = FindTopmostMerchantSlotAtPosition(node.GetChild(i), position);
            if (found != null)
                return found;
        }
        return node is NMerchantSlot slot && IsMerchantSlotHit(slot, position) ? slot : null;
    }

    private static bool IsCardHolderHit(NCardHolder holder, Vector2 position)
    {
        var hitbox = holder.Hitbox;
        return hitbox != null
            && GodotObject.IsInstanceValid(hitbox)
            && holder.IsVisibleInTree()
            && hitbox.IsVisibleInTree()
            && hitbox.IsInsideTree()
            && hitbox.GetGlobalRect().HasPoint(position);
    }

    private static bool IsMerchantSlotHit(NMerchantSlot slot, Vector2 position)
    {
        var hitbox = slot.Hitbox;
        return hitbox != null
            && GodotObject.IsInstanceValid(hitbox)
            && slot.IsVisibleInTree()
            && hitbox.IsVisibleInTree()
            && hitbox.IsInsideTree()
            && hitbox.GetGlobalRect().HasPoint(position);
    }

    private static void PreviewMerchantSlot(NMerchantSlot slot)
    {
        if (slot.Entry == null || !slot.Entry.IsStocked)
            return;
        slot.GetType().GetMethod("OnPreview", BindingFlags.NonPublic | BindingFlags.Instance)?.Invoke(slot, null);
    }

    private static void DispatchSyntheticRightClick(Vector2 position)
    {
        Input.WarpMouse(position);
        Input.ParseInputEvent(new InputEventMouseMotion { Position = position, GlobalPosition = position, Relative = Vector2.Zero, Velocity = Vector2.Zero });
        Input.ParseInputEvent(new InputEventMouseButton { ButtonIndex = MouseButton.Right, Pressed = true, Position = position, GlobalPosition = position });
        Input.ParseInputEvent(new InputEventMouseButton { ButtonIndex = MouseButton.Right, Pressed = false, Position = position, GlobalPosition = position });
        GD.Print($"[MobileRightClick] synthetic right click pos={position}");
    }

    private struct TouchPointState
    {
        public Vector2 StartPosition;
    }
}
