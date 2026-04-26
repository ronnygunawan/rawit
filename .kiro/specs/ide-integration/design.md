# Design Document: IDE Integration (Instant IDE Reflection)

## Overview

This document describes the design of the IDE integration feature for Rawit, which enables
instant IDE reflection for generated staged APIs in javac-based IDE tools (e.g. IntelliJ IDEA)
by injecting entry-point methods directly into the original annotated class's AST — the same
Lombok-like approach Lombok uses for `@Builder`, `@Getter`, etc.

## Problem Statement

Rawit's annotation processor operates on two distinct compiler paths:

1. **javac path** (CLI builds, Maven, Gradle): The `RawitAnnotationProcessor` registers a
   `JavacTask.TaskListener` that fires after each `.class` file is written. This allows
   single-pass compilation — the processor generates staged API source files and also injects
   parameterless entry-point methods into the original class's bytecode in the same compilation.

2. **ECJ/JDT LS path** (VS Code Java extension): When running under ECJ (Eclipse Compiler for
   Java), the `JavacTask.addTaskListener()` call fails with an `IllegalArgumentException` because
   `JavacTask.instance()` is not available. The processor falls back to multi-pass mode or skips
   bytecode injection when the `.class` file is not yet available.

The fundamental issue is that the entry-point methods (e.g. `Foo.constructor()`, `foo.bar()`)
are added to the original class's **bytecode only** — they are never present in source form.
Javac-based IDE tools (e.g. IntelliJ IDEA with javac backend) may not see these methods until
the class is recompiled.

## Design Approach

### Lombok-like AST injection (javac path)

Rather than adding the entry-point to a separate generated class, the processor uses javac's
internal `TreeMaker` API (accessed via reflection, exactly as Lombok does) to inject the
entry-point method directly into the original annotated class's AST. This means:

- `Foo.constructor()` appears on `Foo` — not on `FooConstructor`
- `foo.bar()` appears on `Foo` — not on `FooBarInvoker`
- The injected method is visible to the javac type-checker immediately

```
Annotation Processor
├── JavaPoetGenerator          → generates FooBarInvoker.java (source, unchanged)
├── BytecodeInjector           → injects foo.bar() into Foo.class bytecode (unchanged)
└── JavacAstInjector           → injects foo.bar() into Foo's javac AST (NEW — Lombok-like)
```

### JavacAstInjector

A new class `JavacAstInjector` (in `rawit.processors.inject`) uses reflection to:

1. Obtain the javac `Context` via `JavacTask.instance(env).getContext()`.
2. Get `TreeMaker` and `Names` instances via their `instance(Context)` static factories.
3. For each annotated class, call `Trees.getTree(classElement)` to get the `JCClassDecl`.
4. Build a `JCMethodDecl` using `TreeMaker.MethodDef(...)`.
5. Append it to `JCClassDecl.defs`.

The injected method:
- Has `public static` modifiers for `@Constructor` and `@Invoker` on static/constructor elements.
- Has `public` (instance) modifiers for `@Invoker` on instance methods.
- Returns the generated caller class (e.g. `FooBarInvoker`).
- Body: `return new FooBarInvoker()` or `return new FooBarInvoker(instance)`.

### Example

Before AST injection (`Foo.java`):
```java
public class Foo {
    @Invoker
    public int bar(int x, int y) { return x + y; }
}
```

After AST injection (as seen by the type-checker):
```java
public class Foo {
    @Invoker
    public int bar(int x, int y) { return x + y; }

    // AST-injected by JavacAstInjector — Lombok-like, visible to type-checker
    public com.example.generated.FooBarInvoker bar() {
        return new com.example.generated.FooBarInvoker(this);
    }
}
```

### Entry-point name resolution

The entry-point method name follows the same rules as `BytecodeInjector.resolveEntryPointName`:

| Case                         | Entry-point name                               |
|------------------------------|------------------------------------------------|
| `@Constructor`               | `"constructor"` (always)                       |
| `@Invoker` on constructor    | Lowercase simple class name (e.g. `"foo"`)    |
| `@Invoker` on method `bar`   | Method name: `"bar"`                           |

### IDE user experience

With this change, the entry-point is always on the original class — identical to the bytecode
path:

