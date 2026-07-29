using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using Godot;
using Godot.Bridge;
using Godot.NativeInterop;
using HarmonyLib;

namespace STS2Mobile;

public static class ModEntry
{
    private static bool _applied;
    private static Harmony _harmony;

    [UnmanagedCallersOnly]
    public static int InitializeGodotSharp(IntPtr godotDllHandle, IntPtr outManagedCallbacks, IntPtr unmanagedCallbacks, int unmanagedCallbacksSize)
    {
        try
        {
            Log($"DIAG offline InitializeGodotSharp begin godotDllHandle=0x{godotDllHandle.ToInt64():x} assembly={SafeAssemblyLocation(typeof(ModEntry).Assembly)} baseDir={AppContext.BaseDirectory} cwd={SafeCurrentDirectory()} framework={RuntimeInformation.FrameworkDescription} os={RuntimeInformation.OSDescription} arch={RuntimeInformation.ProcessArchitecture}");
            DllImportResolver resolver = new GodotDllImportResolver(godotDllHandle).OnResolveDllImport;
            NativeLibrary.SetDllImportResolver(typeof(GodotObject).Assembly, resolver);
            NativeFuncs.Initialize(unmanagedCallbacks, unmanagedCallbacksSize);
            ManagedCallbacks.Create(outManagedCallbacks);
            Log("GodotSharp bootstrapped successfully by offline bootstrap.");
            return 1;
        }
        catch (Exception exception)
        {
            Log($"GodotSharp bootstrap failed in offline bootstrap: {exception}");
            return 0;
        }
    }

    [UnmanagedCallersOnly]
    public static void Apply()
    {
        Log($"DIAG offline Apply entry applied={_applied} assembly={SafeAssemblyLocation(typeof(ModEntry).Assembly)} baseDir={AppContext.BaseDirectory} cwd={SafeCurrentDirectory()} temp={SafeTempPath()} framework={RuntimeInformation.FrameworkDescription} os={RuntimeInformation.OSDescription} arch={RuntimeInformation.ProcessArchitecture}");
        if (_applied)
            return;
        _applied = true;

        AndroidPaths.ConfigureTempDirectory();
        var probe = OfflineProbe.Load();
        probe.Status = "starting";
        probe.Success = false;
        probe.AddOrReplaceCheck("entry", true, "Offline bootstrap entry loaded.", critical: true);
        probe.Write();

        try
        {
            _harmony = new Harmony("com.sts2mobile.offlinebootstrap");
            OfflineRuntimePatches.Apply(_harmony, probe);
            probe.Success = false;
            probe.Status = probe.HasCriticalFailure ? "unsupported_api" : "patches_installed";
        }
        catch (Exception exception)
        {
            probe.Success = false;
            probe.Status = "apply_failed";
            probe.AddOrReplaceCheck("apply_exception", false, exception.ToString(), critical: true);
            Log($"Offline bootstrap failed: {exception}");
        }
        finally
        {
            probe.Write();
        }
    }

    internal static void Log(string message)
    {
        Console.Error.WriteLine($"[STS2Mobile][OfflineBootstrap] {message}");
    }

    private static string SafeAssemblyLocation(Assembly assembly)
    {
        try { return assembly?.Location ?? "<null>"; }
        catch (Exception exception) { return "<failed:" + exception.Message + ">"; }
    }

    private static string SafeCurrentDirectory()
    {
        try { return Directory.GetCurrentDirectory(); }
        catch (Exception exception) { return "<failed:" + exception.Message + ">"; }
    }

    private static string SafeTempPath()
    {
        try { return Path.GetTempPath(); }
        catch (Exception exception) { return "<failed:" + exception.Message + ">"; }
    }
}

internal static class OfflineRuntimePatches
{
    private const BindingFlags AllFlags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;

    public static void Apply(Harmony harmony, OfflineProbe probe)
    {
        bool sts2Loaded = TypeResolver.EnsureAssemblyLoaded("sts2", probe);
        probe.AddCheck("sts2_assembly", sts2Loaded, sts2Loaded ? "sts2 assembly is loadable." : "sts2 assembly could not be loaded.", critical: true);
        if (!sts2Loaded)
            return;

        PatchMethod(harmony, probe, "platform_initialize", "MegaCrit.Sts2.Core.Nodes.NGame", "InitializePlatform", Method(nameof(InitializePlatformPrefix)), critical: false);
        PatchMethod(harmony, probe, "os_debug_log_system_info", "MegaCrit.Sts2.Core.Debug.OsDebugInfo", "LogSystemInfo", Method(nameof(CompleteTaskPrefix)), critical: false);
        PatchGetter(harmony, probe, "prefs_upload_data", "MegaCrit.Sts2.Core.Saves.PrefsSave", "UploadData", Method(nameof(ReturnFalsePrefix)), critical: false);
        PatchFirstAvailableMethod(harmony, probe, "sentry_initialize", new[] { "MegaCrit.Sts2.Core.Debug.SentryService", "MegaCrit.Sts2.Core.Saves.SentryService" }, "Initialize", Method(nameof(SkipPrefix)), critical: false);
        PatchMethod(harmony, probe, "godot_file_io_create_directory", "MegaCrit.Sts2.Core.Saves.GodotFileIo", "CreateDirectory", Method(nameof(CreateDirectoryPrefix)), critical: false);

        PatchMethod(harmony, probe, "save_account_base_path", "MegaCrit.Sts2.Core.Saves.UserDataPathProvider", "GetAccountScopedBasePath", Method(nameof(GetAccountScopedBasePathPrefix)), critical: false);
        PatchMethod(harmony, probe, "save_profile_base_path", "MegaCrit.Sts2.Core.Saves.UserDataPathProvider", "GetProfileScopedBasePath", Method(nameof(GetProfileScopedBasePathPrefix)), critical: false);
        PatchMethod(harmony, probe, "save_profile_path", "MegaCrit.Sts2.Core.Saves.UserDataPathProvider", "GetProfileScopedPath", Method(nameof(GetProfileScopedPathPrefix)), critical: false);
        OfflineModelDbPatches.Apply(harmony, probe);
    }

