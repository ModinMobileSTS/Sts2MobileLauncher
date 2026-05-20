using System;
using System.IO;
using System.Text.Json.Nodes;
using HarmonyLib;
using MegaCrit.Sts2.Core.Debug;
using STS2Mobile.Android;

namespace STS2Mobile.Patches;

public static class ReleaseInfoPatches
{
    public static void Apply(Harmony harmony)
    {
        PatchHelper.Patch(harmony, typeof(ReleaseInfoManager), "LoadConfig", postfix: PatchHelper.Method(typeof(ReleaseInfoPatches), nameof(LoadConfigPostfix)));
    }

    public static void LoadConfigPostfix(ref ReleaseInfo __result)
    {
        try
        {
            if (__result != null || !File.Exists(AppPaths.ReleaseInfoPath))
                return;
            var json = JsonNode.Parse(File.ReadAllText(AppPaths.ReleaseInfoPath));
            if (json == null)
                return;
            var dateText = (string)json["date"];
            __result = new ReleaseInfo
            {
                Commit = (string)json["commit"] ?? string.Empty,
                Version = (string)json["version"] ?? string.Empty,
                Branch = (string)json["branch"] ?? string.Empty,
                Date = DateTime.TryParse(dateText, out var parsed) ? parsed : DateTime.UtcNow,
            };
            PatchHelper.Log($"Loaded release_info from imported payload: {__result.Version} {__result.Commit}");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ReleaseInfo fallback failed: {exception}");
        }
    }
}
