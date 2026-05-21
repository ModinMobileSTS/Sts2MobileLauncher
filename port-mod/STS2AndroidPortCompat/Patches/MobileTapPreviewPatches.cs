using System;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.CompilerServices;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Combat;
using MegaCrit.Sts2.Core.Context;
using MegaCrit.Sts2.Core.Entities.Cards;
using MegaCrit.Sts2.Core.Entities.Creatures;
using MegaCrit.Sts2.Core.Models;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Nodes.Cards.Holders;
using MegaCrit.Sts2.Core.Nodes.Combat;
using MegaCrit.Sts2.Core.Nodes.Rooms;
using MegaCrit.Sts2.Core.Nodes.Screens.Overlays;
using MegaCrit.Sts2.Core.Runs;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class MobileTapPreviewPatches
{
    private const float DirectDragThreshold = 30f;
    private static readonly ConditionalWeakTable<NPlayerHand, PreviewState> States = new();
    private static readonly ConditionalWeakTable<NCardHolder, PinnedState> PinnedHolders = new();
    private static readonly HashSet<int> ConnectedCreatures = new();
    private static readonly MethodInfo StartCardPlayMethod = typeof(NPlayerHand).GetMethod("StartCardPlay", BindingFlags.NonPublic | BindingFlags.Instance, null, new[] { typeof(NHandCardHolder), typeof(bool) }, null);

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NGame), "_Input", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(GameInputPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "OnHolderPressed", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(OnHolderPressedPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "StartCardPlay", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(StartCardPlayPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "CancelAllCardPlay", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(ClearPinnedPrefix)));
        PatchHelper.Patch(harmony, typeof(NPlayerHand), "OnCombatEnded", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(ClearPinnedPrefix)));
        PatchHelper.Patch(harmony, typeof(NCardHolder), "RefreshFocusState", prefix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(CardHolderRefreshFocusStatePrefix)));
        PatchHelper.Patch(harmony, typeof(NCreature), "_Ready", postfix: PatchHelper.Method(typeof(MobileTapPreviewPatches), nameof(CreatureReadyPostfix)));
    }

    public static bool GameInputPrefix(NGame __instance, InputEvent inputEvent)
    {
        try
        {
            if (!OS.HasFeature("mobile"))
                return true;
            var hand = NPlayerHand.Instance;
            if (hand == null || !IsCombatInputActive())
                return true;
            if (HandleMobileTapPreviewDirectDrag(hand, inputEvent) || HandleMobilePinnedCardPlayZoneTap(hand, inputEvent))
            {
                __instance.GetViewport()?.SetInputAsHandled();
                return false;
            }
            if (HandleMobilePinnedCardCancelTap(hand, inputEvent))
                __instance.GetViewport()?.SetInputAsHandled();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Mobile tap preview global input failed: {exception.Message}");
        }
        return true;
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
                ClearDirectDrag(state);
                switch (GetRetapAction())
                {
                    case RetapAction.Play:
                        if (ShouldPlayPinnedCardOnTap(handHolder.CardNode?.Model))
                        {
                            TryPlayPinnedCardOnTap(__instance, handHolder);
                            return false;
                        }
                        ClearPinned(__instance, keepDragCandidate: true);
                        return true;
                    case RetapAction.None:
                        return false;
                    case RetapAction.PutDown:
                    default:
                        ClearPinned(__instance);
                        return false;
                }
            }

            SetPinned(__instance, handHolder, armDirectDrag: true);
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
        ClearPinned(__instance, keepDragCandidate: true);
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
            if (__instance.CardModel != null)
                RunManager.Instance.HoveredModelTracker.OnLocalCardHovered(__instance.CardModel);
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Pinned card refresh failed: {exception.Message}");
            return true;
        }
    }

    public static void CreatureReadyPostfix(NCreature __instance)
    {
        try
        {
            var hitbox = __instance.Hitbox;
            if (hitbox == null || !ConnectedCreatures.Add((int)__instance.GetInstanceId()))
                return;
            hitbox.Connect(Control.SignalName.GuiInput, Callable.From<InputEvent>(inputEvent => OnCreatureHitboxGuiInput(__instance, inputEvent)));
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Creature touch-target bridge failed: {exception.Message}");
        }
    }

    public static void RepinAfterCancelledCardPlay(NHandCardHolder holder)
    {
        try
        {
            var hand = NPlayerHand.Instance;
            if (hand == null || holder == null || !IsTapPreviewEnabled() || hand.CurrentMode != NPlayerHand.Mode.Play || !IsSameValidHolder(holder, holder))
                return;
            if (!hand.IsAwaitingPlay(holder))
                SetPinned(hand, holder, armDirectDrag: false, log: false);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Repin cancelled card play failed: {exception.Message}");
        }
    }

    public static bool IsPinned(NHandCardHolder holder)
    {
        return holder != null && PinnedHolders.TryGetValue(holder, out _);
    }

    public static NCardHolder GetPinnedHolder(NPlayerHand hand)
    {
        return hand != null && States.TryGetValue(hand, out var state) ? state.PinnedHolder : null;
    }

    public static void ClearAllPinned()
    {
        try
        {
            var hand = NPlayerHand.Instance;
            if (hand != null)
                ClearPinned(hand);
        }
        catch
        {
        }
    }

    private static bool ShouldHandle(NPlayerHand hand, NCardHolder holder)
    {
        if (!IsTapPreviewEnabled())
            return false;
        if (holder is not NHandCardHolder handHolder || handHolder.CardNode == null)
            return false;
        if (!CombatManager.Instance.IsInProgress || NOverlayStack.Instance.ScreenCount > 0)
            return false;
        if (hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay || hand.IsAwaitingPlay(handHolder))
            return false;
        return AreCardActionsAllowed(hand);
    }

    private static bool IsTapPreviewEnabled()
    {
        return OS.HasFeature("mobile") && AndroidSettingsBridge.GetBool("touch_lift_preview", true);
    }

    private static bool IsCombatInputActive()
    {
        try
        {
            return CombatManager.Instance.IsInProgress
                && !CombatManager.Instance.IsOverOrEnding
                && NCombatRoom.Instance != null
                && NOverlayStack.Instance.ScreenCount <= 0;
        }
        catch
        {
            return false;
        }
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

    private static void SetPinned(NPlayerHand hand, NHandCardHolder holder, bool armDirectDrag, bool log = true)
    {
        var state = States.GetOrCreateValue(hand);
        if (state.PinnedHolder != null && !ReferenceEquals(state.PinnedHolder, holder))
            UnpinHolder(state.PinnedHolder);
        state.PinnedHolder = holder;
        PinnedHolders.GetOrCreateValue(holder);
        RunManager.Instance.HoveredModelTracker.OnLocalCardHovered(holder.CardModel);
        ForcePinnedPreview(holder);
        if (armDirectDrag)
            ArmDirectDrag(hand, holder);
        else
            ClearDirectDrag(state);
        SetLastFocusedHolderIndex(hand, holder);
        RefreshLayout(hand);
        if (log)
            PatchHelper.Log($"Pinned hand card preview: {holder.CardModel?.Id}");
    }

    private static void ClearPinned(NPlayerHand hand, bool keepDragCandidate = false)
    {
        if (!States.TryGetValue(hand, out var state) || state.PinnedHolder == null)
            return;
        var holder = state.PinnedHolder;
        UnpinHolder(holder);
        state.PinnedHolder = null;
        if (!keepDragCandidate)
            ClearDirectDrag(state);
        try
        {
            RunManager.Instance.HoveredModelTracker.OnLocalCardUnhovered();
        }
        catch
        {
        }
        RefreshLayout(hand);
    }

    private static void UnpinHolder(NCardHolder holder)
    {
        try
        {
            PinnedHolders.Remove(holder);
            var isHoveredField = typeof(NCardHolder).GetField("_isHovered", BindingFlags.NonPublic | BindingFlags.Instance);
            isHoveredField?.SetValue(holder, false);
            var isFocusedField = typeof(NCardHolder).GetField("_isFocused", BindingFlags.NonPublic | BindingFlags.Instance);
            if (isFocusedField?.GetValue(holder) is true)
            {
                isFocusedField.SetValue(holder, false);
                InvokeDoCardHoverEffects(holder, false);
            }
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

    private static void ArmDirectDrag(NPlayerHand hand, NHandCardHolder holder)
    {
        var state = States.GetOrCreateValue(hand);
        state.DragHolder = holder;
        state.DragStartPosition = hand.GetViewport()?.GetMousePosition() ?? Vector2.Zero;
    }

    private static void ClearDirectDrag(PreviewState state)
    {
        state.DragHolder = null;
        state.DragStartPosition = Vector2.Zero;
    }

    private static bool HandleMobileTapPreviewDirectDrag(NPlayerHand hand, InputEvent input)
    {
        if (!States.TryGetValue(hand, out var state) || state.DragHolder == null)
            return false;
        if (IsDirectDragRelease(input))
        {
            ClearDirectDrag(state);
            return false;
        }
        var holder = state.DragHolder;
        if (!IsTapPreviewEnabled() || hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay || !AreCardActionsAllowed(hand) || !IsSameValidHolder(state.PinnedHolder, holder) || hand.IsAwaitingPlay(holder))
        {
            ClearDirectDrag(state);
            return false;
        }
        if (!TryGetDragPosition(input, out var position))
            return false;
        var delta = position - state.DragStartPosition;
        if (delta.LengthSquared() < DirectDragThreshold * DirectDragThreshold || delta.Y > -DirectDragThreshold)
            return false;
        ClearDirectDrag(state);
        StartCardPlay(hand, holder, startedViaShortcut: false);
        return true;
    }

    private static bool HandleMobilePinnedCardPlayZoneTap(NPlayerHand hand, InputEvent input)
    {
        if (!IsTapPreviewEnabled() || hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay || !AreCardActionsAllowed(hand))
            return false;
        if (!IsPress(input) || !TryGetTapPosition(input, out var position))
            return false;
        if (!States.TryGetValue(hand, out var state) || state.PinnedHolder is not NHandCardHolder holder || !IsSameValidHolder(holder, holder) || hand.IsAwaitingPlay(holder) || !ShouldPlayPinnedCardOnTap(holder.CardNode?.Model))
            return false;
        if (position.Y >= hand.GetViewport().GetVisibleRect().Size.Y * 0.75f)
            return false;
        TryPlayPinnedCardOnTap(hand, holder);
        return true;
    }

    private static bool HandleMobilePinnedCardCancelTap(NPlayerHand hand, InputEvent input)
    {
        if (!IsTapPreviewEnabled() || hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay)
            return false;
        if (!IsPress(input) || !TryGetTapPosition(input, out var position))
            return false;
        if (!States.TryGetValue(hand, out var state) || state.PinnedHolder is not NHandCardHolder holder || !IsSameValidHolder(holder, holder) || hand.IsAwaitingPlay(holder))
            return false;
        if (position.Y < hand.GetViewport().GetVisibleRect().Size.Y * 0.75f || IsPositionOverAnyHandCard(hand, position))
            return false;
        ClearPinned(hand);
        return true;
    }

    private static bool IsPositionOverAnyHandCard(NPlayerHand hand, Vector2 position)
    {
        foreach (var activeHolder in hand.ActiveHolders)
        {
            if (GodotObject.IsInstanceValid(activeHolder) && activeHolder.Visible && activeHolder.Hitbox.IsInsideTree() && activeHolder.Hitbox.GetGlobalRect().HasPoint(position))
                return true;
        }
        return false;
    }

    private static void OnCreatureHitboxGuiInput(NCreature creature, InputEvent inputEvent)
    {
        try
        {
            if (!IsPress(inputEvent))
                return;
            var hand = NPlayerHand.Instance;
            if (hand != null && TryStartPinnedCardPlayAtCreature(hand, creature))
                NTargetManager.Instance?.OnNodeHovered(creature);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Creature target tap failed: {exception.Message}");
        }
    }

    private static bool TryStartPinnedCardPlayAtCreature(NPlayerHand hand, NCreature creature)
    {
        if (!IsTapPreviewEnabled() || hand.CurrentMode != NPlayerHand.Mode.Play || hand.PeekButton.IsPeeking || hand.InCardPlay || !AreCardActionsAllowed(hand))
            return false;
        if (!States.TryGetValue(hand, out var state) || state.PinnedHolder is not NHandCardHolder holder || !IsSameValidHolder(holder, holder) || hand.IsAwaitingPlay(holder) || holder.CardNode == null)
            return false;
        if (!CanTargetPinnedCard(holder.CardNode.Model, creature.Entity))
            return false;
        StartCardPlay(hand, holder, startedViaShortcut: true);
        return true;
    }

    private static void StartCardPlay(NPlayerHand hand, NHandCardHolder holder, bool startedViaShortcut)
    {
        if (StartCardPlayMethod == null)
            return;
        ClearPinned(hand, keepDragCandidate: true);
        StartCardPlayMethod.Invoke(hand, new object[] { holder, startedViaShortcut });
    }

    private static bool ShouldPlayPinnedCardOnTap(CardModel card)
    {
        if (card == null)
            return false;
        return card.TargetType switch
        {
            TargetType.AnyEnemy or TargetType.AnyPlayer or TargetType.AnyAlly or TargetType.TargetedNoCreature => false,
            _ => true,
        };
    }

    private static void TryPlayPinnedCardOnTap(NPlayerHand hand, NHandCardHolder holder)
    {
        var model = holder.CardNode?.Model;
        if (model == null || !IsSameValidHolder(holder, holder))
            return;
        ClearPinned(hand);
        RunManager.Instance.HoveredModelTracker.OnLocalCardSelected(model);
        try
        {
            if (!model.TryManualPlay(null) && IsSameValidHolder(holder, holder))
                SetPinned(hand, holder, armDirectDrag: false, log: false);
        }
        finally
        {
            RunManager.Instance.HoveredModelTracker.OnLocalCardDeselected();
        }
    }

    private static bool CanTargetPinnedCard(CardModel card, Creature target)
    {
        if (card == null || target == null || !target.IsAlive)
            return false;
        return card.TargetType switch
        {
            TargetType.AnyEnemy => target.Side != card.Owner.Creature.Side,
            TargetType.AnyPlayer => target.IsPlayer,
            TargetType.AnyAlly => target.IsPlayer && !LocalContext.IsMe(target.Player),
            _ => false,
        };
    }

    private static bool TryGetDragPosition(InputEvent input, out Vector2 position)
    {
        if (input is InputEventMouseMotion mouseMotion)
        {
            position = mouseMotion.Position;
            return true;
        }
        if (input is InputEventScreenDrag screenDrag)
        {
            position = screenDrag.Position;
            return true;
        }
        position = Vector2.Zero;
        return false;
    }

    private static bool TryGetTapPosition(InputEvent input, out Vector2 position)
    {
        if (input is InputEventMouseButton mouseButton && mouseButton.ButtonIndex == MouseButton.Left)
        {
            position = mouseButton.Position;
            return true;
        }
        if (input is InputEventScreenTouch screenTouch)
        {
            position = screenTouch.Position;
            return true;
        }
        position = Vector2.Zero;
        return false;
    }

    private static bool IsPress(InputEvent input)
    {
        return input is InputEventMouseButton { ButtonIndex: MouseButton.Left, Pressed: true }
            || input is InputEventScreenTouch { Pressed: true };
    }

    private static bool IsDirectDragRelease(InputEvent input)
    {
        return input is InputEventMouseButton { ButtonIndex: MouseButton.Left, Pressed: false }
            || input is InputEventScreenTouch { Pressed: false };
    }

    private static void SetLastFocusedHolderIndex(NPlayerHand hand, NHandCardHolder holder)
    {
        try
        {
            typeof(NPlayerHand).GetField("_lastFocusedHolderIdx", BindingFlags.NonPublic | BindingFlags.Instance)?.SetValue(hand, holder.GetIndex());
        }
        catch
        {
        }
    }

    private static void RefreshLayout(NPlayerHand hand)
    {
        try
        {
            hand.ForceRefreshCardIndices();
        }
        catch
        {
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
        public NHandCardHolder DragHolder;
        public Vector2 DragStartPosition;
    }

    private sealed class PinnedState
    {
    }
}
