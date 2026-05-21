using System;
using HarmonyLib;
using MegaCrit.Sts2.Core.Assets;
using MegaCrit.Sts2.Core.Helpers;
using MegaCrit.Sts2.Core.Nodes;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class LifecycleAndPerformancePatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(OneTimeInitialization), "ExecuteVeryEarly", postfix: PatchHelper.Method(typeof(LifecycleAndPerformancePatches), nameof(ExecuteVeryEarlyPostfix)));

        var muteHandlerType = typeof(NGame).Assembly.GetType("MegaCrit.Sts2.Core.Nodes.NMuteInBackgroundHandler");
        if (muteHandlerType != null)
        {
            // Do not patch inherited Godot lifecycle wrappers (_Ready/_Process) or _Notification
            // in the imported PC assembly.  On Android/Godot 4.5 those Harmony lookups force
            // GodotSharp MethodName static constructors for Resource/ResourceFormat* to run while
            // the native engine is still initializing, which aborts with StringName refcount errors
            // such as "Unreferenced static string to 0: _recognize_path" / "_reset_state".
            // The vanilla PC notification handler is good enough for startup; keep the early
            // preload bridge below and defer fuller background-audio parity until runtime is stable.
            PatchHelper.Log("Background audio lifecycle patch disabled on imported PC assembly for Android startup safety.");
        }
    }

    public static void ExecuteVeryEarlyPostfix()
    {
        try
        {
            PreloadManager.Enabled = AndroidSettingsBridge.GetBool("preload_enabled", PreloadManager.Enabled);
            PatchHelper.Log($"Preload enabled from Android companion settings: {PreloadManager.Enabled}");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ExecuteVeryEarlyPostfix failed: {exception.Message}");
        }
    }

    public static void MuteReadyPostfix(object __instance)
    {
        try
        {
            if (!IsAudioCompatibilityMode())
            {
                var node = (Godot.Node)__instance;
                node.ProcessMode = Godot.Node.ProcessModeEnum.Always;
                node.SetProcess(true);
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"MuteReadyPostfix failed: {exception.Message}");
        }
    }

    public static bool MuteProcessPrefix() => IsAudioCompatibilityMode();

    public static bool MuteNotificationPrefix(object __instance, int what)
    {
        if (IsAudioCompatibilityMode())
            return true;
        try
        {
            switch (what)
            {
                case (int)Godot.Node.NotificationWMWindowFocusOut:
                case (int)Godot.Node.NotificationApplicationPaused:
                case (int)Godot.Node.NotificationApplicationFocusOut:
                    CallPrivate(__instance, "Mute");
                    return false;
                case (int)Godot.Node.NotificationWMWindowFocusIn:
                case (int)Godot.Node.NotificationApplicationResumed:
                case (int)Godot.Node.NotificationApplicationFocusIn:
                    CallPrivate(__instance, "Unmute");
                    return false;
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"MuteNotificationPrefix failed: {exception.Message}");
        }
        return true;
    }

    private static bool IsAudioCompatibilityMode() => AndroidSettingsBridge.GetBool("audio_compatibility_mode", false);

    private static void CallPrivate(object target, string methodName)
    {
        target.GetType().GetMethod(methodName, System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)?.Invoke(target, null);
    }
}
