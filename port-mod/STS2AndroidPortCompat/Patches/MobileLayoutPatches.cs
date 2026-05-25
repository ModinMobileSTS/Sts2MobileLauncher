using System;
using Godot;
using HarmonyLib;
using MegaCrit.Sts2.Core.Saves;
using MegaCrit.Sts2.Core.Settings;

namespace STS2Mobile.Patches;

// Repositions main menu elements for mobile viewports. Scales the background
// image to fill taller screens and adjusts button/logo placement when UI scale
// is above 100%.
public static class MobileLayoutPatches
{
    public static void Apply(Harmony harmony)
    {
        var sts2Asm = typeof(MegaCrit.Sts2.Core.Nodes.NGame).Assembly;

        var nMainMenuType = sts2Asm.GetType("MegaCrit.Sts2.Core.Nodes.Screens.MainMenu.NMainMenu");
        if (nMainMenuType != null)
        {
            PatchHelper.Patch(
                harmony,
                nMainMenuType,
                "_Ready",
                postfix: PatchHelper.Method(
                    typeof(MobileLayoutPatches),
                    nameof(MainMenuReadyPostfix)
                )
            );
        }
    }

    public static void MainMenuReadyPostfix(object __instance)
    {
        try
        {
            var menu = (Node)__instance;
            ApplyMainMenuLayout(menu);

            UiScalePatches.UiScaleChanged += OnScaleChanged;

            void OnScaleChanged()
            {
                if (!GodotObject.IsInstanceValid((GodotObject)menu) || !menu.IsInsideTree())
                {
                    UiScalePatches.UiScaleChanged -= OnScaleChanged;
                    return;
                }

                ApplyMainMenuLayout(menu);
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"MainMenuReadyPostfix failed: {ex.Message}");
        }
    }

    private static void ApplyMainMenuLayout(Node menu)
    {
        var window = menu.GetTree().Root;
        var vpSize = window.ContentScaleSize;

        // Scale background to fill the viewport on taller screens.
        if (SaveManager.Instance.SettingsSave.AspectRatioSetting == AspectRatioSetting.Auto)
            ScaleMainMenuBg(menu, window.GetVisibleRect().Size);

        // Reposition buttons and logo when UI scale is above 100%.
        UiScalePatches.EnsureUiScaleLoaded();
        if (UiScalePatches.UiScalePercent <= 100)
            return;

        float viewportWidth = vpSize.X;

        var buttons = menu.GetNodeOrNull<Control>("%MainMenuTextButtons");
        if (buttons != null)
        {
            buttons.AnchorLeft = 0f;
            buttons.AnchorRight = 0.5f;
            buttons.AnchorTop = 0f;
            buttons.AnchorBottom = 1f;
            buttons.OffsetLeft = 0f;
            buttons.OffsetRight = 0f;
            buttons.OffsetTop = 0f;
            buttons.OffsetBottom = 0f;
            buttons.GrowHorizontal = Control.GrowDirection.Both;
            buttons.GrowVertical = Control.GrowDirection.Both;
            buttons.SizeFlagsHorizontal = Control.SizeFlags.ShrinkCenter;
            buttons.SizeFlagsVertical = Control.SizeFlags.ShrinkCenter;
        }

        var bg = menu.GetNodeOrNull<Node>("%MainMenuBg");
        if (bg != null)
        {
            var logo = bg.GetNodeOrNull<Node2D>("%Logo");
            if (logo != null)
            {
                var pos = logo.Position;
                logo.Position = new Vector2(viewportWidth * 0.25f + pos.X, pos.Y);
            }
        }

        PatchHelper.Log("Main menu: repositioned buttons left, logo right");
    }

    private static void ScaleMainMenuBg(Node menu, Vector2 vpSize)
    {
        try
        {
            var bg = menu.GetNodeOrNull<Control>("%MainMenuBg");
            if (bg == null)
                return;

            var bgContainer = bg.GetNodeOrNull<Control>("BgContainer");
            if (bgContainer == null)
                return;

            // BgContainer is 2560x1200. On taller screens it doesn't fill vertically.
            const float bgHeight = 1200f;
            float vpHeight = vpSize.Y;

            if (vpHeight <= bgHeight)
            {
                bgContainer.Scale = Vector2.One;
                return;
            }

            float scale = vpHeight / bgHeight;
            bgContainer.Scale = new Vector2(scale, scale);
            PatchHelper.Log($"Main menu bg: scaled by {scale:F3} for viewport height {vpHeight}");
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"ScaleMainMenuBg failed: {ex.Message}");
        }
    }
}
