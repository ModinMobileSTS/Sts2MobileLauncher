using System;
using System.IO;
using System.Reflection;
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
            __result = CreateReleaseInfo(json);
            PatchHelper.Log($"Loaded release_info from imported payload: {__result.Version} {__result.Commit}");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"ReleaseInfo fallback failed: {exception}");
        }
    }

    private static ReleaseInfo CreateReleaseInfo(JsonNode json)
    {
        var dateText = (string)json["date"];
        var date = DateTime.TryParse(dateText, out var parsed) ? parsed : DateTime.UtcNow;
        var commit = (string)json["commit"] ?? string.Empty;
        var version = (string)json["version"] ?? string.Empty;
        var branch = (string)json["branch"] ?? string.Empty;
        var mainAssemblyHash = (int?)json["main_assembly_hash"] ?? 0;

        var releaseInfoType = typeof(ReleaseInfo);
        var ctor = releaseInfoType.GetConstructor(Type.EmptyTypes);
        var result = (ReleaseInfo)(ctor != null ? ctor.Invoke(null) : Activator.CreateInstance(releaseInfoType));
        SetInitProperty(result, "Commit", commit);
        SetInitProperty(result, "Version", version);
        SetInitProperty(result, "Date", date);
        SetInitProperty(result, "Branch", branch);
        SetInitProperty(result, "MainAssemblyHash", mainAssemblyHash);
        return result;
    }

    private static void SetInitProperty(object target, string name, object value)
    {
        target.GetType().GetProperty(name, BindingFlags.Public | BindingFlags.Instance)?.SetValue(target, value);
    }
}
