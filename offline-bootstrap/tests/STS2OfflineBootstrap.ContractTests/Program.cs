using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Loader;
using System.Threading.Tasks;
using STS2Mobile;

internal static class Program
{
    private static int _failures;

    private static int Main(string[] args)
    {
        RunSyntheticTests();

        foreach (var path in ParseAssemblyPaths(args))
            ValidateRealAssembly(path);

        if (_failures > 0)
        {
            Console.Error.WriteLine("Offline bootstrap contract tests failed: " + _failures);
            return 1;
        }
        Console.WriteLine("Offline bootstrap contract tests passed.");
        return 0;
    }

    private static void RunSyntheticTests()
    {
        AssertShapes(typeof(ModelDbNoArgs), ModelDbInitShape.Parameterless);
        AssertShapes(typeof(ModelDbOptionalTypes), ModelDbInitShape.OptionalInjectedModelTypes);
        AssertShapes(typeof(ModelDbOverloads), ModelDbInitShape.Parameterless, ModelDbInitShape.OptionalInjectedModelTypes);
        AssertNoShapes(typeof(ModelDbRequiredTypes));
        AssertNoShapes(typeof(ModelDbUnknownOptions));
        AssertNoShapes(typeof(ModelDbAsync));

        var optional = ModelDbRuntimeContract.Resolve(typeof(ModelDbOptionalTypes), typeof(FixtureModel), typeof(FixtureSubtypes));
        Assert(optional.Ready, "full optional-Type[] fixture contract should resolve: " + optional.Diagnostic);
        Assert(optional.InitMethods.Count == 1, "optional-Type[] fixture should expose one supported Init method");
        if (optional.InitMethods.Count == 1)
        {
            Assert(!optional.InitMethods[0].HasExplicitInjectedModelTypes(new object[] { null }), "null default injection must use offline two-phase initialization");
            Assert(optional.InitMethods[0].HasExplicitInjectedModelTypes(new object[] { Array.Empty<Type>() }), "an explicit Type[] must preserve the original Init path");
        }
        var uninitialized = System.Runtime.CompilerServices.RuntimeHelpers.GetUninitializedObject(typeof(ConcreteFixtureModel));
        var seededId = new FixtureModelId("seeded");
        optional.SeedModelId(uninitialized, seededId);
        Assert(((FixtureModel)uninitialized).Id.Equals(seededId), "resolved readonly ModelId storage should accept placeholder seeding");
        Assert(optional.ReadContentById() != null, "resolved content map should be writable through IDictionary");

        var noArgs = ModelDbRuntimeContract.Resolve(typeof(ModelDbNoArgs), typeof(FixtureModel), typeof(FixtureSubtypes));
        Assert(noArgs.Ready, "full parameterless fixture contract should resolve: " + noArgs.Diagnostic);
    }

    private static IEnumerable<string> ParseAssemblyPaths(string[] args)
    {
        for (var i = 0; i < (args?.Length ?? 0); i++)
        {
            if (!string.Equals(args[i], "--assembly", StringComparison.Ordinal))
                continue;
            if (++i >= args.Length)
            {
                Assert(false, "--assembly requires a path");
                yield break;
            }
            yield return Path.GetFullPath(args[i]);
        }
    }

    private static void ValidateRealAssembly(string assemblyPath)
    {
        if (!File.Exists(assemblyPath))
        {
            Assert(false, "reference assembly does not exist: " + assemblyPath);
            return;
        }

        var loadContext = new ReferenceAssemblyLoadContext(Path.GetDirectoryName(assemblyPath));
        try
        {
            var assembly = loadContext.LoadFromAssemblyPath(assemblyPath);
            var modelDbType = assembly.GetType("MegaCrit.Sts2.Core.Models.ModelDb", throwOnError: false, ignoreCase: false);
            var abstractModelType = assembly.GetType("MegaCrit.Sts2.Core.Models.AbstractModel", throwOnError: false, ignoreCase: false);
            var generatedSubtypesType = assembly.GetType("MegaCrit.Sts2.Core.Models.AbstractModelSubtypes", throwOnError: false, ignoreCase: false);
            var contract = ModelDbRuntimeContract.Resolve(modelDbType, abstractModelType, generatedSubtypesType);
            Assert(contract.Ready, Path.GetFileName(Path.GetDirectoryName(assemblyPath)) + "/sts2.dll contract should resolve: " + contract.Diagnostic);
            if (contract.Ready)
            {
                Console.WriteLine(
                    "Resolved " + assemblyPath
                    + ": " + string.Join(" | ", contract.InitMethods.Select(method => method.Shape + "=" + method.Signature))
                    + "; model_types=" + contract.ModelTypesSource);
            }
        }
        catch (Exception exception)
        {
            Assert(false, "unable to inspect " + assemblyPath + ": " + exception);
        }
        finally
        {
            loadContext.Unload();
        }
    }