    public static bool InitializePlatformPrefix(ref Task<bool> __result)
    {
        ModEntry.Log("Skipping desktop platform initialization in offline bootstrap.");
        __result = Task.FromResult(true);
        return false;
    }

    public static bool CompleteTaskPrefix(ref Task __result)
    {
        __result = Task.CompletedTask;
        return false;
    }

    public static bool SkipPrefix() => false;

    public static bool ReturnFalsePrefix(ref bool __result)
    {
        __result = false;
        return false;
    }

    public static bool CreateDirectoryPrefix(object __instance, string directoryPath)
    {
        try
        {
            var method = __instance?.GetType().GetMethod("GetFullPath", AllFlags);
            var fullPath = method?.Invoke(__instance, new object[] { directoryPath }) as string;
            return fullPath == null || fullPath.Contains("://", StringComparison.Ordinal);
        }
        catch
        {
            return true;
        }
    }

    public static bool GetAccountScopedBasePathPrefix(string dataType, ref string __result)
    {
        __result = CombineGodotPath(ToGodotUserPath(AndroidPaths.AccountRoot), dataType);
        return false;
    }

    public static bool GetProfileScopedBasePathPrefix(int profileId, ref string __result)
    {
        __result = CombineGodotPath(ToGodotUserPath(AndroidPaths.AccountRoot), GetProfileDir(profileId));
        return false;
    }

    public static bool GetProfileScopedPathPrefix(int profileId, string dataType, ref string __result)
    {
        __result = CombineGodotPath(ToGodotUserPath(AndroidPaths.AccountRoot), GetProfileDir(profileId), dataType);
        return false;
    }

    private static string GetProfileDir(int profileId)
    {
        try
        {
            var type = TypeResolver.FindType("MegaCrit.Sts2.Core.Saves.UserDataPathProvider");
            var method = type?.GetMethod("GetProfileDir", AllFlags);
            var value = method?.Invoke(null, new object[] { profileId }) as string;
            if (!string.IsNullOrWhiteSpace(value))
                return value;
        }
        catch (Exception exception)
        {
            ModEntry.Log($"GetProfileDir reflection failed: {exception.Message}");
        }
        return profileId.ToString(CultureInfo.InvariantCulture);
    }

    private static void PatchMethod(Harmony harmony, OfflineProbe probe, string checkName, string typeName, string methodName, System.Reflection.MethodInfo prefix, bool critical)
    {
        try
        {
            var type = TypeResolver.FindType(typeName);
            if (type == null)
            {
                probe.AddCheck(checkName, false, "Type not found: " + typeName, critical);
                return;
            }
            var target = type.GetMethod(methodName, AllFlags);
            if (target == null)
            {
                probe.AddCheck(checkName, false, "Method not found: " + typeName + "." + methodName, critical);
                return;
            }
            if (!IsPatchable(target, type))
            {
                probe.AddCheck(checkName, false, "Method is not implemented on target type: " + target, critical);
                return;
            }
            harmony.Patch(target, prefix: new HarmonyMethod(prefix));
            probe.AddCheck(checkName, true, "Patched " + typeName + "." + methodName, critical);
            ModEntry.Log("Patched " + typeName + "." + methodName);
        }
        catch (Exception exception)
        {
            probe.AddCheck(checkName, false, exception.ToString(), critical);
            ModEntry.Log("Failed to patch " + typeName + "." + methodName + ": " + exception);
        }
    }

    private static void PatchFirstAvailableMethod(Harmony harmony, OfflineProbe probe, string checkName, string[] typeNames, string methodName, System.Reflection.MethodInfo prefix, bool critical)
    {
        foreach (var typeName in typeNames ?? Array.Empty<string>())
        {
            var type = TypeResolver.FindType(typeName);
            if (type == null)
                continue;
            PatchMethod(harmony, probe, checkName, typeName, methodName, prefix, critical);
            return;
        }
        probe.AddCheck(checkName, false, "Type not found: " + string.Join(" / ", typeNames ?? Array.Empty<string>()), critical);
    }

