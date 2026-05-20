using System;
using System.IO;
using System.Text.Json;
using STS2AndroidPortCompat.Android;

namespace STS2AndroidPortCompat.Android;

public static class AndroidSettingsBridge
{
    private static DateTime _lastReadUtc;
    private static JsonDocument _cached;

    public static bool GetBool(string key, bool fallback = false)
    {
        if (!TryGet(key, out var element))
            return fallback;
        return element.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => element.TryGetInt32(out var value) ? value != 0 : fallback,
            JsonValueKind.String => bool.TryParse(element.GetString(), out var value) ? value : fallback,
            _ => fallback,
        };
    }

    public static int GetInt(string key, int fallback = 0)
    {
        if (!TryGet(key, out var element))
            return fallback;
        if (element.ValueKind == JsonValueKind.Number && element.TryGetInt32(out var number))
            return number;
        return int.TryParse(element.ToString(), out var value) ? value : fallback;
    }

    public static float GetFloat(string key, float fallback = 0f)
    {
        if (!TryGet(key, out var element))
            return fallback;
        if (element.ValueKind == JsonValueKind.Number && element.TryGetSingle(out var number))
            return number;
        return float.TryParse(element.ToString(), out var value) ? value : fallback;
    }

    public static string GetString(string key, string fallback = "")
    {
        if (!TryGet(key, out var element))
            return fallback;
        return element.ValueKind == JsonValueKind.String ? element.GetString() ?? fallback : element.ToString();
    }

    public static bool TryGet(string key, out JsonElement element)
    {
        element = default;
        try
        {
            var file = new FileInfo(AppPaths.SettingsPath);
            if (!file.Exists)
                return false;
            if (_cached == null || file.LastWriteTimeUtc > _lastReadUtc)
            {
                _cached?.Dispose();
                _cached = JsonDocument.Parse(File.ReadAllText(file.FullName));
                _lastReadUtc = file.LastWriteTimeUtc;
            }
            return _cached.RootElement.TryGetProperty(key, out element);
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to read Android setting '{key}': {exception.Message}");
            return false;
        }
    }
}
