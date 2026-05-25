using System;
using System.Collections.Generic;
using System.Linq;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Mobile-compat shim for BaseLib v3.x.
//
// BaseLib's BaseLib.Utils.Patching.AsyncMethodCall.Create transpiler injects new
// yield states into compiler-emitted async state-machine MoveNext methods. On
// Android (Godot Mono + MonoMod/Cecil-based emit) this can crash the native
// renderer with a Godot StringName double-unref, e.g.
// "BUG: Unreferenced static string to 0: _initialize".  The crash happens after
// BaseLib starts patching StateMachineType methods, before the game reaches the
// menu.
//
// This compatibility shim prefixes AsyncMethodCall.Create and returns the
// original IL unchanged.  Effect:
//   - BaseLib loads (DLL + PCK init succeeds)
//   - Node factories, config UI, CustomPile patch, content patches: should work
//   - BaseLib async hooks: disabled/no-op
//   - Mods that depend on BaseLib async hook callbacks may load but those hooks
//     will not fire
//
// This is a degraded-mode workaround copied from the working mobile launcher;
// it is intentionally narrower than disabling BaseLib entirely.
public static class BaseLibCompatPatches
{
    private static Harmony _harmony;
    private static bool _patched;

    public static void Apply(Harmony harmony)
    {
        _harmony = harmony;
        AppDomain.CurrentDomain.AssemblyLoad += OnAssemblyLoad;
        PatchHelper.Log("BaseLibCompatPatches: registered AssemblyLoad listener for BaseLib");
    }

    private static void OnAssemblyLoad(object sender, AssemblyLoadEventArgs args)
    {
        if (_patched) return;
        var asmName = args.LoadedAssembly.GetName().Name;
        if (asmName != "BaseLib") return;
        TryPatchBaseLib(args.LoadedAssembly);
    }

    private static void TryPatchBaseLib(System.Reflection.Assembly baseLibAssembly)
    {
        if (_patched)
            return;
        try
        {
            var asyncMethodCallType = baseLibAssembly.GetType("BaseLib.Utils.Patching.AsyncMethodCall");
            if (asyncMethodCallType == null)
            {
                PatchHelper.Log("BaseLibCompat: AsyncMethodCall type not found in BaseLib assembly");
                return;
            }

            var createMethod = AccessTools.Method(asyncMethodCallType, "Create");
            if (createMethod == null)
            {
                PatchHelper.Log("BaseLibCompat: AsyncMethodCall.Create method not found");
                return;
            }

            var prefix = AccessTools.Method(typeof(BaseLibCompatPatches), nameof(AsyncMethodCallCreatePrefix));
            _harmony.Patch(createMethod, prefix: new HarmonyMethod(prefix));
            _patched = true;
            PatchHelper.Log("Patched BaseLib.Utils.Patching.AsyncMethodCall.Create (state-machine hooks disabled for mobile compat)");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"BaseLibCompat: failed to patch on load: {exception.Message}");
        }
    }

    public static bool AsyncMethodCallCreatePrefix(IEnumerable<CodeInstruction> code, ref List<CodeInstruction> __result)
    {
        Console.WriteLine("[BaseLibCompat] Skipping AsyncMethodCall.Create (mobile workaround) — async hook will not fire");
        __result = code.ToList();
        return false;
    }
}
