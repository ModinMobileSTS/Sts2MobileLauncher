using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

namespace STS2Mobile;

internal enum ModelDbInitShape
{
    Parameterless,
    OptionalInjectedModelTypes,
}

internal sealed class ModelDbInitMethodContract
{
    public MethodInfo Method { get; }
    public ModelDbInitShape Shape { get; }
    public string Signature { get; }

    public ModelDbInitMethodContract(MethodInfo method, ModelDbInitShape shape)
    {
        Method = method ?? throw new ArgumentNullException(nameof(method));
        Shape = shape;
        Signature = ModelDbRuntimeContract.DescribeMethod(method);
    }

    public bool HasExplicitInjectedModelTypes(object[] args)
    {
        return Shape == ModelDbInitShape.OptionalInjectedModelTypes
            && args != null
            && args.Length == 1
            && args[0] is Type[];
    }
}

internal sealed class ModelDbRuntimeContract
{
    public Type ModelDbType { get; private set; }
    public Type AbstractModelType { get; private set; }
    public IReadOnlyList<ModelDbInitMethodContract> InitMethods { get; private set; } = Array.Empty<ModelDbInitMethodContract>();
    public MethodInfo GetIdMethod { get; private set; }
    public PropertyInfo ModelTypesProperty { get; private set; }
    public string ModelTypesSource { get; private set; }
    public FieldInfo ContentByIdField { get; private set; }
    public FieldInfo ModelIdField { get; private set; }
    public ConstructorInfo AbstractModelConstructor { get; private set; }
    public string Diagnostic { get; private set; }
    public IReadOnlyList<string> RejectedInitMethods { get; private set; } = Array.Empty<string>();

    public bool Ready => ModelDbType != null
        && AbstractModelType != null
        && InitMethods.Count > 0
        && GetIdMethod != null
        && ModelTypesProperty != null
        && ContentByIdField != null
        && ModelIdField != null
        && AbstractModelConstructor != null;

    public static ModelDbRuntimeContract Resolve(Type modelDbType, Type abstractModelType, Type generatedSubtypesType)
    {
        var contract = new ModelDbRuntimeContract
        {
            ModelDbType = modelDbType,
            AbstractModelType = abstractModelType,
        };
        var errors = new List<string>();

        if (modelDbType == null)
            errors.Add("ModelDb type was not found.");
        if (abstractModelType == null)
            errors.Add("AbstractModel type was not found.");
        if (errors.Count > 0)
        {
            contract.Diagnostic = string.Join(" ", errors);
            return contract;
        }

        var rejected = new List<string>();
        contract.InitMethods = ResolveInitMethods(modelDbType, rejected);
        contract.RejectedInitMethods = rejected;
        if (contract.InitMethods.Count == 0)
        {
            var detail = rejected.Count == 0 ? "no static Init candidates were found" : string.Join("; ", rejected);
            errors.Add("No supported ModelDb.Init API shape was found: " + detail + ".");
        }

        contract.GetIdMethod = modelDbType.GetMethod(
            "GetId",
            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static,
            binder: null,
            types: new[] { typeof(Type) },
            modifiers: null);
        if (contract.GetIdMethod == null || contract.GetIdMethod.ReturnType == typeof(void))
        {
            contract.GetIdMethod = null;
            errors.Add("ModelDb.GetId(Type) was not found.");
        }

        contract.ModelTypesProperty = ResolveModelTypesProperty(modelDbType, generatedSubtypesType, out var modelTypesSource);
        contract.ModelTypesSource = modelTypesSource;
        if (contract.ModelTypesProperty == null)
            errors.Add("No supported model type enumeration property was found.");

        if (contract.GetIdMethod != null)
        {
            contract.ContentByIdField = ResolveContentByIdField(modelDbType, abstractModelType, contract.GetIdMethod.ReturnType, out var contentDiagnostic);
            if (contract.ContentByIdField == null)
                errors.Add(contentDiagnostic);

            contract.ModelIdField = ResolveModelIdField(abstractModelType, contract.GetIdMethod.ReturnType, out var modelIdDiagnostic);
            if (contract.ModelIdField == null)
                errors.Add(modelIdDiagnostic);
        }

        contract.AbstractModelConstructor = abstractModelType.GetConstructor(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null,
            types: Type.EmptyTypes,
            modifiers: null);
        if (contract.AbstractModelConstructor == null)
            errors.Add("AbstractModel parameterless constructor was not found.");

        contract.Diagnostic = errors.Count == 0
            ? "Resolved " + contract.InitMethods.Count + " ModelDb.Init method(s); model_types=" + contract.ModelTypesSource
                + "; content=" + contract.ContentByIdField.Name + "; model_id=" + contract.ModelIdField.Name + "."
            : string.Join(" ", errors);
        return contract;
    }

