using System;
using System.Runtime.InteropServices;
using Godot;
using Godot.Bridge;
using Godot.NativeInterop;
using HarmonyLib;
using STS2Mobile.Patches;

namespace STS2Mobile;

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
            Console.Error.WriteLine("[STS2Mobile] GodotSharp initialized");
            return 1;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"[STS2Mobile] GodotSharp init failed: {exception}");
            return 0;
        }
    }

    [UnmanagedCallersOnly]
    public static void Apply()
    {
        if (_applied)
            return;
        _applied = true;
        _harmony = new Harmony("com.wsdx233.sts2.android_port_compat");
        PatchHelper.Log("Applying Android port compatibility skeleton.");
        try
        {
            PlatformPatches.Apply(_harmony);
            ReleaseInfoPatches.Apply(_harmony);
            AndroidSettingsPatches.Apply(_harmony);
            DisplaySettingsPatches.Apply(_harmony);
            ExternalSettingsPatches.Apply(_harmony);
            ShaderCompatibilityPatches.Apply(_harmony);
            TouchInputPatches.Apply(_harmony);
            AndroidInputCompatPatches.Apply(_harmony);
            MobileTapPreviewPatches.Apply(_harmony);
            MobileHandLayoutPatches.Apply(_harmony);
            QuickRestartPatches.Apply(_harmony);
            LifecycleAndPerformancePatches.Apply(_harmony);
            LanMultiplayerPatches.Apply(_harmony);
            ModLoaderPatches.Apply(_harmony);
            PatchHelper.Log("Android port compatibility skeleton applied.");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Patch application failed: {exception}");
        }
    }
}
