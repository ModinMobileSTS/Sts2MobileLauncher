using System;
using System.IO;
using Godot;

namespace STS2Mobile.Android;

public static class AppPaths
{
    private static string _dataDir;

    public static string DataDir => _dataDir ??= ResolveDataDir();
    public static string GameDir => Path.Combine(DataDir, "game");
    public static string ReleaseInfoPath => Path.Combine(GameDir, "release_info.json");
    public static string AccountRoot => Path.Combine(DataDir, "default", "1");
    public static string SettingsPath => Path.Combine(AccountRoot, "settings.save");
    public static string PendingUnlockAllPath => Path.Combine(AccountRoot, "pending_unlock_all.flag");
    public static string ModsDir => Path.Combine(DataDir, "mods");

    private static string ResolveDataDir()
    {
        try
        {
            var wrapper = (GodotObject)Engine.GetSingleton("JavaClassWrapper")?.Call("wrap", "com.godot.game.GodotApp");
            var result = wrapper?.Call("getGodotDataDir");
            if (result.HasValue && result.Value.VariantType == Variant.Type.String)
            {
                var value = result.Value.AsString();
                if (!string.IsNullOrWhiteSpace(value))
                    return value;
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Java GodotApp data-dir bridge unavailable: {exception.Message}");
        }
        return OS.GetDataDir();
    }
}
