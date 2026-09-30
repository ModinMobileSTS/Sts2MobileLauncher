using System.Reflection;
using System.Reflection.Emit;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using HarmonyLib;
using MonoMod.Utils;

// Execute the actual packaged helper on guarded unmanaged storage, not a copy of
// its implementation. Forcing only detection does not turn CoreCLR into Mono;
// Android access checks and a full game launch still require device validation.
var runtimeField = typeof(PlatformDetection).GetField("runtime", BindingFlags.NonPublic | BindingFlags.Static)!;
var realRuntime = PlatformDetection.Runtime;
var cache = (Dictionary<string, WeakReference>)typeof(ReflectionHelper)
    .GetField("AssemblyCache", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
try
{
    runtimeField.SetValue(null, RuntimeKind.Mono);
    using var guarded = new GuardedAssembly();
    foreach (bool value in new[] { true, false, true })
    {
        guarded.SetMonoCorlibInternal(value);
        guarded.AssertUnchanged();
        AssertCached(guarded);
    }
    // A managed-only Assembly has no native pointer field at all. No private
    // layout probing should be required just to register it in the resolver.
    Assembly ordinary = typeof(GuardedAssembly).Assembly;
    ordinary.SetMonoCorlibInternal(true);
    AssertCached(ordinary);
    try
    {
        new FailingNameAssembly().SetMonoCorlibInternal(true);
        throw new Exception("Expected cache-key failure was swallowed");
    }
    catch (InvalidOperationException error) when (error.Message == "cache-key failure") { }
    Assert(!Monitor.IsEntered(cache), "Assembly cache lock leaked after exception");
    try
    {
        MonoMod.Utils.Extensions.SetMonoCorlibInternal(null!, true);
        throw new Exception("Mono null argument was accepted");
    }
    catch (ArgumentNullException) { }
    Console.WriteLine("PASS helper: true/false/repeated calls preserve native bytes and all resolver cache keys");
}
finally
{
    runtimeField.SetValue(null, realRuntime);
}

// Exercise a real Cecil-generated wrapper and nested generated dependency. This
// crosses DMDGenerator.Postbuild and resolves a private member, not just IL text.
MethodInfo generated;
using (var method = new DynamicMethodDefinition("PrivateMemberSmoke", typeof(int), new[] { typeof(int) }))
{
    var il = method.GetILGenerator();
    il.Emit(OpCodes.Ldarg_0);
    il.Emit(OpCodes.Call, typeof(Target).GetMethod("PrivateIncrement", BindingFlags.NonPublic | BindingFlags.Static)!);
    il.Emit(OpCodes.Ret);
    generated = DMDCecilGenerator.Generate(method);
}
Assert((int)generated.Invoke(null, new object[] { 41 })! == 42, "Cecil private-member wrapper returned wrong value");
using (var caller = new DynamicMethodDefinition("GeneratedCallerSmoke", typeof(int), new[] { typeof(int) }))
{
    var il = caller.GetILGenerator();
    il.Emit(OpCodes.Ldarg_0);
    il.Emit(OpCodes.Call, generated);
    il.Emit(OpCodes.Ret);
    var method = DMDCecilGenerator.Generate(caller);
    Assert((int)method.Invoke(null, new object[] { 71 })! == 72, "Cecil generated-assembly reference failed");
}
Console.WriteLine("PASS Cecil: private member access and generated-to-generated call");

var harmony = new Harmony("sts2re.monomod.corlib.safety");
var target = typeof(Target).GetMethod(nameof(Target.Calculate))!;
Assert(Target.Calculate(5) == 10, "Unexpected unpatched result");
try
{
    harmony.Patch(target, postfix: new HarmonyMethod(typeof(Target).GetMethod(nameof(Target.Postfix))!));
    Assert(Target.Calculate(5) == 17, "Harmony wrapper did not execute");
}
finally
{
    harmony.Unpatch(target, HarmonyPatchType.All, harmony.Id);
}
Assert(Target.Calculate(5) == 10, "Harmony unpatch did not restore behavior");
Console.WriteLine("PASS Harmony: patch, execution and unpatch (host runtime)");

void AssertCached(Assembly assembly)
{
    var name = assembly.GetName();
    foreach (string key in new[] { assembly.GetRuntimeHashedFullName(), name.FullName, name.Name! })
        Assert(cache.TryGetValue(key, out var entry) && ReferenceEquals(entry.Target, assembly), "Missing assembly cache key: " + key);
}

static void Assert(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}

sealed class GuardedAssembly : Assembly, IDisposable
{
    public readonly IntPtr _mono_assembly;
    private readonly byte[] original = Enumerable.Repeat((byte)0xa5, 256).ToArray();
    private readonly AssemblyName name = new("Guarded.MonoAssembly, Version=1.0.0.0");

    public GuardedAssembly()
    {
        _mono_assembly = Marshal.AllocHGlobal(original.Length);
        Marshal.Copy(original, 0, _mono_assembly, original.Length);
    }

    public override string FullName => name.FullName;
    public override AssemblyName GetName() => name;
    public override AssemblyName GetName(bool copiedName) => name;

    public void AssertUnchanged()
    {
        byte[] actual = new byte[original.Length];
        Marshal.Copy(_mono_assembly, actual, 0, actual.Length);
        for (int i = 0; i < actual.Length; i++)
            if (actual[i] != original[i])
                throw new Exception($"Native assembly memory changed at +0x{i:x}: {original[i]:x2} -> {actual[i]:x2}");
    }

    public void Dispose() => Marshal.FreeHGlobal(_mono_assembly);
}

sealed class FailingNameAssembly : Assembly
{
    public override AssemblyName GetName() => new("Failing.MonoAssembly");
    public override string FullName => throw new InvalidOperationException("cache-key failure");
}

static class Target
{
    private static int PrivateIncrement(int input) => input + 1;
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static int Calculate(int input) => input * 2;
    public static void Postfix(ref int __result) => __result += 7;
}
