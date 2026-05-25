using System;
using System.Reflection;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;

namespace STS2Mobile.Patches;

// Scales combat room backgrounds and creature positions for non-standard aspect ratios.
// On taller screens the background container is scaled up so no black bars appear
// behind the combat scene.
public static class CombatBackgroundPatches
{
    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var combatRoomType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.Rooms.NCombatRoom");
        if (combatRoomType != null)
        {
            PatchHelper.Patch(
                harmony,
                combatRoomType,
                "SetUpBackground",
                postfix: PatchHelper.Method(
                    typeof(CombatBackgroundPatches),
                    nameof(SetUpBackgroundPostfix)
                )
            );

            PatchHelper.Patch(
                harmony,
                combatRoomType,
                "_Ready",
                postfix: PatchHelper.Method(
                    typeof(CombatBackgroundPatches),
                    nameof(CombatRoomReadyPostfix)
                )
            );
        }
    }

    public static void SetUpBackgroundPostfix(object __instance)
    {
        try
        {
            var room = (Control)__instance;
            ApplyBgScale(room);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CombatBg] SetUpBackgroundPostfix failed: {ex.Message}");
        }
    }

    public static void CombatRoomReadyPostfix(object __instance)
    {
        try
        {
            var room = (Control)__instance;
            var adjustMethod = AccessTools.Method(
                __instance.GetType(),
                "AdjustCreatureScaleForAspectRatio"
            );

            // Deferred so layout dimensions are finalized before adjusting.
            DeferAdjust(room, adjustMethod);

            // Re-apply when UI scale changes mid-combat.
            UiScalePatches.UiScaleChanged += OnScaleChanged;

            void OnScaleChanged()
            {
                if (!GodotObject.IsInstanceValid(room) || !room.IsInsideTree())
                {
                    UiScalePatches.UiScaleChanged -= OnScaleChanged;
                    return;
                }

                ApplyBgScale(room);
                DeferAdjust(room, adjustMethod);
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"[CombatBg] CombatRoomReadyPostfix failed: {ex.Message}");
        }
    }

    private static void ApplyBgScale(Control room)
    {
        if (SaveManager.Instance.SettingsSave.AspectRatioSetting != AspectRatioSetting.Auto)
            return;

        var window = room.GetTree().Root;
        var vpSize = window.GetVisibleRect().Size;
        float vpRatio = vpSize.X / vpSize.Y;

        // Background images are ~2.14:1 (2764x1296). Scale up on taller screens.
        const float bgRatio = 2764.8f / 1296f;

        var bgContainer = room.GetNodeOrNull<Control>("%BgContainer");
        if (bgContainer == null)
            return;

        if (vpRatio >= bgRatio)
        {
            bgContainer.Scale = Vector2.One;
            return;
        }

        float scaleNeeded = bgRatio / vpRatio;
        bgContainer.Scale = new Vector2(scaleNeeded, scaleNeeded);
        PatchHelper.Log(
            $"[CombatBg] Scaled BgContainer by {scaleNeeded:F3} for viewport ratio {vpRatio:F2}"
        );
    }

    private static void DeferAdjust(Control room, MethodInfo adjustMethod)
    {
        if (adjustMethod == null)
            return;

        Callable
            .From(() =>
            {
                try
                {
                    if (GodotObject.IsInstanceValid(room) && room.IsInsideTree())
                        adjustMethod.Invoke(room, null);
                }
                catch (Exception ex)
                {
                    PatchHelper.Log($"[CombatBg] Deferred adjust failed: {ex.Message}");
                }
            })
            .CallDeferred();
    }
}