    private static void PatchGetter(Harmony harmony, OfflineProbe probe, string checkName, string typeName, string propertyName, System.Reflection.MethodInfo prefix, bool critical)
    {
        try
        {
            var type = TypeResolver.FindType(typeName);
            if (type == null)
            {
                probe.AddCheck(checkName, false, "Type not found: " + typeName, critical);
                return;
            }
            var getter = type.GetProperty(propertyName, AllFlags)?.GetGetMethod(true);
            if (getter == null)
            {
                probe.AddCheck(checkName, false, "Getter not found: " + typeName + "." + propertyName, critical);
                return;
            }
            harmony.Patch(getter, prefix: new HarmonyMethod(prefix));
            probe.AddCheck(checkName, true, "Patched getter " + typeName + "." + propertyName, critical);
            ModEntry.Log("Patched getter " + typeName + "." + propertyName);
        }
        catch (Exception exception)
        {
            probe.AddCheck(checkName, false, exception.ToString(), critical);
            ModEntry.Log("Failed to patch getter " + typeName + "." + propertyName + ": " + exception);
        }
    }

    private static System.Reflection.MethodInfo Method(string name) => typeof(OfflineRuntimePatches).GetMethod(name, AllFlags);

    private static bool IsPatchable(System.Reflection.MethodInfo method, Type targetType)
    {
        if (method.DeclaringType != targetType)
            return false;
        if ((method.Attributes & MethodAttributes.PinvokeImpl) != 0)
            return false;
        if ((method.GetMethodImplementationFlags() & MethodImplAttributes.InternalCall) != 0)
            return false;
        return true;
    }

    private static string ToGodotUserPath(string absolutePath)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(absolutePath))
                Directory.CreateDirectory(absolutePath);
        }
        catch (Exception exception)
        {
            ModEntry.Log($"Failed to ensure account root '{absolutePath}': {exception.Message}");
        }

        try
        {
            var dataDir = NormalizeAbsolute(AndroidPaths.DataDir);
            var fullPath = NormalizeAbsolute(absolutePath);
            if (string.IsNullOrWhiteSpace(dataDir) || string.IsNullOrWhiteSpace(fullPath))
                return "user://default/1";
            var prefix = dataDir.EndsWith(Path.DirectorySeparatorChar) ? dataDir : dataDir + Path.DirectorySeparatorChar;
            if (fullPath.StartsWith(prefix, StringComparison.Ordinal))
            {
                var relative = fullPath.Substring(prefix.Length).Replace(Path.DirectorySeparatorChar, '/');
                return string.IsNullOrWhiteSpace(relative) ? "user://" : "user://" + relative.Trim('/');
            }
        }
        catch (Exception exception)
        {
            ModEntry.Log($"Failed to convert account root to user:// path: {exception.Message}");
        }
        return "user://default/1";
    }

    private static string NormalizeAbsolute(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return string.Empty;
        try { return Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar); }
        catch { return path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar); }
    }

    private static string CombineGodotPath(params string[] parts)
    {
        var result = string.Empty;
        foreach (var raw in parts)
        {
            var part = (raw ?? string.Empty).Replace('\\', '/').Trim();
            if (string.IsNullOrWhiteSpace(part))
                continue;
            if (string.IsNullOrEmpty(result))
            {
                result = part.EndsWith("://", StringComparison.Ordinal) ? part : part.TrimEnd('/');
                continue;
            }
            part = part.Trim('/');
            if (string.IsNullOrWhiteSpace(part))
                continue;
            result = result.EndsWith("://", StringComparison.Ordinal) ? result + part : result.TrimEnd('/') + "/" + part;
        }
        return string.IsNullOrWhiteSpace(result) ? "user://default/1" : result;
    }
}

internal static class OfflineModelDbPatches
{
    private const BindingFlags AllFlags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
    private static readonly object PhaseLock = new object();
    private static readonly Dictionary<Type, object> PreRegisteredModels = new Dictionary<Type, object>();
    private static readonly List<Type> PreRegisteredOrder = new List<Type>();
    private static readonly List<string> InitializationFailures = new List<string>();
    private static bool _phase1Completed;
    private static bool _phase2Completed;
    private static bool _phaseInProgress;
    private static bool _suppressAbstractModelConstructor;
    private static int _expectedModelCount;
    private static int _registeredModelCount;
    private static int _constructedModelCount;
    private static OfflineProbe _probe;
    private static ModelDbRuntimeContract _contract;

