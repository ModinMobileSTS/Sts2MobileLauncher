using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;
using Godot;
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
        PatchHelper.Patch(harmony, typeof(NGame), "LaunchMainMenu", prefix: PatchHelper.Method(typeof(LifecycleAndPerformancePatches), nameof(LaunchMainMenuPrefix)));
        PatchHelper.Patch(harmony, typeof(NGame), "LoadDeferredStartupAssetsAsync", prefix: PatchHelper.Method(typeof(LifecycleAndPerformancePatches), nameof(LoadDeferredStartupAssetsPrefix)));

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

    public static bool LaunchMainMenuPrefix(NGame __instance, bool skipLogo, ref Task __result)
    {
        if (!OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase))
            return true;
        __result = LaunchMainMenuAndroidAsync(__instance, skipLogo);
        return false;
    }

    public static bool LoadDeferredStartupAssetsPrefix(ref Task __result)
    {
        if (!OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase))
            return true;
        __result = LoadDeferredStartupAssetsAndroidAsync();
        return false;
    }

    private static async Task LaunchMainMenuAndroidAsync(NGame game, bool skipLogo)
    {
        PatchHelper.Log($"Android startup preload flow begin (skipLogo={skipLogo}, preload={PreloadManager.Enabled}).");
        Node logoAnimation = null;
        if (skipLogo)
        {
            await PreloadManager.LoadMainMenuEssentials();
        }
        else
        {
            await PreloadManager.LoadLogoAnimation();
            logoAnimation = CreateSceneNode("MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NLogoAnimation");
            if (logoAnimation != null)
                SetCurrentRootScene(game, logoAnimation);
            await PreloadManager.LoadMainMenuEssentials();
        }

        if (logoAnimation != null)
        {
            await TryPlayLogoAsync(game, logoAnimation);
        }

        if (PreloadManager.Enabled)
        {
            var session = StartAndroidStartupWarmup();
            await WaitForSessionAsync(game, session, "AndroidStartupWarmup");
        }

        await CallPrivateTask(game, "LoadMainMenu");
        PatchHelper.Log($"Android startup preload flow complete at {Time.GetTicksMsec():N0}ms.");
        _ = TaskHelper.RunSafely(LoadDeferredStartupAssetsAndroidAsync());
        TryCheckCommandLineJoin(game);
    }

    private static async Task LoadDeferredStartupAssetsAndroidAsync()
    {
        OneTimeInitialization.ExecuteDeferred();
        if (PreloadManager.Enabled)
        {
            await PreloadManager.LoadCommonAndMainMenuAssets();
            PatchHelper.Log($"Android deferred preload complete; cached={PreloadManager.Cache.GetCacheKeys().Count()}.");
        }
        else
        {
            PatchHelper.Log("Android deferred preload skipped because preload is disabled; deferred initialization still ran.");
        }
    }

    private static AssetLoadingSession StartAndroidStartupWarmup()
    {
        var paths = new HashSet<string>();
        AddAll(paths, AssetSets.CommonAssets);
        AddAll(paths, AssetSets.MainMenuSet);
        AddAll(paths, GetAllVfxScenePaths());
        PatchHelper.Log($"Android startup warmup preloading {paths.Count:N0} common/menu/VFX assets.");
        var session = PreloadManager.Cache.CreateSession("AndroidStartupWarmup", paths);
        NAssetLoader.Instance.LoadInTheBackground(session);
        return session;
    }

    private static async Task WaitForSessionAsync(Node node, AssetLoadingSession session, string name)
    {
        if (session == null)
            return;
        while (!session.IsCompleted)
            await node.ToSignal(node.GetTree(), SceneTree.SignalName.ProcessFrame);
        PatchHelper.Log($"{name} preload session completed.");
    }

    private static IEnumerable<string> GetAllVfxScenePaths()
    {
        var paths = new HashSet<string>(StringComparer.Ordinal);
        CollectScenePathsRecursive("res://scenes/vfx", paths);
        return paths;
    }

    private static void CollectScenePathsRecursive(string directoryPath, HashSet<string> target)
    {
        using var dir = DirAccess.Open(directoryPath);
        if (dir == null)
            return;
        foreach (var file in dir.GetFiles())
        {
            if (file.EndsWith(".tscn", StringComparison.OrdinalIgnoreCase))
                target.Add(directoryPath + "/" + file);
        }
        foreach (var directory in dir.GetDirectories())
        {
            if (!string.IsNullOrWhiteSpace(directory))
                CollectScenePathsRecursive(directoryPath + "/" + directory, target);
        }
    }

    private static void AddAll(HashSet<string> target, IEnumerable<string> paths)
    {
        if (paths == null)
            return;
        foreach (var path in paths)
        {
            if (!string.IsNullOrWhiteSpace(path))
                target.Add(path);
        }
    }

    private static Node CreateSceneNode(string typeName)
    {
        var type = typeof(NGame).Assembly.GetType(typeName);
        var method = type?.GetMethod("Create", BindingFlags.Public | BindingFlags.Static);
        return method?.Invoke(null, null) as Node;
    }

    private static void SetCurrentRootScene(NGame game, Node scene)
    {
        var container = game.RootSceneContainer;
        if (container == null || scene is not Control control)
            return;
        container.SetCurrentScene(control);
    }

    private static async Task TryPlayLogoAsync(NGame game, Node logoAnimation)
    {
        try
        {
            var transition = game.Transition;
            var fadeIn = transition?.GetType().GetMethod("FadeIn", new[] { typeof(float), typeof(string), typeof(System.Threading.CancellationToken?) });
            if (fadeIn != null)
                await (Task)fadeIn.Invoke(transition, new object[] { 0.8f, "res://materials/transitions/fade_transition_mat.tres", null });
            var playAnimation = logoAnimation.GetType().GetMethod("PlayAnimation", new[] { typeof(System.Threading.CancellationToken) });
            if (playAnimation != null)
                await (Task)playAnimation.Invoke(logoAnimation, new object[] { System.Threading.CancellationToken.None });
            var fadeOut = transition?.GetType().GetMethod("FadeOut", new[] { typeof(float), typeof(string), typeof(System.Threading.CancellationToken?) });
            if (fadeOut != null)
                await (Task)fadeOut.Invoke(transition, new object[] { 0.8f, "res://materials/transitions/fade_transition_mat.tres", null });
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android logo flow failed; continuing to main menu: {exception.Message}");
        }
    }

    private static Task CallPrivateTask(object target, string methodName)
    {
        var method = target.GetType().GetMethod(methodName, BindingFlags.NonPublic | BindingFlags.Instance, null, new[] { typeof(bool) }, null)
            ?? target.GetType().GetMethod(methodName, BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null);
        if (method == null)
        {
            PatchHelper.Log($"Android startup reflection failed: {target.GetType().Name}.{methodName} not found.");
            return Task.CompletedTask;
        }
        var parameters = method.GetParameters().Length == 1 ? new object[] { false } : null;
        return method.Invoke(target, parameters) as Task ?? Task.CompletedTask;
    }

    private static void TryCheckCommandLineJoin(NGame game)
    {
        try
        {
            var field = typeof(NGame).GetField("_joinCallbackHandler", BindingFlags.NonPublic | BindingFlags.Instance);
            var handler = field?.GetValue(game);
            handler?.GetType().GetMethod("CheckForCommandLineJoin", BindingFlags.Public | BindingFlags.Instance)?.Invoke(handler, null);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Android command-line join check failed: {exception.Message}");
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
