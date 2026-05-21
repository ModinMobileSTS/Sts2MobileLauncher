using System;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.ControllerInput;
using MegaCrit.Sts2.Core.Models;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.Cards.Holders;
using MegaCrit.Sts2.Core.Nodes.CommonUi;
using MegaCrit.Sts2.Core.Nodes.GodotExtensions;
using MegaCrit.Sts2.Core.Nodes.Screens.CardSelection;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

/// <summary>
/// Restores the Android port's mobile selection confirmation for reward/choice
/// card screens without replacing the original PC scenes.  The imported PC scene
/// has the card-holder press signal wired directly to completion; this patch
/// intercepts that press, pins the card visually, and injects the stock confirm
/// button scene as the second tap target.
/// </summary>
public static class MobileSelectionConfirmationPatches
{
    private const string ConfirmButtonName = "AndroidMobileConfirm";
    private static readonly ConditionalWeakTable<Node, SelectionState> States = new();
    private static readonly BindingFlags InstanceFlags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "_Ready", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(CardRewardReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "RefreshOptions", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ClearSelectionPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "SelectCard", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(CardRewardSelectPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "OnAlternateRewardSelected", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ClearSelectionPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "AfterOverlayClosed", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ClearSelectionPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "AfterOverlayHidden", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(OverlayHiddenPostfix)));
        PatchHelper.Patch(harmony, typeof(NCardRewardSelectionScreen), "AfterOverlayShown", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(OverlayShownPostfix)));

        PatchHelper.Patch(harmony, typeof(NChooseACardSelectionScreen), "_Ready", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ChooseCardReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NChooseACardSelectionScreen), "SelectHolder", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ChooseCardSelectPrefix)));
        PatchHelper.Patch(harmony, typeof(NChooseACardSelectionScreen), "AfterOverlayClosed", prefix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(ClearSelectionPrefix)));
        PatchHelper.Patch(harmony, typeof(NChooseACardSelectionScreen), "AfterOverlayHidden", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(OverlayHiddenPostfix)));
        PatchHelper.Patch(harmony, typeof(NChooseACardSelectionScreen), "AfterOverlayShown", postfix: PatchHelper.Method(typeof(MobileSelectionConfirmationPatches), nameof(OverlayShownPostfix)));
    }

    public static void CardRewardReadyPostfix(NCardRewardSelectionScreen __instance)
    {
        EnsureConfirmButton(__instance, ConfirmRewardSelection);
    }

    public static void ChooseCardReadyPostfix(NChooseACardSelectionScreen __instance)
    {
        EnsureConfirmButton(__instance, ConfirmChooseCardSelection);
    }

    public static bool CardRewardSelectPrefix(NCardRewardSelectionScreen __instance, NCardHolder cardHolder)
    {
        try
        {
            if (!IsEnabled())
                return true;
            if (!HasPendingCompletion(__instance, "_completionSource"))
                return true;
            SelectHolder(__instance, cardHolder);
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Mobile card-reward confirmation failed; falling back to vanilla selection: {exception.Message}");
            return true;
        }
    }

    public static bool ChooseCardSelectPrefix(NChooseACardSelectionScreen __instance, NCardHolder cardHolder)
    {
        try
        {
            if (!IsEnabled())
                return true;
            if (Time.GetTicksMsec() - GetUlongField(__instance, "_openedTicks") <= 350)
                return false;
            SelectHolder(__instance, cardHolder);
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Mobile choose-card confirmation failed; falling back to vanilla selection: {exception.Message}");
            return true;
        }
    }

    public static void ClearSelectionPrefix(Node __instance)
    {
        ClearSelection(__instance);
    }

    public static void OverlayHiddenPostfix(Node __instance)
    {
        GetState(__instance)?.ConfirmButton?.Disable();
    }

    public static void OverlayShownPostfix(Node __instance)
    {
        var state = GetState(__instance);
        if (IsEnabled() && state?.SelectedHolder != null)
            state.ConfirmButton?.Enable();
    }

    private static void EnsureConfirmButton(Node screen, Action<Node> onConfirm)
    {
        try
        {
            var state = States.GetOrCreateValue(screen);
            if (state.ConfirmButton != null && GodotObject.IsInstanceValid(state.ConfirmButton))
                return;
            var existing = screen.GetNodeOrNull<NConfirmButton>($"%Confirm")
                ?? screen.GetNodeOrNull<NConfirmButton>("Confirm")
                ?? screen.GetNodeOrNull<NConfirmButton>(ConfirmButtonName);
            var button = existing ?? CreateConfirmButton();
            if (button == null)
                return;
            if (button.GetParent() == null)
                screen.AddChild(button);
            button.Name = ConfirmButtonName;
            button.Connect(NClickableControl.SignalName.Released, Callable.From<NButton>(_ => onConfirm(screen)));
            button.Disable();
            state.ConfirmButton = button;
            PatchHelper.Log($"Mobile selection confirmation button ready on {screen.GetType().Name}.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Inject mobile selection confirmation button failed: {exception.Message}");
        }
    }

    private static NConfirmButton CreateConfirmButton()
    {
        var packed = ResourceLoader.Load<PackedScene>("res://scenes/ui/confirm_button.tscn");
        return packed?.Instantiate<NConfirmButton>();
    }

    private static void SelectHolder(Node screen, NCardHolder holder)
    {
        if (holder == null || holder.CardModel == null)
            return;
        var state = States.GetOrCreateValue(screen);
        if (!ReferenceEquals(state.SelectedHolder, holder))
        {
            SetPinnedPreview(state.SelectedHolder, false);
            state.SelectedHolder = holder;
            SetPinnedPreview(holder, true);
            holder.TryGrabFocus();
        }
        state.ConfirmButton?.Enable();
    }

    private static void ConfirmRewardSelection(Node screen)
    {
        var state = GetState(screen);
        var holder = state?.SelectedHolder;
        if (holder == null)
            return;
        ClearSelection(screen, disableConfirm: true, unpin: true);
        var completion = GetField(screen, "_completionSource") as TaskCompletionSource<Tuple<IEnumerable<NCardHolder>, bool>>;
        if (completion == null || completion.Task.IsCompleted)
            return;
        completion.SetResult(new Tuple<IEnumerable<NCardHolder>, bool>(new[] { holder }, true));
    }

    private static void ConfirmChooseCardSelection(Node screen)
    {
        var state = GetState(screen);
        var holder = state?.SelectedHolder;
        var card = holder?.CardModel;
        if (card == null)
            return;
        ClearSelection(screen, disableConfirm: true, unpin: true);
        SetField(screen, "_screenComplete", true);
        SetField(screen, "_cardSelected", true);
        var completion = GetField(screen, "_completionSource") as TaskCompletionSource<IEnumerable<CardModel>>;
        if (completion == null || completion.Task.IsCompleted)
            return;
        completion.SetResult(new[] { card });
    }

    private static void ClearSelection(Node screen, bool disableConfirm = true, bool unpin = true)
    {
        var state = GetState(screen);
        if (state == null)
            return;
        if (unpin)
            SetPinnedPreview(state.SelectedHolder, false);
        state.SelectedHolder = null;
        if (disableConfirm)
            state.ConfirmButton?.Disable();
    }

    private static void SetPinnedPreview(NCardHolder holder, bool pinned)
    {
        if (holder == null || !GodotObject.IsInstanceValid(holder))
            return;
        var androidMethod = holder.GetType().GetMethod("SetPinnedPreview", InstanceFlags);
        if (androidMethod != null)
        {
            androidMethod.Invoke(holder, new object[] { pinned });
            return;
        }
        typeof(NCardHolder).GetField("_isHovered", InstanceFlags)?.SetValue(holder, pinned);
        var focusedField = typeof(NCardHolder).GetField("_isFocused", InstanceFlags);
        var wasFocused = focusedField?.GetValue(holder) is true;
        if (wasFocused != pinned)
        {
            focusedField?.SetValue(holder, pinned);
            holder.GetType().GetMethod("DoCardHoverEffects", InstanceFlags)?.Invoke(holder, new object[] { pinned });
        }
    }

    private static bool IsEnabled()
    {
        return OS.HasFeature("mobile")
            && !(NControllerManager.Instance?.IsUsingController ?? false)
            && AndroidSettingsBridge.GetBool("mobile_selection_confirmation", true);
    }

    private static bool HasPendingCompletion(object target, string fieldName)
    {
        var completion = GetField(target, fieldName);
        var task = completion?.GetType().GetProperty("Task", BindingFlags.Public | BindingFlags.Instance)?.GetValue(completion) as Task;
        return task != null && !task.IsCompleted;
    }

    private static SelectionState GetState(Node screen)
    {
        return screen != null && States.TryGetValue(screen, out var state) ? state : null;
    }

    private static object GetField(object target, string name)
    {
        return target?.GetType().GetField(name, InstanceFlags)?.GetValue(target);
    }

    private static void SetField(object target, string name, object value)
    {
        target?.GetType().GetField(name, InstanceFlags)?.SetValue(target, value);
    }

    private static ulong GetUlongField(object target, string name)
    {
        var value = GetField(target, name);
        return value switch
        {
            ulong ulongValue => ulongValue,
            long longValue => (ulong)Math.Max(0L, longValue),
            int intValue => (ulong)Math.Max(0, intValue),
            _ => 0UL,
        };
    }

    private sealed class SelectionState
    {
        public NCardHolder SelectedHolder;
        public NConfirmButton ConfirmButton;
    }
}