    public static void Apply(Harmony harmony, OfflineProbe probe)
    {
        _probe = probe;
        try
        {
            var modelDbType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.ModelDb");
            var abstractModelType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModel");
            var generatedSubtypesType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModelSubtypes");
            _contract = ModelDbRuntimeContract.Resolve(modelDbType, abstractModelType, generatedSubtypesType);
            probe.SetModelDbContract(_contract);
            if (!_contract.Ready)
            {
                probe.AddOrReplaceCheck("modeldb_contract", false, _contract.Diagnostic, critical: true);
                return;
            }
            probe.AddOrReplaceCheck("modeldb_contract", true, _contract.Diagnostic, critical: true);

            var initPrefix = typeof(OfflineModelDbPatches).GetMethod(nameof(ModelDbInitPrefix), AllFlags);
            var initPostfix = typeof(OfflineModelDbPatches).GetMethod(nameof(ModelDbInitPostfix), AllFlags);
            var constructorPrefix = typeof(OfflineModelDbPatches).GetMethod(nameof(AbstractModelConstructorPrefix), AllFlags);
            if (initPrefix == null || initPostfix == null || constructorPrefix == null)
                throw new MissingMethodException("Offline ModelDb Harmony patch methods were not found.");

            harmony.Patch(
                _contract.AbstractModelConstructor,
                prefix: new HarmonyMethod(constructorPrefix) { priority = Priority.First });
            probe.AddOrReplaceCheck("abstract_model_ctor_guard", true, "Patched AbstractModel constructor for offline two-phase ModelDb init.", critical: true);

            foreach (var init in _contract.InitMethods)
            {
                harmony.Patch(
                    init.Method,
                    prefix: new HarmonyMethod(initPrefix) { priority = Priority.Last },
                    postfix: new HarmonyMethod(initPostfix) { priority = Priority.First });
                ModEntry.Log("Patched offline ModelDb API shape " + init.Shape + ": " + init.Signature);
            }

            probe.AddOrReplaceCheck(
                "modeldb_two_phase",
                true,
                "Patched " + _contract.InitMethods.Count + " supported ModelDb.Init API shape(s) for two-phase placeholder registration.",
                critical: true);
        }
        catch (Exception exception)
        {
            probe.AddOrReplaceCheck("modeldb_two_phase", false, exception.ToString(), critical: true);
            ModEntry.Log("Failed to patch offline ModelDb init: " + exception);
        }
    }

    public static bool ModelDbInitPrefix(MethodBase __originalMethod, object[] __args)
    {
        var init = RequireInitContract(__originalMethod);
        if (init.HasExplicitInjectedModelTypes(__args))
        {
            ModEntry.Log("Preserving original ModelDb.Init for an explicit injected model type set.");
            return true;
        }
        RunTwoPhaseModelDbInit(init);
        return false;
    }

    public static void ModelDbInitPostfix(MethodBase __originalMethod, object[] __args)
    {
        var init = RequireInitContract(__originalMethod);
        if (init.HasExplicitInjectedModelTypes(__args))
            return;
        RunTwoPhaseModelDbInit(init);
    }

    public static bool AbstractModelConstructorPrefix(object __instance)
    {
        if (!_suppressAbstractModelConstructor || __instance == null)
            return true;

        try
        {
            var id = _contract.GetModelId(__instance.GetType());
            if (id == null)
                throw new InvalidOperationException("ModelDb.GetId returned null for " + __instance.GetType().FullName + ".");
            _contract.SeedModelId(__instance, id);
            return false;
        }
        catch (Exception exception)
        {
            var root = GetRootException(exception);
            RecordInitializationFailure("AbstractModel constructor guard failed for " + __instance.GetType().FullName + ": " + root.GetType().Name + ": " + root.Message);
            throw new InvalidOperationException("Offline ModelDb constructor guard failed for " + __instance.GetType().FullName + ".", root);
        }
    }

    private static ModelDbInitMethodContract RequireInitContract(MethodBase method)
    {
        var init = _contract?.FindInitMethod(method);
        if (init != null)
            return init;
        throw new InvalidOperationException("Offline ModelDb received an unrecognized Init method: " + method + ".");
    }

    private static void RunTwoPhaseModelDbInit(ModelDbInitMethodContract init)
    {
        lock (PhaseLock)
        {
            if (_phase2Completed)
            {
                ModEntry.Log("Offline ModelDb.Init invoked again after completion; ignoring.");
                return;
            }
            if (_phaseInProgress)
                throw new InvalidOperationException("Offline ModelDb two-phase initialization was re-entered.");
            _phaseInProgress = true;
        }

        _probe?.BeginModelDbInitialization(init);
        _probe?.Write();
        try
        {
            RunPhase1PreRegistration();
            RunPreRegisteredModelConstructors();
            if (_expectedModelCount <= 0 || _registeredModelCount != _expectedModelCount || _constructedModelCount != _expectedModelCount)
            {
                RecordInitializationFailure(
                    "Model count invariant failed: expected=" + _expectedModelCount
                    + ", registered=" + _registeredModelCount
                    + ", constructed=" + _constructedModelCount + ".");
            }
            string[] failures;
            lock (PhaseLock)
                failures = InitializationFailures.ToArray();
            if (failures.Length > 0)
                throw new InvalidOperationException("Offline ModelDb initialization reported " + failures.Length + " failure(s): " + string.Join(" | ", failures.Take(8)));
            _probe?.CompleteModelDbInitialization(_expectedModelCount, _registeredModelCount, _constructedModelCount);
            _probe?.Write();
        }
        catch (Exception exception)
        {
            var root = GetRootException(exception);
            _probe?.FailModelDbInitialization(root, _expectedModelCount, _registeredModelCount, _constructedModelCount, InitializationFailures);
            _probe?.Write();
            throw;
        }
        finally
        {
            lock (PhaseLock)
                _phaseInProgress = false;
        }
    }