    public ModelDbInitMethodContract FindInitMethod(MethodBase method)
    {
        if (method == null)
            return null;
        foreach (var candidate in InitMethods)
        {
            if (candidate.Method == method)
                return candidate;
            try
            {
                if (candidate.Method.Module == method.Module && candidate.Method.MetadataToken == method.MetadataToken)
                    return candidate;
            }
            catch
            {
            }
        }
        return null;
    }

    public IEnumerable<Type> ReadModelTypes()
    {
        var value = ModelTypesProperty?.GetValue(null);
        if (value is IEnumerable<Type> typed)
            return typed;
        if (value is IEnumerable untyped)
            return untyped.Cast<object>().OfType<Type>();
        throw new InvalidOperationException("Resolved model type property did not return an enumerable Type collection: " + ModelTypesProperty + ".");
    }

    public IDictionary ReadContentById()
    {
        var value = ContentByIdField?.GetValue(null);
        if (value is IDictionary dictionary)
            return dictionary;
        throw new InvalidOperationException("Resolved ModelDb content field is not a writable IDictionary: " + ContentByIdField + ".");
    }

    public object GetModelId(Type modelType)
    {
        return GetIdMethod.Invoke(null, new object[] { modelType });
    }

    public void SeedModelId(object model, object id)
    {
        ModelIdField.SetValue(model, id);
    }

    internal static IReadOnlyList<ModelDbInitMethodContract> ResolveInitMethods(Type modelDbType, List<string> rejected)
    {
        var resolved = new List<ModelDbInitMethodContract>();
        if (modelDbType == null)
            return resolved;

        var methods = modelDbType
            .GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)
            .Where(method => string.Equals(method.Name, "Init", StringComparison.Ordinal))
            .OrderBy(SafeMetadataToken)
            .ThenBy(DescribeMethod, StringComparer.Ordinal)
            .ToArray();

