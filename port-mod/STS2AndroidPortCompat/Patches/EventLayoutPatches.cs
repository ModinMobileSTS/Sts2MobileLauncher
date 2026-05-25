using System;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Adjusts event screen layout and button sizes for scaled viewports. Shifts the
// event panel upward and clamps button widths to the available viewport width
// when UI scale is above 100%.
public static class EventLayoutPatches
{
    // Original offsets from the scene file, used to reset when scale is at 100%.
    private const float OriginalOffsetLeft = -38f;
    private const float OriginalOffsetRight = 762f;
    private const float OriginalButtonWidth = 800f;

    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var eventLayoutType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.Events.NEventLayout");
        if (eventLayoutType != null)
        {
            PatchHelper.Patch(
                harmony,
                eventLayoutType,
                "_Ready",
                postfix: PatchHelper.Method(typeof(EventLayoutPatches), nameof(ReadyPostfix))
            );

            PatchHelper.Patch(
                harmony,
                eventLayoutType,
                "AddOptions",
                postfix: PatchHelper.Method(typeof(EventLayoutPatches), nameof(AddOptionsPostfix))
            );
        }
    }

    public static void ReadyPostfix(object __instance)
    {
        try
        {
            var layout = (Control)__instance;
            ApplyLayout(layout);

            UiScalePatches.UiScaleChanged += OnScaleChanged;

            void OnScaleChanged()
            {
                if (!GodotObject.IsInstanceValid(layout) || !layout.IsInsideTree())
                {
                    UiScalePatches.UiScaleChanged -= OnScaleChanged;
                    return;
                }

                ApplyLayout(layout);
                ApplyButtonSizes(layout);
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"EventLayoutReadyPostfix failed: {ex.Message}");
        }
    }

    public static void AddOptionsPostfix(object __instance)
    {
        try
        {
            var layout = (Control)__instance;
            ApplyButtonSizes(layout);
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"EventAddOptionsPostfix failed: {ex.Message}");
        }
    }

    private static void ApplyLayout(Control layout)
    {
        UiScalePatches.EnsureUiScaleLoaded();

        var window = layout.GetTree().Root;
        float vpWidth = window.ContentScaleSize.X;

        var vbox = layout.GetNodeOrNull<Control>("VBoxContainer");
        if (vbox == null)
            return;

        if (UiScalePatches.UiScalePercent <= 100)
        {
            // Reset to original scene values
            layout.Position = new Vector2(layout.Position.X, 0f);
            vbox.OffsetLeft = OriginalOffsetLeft;
            vbox.OffsetRight = OriginalOffsetRight;
            return;
        }

        float scale = UiScalePatches.UiScalePercent / 100f;
        float shiftUp = layout.Size.Y * (1f - 1f / scale) * 0.5f;
        layout.Position = new Vector2(layout.Position.X, -shiftUp);

        float margin = 40f;
        float maxWidth = vpWidth - margin * 2f;
        float buttonWidth = Math.Min(OriginalButtonWidth, maxWidth);

        float half = buttonWidth / 2f;
        vbox.AnchorLeft = 0.5f;
        vbox.AnchorRight = 0.5f;
        vbox.OffsetLeft = -half;
        vbox.OffsetRight = half;
    }

    private static void ApplyButtonSizes(Control layout)
    {
        UiScalePatches.EnsureUiScaleLoaded();

        var window = layout.GetTree().Root;
        float vpWidth = window.ContentScaleSize.X;
        float margin = 40f;
        float maxWidth = vpWidth - margin * 2f;

        var optionsContainer = layout.GetNodeOrNull("VBoxContainer/OptionsContainer");
        if (optionsContainer == null)
            return;

        // Clamp button width to viewport when scaled above 100%.
        float targetWidth =
            UiScalePatches.UiScalePercent <= 100
                ? OriginalButtonWidth
                : Math.Min(OriginalButtonWidth, maxWidth);

        foreach (var child in optionsContainer.GetChildren())
        {
            if (child is Control btn)
            {
                btn.CustomMinimumSize = new Vector2(targetWidth, btn.CustomMinimumSize.Y);
            }
        }
    }
}