    private static void RunPhase1PreRegistration()
    {
        lock (PhaseLock)
        {
            if (_phase1Completed || _phase2Completed)
                return;
        }

        var types = GetVanillaModelTypes();
        if (types.Length == 0)
            throw new InvalidOperationException("Resolved model type source returned no concrete AbstractModel types.");
        PreRegisterModelPlaceholders(types);
        lock (PhaseLock)
            _phase1Completed = true;
    }

    private static Type[] GetVanillaModelTypes()
    {
        return _contract.ReadModelTypes()
            .Where(type => type != null && !type.IsAbstract && _contract.AbstractModelType.IsAssignableFrom(type))
            .Distinct()
            .ToArray();
    }

    private static void PreRegisterModelPlaceholders(Type[] rawTypes)
    {
        var dictionary = _contract.ReadContentById();
        var staged = new List<StagedModelPlaceholder>();
        var stagedIds = new HashSet<object>();
        var preexisting = 0;
        var types = rawTypes ?? Array.Empty<Type>();
        _expectedModelCount = types.Length;

        foreach (var type in types)
        {
            try
            {
                var id = _contract.GetModelId(type);
                if (id == null)
                    throw new InvalidOperationException("ModelDb.GetId returned null.");
                if (!stagedIds.Add(id))
                    throw new InvalidOperationException("Duplicate ModelId was produced before placeholder commit: " + id + ".");
                if (dictionary.Contains(id))
                {
                    var existing = dictionary[id];
                    if (existing == null || existing.GetType() != type)
                        throw new InvalidOperationException("ModelDb already contains " + id + " mapped to " + existing?.GetType().FullName + ".");
                    preexisting++;
                    continue;
                }
                var constructor = type.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null);
                if (constructor == null)
                    throw new MissingMethodException(type.FullName, ".ctor()");
                var model = RuntimeHelpers.GetUninitializedObject(type);
                _contract.SeedModelId(model, id);
                staged.Add(new StagedModelPlaceholder(type, id, model));
            }
            catch (Exception exception)
            {
                var root = GetRootException(exception);
                RecordInitializationFailure("Pre-registration failed for " + type.FullName + ": " + root.GetType().Name + ": " + root.Message);
            }
        }

        if (InitializationFailures.Count > 0)
            throw new InvalidOperationException("Offline ModelDb pre-registration capability check failed before dictionary commit.");

        var committed = new List<StagedModelPlaceholder>();
        try
        {
            foreach (var placeholder in staged)
            {
                dictionary[placeholder.Id] = placeholder.Model;
                committed.Add(placeholder);
            }
        }
        catch
        {
            foreach (var placeholder in committed)
                dictionary.Remove(placeholder.Id);
            throw;
        }

        lock (PhaseLock)
        {
            foreach (var placeholder in staged)
            {
                PreRegisteredModels[placeholder.Type] = placeholder.Model;
                PreRegisteredOrder.Add(placeholder.Type);
            }
            _registeredModelCount = staged.Count + preexisting;
            _constructedModelCount = preexisting;
        }
        ModEntry.Log("Offline ModelDb atomically pre-registered " + staged.Count + " model placeholder(s); preexisting=" + preexisting + ", expected=" + _expectedModelCount + ".");
    }

    private static void RunPreRegisteredModelConstructors()
    {
        List<Type> types;
        lock (PhaseLock)
            types = new List<Type>(PreRegisteredOrder);

        ClearModelDbDerivedCaches("before offline constructor phase");
        try
        {
            _suppressAbstractModelConstructor = true;
            var succeeded = 0;
            foreach (var type in types)
            {
                object model;
                lock (PhaseLock)
                {
                    if (!PreRegisteredModels.TryGetValue(type, out model))
                    {
                        RecordInitializationFailure("Constructor phase lost placeholder for " + type.FullName + ".");
                        continue;
                    }
                }
                try
                {
                    RuntimeHelpers.RunClassConstructor(type.TypeHandle);
                    var constructor = type.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null)
                        ?? throw new MissingMethodException(type.FullName, ".ctor()");
                    constructor.Invoke(model, null);
                    succeeded++;
                }
                catch (Exception exception)
                {
                    var root = GetRootException(exception);
                    RecordInitializationFailure("Constructor phase failed for " + type.FullName + ": " + root.GetType().Name + ": " + root.Message);
                }
            }
            lock (PhaseLock)
                _constructedModelCount += succeeded;
            ModEntry.Log("Offline ModelDb constructor phase completed; succeeded=" + succeeded + ", failed=" + InitializationFailures.Count + ".");
        }
        finally
        {
            _suppressAbstractModelConstructor = false;
            ClearModelDbDerivedCaches("after offline constructor phase");
            lock (PhaseLock)
                _phase2Completed = true;
        }
    }

    private static void RecordInitializationFailure(string message)
    {
        lock (PhaseLock)
            InitializationFailures.Add(message ?? "Unknown ModelDb initialization failure.");
        ModEntry.Log("Offline ModelDb " + message);
    }

    private sealed class StagedModelPlaceholder
    {
        public Type Type { get; }
        public object Id { get; }
        public object Model { get; }

        public StagedModelPlaceholder(Type type, object id, object model)
        {
            Type = type;
            Id = id;
            Model = model;
        }
    }

    private static void ClearModelDbDerivedCaches(string reason)
    {
        try
        {
            var modelDbType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.ModelDb");
            if (modelDbType == null)
                return;
            string[] fields =
            {
                "_allCards",
                "_allCardPools",
                "_allCharacterCardPools",
                "_allSharedEvents",
                "_allEvents",
                "_allEncounters",
                "_eventEncounters",
                "_allPotions",
                "_allPotionPools",
                "_allCharacterPotionPools",
                "_allSharedPotionPools",
                "_allPowers",
                "_allRelics",
                "_allCharacterRelicPools",
                "_acts",
                "_actsByIndex",
                "_badges",
                "_achievements"
            };
            var cleared = 0;
            foreach (var fieldName in fields)
            {
                var field = modelDbType.GetField(fieldName, BindingFlags.NonPublic | BindingFlags.Static);
                if (field == null || field.FieldType.IsValueType || field.GetValue(null) == null)
                    continue;
                field.SetValue(null, null);
                cleared++;
            }
            if (cleared > 0)
                ModEntry.Log("Offline ModelDb cleared " + cleared + " derived cache field(s) " + reason + ".");
        }
        catch (Exception exception)
        {
            ModEntry.Log("Offline ModelDb failed to clear derived caches " + reason + ": " + exception.Message);
        }
    }

    private static Exception GetRootException(Exception exception)
    {
        while (exception is TargetInvocationException { InnerException: not null })
            exception = exception.InnerException;
        return exception;
    }
}

