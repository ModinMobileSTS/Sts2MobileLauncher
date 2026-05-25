using System;
using Godot;
using HarmonyLib;

namespace STS2Mobile.Patches;

// Issue #11 — Fold6 unfolded fullscreen에서 검정 배경에 가로 tear 줄무늬가 발생하지만 dev 측
// 단말(Fold7)에서는 재현되지 않아 보고자 단말의 surface/swapchain/디스플레이 메타를 logcat
// 으로 받아내기 위한 1회성 진단 패치.
//
// 1) boot 직후 1회: 단말 GPU/렌더러/디스플레이 정보
// 2) Window.SizeChanged 마다: viewport / content scale / 비율
//
// 둘 다 저빈도 이벤트(boot 1회 + 사용자 fold/unfold/회전/분할 진입 시), 메인 루프 밖에서만
// 동작하므로 게임 FPS에는 영향 없음.
public static class RenderDiagnosticPatches
{
    private const string Tag = "[Diag/Fold]";
    private static bool _bootLogged;
    private static bool _sizeHookConnected;

    public static void Apply(Harmony _)
    {
        // SceneTree가 아직 만들어지지 않았을 수 있으므로 deferred로 진단을 미룬다.
        // ModEntry.ScheduleStandaloneLauncher와 동일한 self-retry 패턴.
        Callable.From(RunWhenReady).CallDeferred();
    }

    private static void RunWhenReady()
    {
        if (Engine.GetMainLoop() is not SceneTree tree)
        {
            Callable.From(RunWhenReady).CallDeferred();
            return;
        }

        try
        {
            LogBootDiagnostic();
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} boot diag failed: {ex.Message}");
        }

        try
        {
            var window = tree.Root;
            if (window != null && !_sizeHookConnected)
            {
                window.SizeChanged += OnWindowSizeChanged;
                _sizeHookConnected = true;
                LogSize("initial");
            }
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} size-hook failed: {ex.Message}");
        }
    }

    private static void LogBootDiagnostic()
    {
        if (_bootLogged)
            return;
        _bootLogged = true;

        // OS / 단말
        PatchHelper.Log(
            $"{Tag} OS={OS.GetName()} model={OS.GetModelName()} "
                + $"distro={OS.GetDistributionName()} version={OS.GetVersion()}"
        );

        // GPU / 렌더러
        try
        {
            PatchHelper.Log(
                $"{Tag} adapter={RenderingServer.GetVideoAdapterName()} "
                    + $"vendor={RenderingServer.GetVideoAdapterVendor()} "
                    + $"type={RenderingServer.GetVideoAdapterType()} "
                    + $"api={RenderingServer.GetVideoAdapterApiVersion()}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} adapter info unavailable: {ex.Message}");
        }

        // 렌더링 백엔드 (Forward+ / Mobile / Compatibility)
        try
        {
            var method = ProjectSettings.GetSetting("rendering/renderer/rendering_method");
            var methodMobile = ProjectSettings.GetSetting(
                "rendering/renderer/rendering_method.mobile"
            );
            var driver = ProjectSettings.GetSetting("rendering/rendering_device/driver");
            PatchHelper.Log(
                $"{Tag} rendering_method={method} mobile={methodMobile} driver={driver}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} project rendering settings unavailable: {ex.Message}");
        }

        // 디스플레이 / DPI
        try
        {
            var screen = DisplayServer.WindowGetCurrentScreen();
            var screenSize = DisplayServer.ScreenGetSize(screen);
            var dpi = DisplayServer.ScreenGetDpi(screen);
            var refresh = DisplayServer.ScreenGetRefreshRate(screen);
            PatchHelper.Log(
                $"{Tag} screen[{screen}] size={screenSize.X}x{screenSize.Y} dpi={dpi} "
                    + $"refresh={refresh:F1}Hz"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} screen info unavailable: {ex.Message}");
        }
    }

    private static void OnWindowSizeChanged() => LogSize("changed");

    private static void LogSize(string reason)
    {
        try
        {
            if (Engine.GetMainLoop() is not SceneTree tree)
                return;
            var window = tree.Root;
            if (window == null)
                return;

            var winSize = DisplayServer.WindowGetSize();
            var visible = window.GetVisibleRect().Size;
            var contentScale = window.ContentScaleSize;
            float ratio = winSize.Y > 0 ? (float)winSize.X / winSize.Y : 0f;

            PatchHelper.Log(
                $"{Tag} size({reason}) win={winSize.X}x{winSize.Y} "
                    + $"visible={visible.X}x{visible.Y} "
                    + $"contentScale={contentScale.X}x{contentScale.Y} "
                    + $"mode={window.ContentScaleMode} aspect={window.ContentScaleAspect} "
                    + $"ratio={ratio:F4}"
            );
        }
        catch (Exception ex)
        {
            PatchHelper.Log($"{Tag} size log failed: {ex.Message}");
        }
    }
}
