using System;
using System.Reflection;
using HarmonyLib;

namespace STS2AndroidPortCompat;

public static class PatchHelper
{
    private const BindingFlags AllFlags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;

    public static MethodInfo Method(Type type, string name) => type.GetMethod(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);

    public static void Patch(Harmony harmony, Type targetType, string methodName, MethodInfo prefix = null, MethodInfo postfix = null, MethodInfo transpiler = null, BindingFlags flags = AllFlags)
    {
        try
        {
            var target = targetType.GetMethod(methodName, flags);
            if (target == null)
            {
                Log($"FAILED {targetType.FullName}.{methodName}: method not found");
                return;
            }
            harmony.Patch(
                target,
                prefix: prefix == null ? null : new HarmonyMethod(prefix),
                postfix: postfix == null ? null : new HarmonyMethod(postfix),
                transpiler: transpiler == null ? null : new HarmonyMethod(transpiler));
            Log($"Patched {targetType.FullName}.{methodName}");
        }
        catch (Exception exception)
        {
            Log($"FAILED {targetType.FullName}.{methodName}: {exception}");
        }
    }

    public static void PatchGetter(Harmony harmony, Type targetType, string propertyName, MethodInfo prefix)
    {
        try
        {
            var property = targetType.GetProperty(propertyName, AllFlags);
            var getter = property?.GetGetMethod(true);
            if (getter == null)
            {
                Log($"FAILED {targetType.FullName}.{propertyName} getter: not found");
                return;
            }
            harmony.Patch(getter, prefix == null ? null : new HarmonyMethod(prefix));
            Log($"Patched {targetType.FullName}.{propertyName} getter");
        }
        catch (Exception exception)
        {
            Log($"FAILED {targetType.FullName}.{propertyName} getter: {exception}");
        }
    }

    public static void Log(string message) => Console.Error.WriteLine($"[STS2AndroidPortCompat] {message}");
}