internal static class TypeResolver
{
    public static bool EnsureAssemblyLoaded(string simpleName, OfflineProbe probe)
    {
        if (AppDomain.CurrentDomain.GetAssemblies().Any(a => string.Equals(a.GetName().Name, simpleName, StringComparison.OrdinalIgnoreCase)))
            return true;
        try
        {
            Assembly.Load(simpleName);
            return true;
        }
        catch (Exception exception)
        {
            probe?.AddCheck("load_" + simpleName, false, exception.Message, critical: false);
        }
        return false;
    }

    public static Type FindType(string fullName)
    {
        foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
        {
            Type type = null;
            try { type = assembly.GetType(fullName, throwOnError: false, ignoreCase: false); }
            catch { }
            if (type != null)
                return type;
        }
        try
        {
            var sts2 = Assembly.Load("sts2");
            return sts2.GetType(fullName, throwOnError: false, ignoreCase: false);
        }
        catch
        {
            return null;
        }
    }
}

internal static class AndroidPaths
{
    private const string KnownPackageName = "com.megacrit.sts2re";
    private static string _dataDir;
    private static string _accountRoot;
    private static Dictionary<string, string> _launchContext;

    public static string DataDir => _dataDir ??= ResolveDataDir();
    public static string AccountRoot => _accountRoot ??= ResolveLaunchContextPath("selected_account_root", Path.Combine(DataDir, "default", "1"));
    public static string GameDir => ResolveLaunchContextPath("selected_game_dir", Path.Combine(DataDir, "game"));
    public static string CompatPackDir => ResolveLaunchContextPath("selected_compat_pack_dir", string.Empty);
    public static string SelectedCompatPackId => ResolveLaunchContextValue("compat_pack_id");
    public static string SelectedCompatTargetId => ResolveLaunchContextValue("compat_target_id");

    public static void ConfigureTempDirectory()
    {
        try
        {
            var tempDir = Path.Combine(DataDir, "tmp");
            Directory.CreateDirectory(tempDir);
            System.Environment.SetEnvironmentVariable("TMPDIR", tempDir);
            System.Environment.SetEnvironmentVariable("TMP", tempDir);
            System.Environment.SetEnvironmentVariable("TEMP", tempDir);
            ModEntry.Log("Android temp directory configured: " + tempDir);
        }
        catch (Exception exception)
        {
            ModEntry.Log("Failed to configure temp directory: " + exception);
        }
    }

    public static string LauncherDir()
    {
        return Path.Combine(DataDir, "launcher");
    }

    private static string ResolveLaunchContextPath(string key, string fallback)
    {
        var value = ResolveLaunchContextValue(key);
        return NormalizeAbsolutePath(string.IsNullOrWhiteSpace(value) ? fallback : value);
    }

    private static string ResolveLaunchContextValue(string key)
    {
        try
        {
            var context = LaunchContext();
            return context.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value : string.Empty;
        }
        catch (Exception exception)
        {
            ModEntry.Log("Launch context lookup failed for " + key + ": " + exception.Message);
            return string.Empty;
        }
    }

    private static Dictionary<string, string> LaunchContext()
    {
        if (_launchContext != null)
            return _launchContext;
        _launchContext = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var path = Path.Combine(DataDir, "launcher", "selected_instance.json");
        if (!File.Exists(path))
            return _launchContext;
        using var document = JsonDocument.Parse(File.ReadAllText(path));
        foreach (var property in document.RootElement.EnumerateObject())
        {
            if (property.Value.ValueKind == JsonValueKind.String)
                _launchContext[property.Name] = property.Value.GetString();
        }
        return _launchContext;
    }

