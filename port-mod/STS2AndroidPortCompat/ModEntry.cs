using System;
using System.Runtime.InteropServices;
using Godot;
using Godot.Bridge;
using Godot.NativeInterop;
using HarmonyLib;
using STS2Mobile.Patches;

namespace STS2Mobile;

// Entry point for the Android compatibility patcher.  The patch order mirrors
// ../s2/.cache/StS2-Launcher_Mod_Manager as closely as this Java-shell based
// port allows: early diagnostics first, BaseLib listener before ModManager can
// load user DLLs, then the same startup/platform/release/settings/layout/input/
// mod-loader groups.  Project-specific Android settings/shader/quick-restart
// patches are layered after the reference groups.
public static class ModEntry
{
    private static bool _applied;
    private static Harmony _harmony;

    [UnmanagedCallersOnly]
    public static int InitializeGodotSharp(IntPtr godotDllHandle, IntPtr outManagedCallbacks, IntPtr unmanagedCallbacks, int unmanagedCallbacksSize)
    {
        try
        {
            DllImportResolver resolver = new GodotDllImportResolver(godotDllHandle).OnResolveDllImport;
            NativeLibrary.SetDllImportResolver(typeof(GodotObject).Assembly, resolver);
            NativeFuncs.Initialize(unmanagedCallbacks, unmanagedCallbacksSize);
            ManagedCallbacks.Create(outManagedCallbacks);
            Console.Error.WriteLine("[STS2Mobile] GodotSharp bootstrapped successfully");
            return 1;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"[STS2Mobile] GodotSharp bootstrap failed: {exception}");
            return 0;
        }
    }

    [UnmanagedCallersOnly]
    public static void Apply()
    {
        if (_applied)
            return;
        _applied = true;

        PatchHelper.Log("Initializing STS2Mobile Android port compatibility.");
        _harmony = new Harmony("com.sts2mobile");

        // Reference launcher applies this outside the game-type try/catch so it
        // also runs in bootstrap/launcher-only mode.
        RenderDiagnosticPatches.Apply(_harmony);

        try
        {
            // Keep BaseLib first.  It only registers an AssemblyLoad listener,
            // and must be present before ModManager loads BaseLib.dll.
            BaseLibCompatPatches.Apply(_harmony);
            ModelDbInitPatch.Apply(_harmony);
            PlatformPatches.Apply(_harmony);
            ReleaseInfoPatches.Apply(_harmony);

            // Reference launcher's mobile/default settings + UI scale patches,
            // plus this port's Java companion settings bridge.
            SettingsPatches.Apply(_harmony);
            AndroidSettingsPatches.Apply(_harmony);
            DisplaySettingsPatches.Apply(_harmony);
            AndroidFontCoveragePatches.Apply(_harmony);
            UiScalePatches.Apply(_harmony);

            // Reference mobile layout/input fixes.
            MobileLayoutPatches.Apply(_harmony);
            EventLayoutPatches.Apply(_harmony);
            MerchantLayoutPatches.Apply(_harmony);
            TouchInputPatches.Apply(_harmony);
            CardRewardPatches.Apply(_harmony);
            EarlyAccessDisclaimerPatches.Apply(_harmony);
            FeedbackScreenPatches.Apply(_harmony);
            CombatBackgroundPatches.Apply(_harmony);

            // This port's extra Android UI/input/shader/lifecycle additions.
            AndroidUiSafetyPatches.Apply(_harmony);
            ExternalSettingsPatches.Apply(_harmony);
            AndroidInGameSettingsPatches.Apply(_harmony);
            ShaderCompatibilityPatches.Apply(_harmony);
            AndroidInputCompatPatches.Apply(_harmony);
            MobileSelectionConfirmationPatches.Apply(_harmony);
            MobileTapPreviewPatches.Apply(_harmony);
            MobileHandLayoutPatches.Apply(_harmony);
            IntentAnimationPatches.Apply(_harmony);
            QuickRestartPatches.Apply(_harmony);
            LifecycleAndPerformancePatches.Apply(_harmony);

            // Reference launcher applies LAN before mod-loader; keep the same
            // relative order while deferring the heavy LAN patch set to menu time.
            LanMultiplayerBootstrapPatches.Apply(_harmony);
            ModLoaderPatches.Apply(_harmony);
            SaveDiagnosticPatches.Apply(_harmony);

            PatchHelper.Log("All Android port compatibility patches applied.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Patch application failed: {exception}");
        }
    }
}