    private static void AssertShapes(Type type, params ModelDbInitShape[] expected)
    {
        var rejected = new List<string>();
        var actual = ModelDbRuntimeContract.ResolveInitMethods(type, rejected).Select(contract => contract.Shape).ToArray();
        Assert(actual.SequenceEqual(expected), type.Name + " shapes were [" + string.Join(", ", actual) + "] instead of [" + string.Join(", ", expected) + "]; rejected=" + string.Join("; ", rejected));
    }

    private static void AssertNoShapes(Type type)
    {
        AssertShapes(type, Array.Empty<ModelDbInitShape>());
    }

    private static void Assert(bool condition, string message)
    {
        if (condition)
            return;
        _failures++;
        Console.Error.WriteLine("FAIL: " + message);
    }

    private readonly struct FixtureModelId : IEquatable<FixtureModelId>
    {
        private readonly string _value;
        public FixtureModelId(string value) => _value = value;
        public bool Equals(FixtureModelId other) => string.Equals(_value, other._value, StringComparison.Ordinal);
        public override bool Equals(object obj) => obj is FixtureModelId other && Equals(other);
        public override int GetHashCode() => _value?.GetHashCode(StringComparison.Ordinal) ?? 0;
    }

    private abstract class FixtureModel
    {
        public FixtureModelId Id { get; }
        protected FixtureModel() { }
    }

    private sealed class ConcreteFixtureModel : FixtureModel
    {
        public ConcreteFixtureModel() { }
    }

    private static class FixtureSubtypes
    {
        public static IReadOnlyList<Type> All { get; } = new[] { typeof(ConcreteFixtureModel) };
    }

    private static class ModelDbNoArgs
    {
        private static readonly Dictionary<FixtureModelId, FixtureModel> _contentById = new();
        public static FixtureModelId GetId(Type type) => new(type.FullName);
        public static void Init() { }
    }

    private static class ModelDbOptionalTypes
    {
        private static readonly Dictionary<FixtureModelId, FixtureModel> _contentById = new();
        public static FixtureModelId GetId(Type type) => new(type.FullName);
        public static void Init(Type[] injectedModelTypes = null) { }
    }

    private static class ModelDbOverloads
    {
        public static void Init() { }
        public static void Init(Type[] injectedModelTypes = null) { }
    }

    private static class ModelDbRequiredTypes
    {
        public static void Init(Type[] injectedModelTypes) { }
    }

    private static class ModelDbUnknownOptions
    {
        public static void Init(Type[] injectedModelTypes = null, bool rebuildCaches = true) { }
    }

    private static class ModelDbAsync
    {
        public static Task Init() => Task.CompletedTask;
    }

    private sealed class ReferenceAssemblyLoadContext : AssemblyLoadContext
    {
        private readonly string _referenceDirectory;

        public ReferenceAssemblyLoadContext(string referenceDirectory)
            : base(isCollectible: true)
        {
            _referenceDirectory = referenceDirectory;
        }

        protected override Assembly Load(AssemblyName assemblyName)
        {
            if (string.IsNullOrWhiteSpace(_referenceDirectory))
                return null;
            var candidate = Path.Combine(_referenceDirectory, assemblyName.Name + ".dll");
            return File.Exists(candidate) ? LoadFromAssemblyPath(candidate) : null;
        }
    }
}
