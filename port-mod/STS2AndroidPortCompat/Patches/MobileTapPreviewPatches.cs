using System;
using System.Reflection;
using System.Runtime.CompilerServices;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Combat;
using MegaCrit.Sts2.Core.Entities.Cards;
using MegaCrit.Sts2.Core.Models;
using MegaCrit.Sts2.Core.Nodes.Cards.Holders;
using MegaCrit.Sts2.Core.Nodes.Combat;
using MegaCrit.Sts2.Core.Nodes.Screens.Overlays;
using MegaCrit.Sts2.Core.Runs;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class MobileTapPreviewPatches
{
    private static readonly ConditionalWeakTable<NPlayerHand, PreviewState> States = new();
    private static readonly ConditionalWeakTable<NCardHolder, PinnedState> PinnedHolders = new();

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "OnHolderPressed", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(OnHolderPressedPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "StartCardPlay", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(StartCardPlayPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "CancelAllCardPlay", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(ClearPinnedPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "OnCombatEnded", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(ClearPinnedPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardHolder), "RefreshFocusState", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(CardHolderRefreshFocusStatePrefix)));
    }

    public static bool OnHolderPressedPrefix(NPlayerHand __instance, NCardHolder holder)
    {
        try
        {
            if (!ShouldHandle(__instance, holder))
                return true;

            var handHolder = (NHandCardHolder)holder;
            var state = States.GetOrCreateValue(__instance);
            if (IsSameValidHolder(state.PinnedHolder, handHolder))
            {
                switch (GetRetapAction())
                {
                    case RetapAction.Play:
                        ClearPinned(__instance);
                        return true;
                    case RetapAction.None:
                        return false;
                    case RetapAction.PutDown:
                    default:
                        ClearPinned(__instance);
                        return false;
                }
            }

            SetPinned(__instance, handHolder);
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Mobile tap preview failed; falling back to original press: {exception.Message}");
            return true;
        }
    }

    public static void StartCardPlayPrefix(NPlayerHand __instance)
    {
        ClearPinned(__instance);
    }

    public static void ClearPinnedPrefix(NPlayerHand __instance)
    {
        ClearPinned(__instance);
    }

    public static bool CardHolderRefreshFocusStatePrefix(NCardHolder __instance)
    {
        if (!PinnedHolders.TryGetValue(__instance, out _))
            return true;
        try
        {
            var isFocusedField = typeof(NCardHolder).GetField("_isFocused", BindingFlags.NonPublic | BindingFlags.Instance);
            if (isFocusedField?.GetValue(__instance) is not true)
            {
                isFocusedField?.SetValue(__instance, true);
                InvokeDoCardHoverEffects(__instance, true);
            }
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Pinned card refresh failed: {exception.Message}");
            return true;
        }
    }

    private static bool ShouldHandle(NPlayerHand hand, NCardHolder holder)
    {
        if (!OS.HasFeature("mobile") || !AndroidSettingsBridge.GetBool("touch_lift_preview", true))
            return false;
        if (holder is not NHandCardHolder handHolder || handHolder.CardNode == null)
            return false;
        if (!CombatManager.Instance.IsInProgress || NOverlayStack.Instance.ScreenCount > 0)
            return false;
        if (hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay || hand.IsAwaitingPlay(handHolder))
            return false;
        return AreCardActionsAllowed(hand);
    }

    private static bool AreCardActionsAllowed(NPlayerHand hand)
    {
        try
        {
            var method = typeof(NPlayerHand).GetMethod("AreCardActionsAllowed", BindingFlags.NonPublic | BindingFlags.Instance);
            return method == null || (bool)method.Invoke(hand, null);
        }
        catch
        {
            return !CombatManager.Instance.PlayerActionsDisabled;
        }
    }

    private static void SetPinned(NPlayerHand hand, NHandCardHolder holder)
    {
        var state = States.GetOrCreateValue(hand);
        if (state.PinnedHolder != null && !ReferenceEquals(state.PinnedHolder, holder))
            UnpinHolder(state.PinnedHolder);
        state.PinnedHolder = holder;
        PinnedHolders.GetOrCreateValue(holder);
        RunManager.Instance.HoveredModelTracker.OnLocalCardHovered(holder.CardModel);
        ForcePinnedPreview(holder);
        PatchHelper.Log($"Pinned hand card preview: {holder.CardModel?.Id}");
    }

    private static void ClearPinned(NPlayerHand hand)
    {
        if (!States.TryGetValue(hand, out var state) || state.PinnedHolder == null)
            return;
        UnpinHolder(state.PinnedHolder);
        state.PinnedHolder = null;
        try
        {
            RunManager.Instance.HoveredModelTracker.OnLocalCardUnhovered();
        }
        catch
        {
        }
    }

    private static void UnpinHolder(NCardHolder holder)
    {
        try
        {
            PinnedHolders.Remove(holder);
            var isFocusedField = typeof(NCardHolder).GetField("_isFocused", BindingFlags.NonPublic | BindingFlags.Instance);
            if (isFocusedField?.GetValue(holder) is true)
            {
                isFocusedField.SetValue(holder, false);
                InvokeDoCardHoverEffects(holder, false);
            }
            holder.ReleaseFocus();
            holder.Hitbox?.ReleaseFocus();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Unpin hand card failed: {exception.Message}");
        }
    }

    private static void ForcePinnedPreview(NCardHolder holder)
    {
        try
        {
            holder.GrabFocus();
            holder.Hitbox?.GrabFocus();
            var isHoveredField = typeof(NCardHolder).GetField("_isHovered", BindingFlags.NonPublic | BindingFlags.Instance);
            isHoveredField?.SetValue(holder, true);
            var isFocusedField = typeof(NCardHolder).GetField("_isFocused", BindingFlags.NonPublic | BindingFlags.Instance);
            if (isFocusedField?.GetValue(holder) is not true)
            {
                isFocusedField?.SetValue(holder, true);
                InvokeDoCardHoverEffects(holder, true);
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Force pinned preview failed: {exception.Message}");
        }
    }

    private static void InvokeDoCardHoverEffects(NCardHolder holder, bool isHovered)
    {
        holder.GetType().GetMethod("DoCardHoverEffects", BindingFlags.NonPublic | BindingFlags.Instance)?.Invoke(holder, new object[] { isHovered });
    }

    private static bool IsSameValidHolder(NCardHolder current, NHandCardHolder candidate)
    {
        return ReferenceEquals(current, candidate) && GodotObject.IsInstanceValid(candidate) && candidate.IsInsideTree();
    }

    private static RetapAction GetRetapAction()
    {
        return AndroidSettingsBridge.GetString("touch_lift_retap_action", "put_down").Trim().ToLowerInvariant().Replace("-", "_") switch
        {
            "play" => RetapAction.Play,
            "none" => RetapAction.None,
            _ => RetapAction.PutDown,
        };
    }

    private enum RetapAction
    {
        PutDown,
        Play,
        None,
    }

    private sealed class PreviewState
    {
        public NCardHolder PinnedHolder;
    }

    private sealed class PinnedState
    {
    }
}
