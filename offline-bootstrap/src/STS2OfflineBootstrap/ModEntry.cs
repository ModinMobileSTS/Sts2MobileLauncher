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
        probe.AddCheck("entry", true, "Offline bootstrap entry loaded.", critical: true);

        try
        {
            _harmony = new Harmony("com.sts2mobile.offlinebootstrap");
            OfflineRuntimePatches.Apply(_harmony, probe);
            probe.Success = probe.HasCriticalFailure == false;
            probe.Status = probe.Success ? "ready" : "probe_failed";
        }
        catch (Exception exception)
        {
            probe.Success = false;
            probe.Status = "exception";
            probe.AddCheck("apply_exception", false, exception.ToString(), critical: true);
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
    private static bool _phase1Completed;
    private static bool _phase2Completed;
    private static bool _suppressAbstractModelConstructor;
    private static FieldInfo _modelIdBackingField;
    private static bool _loggedModelIdSeedFailure;

    public static void Apply(Harmony harmony, OfflineProbe probe)
    {
        try
        {
            var modelDbType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.ModelDb");
            var abstractModelType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModel");
            if (modelDbType == null || abstractModelType == null)
            {
                probe.AddCheck("modeldb_two_phase", false, "ModelDb or AbstractModel type not found.", critical: true);
                return;
            }

            var init = modelDbType.GetMethod("Init", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, Type.EmptyTypes, null);
            var initPrefix = typeof(OfflineModelDbPatches).GetMethod(nameof(ModelDbInitPrefix), AllFlags);
            if (init == null || initPrefix == null)
            {
                probe.AddCheck("modeldb_two_phase", false, "ModelDb.Init patch target not found.", critical: true);
                return;
            }
            harmony.Patch(init, prefix: new HarmonyMethod(initPrefix));

            var constructor = abstractModelType.GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null, Type.EmptyTypes, null);
            var constructorPrefix = typeof(OfflineModelDbPatches).GetMethod(nameof(AbstractModelConstructorPrefix), AllFlags);
            if (constructor != null && constructorPrefix != null)
            {
                harmony.Patch(constructor, prefix: new HarmonyMethod(constructorPrefix) { priority = Priority.First });
                probe.AddCheck("abstract_model_ctor_guard", true, "Patched AbstractModel constructor for offline two-phase ModelDb init.", critical: false);
            }
            else
            {
                probe.AddCheck("abstract_model_ctor_guard", false, "AbstractModel constructor patch target not found.", critical: false);
            }

            probe.AddCheck("modeldb_two_phase", true, "Patched ModelDb.Init for offline two-phase placeholder registration.", critical: true);
            ModEntry.Log("Patched ModelDb.Init for offline two-phase placeholder registration.");
        }
        catch (Exception exception)
        {
            probe.AddCheck("modeldb_two_phase", false, exception.ToString(), critical: true);
            ModEntry.Log("Failed to patch offline ModelDb init: " + exception);
        }
    }

    public static bool ModelDbInitPrefix()
    {
        RunTwoPhaseModelDbInit();
        return false;
    }

    public static bool AbstractModelConstructorPrefix(object __instance)
    {
        if (!_suppressAbstractModelConstructor || __instance == null)
            return true;

        try
        {
            var modelDbType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.ModelDb");
            var getId = modelDbType?.GetMethod("GetId", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, new[] { typeof(Type) }, null);
            var id = getId?.Invoke(null, new object[] { __instance.GetType() });
            if (id != null)
                TrySeedModelId(__instance, id);
            return false;
        }
        catch (Exception exception)
        {
            ModEntry.Log("Offline ModelDb constructor guard failed for " + __instance.GetType().FullName + ": " + GetRootException(exception).Message);
            return true;
        }
    }

    private static void RunTwoPhaseModelDbInit()
    {
        lock (PhaseLock)
        {
            if (_phase2Completed)
            {
                ModEntry.Log("Offline ModelDb.Init invoked again after completion; ignoring.");
                return;
            }
        }

        RunPhase1PreRegistration();
        RunPreRegisteredModelConstructors();
    }

    private static void RunPhase1PreRegistration()
    {
        lock (PhaseLock)
        {
            if (_phase1Completed || _phase2Completed)
                return;
            _phase1Completed = true;
        }

        Type[] types;
        try
        {
            types = GetVanillaModelTypes();
        }
        catch (Exception exception)
        {
            ModEntry.Log("Offline ModelDb failed to enumerate vanilla model types: " + GetRootException(exception).Message);
            return;
        }

        PreRegisterModelPlaceholders(types);
    }

    private static Type[] GetVanillaModelTypes()
    {
        var subtypesType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModelSubtypes");
        var allProperty = subtypesType?.GetProperty("All", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        var value = allProperty?.GetValue(null);
        if (value is IEnumerable<Type> enumerable)
            return enumerable.Where(type => type != null && !type.IsAbstract).ToArray();
        throw new InvalidOperationException("AbstractModelSubtypes.All not found.");
    }

    private static void PreRegisterModelPlaceholders(Type[] rawTypes)
    {
        var modelDbType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.ModelDb");
        var abstractModelType = TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModel");
        var getId = modelDbType?.GetMethod("GetId", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static, null, new[] { typeof(Type) }, null);
        var contentByIdField = modelDbType?.GetField("_contentById", BindingFlags.NonPublic | BindingFlags.Static);
        var contentById = contentByIdField?.GetValue(null);
        var setItem = contentById?.GetType().GetMethod("set_Item");
        var dictionary = contentById as System.Collections.IDictionary;
        if (modelDbType == null || abstractModelType == null || getId == null || contentById == null || setItem == null || dictionary == null)
        {
            ModEntry.Log("Offline ModelDb pre-registration failed: missing reflection members.");
            return;
        }

        var registered = 0;
        var skipped = 0;
        lock (PhaseLock)
        {
            foreach (var type in rawTypes ?? Array.Empty<Type>())
            {
                if (type == null || type.IsAbstract || !abstractModelType.IsAssignableFrom(type))
                    continue;
                if (PreRegisteredModels.ContainsKey(type))
                    continue;
                try
                {
                    var id = getId.Invoke(null, new object[] { type });
                    if (id == null || dictionary.Contains(id))
                    {
                        skipped++;
                        continue;
                    }
                    var model = RuntimeHelpers.GetUninitializedObject(type);
                    TrySeedModelId(model, id);
                    setItem.Invoke(contentById, new[] { id, model });
                    PreRegisteredModels[type] = model;
                    PreRegisteredOrder.Add(type);
                    registered++;
                }
                catch (Exception exception)
                {
                    skipped++;
                    var root = GetRootException(exception);
                    ModEntry.Log("Offline ModelDb pre-registration failed for " + type.FullName + ": " + root.GetType().Name + ": " + root.Message);
                }
            }
        }
        ModEntry.Log("Offline ModelDb pre-registered " + registered + " vanilla model placeholder(s); skipped=" + skipped + ".");
    }

    private static void RunPreRegisteredModelConstructors()
    {
        List<Type> types;
        lock (PhaseLock)
        {
            types = new List<Type>(PreRegisteredOrder);
        }

        ClearModelDbDerivedCaches("before offline constructor phase");
        try
        {
            _suppressAbstractModelConstructor = true;
            var succeeded = 0;
            var failed = 0;
            foreach (var type in types)
            {
                object model;
                lock (PhaseLock)
                {
                    if (!PreRegisteredModels.TryGetValue(type, out model))
                        continue;
                }
                try
                {
                    RuntimeHelpers.RunClassConstructor(type.TypeHandle);
                    var constructor = type.GetConstructor(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null);
                    constructor?.Invoke(model, null);
                    succeeded++;
                }
                catch (Exception exception)
                {
                    failed++;
                    var root = GetRootException(exception);
                    ModEntry.Log("Offline ModelDb constructor phase failed for " + type.FullName + ": " + root.GetType().Name + ": " + root.Message);
                }
            }
            ModEntry.Log("Offline ModelDb constructor phase completed; succeeded=" + succeeded + ", failed=" + failed + ".");
        }
        finally
        {
            _suppressAbstractModelConstructor = false;
            ClearModelDbDerivedCaches("after offline constructor phase");
            lock (PhaseLock)
            {
                _phase2Completed = true;
            }
        }
    }

    private static void TrySeedModelId(object model, object id)
    {
        if (model == null || id == null)
            return;
        try
        {
            _modelIdBackingField ??= TypeResolver.FindType("MegaCrit.Sts2.Core.Models.AbstractModel")?.GetField("<Id>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);
            _modelIdBackingField?.SetValue(model, id);
        }
        catch (Exception exception)
        {
            if (!_loggedModelIdSeedFailure)
            {
                _loggedModelIdSeedFailure = true;
                ModEntry.Log("Offline ModelDb failed to seed placeholder Id: " + exception.Message);
            }
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
        try
        {
            var context = LaunchContext();
            if (context.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value))
                return NormalizeAbsolutePath(value);
        }
        catch (Exception exception)
        {
            ModEntry.Log("Launch context lookup failed for " + key + ": " + exception.Message);
        }
        return NormalizeAbsolutePath(fallback);
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
    public int Schema { get; set; } = 1;
    public string ProbeContract { get; set; } = "offline-bootstrap-v1";
    public string Status { get; set; } = "starting";
    public bool Success { get; set; }
    public string WrittenUtc { get; set; }
    public string FilesDir { get; set; }
    public string GameDir { get; set; }
    public string PayloadVersion { get; set; }
    public string Sts2DllSha256 { get; set; }
    public List<ProbeCheck> Checks { get; set; } = new List<ProbeCheck>();

    public bool HasCriticalFailure => Checks.Any(check => check.Critical && !check.Success);

    public static OfflineProbe Load()
    {
        var probe = new OfflineProbe
        {
            FilesDir = AndroidPaths.DataDir,
            GameDir = AndroidPaths.GameDir,
        };
        probe.ReadPayloadManifest();
        return probe;
    }

    public void AddCheck(string name, bool success, string message, bool critical)
    {
        Checks.Add(new ProbeCheck
        {
            Name = name,
            Success = success,
            Critical = critical,
            Message = message ?? string.Empty,
        });
    }

    public void Write()
    {
        try
        {
            WrittenUtc = DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture);
            var launcherDir = AndroidPaths.LauncherDir();
            Directory.CreateDirectory(launcherDir);
            var path = Path.Combine(launcherDir, "offline-bootstrap-probe.json");
            var options = new JsonSerializerOptions { WriteIndented = true };
            File.WriteAllText(path, JsonSerializer.Serialize(this, options));
            ModEntry.Log("Wrote offline bootstrap probe: " + path);
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
            AddCheck("payload_manifest", false, exception.Message, critical: false);
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