| Path              | Entry point              | Requires                          |
|-------------------|--------------------------|-----------------------------------|
| javac (CLI)       | `Foo.constructor()`      | Bytecode injection (automatic)    |
| javac (IDE/type-checker) | `Foo.constructor()` | AST injection (automatic)    |
| ECJ / VS Code Java | `Foo.constructor()`    | Future JDT plugin (see below)     |

### Idempotency

`JavacAstInjector.inject()` checks whether a method with the target name already exists in the
class (`methodExists()`) before injecting. If the method is already present (from a previous
processing round or from bytecode injection), the call is silently skipped.

### Fallback

`JavacAstInjector.tryCreate()` returns `null` when not running under javac (e.g. ECJ) or when
the javac internal API is inaccessible. All injection calls on a null injector are skipped.
The existing bytecode-injection path (`BytecodeInjector`) is unchanged and unaffected.

## Implementation

### Changed files

- `src/main/java/rawit/processors/inject/JavacAstInjector.java` *(new)*
  - `tryCreate(ProcessingEnvironment)`: factory; returns null on non-javac or failure
  - `inject(EntryPoint)`: injects a single entry-point method into the class AST
  - `buildFQNExpr()`, `methodExists()`: private helpers

- `src/main/java/rawit/processors/inject/BytecodeInjector.java`
  - Added `public static resolveEntryPointName(MergeTree)` — shared name resolution
  - Added `public static resolveCallerClassBinaryName(MergeTree)` — shared caller class resolution
  - Added `public static isInstanceEntryPoint(MergeTree)` — whether entry-point needs `instance` param
  - Added `private static resolvePackagePrefix(String)` and `toPascalCase(String)` at outer class level
  - `InjectionClassVisitor.resolveOverloadName` and `resolveCallerClassBinaryName` now delegate to the
    public outer-class statics (DRY)

- `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
  - Added `JavacAstInjector astInjector` field
  - `init()`: calls `JavacAstInjector.tryCreate()` alongside `TaskListener` registration
  - `process()`: new Stage 4b — loops over `allTrees` and calls `astInjector.inject()` for each

### Test coverage

- `RawitAnnotationProcessorIntegrationTest` (integration tests): three new `@Test` methods
  verifying that the entry-point method is present **on the original class** (not on the
  generated class) and that the full chain works end-to-end via the single-pass compile path:
  - `astInjection_constructorAnnotation_entryPointOnOriginalClass`
  - `astInjection_instanceInvoker_entryPointOnOriginalClass`
  - `astInjection_staticInvoker_entryPointOnOriginalClass`

## Limitations and future work

- **ECJ / VS Code Java**: `JavacAstInjector` is a no-op when running under ECJ because ECJ does
  not expose the javac internal APIs. A future JDT/ECJ plugin could inject virtual members into
  JDT's AST model, making the same ergonomic `Foo.constructor()` syntax available in VS Code Java.
- **Module access**: The implementation relies on javac opening its internal packages to annotation
  processors running in the unnamed module — a long-standing practice (Lombok does the same).
  This may require `--add-opens` on some JDK distributions.
- The current implementation does not add a Java agent or JDT plugin; it relies solely on the
  `TreeMaker` API available within the javac process.

## Traceability

| Requirement | Implementation                                     | Test                                                  |
|-------------|----------------------------------------------------|-------------------------------------------------------|
| 1.1         | `JavacAstInjector.inject()`                        | `RawitAnnotationProcessorIntegrationTest` (3 new tests) |
| 1.2         | `JavacAstInjector.methodExists()` idempotency check | Multiple rounds of integration test compile          |
| 1.3         | `inject()` fails silently; null check in processor | Tests run under ECJ would not fail                   |
| 2.1         | AST-injected method visible to javac type-checker  | `compileSinglePassAndLoad` + reflection `getMethod()` |
| 2.2         | `BytecodeInjector.resolveEntryPointName` shared    | All existing and new integration tests               |
| 3.1         | Integration tests assert chain result equivalence  | `astInjection_*` tests                               |
| 3.2         | `BytecodeInjector` unchanged; AST injection additive | All existing integration tests still pass           |
| 4.1–4.3     | `BytecodeInjector.resolveEntryPointName()`         | `astInjection_constructorAnnotation_*`, `astInjection_instanceInvoker_*` |
| 5.1         | Injected method body is a single constructor call  | Code review of `JavacAstInjector.inject()`           |

