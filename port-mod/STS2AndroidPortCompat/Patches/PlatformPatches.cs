using System.Threading.Tasks;
using HarmonyLib;
using MegaCrit.Sts2.Core.Debug;
using MegaCrit.Sts2.Core.Nodes;
using MegaCrit.Sts2.Core.Saves;

namespace STS2Mobile.Patches;

public static class PlatformPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(NGame), "InitializePlatform", prefix: PatchHelper.Method(typeof(PlatformPatches), nameof(InitializePlatformPrefix)));
        PatchHelper.Patch(harmony, typeof(OsDebugInfo), "LogSystemInfo", prefix: PatchHelper.Method(typeof(PlatformPatches), nameof(SkipPrefix)));
        PatchHelper.PatchGetter(harmony, typeof(PrefsSave), "UploadData", prefix: PatchHelper.Method(typeof(PlatformPatches), nameof(ReturnFalsePrefix)));
        PatchHelper.Patch(harmony, typeof(GodotFileIo), "CreateDirectory", prefix: PatchHelper.Method(typeof(PlatformPatches), nameof(CreateDirectoryPrefix)));
        PatchHelper.Patch(harmony, typeof(SentryService), "Initialize", prefix: PatchHelper.Method(typeof(PlatformPatches), nameof(SkipPrefix)));
    }

    public static bool InitializePlatformPrefix(ref Task<bool> __result)
    {
        PatchHelper.Log("Skipping desktop Steam initialization on Android.");
        __result = Task.FromResult(true);
        return false;
    }

    public static bool SkipPrefix() => false;

    public static bool ReturnFalsePrefix(ref bool __result)
    {
        __result = false;
        return false;
    }

    public static bool CreateDirectoryPrefix(GodotFileIo __instance, string directoryPath)
    {
        var fullPath = __instance.GetFullPath(directoryPath);
        return fullPath.Contains("://");
    }
}