    private static string ResolveDataDir()
    {
        foreach (var candidate in DataDirCandidates())
        {
            var normalized = NormalizeAbsolutePath(candidate).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (string.IsNullOrWhiteSpace(normalized))
                continue;
            try
            {
                if (File.Exists(Path.Combine(normalized, "launcher", "selected_instance.json")) || Directory.Exists(Path.Combine(normalized, ".godot", "mono")) || normalized.EndsWith("/files", StringComparison.Ordinal))
                    return normalized;
            }
            catch { }
        }
        return "/data/user/0/" + KnownPackageName + "/files";
    }

    private static IEnumerable<string> DataDirCandidates()
    {
        yield return TryResolveFilesDirFromPublishDir(Path.GetDirectoryName(typeof(ModEntry).Assembly.Location));
        yield return TryResolveFilesDirFromPublishDir(AppContext.BaseDirectory);
        yield return "/data/user/0/" + ProcessPackageName() + "/files";
        yield return "/data/data/" + ProcessPackageName() + "/files";
        yield return "/data/user/0/" + KnownPackageName + "/files";
        yield return "/data/data/" + KnownPackageName + "/files";
    }

    private static string TryResolveFilesDirFromPublishDir(string assemblyDir)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(assemblyDir))
                return null;
            var dir = new DirectoryInfo(assemblyDir);
            if (dir.Name == "arm64" && dir.Parent?.Name == "publish" && dir.Parent.Parent?.Name == "mono" && dir.Parent.Parent.Parent?.Name == ".godot" && dir.Parent.Parent.Parent.Parent != null)
                return dir.Parent.Parent.Parent.Parent.FullName;
        }
        catch { }
        return null;
    }

    private static string ProcessPackageName()
    {
        try
        {
            var raw = System.Text.Encoding.UTF8.GetString(File.ReadAllBytes("/proc/self/cmdline"));
            var process = raw.Split('\0')[0].Trim();
            var colon = process.IndexOf(':');
            return colon > 0 ? process.Substring(0, colon) : process;
        }
        catch
        {
            return KnownPackageName;
        }
    }

    private static string NormalizeAbsolutePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return string.Empty;
        try { return Path.GetFullPath(path); }
        catch { return path; }
    }
}

internal sealed class OfflineProbe
{
    private readonly object _sync = new object();

    public int Schema { get; set; } = 2;
    public string ProbeContract { get; set; } = "offline-bootstrap-v2";
    public string Status { get; set; } = "starting";
    public bool Success { get; set; }
    public string WrittenUtc { get; set; }
    public string FilesDir { get; set; }
    public string GameDir { get; set; }
    public string PackId { get; set; }
    public string TargetId { get; set; }
    public string CompatVersion { get; set; }
    public string PackZipSha256 { get; set; }
    public string BootstrapAssemblyMvid { get; set; }
    public string PayloadVersion { get; set; }
    public string Sts2DllSha256 { get; set; }
    public string Sts2AssemblyMvid { get; set; }
    public string ModelDbStrategy { get; set; }
    public string ModelTypesSource { get; set; }
    public List<string> ModelDbInitSignatures { get; set; } = new List<string>();
    public int ExpectedModels { get; set; }
    public int RegisteredModels { get; set; }
    public int ConstructedModels { get; set; }
    public string FailureSummary { get; set; }
    public List<ProbeCheck> Checks { get; set; } = new List<ProbeCheck>();

    public bool HasCriticalFailure
    {
        get
        {
            lock (_sync)
                return Checks.Any(check => check.Critical && !check.Success);
        }
    }

    public static OfflineProbe Load()
    {
        var probe = new OfflineProbe
        {
            FilesDir = AndroidPaths.DataDir,
            GameDir = AndroidPaths.GameDir,
            PackId = AndroidPaths.SelectedCompatPackId,
            TargetId = AndroidPaths.SelectedCompatTargetId,
            CompatVersion = ReadAssemblyMetadata("OfflineBootstrapVersion") ?? "unknown",
            BootstrapAssemblyMvid = typeof(ModEntry).Module.ModuleVersionId.ToString("D"),
        };
        probe.ReadPayloadManifest();
        probe.ReadCompatManifest();
        return probe;
    }

    public void SetModelDbContract(ModelDbRuntimeContract contract)
    {
        if (contract == null)
            return;
        lock (_sync)
        {
            ModelDbInitSignatures = contract.InitMethods.Select(method => method.Signature).ToList();
            ModelDbStrategy = string.Join("+", contract.InitMethods.Select(method => method.Shape.ToString()).Distinct());
            ModelTypesSource = contract.ModelTypesSource;
            try { Sts2AssemblyMvid = contract.ModelDbType?.Module.ModuleVersionId.ToString("D"); }
            catch { Sts2AssemblyMvid = string.Empty; }
        }
    }

    public void BeginModelDbInitialization(ModelDbInitMethodContract init)
    {
        lock (_sync)
        {
            Status = "modeldb_initializing";
            Success = false;
            FailureSummary = string.Empty;
        }
        AddOrReplaceCheck("modeldb_runtime", false, "Running offline two-phase initialization through " + init.Signature + ".", critical: false);
    }

