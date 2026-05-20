using System;
using System.Collections.Generic;
using Godot;
using HarmonyLib;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class ShaderCompatibilityPatches
{
    private const string OverlayPackFileName = "port_compat.pck";

    private static readonly Dictionary<string, string> ShaderOverrides = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        { "res://shaders/dark_blur.gdshader", "res://shaders/mobile_compat/dark_blur_compat.gdshader" },
        { "res://shaders/radial_blur.gdshader", "res://shaders/mobile_compat/radial_blur_compat.gdshader" },
        { "res://shaders/doom_overlay.gdshader", "res://shaders/mobile_compat/doom_overlay_compat.gdshader" },
        { "res://shaders/vfx/distortion/vfx_screen_distortion_outward_shader.gdshader", "res://shaders/mobile_compat/screen_distortion_compat.gdshader" },
        { "res://shaders/vfx/scream/vfx_scream_distortion_polar_shader.gdshader", "res://shaders/mobile_compat/scream_distortion_compat.gdshader" },
        { "res://shaders/vfx/vfx_water_reflection_post.gdshader", "res://shaders/mobile_compat/water_reflection_post_compat.gdshader" },
        { "res://shaders/vfx/the_insatiable_sand_fall_2.gdshader", "res://shaders/mobile_compat/sand_fall_post_compat.gdshader" },
        { "res://shaders/blur/canvas_group_mask_blur.gdshader", "res://shaders/mobile_compat/canvas_group_mask_blur_compat.gdshader" },
        { "res://shaders/overlay_blend.gdshader", "res://shaders/mobile_compat/overlay_blend_compat.gdshader" },
    };

    private static bool _loadedOverlayPack;
    private static bool _loggedDisabled;

    public static void Apply(Harmony harmony)
    {
        Callable.From(LoadOverlayPackWhenReady).CallDeferred();
        var nodeType = typeof(Node);
        PatchHelper.Patch(harmony, nodeType, "AddChild", postfix: PatchHelper.Method(typeof(ShaderCompatibilityPatches), nameof(NodeAddChildPostfix)));
        PatchHelper.Patch(harmony, nodeType, "AddChildSafely", postfix: PatchHelper.Method(typeof(ShaderCompatibilityPatches), nameof(NodeAddChildPostfix)));
    }

    public static void NodeAddChildPostfix(Node node)
    {
        if (node == null || !IsEnabled())
            return;
        try
        {
            ApplyRecursive(node);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Shader compatibility traversal failed: {exception.Message}");
        }
    }

    private static void LoadOverlayPackWhenReady()
    {
        if (_loadedOverlayPack)
            return;
        if (Engine.GetMainLoop() is not SceneTree)
        {
            Callable.From(LoadOverlayPackWhenReady).CallDeferred();
            return;
        }
        try
        {
            var pckPath = System.IO.Path.Combine(OS.GetDataDir(), OverlayPackFileName);
            if (System.IO.File.Exists(pckPath))
            {
                _loadedOverlayPack = ProjectSettings.LoadResourcePack(pckPath);
                PatchHelper.Log($"Shader compatibility overlay pack load {(_loadedOverlayPack ? "succeeded" : "failed")}: {pckPath}");
            }
            else
            {
                PatchHelper.Log($"Shader compatibility overlay pack missing: {pckPath}");
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Shader compatibility overlay load failed: {exception.Message}");
        }
    }

    private static bool IsEnabled()
    {
        if (!OS.GetName().Equals("Android", StringComparison.OrdinalIgnoreCase) && !OS.GetName().Equals("iOS", StringComparison.OrdinalIgnoreCase))
            return false;
        var enabled = AndroidSettingsBridge.GetBool("shader_compatibility_mode", false);
        if (!enabled && !_loggedDisabled)
        {
            _loggedDisabled = true;
            PatchHelper.Log("Shader compatibility mode disabled by companion settings.");
        }
        return enabled;
    }

    private static void ApplyRecursive(Node node)
    {
        if (node is CanvasItem canvasItem)
            TryReplaceMaterial(canvasItem);
        foreach (Node child in node.GetChildren())
            ApplyRecursive(child);
    }

    private static void TryReplaceMaterial(CanvasItem canvasItem)
    {
        if (canvasItem.Material is not ShaderMaterial shaderMaterial)
            return;
        var shader = shaderMaterial.Shader;
        var resourcePath = shader?.ResourcePath ?? string.Empty;
        if (string.IsNullOrWhiteSpace(resourcePath) || !ShaderOverrides.TryGetValue(resourcePath, out var replacementPath))
            return;
        var loadedShader = ResourceLoader.Load<Shader>(replacementPath, null, ResourceLoader.CacheMode.Reuse);
        if (loadedShader == null)
        {
            PatchHelper.Log($"Shader compatibility replacement missing: {replacementPath}");
            return;
        }
        var replacementMaterial = (ShaderMaterial)shaderMaterial.Duplicate(true);
        replacementMaterial.Shader = loadedShader;
        canvasItem.Material = replacementMaterial;
        PatchHelper.Log($"Shader compatibility replaced {resourcePath} -> {replacementPath}");
    }
}