        foreach (var method in methods)
        {
            if (method.IsGenericMethodDefinition || method.ReturnType != typeof(void))
            {
                rejected?.Add(DescribeMethod(method) + " (requires non-generic void return)");
                continue;
            }

            var parameters = method.GetParameters();
            if (parameters.Length == 0)
            {
                resolved.Add(new ModelDbInitMethodContract(method, ModelDbInitShape.Parameterless));
                continue;
            }

            if (parameters.Length == 1
                && parameters[0].ParameterType == typeof(Type[])
                && parameters[0].IsOptional
                && parameters[0].HasDefaultValue
                && parameters[0].DefaultValue == null)
            {
                resolved.Add(new ModelDbInitMethodContract(method, ModelDbInitShape.OptionalInjectedModelTypes));
                continue;
            }

            rejected?.Add(DescribeMethod(method) + " (unknown parameter semantics)");
        }
        return resolved;
    }

    internal static string DescribeMethod(MethodInfo method)
    {
        if (method == null)
            return "<null>";
        var parameters = string.Join(", ", method.GetParameters().Select(parameter =>
        {
            var suffix = parameter.HasDefaultValue ? "=" + DescribeDefaultValue(parameter.DefaultValue) : string.Empty;
            return FriendlyTypeName(parameter.ParameterType) + " " + parameter.Name + suffix;
        }));
        return FriendlyTypeName(method.ReturnType) + " " + method.DeclaringType?.FullName + "." + method.Name + "(" + parameters + ")";
    }

    private static PropertyInfo ResolveModelTypesProperty(Type modelDbType, Type generatedSubtypesType, out string source)
    {
        source = string.Empty;
        var generated = generatedSubtypesType?.GetProperty("All", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        if (IsTypeEnumerableProperty(generated))
        {
            source = generatedSubtypesType.FullName + ".All";
            return generated;
        }

        var modelDb = modelDbType.GetProperty("AllAbstractModelSubtypes", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
        if (IsTypeEnumerableProperty(modelDb))
        {
            source = modelDbType.FullName + ".AllAbstractModelSubtypes";
            return modelDb;
        }
        return null;
    }

    private static bool IsTypeEnumerableProperty(PropertyInfo property)
    {
        return property?.GetGetMethod(nonPublic: true)?.IsStatic == true
            && typeof(IEnumerable<Type>).IsAssignableFrom(property.PropertyType);
    }

    private static FieldInfo ResolveContentByIdField(Type modelDbType, Type abstractModelType, Type modelIdType, out string diagnostic)
    {
        diagnostic = "ModelDb writable content dictionary was not found.";
        var exact = modelDbType.GetField("_contentById", BindingFlags.NonPublic | BindingFlags.Static);
        if (IsCompatibleDictionaryField(exact, abstractModelType, modelIdType))
            return exact;

        var candidates = modelDbType
            .GetFields(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static)
            .Where(field => IsCompatibleDictionaryField(field, abstractModelType, modelIdType))
            .ToArray();
        if (candidates.Length == 1)
            return candidates[0];
        if (candidates.Length > 1)
            diagnostic = "ModelDb content dictionary discovery was ambiguous: " + string.Join(", ", candidates.Select(field => field.Name)) + ".";
        return null;
    }

    private static bool IsCompatibleDictionaryField(FieldInfo field, Type abstractModelType, Type modelIdType)
    {
        if (field == null || !field.IsStatic || !typeof(IDictionary).IsAssignableFrom(field.FieldType))
            return false;
        var dictionaryInterface = field.FieldType
            .GetInterfaces()
            .Concat(new[] { field.FieldType })
            .FirstOrDefault(type => type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IDictionary<,>));
        if (dictionaryInterface == null)
            return string.Equals(field.Name, "_contentById", StringComparison.Ordinal);
        var arguments = dictionaryInterface.GetGenericArguments();
        return arguments[0] == modelIdType && abstractModelType.IsAssignableFrom(arguments[1]);
    }

    private static FieldInfo ResolveModelIdField(Type abstractModelType, Type modelIdType, out string diagnostic)
    {
        diagnostic = "AbstractModel model ID storage field was not found.";
        var exact = abstractModelType.GetField("<Id>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);
        if (exact != null && exact.FieldType == modelIdType)
            return exact;

        var candidates = abstractModelType
            .GetFields(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
            .Where(field => field.FieldType == modelIdType && field.Name.IndexOf("id", StringComparison.OrdinalIgnoreCase) >= 0)
            .ToArray();
        if (candidates.Length == 1)
            return candidates[0];
        if (candidates.Length > 1)
            diagnostic = "AbstractModel model ID field discovery was ambiguous: " + string.Join(", ", candidates.Select(field => field.Name)) + ".";
        return null;
    }

    private static int SafeMetadataToken(MethodInfo method)
    {
        try { return method.MetadataToken; }
        catch { return int.MaxValue; }
    }

    private static string DescribeDefaultValue(object value)
    {
        if (value == null)
            return "null";
        if (value == Missing.Value)
            return "missing";
        return Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture) ?? value.ToString();
    }

    private static string FriendlyTypeName(Type type)
    {
        if (type == null)
            return "<null>";
        if (type.IsArray)
            return FriendlyTypeName(type.GetElementType()) + "[]";
        return type.FullName ?? type.Name;
    }
}
