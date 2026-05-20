using System;
using System.IO;
using Godot;

namespace STS2Mobile.Android;

public static class AppPaths
{
    public static string DataDir => OS.GetDataDir();
    public static string GameDir => Path.Combine(DataDir, "game");
    public static string ReleaseInfoPath => Path.Combine(GameDir, "release_info.json");
    public static string AccountRoot => Path.Combine(DataDir, "default", "1");
    public static string SettingsPath => Path.Combine(AccountRoot, "settings.save");
    public static string PendingUnlockAllPath => Path.Combine(AccountRoot, "pending_unlock_all.flag");
    public static string ModsDir => Path.Combine(DataDir, "mods");

    public static GodotObject GetGodotApp()
    {
        try
        {
            var wrapper = (GodotObject)Engine.GetSingleton("JavaClassWrapper")?.Call("wrap", "com.godot.game.GodotApp");
            return (GodotObject)wrapper?.Call("getInstance");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Java GodotApp bridge unavailable: {exception.Message}");
            return null;
        }
    }
}