    public void CompleteModelDbInitialization(int expected, int registered, int constructed)
    {
        lock (_sync)
        {
            ExpectedModels = expected;
            RegisteredModels = registered;
            ConstructedModels = constructed;
            FailureSummary = string.Empty;
            Success = true;
            Status = "ready";
        }
        AddOrReplaceCheck(
            "modeldb_runtime",
            true,
            "Offline two-phase ModelDb initialization completed; expected=" + expected + ", registered=" + registered + ", constructed=" + constructed + ".",
            critical: true);
    }

    public void FailModelDbInitialization(Exception exception, int expected, int registered, int constructed, IEnumerable<string> failures)
    {
        var details = failures == null ? Array.Empty<string>() : failures.Where(value => !string.IsNullOrWhiteSpace(value)).Take(16).ToArray();
        var summary = exception?.GetType().Name + ": " + exception?.Message;
        if (details.Length > 0)
            summary += " | " + string.Join(" | ", details);
        if (summary.Length > 8192)
            summary = summary.Substring(0, 8192);
        lock (_sync)
        {
            ExpectedModels = expected;
            RegisteredModels = registered;
            ConstructedModels = constructed;
            FailureSummary = summary;
            Success = false;
            Status = "runtime_failed";
        }
        AddOrReplaceCheck("modeldb_runtime", false, summary, critical: true);
    }

    public void AddCheck(string name, bool success, string message, bool critical)
    {
        AddOrReplaceCheck(name, success, message, critical);
    }

    public void AddOrReplaceCheck(string name, bool success, string message, bool critical)
    {
        lock (_sync)
        {
            Checks.RemoveAll(check => string.Equals(check.Name, name, StringComparison.Ordinal));
            Checks.Add(new ProbeCheck
            {
                Name = name,
                Success = success,
                Critical = critical,
                Message = message ?? string.Empty,
            });
        }
    }

    public void Write()
    {
        string json;
        string status;
        try
        {
            lock (_sync)
            {
                WrittenUtc = DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture);
                json = JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true });
                status = Status;
            }
            var launcherDir = AndroidPaths.LauncherDir();
            Directory.CreateDirectory(launcherDir);
            var path = Path.Combine(launcherDir, "offline-bootstrap-probe.json");
            var temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllText(temporary, json);
                try
                {
                    File.Move(temporary, path, overwrite: true);
                }
                catch
                {
                    File.Copy(temporary, path, overwrite: true);
                    File.Delete(temporary);
                }
            }
            finally
            {
                if (File.Exists(temporary))
                    File.Delete(temporary);
            }
            ModEntry.Log("Wrote offline bootstrap probe status=" + status + ": " + path);
        }
        catch (Exception exception)
        {
            ModEntry.Log("Failed to write offline bootstrap probe: " + exception);
        }
    }

    private void ReadPayloadManifest()
    {
        try
        {
            var manifestPath = Path.Combine(GameDir ?? string.Empty, ".payload_manifest.json");
            if (!File.Exists(manifestPath))
                return;
            using var document = JsonDocument.Parse(File.ReadAllText(manifestPath));
            PayloadVersion = FindString(document.RootElement, "identity", "release_info", "version")
                ?? FindString(document.RootElement, "game", "release_info", "version")
                ?? FindString(document.RootElement, "identity", "version")
                ?? FindString(document.RootElement, "game", "version");
            Sts2DllSha256 = FindString(document.RootElement, "identity", "sts2_dll_sha256")
                ?? FindString(document.RootElement, "game", "sts2_dll_sha256");
        }
        catch (Exception exception)
        {
            AddOrReplaceCheck("payload_manifest", false, exception.Message, critical: false);
        }
    }

    private void ReadCompatManifest()
    {
        try
        {
            var compatPackDir = AndroidPaths.CompatPackDir;
            if (string.IsNullOrWhiteSpace(compatPackDir))
                return;
            var manifestPath = Path.Combine(compatPackDir, "compat_manifest.json");
            if (!File.Exists(manifestPath))
                return;
            using var document = JsonDocument.Parse(File.ReadAllText(manifestPath));
            PackId = FindString(document.RootElement, "pack_id") ?? PackId;
            CompatVersion = FindString(document.RootElement, "compat_version") ?? CompatVersion;
            PackZipSha256 = FindString(document.RootElement, "installed_source", "zip_sha256") ?? PackZipSha256;
        }
        catch (Exception exception)
        {
            AddOrReplaceCheck("compat_manifest", false, exception.Message, critical: false);
        }
    }

    private static string ReadAssemblyMetadata(string key)
    {
        try
        {
            return typeof(ModEntry).Assembly
                .GetCustomAttributes<AssemblyMetadataAttribute>()
                .FirstOrDefault(attribute => string.Equals(attribute.Key, key, StringComparison.Ordinal))
                ?.Value;
        }
        catch
        {
            return null;
        }
    }

    private static string FindString(JsonElement root, params string[] path)
    {
        var current = root;
        foreach (var part in path)
        {
            if (current.ValueKind != JsonValueKind.Object || !current.TryGetProperty(part, out current))
                return null;
        }
        return current.ValueKind == JsonValueKind.String ? current.GetString() : null;
    }
}

internal sealed class ProbeCheck
{
    public string Name { get; set; }
    public bool Success { get; set; }
    public bool Critical { get; set; }
    public string Message { get; set; }
}
