using System;
using System.Collections.Generic;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Assets;
using MegaCrit.Sts2.Core.Entities.Intents;
using MegaCrit.Sts2.Core.Nodes.Combat;

namespace STS2Mobile.Patches;

public static class IntentAnimationPatches
{
    private const float BobSpeed = Mathf.Pi;
    private const float BobDistance = 10f;
    private const float BobOffset = 8f;
    private const int AnimationFps = 24;

    private static readonly Dictionary<ulong, IntentAnimState> States = new();
    private static readonly BindingFlags InstanceFlags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance;

    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NIntent), "_Ready", postfix: PatchHelper.Method(typeof(IntentAnimationPatches), nameof(ReadyPostfix)));
        PatchHelper.Patch(harmony, typeof(NIntent), "_ExitTree", prefix: PatchHelper.Method(typeof(IntentAnimationPatches), nameof(ExitTreePrefix)));
        PatchHelper.Patch(harmony, typeof(NIntent), "UpdateIntent", postfix: PatchHelper.Method(typeof(IntentAnimationPatches), nameof(UpdateIntentPostfix)));
        PatchHelper.Patch(harmony, typeof(NIntent), "_Process", prefix: PatchHelper.Method(typeof(IntentAnimationPatches), nameof(ProcessPrefix)));
    }

    public static void ReadyPostfix(NIntent __instance)
    {
        try
        {
            StartBobTween(__instance);
            var state = GetState(__instance);
            state.AnimationStartTimeSeconds = GetCurrentTimeSeconds();
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Intent animation ready patch failed: {exception.Message}");
        }
    }

    public static void ExitTreePrefix(NIntent __instance)
    {
        try
        {
            GetState(__instance, create: false)?.BobTween?.Kill();
            States.Remove(__instance.GetInstanceId());
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Intent animation exit patch failed: {exception.Message}");
        }
    }

    public static void UpdateIntentPostfix(NIntent __instance)
    {
        try
        {
            var state = GetState(__instance);
            var animationName = GetAnimationName(__instance);
            if (!string.Equals(state.AnimationName, animationName, StringComparison.Ordinal))
            {
                state.AnimationName = animationName;
                state.AnimationFrame = null;
                state.AnimationStartTimeSeconds = GetCurrentTimeSeconds();
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Intent animation update patch failed: {exception.Message}");
        }
    }

    public static bool ProcessPrefix(NIntent __instance)
    {
        try
        {
            var state = GetState(__instance);
            var animationName = GetAnimationName(__instance);
            if (string.IsNullOrEmpty(animationName))
                return false;

            if (!string.Equals(state.AnimationName, animationName, StringComparison.Ordinal))
            {
                state.AnimationName = animationName;
                state.AnimationFrame = null;
                state.AnimationStartTimeSeconds = GetCurrentTimeSeconds();
            }

            var frameCount = IntentAnimData.GetAnimationFrameCount(animationName);
            if (frameCount <= 0)
                return false;

            var frame = (int)((GetCurrentTimeSeconds() - state.AnimationStartTimeSeconds) * AnimationFps) % frameCount;
            if (state.AnimationFrame != frame)
            {
                state.AnimationFrame = frame;
                var sprite = GetField<Sprite2D>(__instance, "_intentSprite");
                if (sprite != null)
                    sprite.Texture = PreloadManager.Cache.GetTexture2D(IntentAnimData.GetAnimationFrame(animationName, frame));
            }
            return false;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Intent animation process patch failed; falling back to vanilla: {exception.Message}");
            return true;
        }
    }

    private static void StartBobTween(NIntent intent)
    {
        var holder = GetField<Control>(intent, "_intentHolder");
        if (holder == null)
            return;

        var state = GetState(intent);
        state.BobTween?.Kill();

        var timeOffset = GetFieldValue<float>(intent, "_timeOffset");
        var phase = Mathf.PosMod(timeOffset, Mathf.Tau);
        holder.Position = new Vector2(holder.Position.X, GetBobPositionY(phase));

        var movingUp = phase < Mathf.Pi * 0.5f || phase >= Mathf.Pi * 1.5f;
        var targetPhase = movingUp ? Mathf.Pi * 0.5f : Mathf.Pi * 1.5f;
        if (movingUp && phase >= targetPhase)
            targetPhase += Mathf.Tau;

        var duration = (targetPhase - phase) / BobSpeed;
        if (duration <= 0.001f)
        {
            StartSteadyBobTween(intent, holder, state, movingUp);
            return;
        }

        state.BobTween = intent.CreateTween();
        state.BobTween.TweenProperty(holder, "position:y", movingUp ? GetBobTopY() : GetBobBottomY(), duration)
            .SetEase(Tween.EaseType.InOut)
            .SetTrans(Tween.TransitionType.Sine);
        state.BobTween.TweenCallback(Callable.From(() => StartSteadyBobTween(intent, holder, state, movingUp)));
    }

    private static void StartSteadyBobTween(NIntent intent, Control holder, IntentAnimState state, bool isAtTop)
    {
        if (!GodotObject.IsInstanceValid(intent) || !GodotObject.IsInstanceValid(holder))
            return;
        state.BobTween?.Kill();
        state.BobTween = intent.CreateTween().SetLoops();
        if (isAtTop)
        {
            state.BobTween.TweenProperty(holder, "position:y", GetBobBottomY(), 1.0).SetEase(Tween.EaseType.InOut).SetTrans(Tween.TransitionType.Sine);
            state.BobTween.TweenProperty(holder, "position:y", GetBobTopY(), 1.0).SetEase(Tween.EaseType.InOut).SetTrans(Tween.TransitionType.Sine);
        }
        else
        {
            state.BobTween.TweenProperty(holder, "position:y", GetBobTopY(), 1.0).SetEase(Tween.EaseType.InOut).SetTrans(Tween.TransitionType.Sine);
            state.BobTween.TweenProperty(holder, "position:y", GetBobBottomY(), 1.0).SetEase(Tween.EaseType.InOut).SetTrans(Tween.TransitionType.Sine);
        }
    }

    private static float GetBobPositionY(float phase) => -(Mathf.Sin(phase) * BobDistance + BobOffset);

    private static float GetBobTopY() => -(BobDistance + BobOffset);

    private static float GetBobBottomY() => BobDistance - BobOffset;

    private static double GetCurrentTimeSeconds() => Time.GetTicksUsec() * 1E-06;

    private static string GetAnimationName(NIntent intent) => GetFieldValue<string>(intent, "_animationName");

    private static IntentAnimState GetState(NIntent intent, bool create = true)
    {
        var id = intent.GetInstanceId();
        if (!States.TryGetValue(id, out var state) && create)
        {
            state = new IntentAnimState();
            States[id] = state;
        }
        return state;
    }

    private static T GetField<T>(object target, string name) where T : class
    {
        return target?.GetType().GetField(name, InstanceFlags)?.GetValue(target) as T;
    }

    private static T GetFieldValue<T>(object target, string name)
    {
        var value = target?.GetType().GetField(name, InstanceFlags)?.GetValue(target);
        return value is T typed ? typed : default;
    }

    private sealed class IntentAnimState
    {
        public Tween BobTween;
        public string AnimationName;
        public int? AnimationFrame;
        public double AnimationStartTimeSeconds;
    }
}
