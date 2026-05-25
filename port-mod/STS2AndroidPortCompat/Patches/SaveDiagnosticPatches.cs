using System;
using System.Collections.Generic;
using System.Reflection;
using System.Reflection.Emit;
using HarmonyLib;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Saves.Managers;
using MegaCrit.Sts2.Core.Saves.Migrations;

namespace STS2Mobile.Patches;

// Injects diagnostic logging into ProgressSaveManager.LoadProgress() via transpiler
// to trace why the game creates a fresh default save instead of loading the pulled one.
public static class SaveDiagnosticPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(
            harmony,
            typeof(ProgressSaveManager),
            "LoadProgress",
            transpiler: PatchHelper.Method(
                typeof(SaveDiagnosticPatches),
                nameof(LoadProgressTranspiler)
            )
        );
    }

    public static IEnumerable<CodeInstruction> LoadProgressTranspiler(
        IEnumerable<CodeInstruction> instructions
    )
    {
        var codes = new List<CodeInstruction>(instructions);
        bool injectedLoadSave = false;
        bool injectedCreateDefault = false;

        for (int i = 0; i < codes.Count; i++)
        {
            var ci = codes[i];

            // Match callvirt MigrationManager::LoadSave<SerializableProgress>.
            // DeclaringType check uses Name to handle generic type resolution differences.
            if (
                !injectedLoadSave
                && (ci.opcode == OpCodes.Call || ci.opcode == OpCodes.Callvirt)
                && ci.operand is MethodInfo loadMethod
                && loadMethod.Name == "LoadSave"
                && loadMethod.DeclaringType?.Name == nameof(MigrationManager)
            )
            {
                codes.Insert(i + 1, new CodeInstruction(OpCodes.Dup));
                codes.Insert(
                    i + 2,
                    new CodeInstruction(
                        OpCodes.Call,
                        AccessTools.Method(typeof(SaveDiagnosticPatches), nameof(LogLoadResult))
                    )
                );
                PatchHelper.Log($"[Diag] Injected LoadSave logger at IL[{i}]");
                injectedLoadSave = true;
                i += 2;
            }

            // Match call ProgressState::CreateDefault.
            if (
                !injectedCreateDefault
                && (ci.opcode == OpCodes.Call || ci.opcode == OpCodes.Callvirt)
                && ci.operand is MethodInfo createMethod
                && createMethod.Name == "CreateDefault"
                && createMethod.DeclaringType?.Name == nameof(ProgressState)
            )
            {
                codes.Insert(
                    i,
                    new CodeInstruction(
                        OpCodes.Call,
                        AccessTools.Method(
                            typeof(SaveDiagnosticPatches),
                            nameof(LogCreatingDefault)
                        )
                    )
                );
                PatchHelper.Log($"[Diag] Injected CreateDefault logger at IL[{i}]");
                injectedCreateDefault = true;
                i++;
            }
        }

        if (!injectedLoadSave)
            PatchHelper.Log("[Diag] WARNING: LoadSave call not found in LoadProgress IL");
        if (!injectedCreateDefault)
            PatchHelper.Log("[Diag] WARNING: CreateDefault call not found in LoadProgress IL");

        return codes;
    }

    public static void LogLoadResult(object result)
    {
        try
        {
            var type = result.GetType();
            var status = type.GetProperty("Status")?.GetValue(result);
            var success = type.GetProperty("Success")?.GetValue(result);
            var saveData = type.GetProperty("SaveData")?.GetValue(result);
            var error = type.GetProperty("ErrorMessage")?.GetValue(result);

            PatchHelper.Log(
                $"[Diag] LoadProgress result: Status={status}, "
                    + $"Success={success}, HasData={saveData != null}, "
                    + $"Error={error ?? "none"}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[Diag] LogLoadResult failed: {ex.Message}");
        }
    }

    public static void LogCreatingDefault()
    {
        PatchHelper.Log(
            "[Diag] LoadProgress: creating default empty progress (load failed or file missing)"
        );
    }
}
