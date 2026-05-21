using System;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.CompilerServices;
using HarmonyLib;
using MegaCrit.Sts2.Core.Models;

namespace STS2Mobile.Patches;

/// <summary>
/// Replaces the original one-pass ModelDb.Init with launcher-style two-phase
/// initialization. The imported PC assembly constructs some encounter models
/// before the monster models referenced by their static fields are registered
/// (for example BowlbugsNormal -> BowlbugEgg), so raw ModelDb.Get<T>() lookups
/// can fail during type initialization. Pre-registering placeholders lets those
/// cross-model references resolve, then constructors initialize the same objects
/// in place.
/// </summary>
public static class ModelDbInitPatch
{
    private static bool _suppressContains;

    public static void Apply(Harmony harmony)
    {
        try
        {
            var target = typeof(ModelDb).GetMethod(nameof(ModelDb.Init), BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
            var prefix = typeof(ModelDbInitPatch).GetMethod(nameof(InitPrefix), BindingFlags.Public | BindingFlags.Static);
            if (target == null || prefix == null)
            {
                PatchHelper.Log("FAILED ModelDb.Init: method not found");
                return;
            }
            harmony.Patch(target, prefix: new HarmonyMethod(prefix) { priority = Priority.Last });
            PatchHelper.Log("Patched ModelDb.Init (two-phase Android startup compatibility; Priority.Last).");
        }
        catch (Exception exception)
        {
            PatchHelper.Log($"FAILED ModelDb.Init: {exception}");
        }
    }

    public static bool ContainsPrefix(ref bool __result)
    {
        if (_suppressContains)
        {
            __result = false;
            return false;
        }
        return true;
    }

    public static bool InitPrefix()
    {
        PatchHelper.Log("Running patched ModelDb.Init().");

        var modelDbType = typeof(ModelDb);
        var allSubtypesProperty = modelDbType.GetProperty("AllAbstractModelSubtypes", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        var getIdMethod = modelDbType.GetMethod("GetId", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, new[] { typeof(Type) }, null);
        var contentByIdField = modelDbType.GetField("_contentById", BindingFlags.NonPublic | BindingFlags.Static);
        var contentById = contentByIdField?.GetValue(null);
        var setItemMethod = contentById?.GetType().GetMethod("set_Item");

        if (allSubtypesProperty == null || getIdMethod == null || contentById == null || setItemMethod == null)
        {
            PatchHelper.Log("ModelDbInitPatch: failed to locate ModelDb reflection members; falling back to original Init().");
            return true;
        }

        var types = (Type[])allSubtypesProperty.GetValue(null) ?? Array.Empty<Type>();
        PatchHelper.Log($"ModelDbInitPatch phase 1: pre-registering {types.Length} model types.");

        var typeObjects = new Dictionary<Type, object>();
        var preRegistered = 0;
        for (var index = 0; index < types.Length; index++)
        {
            var type = types[index];
            try
            {
                var id = getIdMethod.Invoke(null, new object[] { type });
                var model = RuntimeHelpers.GetUninitializedObject(type);
                setItemMethod.Invoke(contentById, new[] { id, model });
                typeObjects[type] = model;
                preRegistered++;
            }
            catch (Exception exception)
            {
                PatchHelper.Log($"ModelDbInitPatch phase 1 failed for {type.FullName}: {GetRootException(exception).GetType().Name}: {GetRootException(exception).Message}");
            }
        }
        PatchHelper.Log($"ModelDbInitPatch phase 1 complete: {preRegistered}/{types.Length} model types pre-registered.");

        var containsHarmony = new Harmony("com.wsdx233.sts2.android_port_compat.modeldb_contains");
        var containsMethod = modelDbType.GetMethod("Contains", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, new[] { typeof(Type) }, null);
        var containsPrefix = typeof(ModelDbInitPatch).GetMethod(nameof(ContainsPrefix), BindingFlags.Public | BindingFlags.Static);
        var containsPatched = false;

        try
        {
            if (containsMethod != null && containsPrefix != null)
            {
                containsHarmony.Patch(containsMethod, prefix: new HarmonyMethod(containsPrefix));
                containsPatched = true;
            }
            else
            {
                PatchHelper.Log("ModelDbInitPatch: ModelDb.Contains(Type) not found; constructors may detect pre-registered placeholders.");
            }

            PatchHelper.Log("ModelDbInitPatch phase 2: running static and instance constructors.");
            _suppressContains = true;
            var successCount = 0;
            var failed = new List<Type>();

            foreach (var type in types)
            {
                if (!typeObjects.ContainsKey(type))
                    continue;

                try
                {
                    RuntimeHelpers.RunClassConstructor(type.TypeHandle);
                    var constructor = type.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null);
                    constructor?.Invoke(typeObjects[type], null);
                    successCount++;
                }
                catch (Exception exception)
                {
                    var root = GetRootException(exception);
                    failed.Add(type);
                    PatchHelper.Log($"ModelDbInitPatch phase 2 failed for {type.FullName}: {root.GetType().Name}: {root.Message}");
                }
            }

            if (failed.Count > 0)
            {
                PatchHelper.Log($"ModelDbInitPatch warning: {failed.Count}/{types.Length} model constructors failed.");
            }
            else
            {
                PatchHelper.Log($"ModelDbInitPatch phase 2 complete: all {successCount} pre-registered model types initialized.");
            }
        }
        finally
        {
            _suppressContains = false;
            if (containsPatched && containsMethod != null && containsPrefix != null)
            {
                containsHarmony.Unpatch(containsMethod, containsPrefix);
            }
        }

        return false;
    }

    private static Exception GetRootException(Exception exception)
    {
        while (exception.InnerException != null)
            exception = exception.InnerException;
        return exception;
    }
}
