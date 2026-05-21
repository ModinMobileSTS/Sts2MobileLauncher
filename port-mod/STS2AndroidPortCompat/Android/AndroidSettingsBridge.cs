using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace STS2Mobile.Android;

public static class AndroidSettingsBridge
{
    private static DateTime _lastReadUtc;
    private static JsonDocument _cached;

    public static string SettingsPath => AppPaths.SettingsPath;

    public static bool Exists
    {
        get
        {
            try
            {
                return File.Exists(SettingsPath);
            }
            catch
            {
                return false;
            }
        }
    }

    public static void InvalidateCache()
    {
        _cached?.Dispose();
        _cached = null;
        _lastReadUtc = DateTime.MinValue;
    }

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

    public static (int X, int Y) GetSize(string key, int fallbackX = 0, int fallbackY = 0)
    {
        if (!TryGet(key, out var element))
            return (fallbackX, fallbackY);
        try
        {
            if (element.ValueKind == JsonValueKind.Object)
            {
                var x = fallbackX;
                var y = fallbackY;
                if (element.TryGetProperty("x", out var lowerX) || element.TryGetProperty("X", out lowerX))
                    x = JsonElementToInt(lowerX, fallbackX);
                if (element.TryGetProperty("y", out var lowerY) || element.TryGetProperty("Y", out lowerY))
                    y = JsonElementToInt(lowerY, fallbackY);
                return (x, y);
            }
            if (element.ValueKind == JsonValueKind.Array && element.GetArrayLength() >= 2)
            {
                return (
                    JsonElementToInt(element[0], fallbackX),
                    JsonElementToInt(element[1], fallbackY));
            }
            if (element.ValueKind == JsonValueKind.String)
            {
                var parts = element.GetString()?.Split(',', 'x', 'X');
                if (parts != null && parts.Length >= 2 && int.TryParse(parts[0].Trim(), out var x) && int.TryParse(parts[1].Trim(), out var y))
                    return (x, y);
            }
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to parse size Android setting '{key}': {exception.Message}");
        }
        return (fallbackX, fallbackY);
    }

    private static int JsonElementToInt(JsonElement element, int fallback)
    {
        if (element.ValueKind == JsonValueKind.Number && element.TryGetInt32(out var number))
            return number;
        return int.TryParse(element.ToString(), out var value) ? value : fallback;
    }

    public static bool TryReadRaw(out string json)
    {
        json = null;
        try
        {
            var path = SettingsPath;
            if (!File.Exists(path))
                return false;
            json = File.ReadAllText(path);
            return true;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to read raw Android settings: {exception.Message}");
            return false;
        }
    }

    public static bool TryWriteRaw(string json)
    {
        try
        {
            var path = SettingsPath;
            var directory = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(directory))
                Directory.CreateDirectory(directory);
            File.WriteAllText(path, json);
            InvalidateCache();
            return true;
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to write raw Android settings: {exception.Message}");
            return false;
        }
    }

    public static bool Update(Action<JsonObject> update)
    {
        try
        {
            JsonObject root;
            if (TryReadRaw(out var json) && !string.IsNullOrWhiteSpace(json))
                root = JsonNode.Parse(json)?.AsObject() ?? new JsonObject();
            else
                root = new JsonObject();
            update(root);
            return TryWriteRaw(root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"Failed to update Android settings: {exception.Message}");
            return false;
        }
    }

    public static bool SetValue(string key, object value)
    {
        return Update(root => root[key] = value switch
        {
            null => null,
            bool boolValue => boolValue,
            int intValue => intValue,
            long longValue => longValue,
            float floatValue => floatValue,
            double doubleValue => doubleValue,
            string stringValue => stringValue,
            JsonNode node => node.DeepClone(),
            _ => value.ToString(),
        });
    }

    public static bool SetBool(string key, bool value) => SetValue(key, value);

    public static bool SetInt(string key, int value) => SetValue(key, value);

    public static bool SetFloat(string key, float value) => SetValue(key, value);

    public static bool SetString(string key, string value) => SetValue(key, value ?? string.Empty);

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
