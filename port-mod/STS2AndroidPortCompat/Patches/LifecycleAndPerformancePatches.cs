using System;
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

        AndroidStartupLoadingScreen startupLoadingScreen = await ShowStartupWarmupScreenAndLoadAssetsAsync(game, keepVisibleAfterWarmup: !PreloadManager.Enabled);

        if (startupLoadingScreen != null)
        {
            startupLoadingScreen.SetStatus("Loading main menu...", "Creating main menu scene", 0.95f);
            await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
        }

        await CallPrivateTask(game, "LoadMainMenu");
        await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
        await WarmMainMenuHotspotsAsync(game, startupLoadingScreen);

        if (startupLoadingScreen != null)
        {
            startupLoadingScreen.SetStatus("Startup complete", "Launching main menu", 1f);
            await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
            await DismissStartupLoadingScreenAsync(game, startupLoadingScreen);
        }

        PatchHelper.Log($"Android startup preload flow complete at {Time.GetTicksMsec():N0}ms.");
        LogResourceStats(PreloadManager.Enabled ? "main menu loaded (startup warmup complete)" : "main menu loaded (essential)");
        _ = TaskHelper.RunSafely(LoadDeferredStartupAssetsAndroidAsync());
        TryCheckCommandLineJoin(game);
    }

    private static async Task LoadDeferredStartupAssetsAndroidAsync()
    {
        OneTimeInitialization.ExecuteDeferred();
        await Task.Yield();
        PatchHelper.Log("Android deferred initialization complete; bulk common/main-menu preload remains disabled to match the source-port startup flow.");
        LogResourceStats("main menu loaded (deferred init complete)");
    }

    private static async Task<AndroidStartupLoadingScreen> ShowStartupWarmupScreenAndLoadAssetsAsync(NGame game, bool keepVisibleAfterWarmup)
    {
        bool transitionVisible = false;
        if (game.Transition != null)
        {
            transitionVisible = game.Transition.Visible;
            game.Transition.Visible = false;
        }

        var startupLoadingScreen = new AndroidStartupLoadingScreen { Name = "AndroidStartupLoadingScreen" };
        game.AddChild(startupLoadingScreen);
        await startupLoadingScreen.PresentAsync();

        if (PreloadManager.Enabled)
        {
            AssetLoadingSession session = await StartAndroidStartupWarmupAsync();
            await startupLoadingScreen.RunWarmup(session);
            LogResourceStats("startup warmup complete");
        }
        else
        {
            startupLoadingScreen.SetStatus("Loading main menu...", "Preload disabled", 0.25f);
            await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
        }

        if (!keepVisibleAfterWarmup)
        {
            startupLoadingScreen.QueueFree();
            if (game.Transition != null)
                game.Transition.Visible = transitionVisible;
            await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
            return null;
        }

        startupLoadingScreen.SetMeta("restore_transition_visible", transitionVisible);
        return startupLoadingScreen;
    }

    private static async Task DismissStartupLoadingScreenAsync(NGame game, AndroidStartupLoadingScreen startupLoadingScreen)
    {
        bool transitionVisible = startupLoadingScreen.HasMeta("restore_transition_visible") && (bool)startupLoadingScreen.GetMeta("restore_transition_visible");
        startupLoadingScreen.QueueFree();
        if (game.Transition != null)
            game.Transition.Visible = transitionVisible;
        await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
    }

    private static Task<AssetLoadingSession> StartAndroidStartupWarmupAsync()
    {
        PatchHelper.Log("Startup warmup: skipping bulk asset preload; scene-only VFX warmup will run on the loading screen.");
        return Task.FromResult(AssetLoadingSession.Empty());
    }

    private static async Task WarmMainMenuHotspotsAsync(NGame game, AndroidStartupLoadingScreen startupLoadingScreen)
    {
        try
        {
            object mainMenu = game.GetType().GetProperty("MainMenu", BindingFlags.Public | BindingFlags.Instance)?.GetValue(game);
            if (mainMenu == null)
                return;
            object submenuStack = mainMenu.GetType().GetProperty("SubmenuStack", BindingFlags.Public | BindingFlags.Instance)?.GetValue(mainMenu);
            if (submenuStack == null)
                return;
            MethodInfo getSubmenuType = submenuStack.GetType().GetMethod("GetSubmenuType", new[] { typeof(Type) });
            if (getSubmenuType == null)
                return;

            string[] hotTypeNames =
            {
                "MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NSingleplayerSubmenu",
                "MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NMultiplayerSubmenu",
            };

            for (int i = 0; i < hotTypeNames.Length; i++)
            {
                Type submenuType = game.GetType().Assembly.GetType(hotTypeNames[i]);
                if (submenuType == null)
                    continue;
                startupLoadingScreen?.SetStatus("Loading main menu...", $"Preparing menu {i + 1}/{hotTypeNames.Length}", 0.975f + (0.01f * i));
                getSubmenuType.Invoke(submenuStack, new object[] { submenuType });
                await game.ToSignal(game.GetTree(), SceneTree.SignalName.ProcessFrame);
            }

            PatchHelper.Log("Main menu hotspot preload complete: singleplayer/multiplayer submenus instantiated.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Main menu hotspot preload skipped: {exception.Message}");
        }
    }

    private static void LogResourceStats(string context)
    {
        try
        {
            ulong staticMemoryUsage = OS.GetStaticMemoryUsage();
            ulong videoMemoryUsage = RenderingServer.GetRenderingInfo(RenderingServer.RenderingInfo.VideoMemUsed);
            int objectCount = (int)Performance.GetMonitor(Performance.Monitor.ObjectCount);
            int resourceCount = (int)Performance.GetMonitor(Performance.Monitor.ObjectResourceCount);
            int nodeCount = (int)Performance.GetMonitor(Performance.Monitor.ObjectNodeCount);
            int cachedAssets = PreloadManager.Cache.GetCacheKeys().Count();
            PatchHelper.Log($"[Startup] Resource stats ({context}): StaticMem={FormatBytes(staticMemoryUsage)}, VRAM={FormatBytes(videoMemoryUsage)}, Objects={objectCount:N0}, Resources={resourceCount:N0}, Nodes={nodeCount:N0}, CachedAssets={cachedAssets:N0}");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Resource stats failed ({context}): {exception.Message}");
        }
    }

    private static string FormatBytes(ulong bytes)
    {
        string[] units = { "B", "KB", "MB", "GB" };
        int index = 0;
        double value = bytes;
        while (value >= 1024.0 && index < units.Length - 1)
        {
            value /= 1024.0;
            index++;
        }
        return $"{value:0.#}{units[index]}";
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
