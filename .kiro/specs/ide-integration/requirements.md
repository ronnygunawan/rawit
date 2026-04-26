# Requirements Document: IDE Integration (Instant IDE Reflection)

## Introduction

Rawit generates staged APIs at build time via annotation processing. In VS Code Java (JDT LS / ECJ
path), the bytecode-injected entry-point methods on the original class (e.g. `Foo.constructor()`,
`foo.bar()`) are not always immediately visible to the IDE without a manual workspace clean. This
feature adds a source-level IDE integration path so that generated APIs are discoverable in the IDE
immediately after save/compile, without requiring a workspace clean.

## Acceptance Criteria

The entry-point methods MUST be added directly to the **original annotated class** — not to a
separate generated class. This is the Lombok-like approach: just as `@Builder` adds `Foo.builder()`
to the original `Foo` class, Rawit MUST add `Foo.constructor()` / `foo.bar()` to the original class
in a source-visible way.

## Requirements

### 1. AST injection on the original class (javac path)

#### 1.1 Every annotated class MUST receive the entry-point method directly in its javac AST

When running under javac, the annotation processor MUST inject a `public static` (or instance)
entry-point method into the original annotated class's AST using javac's internal `TreeMaker` API
(the Lombok approach). This makes the method visible to the javac type-checker and javac-based IDE
tools (e.g. IntelliJ IDEA) without any separate generated-class reference.

The injected method MUST have the following signatures depending on the annotation and element kind:

| Annotation     | Element kind        | Entry-point on original class          |
|----------------|---------------------|----------------------------------------|
| `@Constructor` | Constructor         | `public static FooConstructor constructor()` |
| `@Invoker`     | Constructor         | `public static FooInvoker foo()`       |
| `@Invoker`     | Static method `bar` | `public static FooBarInvoker bar()`    |
| `@Invoker`     | Instance method `bar` | `public FooBarInvoker bar()`         |

The method body MUST delegate to the generated class constructor:
- No-instance case: `return new GeneratedClass()`
- Instance case: `return new GeneratedClass(this)` / `return new GeneratedClass(instance)`

#### 1.2 The injected method MUST be idempotent

If the method already exists in the class (e.g. from a previous annotation processing round or from
bytecode injection), the AST injection MUST be skipped silently.

#### 1.3 Injection failures MUST be silent no-ops

If the internal javac API is unavailable (e.g. ECJ, unsupported JDK), the injection MUST silently
fall back without emitting any error or warning. Bytecode injection continues to provide runtime
correctness.

### 2. IDE discovery without workspace clean

#### 2.1 Entry-points on the original class MUST be visible to the javac type-checker

After AST injection, code in other classes that calls `Foo.constructor()` or `foo.bar()` MUST
compile successfully in the same javac compilation pass.

#### 2.2 The injected entry-point name MUST match the bytecode-injected name

The method name injected into the AST MUST be identical to the name injected into the original
class's bytecode by `BytecodeInjector`, to ensure a consistent developer experience.

### 3. Parity with bytecode path

#### 3.1 The AST entry-point MUST produce semantically equivalent results

Calling `Foo.constructor().x(1).y(2).construct()` via the AST-injected entry-point MUST produce
the same result as via the bytecode-injected path.

#### 3.2 CLI javac behavior MUST be preserved

The bytecode-injected entry-point on the original class MUST continue to work as before. AST
injection is complementary — it adds source-visibility on top of the existing bytecode injection.

### 4. Naming conventions

#### 4.1 `@Constructor` entry-point name is always `"constructor"`

#### 4.2 `@Invoker` on a constructor entry-point name is the lowercase class simple name

For `@Invoker` on `class Foo`'s constructor, the entry-point MUST be named after the simple class
name in lowercase (e.g., `foo()`).

#### 4.3 `@Invoker` on a method entry-point name is the method name

For `@Invoker` on method `bar`, the entry-point MUST be named `bar()`.

### 5. No runtime overhead

#### 5.1 The entry-point MUST NOT introduce runtime overhead beyond object creation

The injected method body MUST consist solely of a constructor call: `return new GeneratedClass()`
or `return new GeneratedClass(instance)`. No extra logic, caching, or synchronization is permitted.

## Glossary

- **AST injection**: The process of adding a method node to a class's javac Abstract Syntax Tree
  during annotation processing using `TreeMaker`, making it source-visible to the type-checker.
- **Bytecode-injected entry-point**: The method injected by `BytecodeInjector` into the original
  annotated class's bytecode (e.g. `Foo.constructor()`, `foo.bar()`). This is the primary API
  path on the javac compiler path.
- **Lombok-like**: Following the same pattern as Lombok where generated methods appear on the
  original annotated class, not on a separate generated class.
- **JDT LS**: Eclipse Java Development Tools Language Server — the Java language server used by
  VS Code Java extension for IDE features.
- **ECJ**: Eclipse Compiler for Java — the underlying compiler in JDT LS.
