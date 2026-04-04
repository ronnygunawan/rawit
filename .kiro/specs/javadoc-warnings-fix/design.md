# Javadoc Warnings Fix — Bugfix Design

## Overview

The GitHub release pipeline fails during `maven-javadoc-plugin` 3.6.3 execution because the
plugin treats Javadoc warnings as errors. Thirteen source files in `src/main/java/rawit/` contain
missing `@param`, `@return`, or class/constructor-level Javadoc comments, plus one broken
`{@link}` reference in `OverloadResolver.java`. The fix is **purely additive**: only Javadoc
comments are added or corrected — no runtime behaviour changes.

## Glossary

- **Bug_Condition (C)**: A public member in `src/main/java/` that causes `maven-javadoc-plugin`
  to emit a warning or error — either a missing Javadoc comment/tag, or a broken `{@link}`.
- **Property (P)**: The desired state after the fix — every public member has complete Javadoc
  and every `{@link}` resolves to a real method on the referenced type.
- **Preservation**: All runtime behaviour (annotation processing, code generation, bytecode
  injection, validation) must remain byte-for-byte identical after the fix.
- **compact constructor**: The implicit or explicit constructor of a Java record, which Javadoc
  requires to be documented separately from the record-level `@param` tags.
- **`{@link}` reference**: An inline Javadoc tag that must resolve to an existing member on the
  named type; `Filer` does not declare `getResource` — `JavaFileManager` does.

## Bug Details

### Bug Condition

The bug manifests when `maven-javadoc-plugin` 3.6.3 processes any of the thirteen affected files.
The plugin emits a warning (treated as an error) for each public member that lacks required
Javadoc, and emits a hard error for the broken `{@link}` reference.

**Formal Specification:**
```
FUNCTION isBugCondition(sourceFile, member)
  INPUT: sourceFile — a Java source file under src/main/java/
         member    — a public class, constructor, or method within that file
  OUTPUT: boolean

  RETURN (member is a public constructor AND member has no Javadoc comment)
      OR (member is a public method AND member has no @return tag AND returnType != void)
      OR (member is a record component AND enclosing record has no @param for that component)
      OR (member Javadoc contains a {@link T#m} AND m is not declared on T)
END FUNCTION
```

### Per-File Changes Required

#### `Parameter.java` — record compact constructor
- Add `@param name` and `@param typeDescriptor` tags to the existing record-level Javadoc comment.
- The record already has a class-level comment; the compact constructor `public Parameter { … }`
  needs a Javadoc comment with `@param` tags for both components.

**Before (compact constructor — no Javadoc):**
```java
public record Parameter(String name, String typeDescriptor) {}
```
**After:**
```java
/**
 * Represents a single method parameter as a (name, JVM type descriptor) pair.
 * ...
 * @param name           the parameter name as it appears in source
 * @param typeDescriptor the JVM type descriptor, e.g. {@code "I"} or {@code "Ljava/lang/String;"}
 */
public record Parameter(String name, String typeDescriptor) {}
```

#### `AnnotatedMethod.java` — canonical constructor, convenience constructors, compact constructor
- The canonical 9-arg constructor already has `@param` tags on the record declaration; the
  compact constructor `public AnnotatedMethod { … }` needs a brief Javadoc comment.
- The two convenience constructors need `@param` tags for every parameter they declare.

**Compact constructor — add:**
```java
/** Defensive copy constructor; copies {@code parameters} and {@code checkedExceptions}. */
public AnnotatedMethod { … }
```

**7-arg convenience constructor — add `@param` for all 7 params.**

**8-arg convenience constructor — add `@param` for all 8 params.**

#### `BytecodeInjector.java` — default constructor warning
Add an explicit no-arg constructor with a Javadoc comment:
```java
/** Creates a new {@code BytecodeInjector}. */
public BytecodeInjector() {}
```

#### `ElementValidator.java` — default constructor warning
```java
/** Creates a new {@code ElementValidator}. */
public ElementValidator() {}
```

#### `OverloadResolver.java` — default constructor warning + broken `{@link}`
Add explicit constructor:
```java
/** Creates a new {@code OverloadResolver}. */
public OverloadResolver() {}
```
Fix the broken `{@link}` in the `resolve` method Javadoc:
- **Before:** `{@link javax.tools.Filer#getResource}`
- **After:** `{@link javax.tools.JavaFileManager#getResource(javax.tools.JavaFileManager.Location, String, String)}`

#### `RawitAnnotationProcessor.java` — default constructor warning
The class extends `AbstractProcessor` which has a no-arg constructor. Add:
```java
/** Creates a new {@code RawitAnnotationProcessor}. */
public RawitAnnotationProcessor() {}
```

#### `InvokerClassSpec.java` — missing constructor comment, missing `@return` on `build()`
Add Javadoc to the constructor:
```java
/**
 * Creates a new {@code InvokerClassSpec} for the given merge tree.
 *
 * @param tree the merge tree describing the overload group to generate code for
 */
public InvokerClassSpec(final MergeTree tree) { … }
```
Add `@return` to `build()`:
```java
/**
 * Builds and returns the Invoker_Class {@link TypeSpec}.
 *
 * @return the fully constructed {@link TypeSpec} for the Invoker_Class
 */
public TypeSpec build() { … }
```

#### `JavaPoetGenerator.java` — missing constructor comment
```java
/**
 * Creates a new {@code JavaPoetGenerator}.
 *
 * @param messager the compiler messager used to emit diagnostics
 */
public JavaPoetGenerator(final Messager messager) { … }
```

#### `MergeNode.java` — `BranchingNode` and `TerminalNode` compact constructors
```java
// BranchingNode compact constructor:
/** Defensive copy; makes {@code branches} unmodifiable. */
public BranchingNode { … }

// TerminalNode compact constructor:
/** Defensive copy; makes {@code overloads} unmodifiable. */
public TerminalNode { … }
```

#### `MergeTreeBuilder.java` — missing constructor comment
```java
/**
 * Creates a new {@code MergeTreeBuilder}.
 *
 * @param messager the compiler messager used to emit conflict diagnostics
 */
public MergeTreeBuilder(final Messager messager) { … }
```

#### `OverloadGroup.java` — compact constructor
```java
/** Defensive copy; makes {@code members} unmodifiable. */
public OverloadGroup { … }
```

#### `StageInterfaceSpec.java` — missing constructor comment
```java
/**
 * Creates a new {@code StageInterfaceSpec} for the given merge tree.
 *
 * @param tree the merge tree to generate stage interfaces for
 */
public StageInterfaceSpec(final MergeTree tree) { … }
```

#### `TerminalInterfaceSpec.java` — missing constructor comment
```java
/**
 * Creates a new {@code TerminalInterfaceSpec} for the given annotated method.
 *
 * @param method the representative annotated method for this terminal stage
 */
public TerminalInterfaceSpec(final AnnotatedMethod method) { … }
```

#### `ValidationResult.java` — `Valid` and `Invalid` compact constructors
```java
// Valid compact constructor:
/** Creates a {@link Valid} result. */
public Valid() {}   // record with no components — no @param needed

// Invalid compact constructor:
/** Creates an {@link Invalid} result. */
public Invalid() {}
```

### Examples

- `Parameter.java`: `mvn javadoc:javadoc` emits `warning: no @param for name` → after fix, no
  warning.
- `OverloadResolver.java`: `mvn javadoc:javadoc` emits `error: reference not found` for
  `{@link javax.tools.Filer#getResource}` → after fix, link resolves to
  `JavaFileManager#getResource(...)`.
- `BytecodeInjector.java`: `mvn javadoc:javadoc` emits `warning: use of default constructor,
  which does not provide a comment` → after fix, explicit documented constructor present.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Annotation processing pipeline (`RawitAnnotationProcessor.process`) produces identical output.
- `ElementValidator.validate` emits the same diagnostics for the same inputs.
- `MergeTreeBuilder.build` produces the same `MergeTree` structure for the same `OverloadGroup`.
- `BytecodeInjector.inject` produces byte-for-byte identical `.class` file modifications.
- `JavaPoetGenerator.generate` produces source-identical generated files.
- All existing unit tests and property-based tests pass without modification.

**Scope:**
All inputs that do NOT involve Javadoc comment parsing are completely unaffected. This includes:
- All annotation processor round inputs (`RoundEnvironment`, `TypeElement`, etc.)
- All bytecode read/write operations
- All JavaPoet `TypeSpec` / `MethodSpec` construction logic

## Hypothesized Root Cause

1. **Missing `@param` tags on record components**: Java records require `@param` tags either on
   the record declaration or on the compact constructor. The affected records (`Parameter`,
   `AnnotatedMethod`, `OverloadGroup`, `MergeNode.BranchingNode`, `MergeNode.TerminalNode`,
   `ValidationResult.Valid`, `ValidationResult.Invalid`) have compact constructors with no
   Javadoc at all.

2. **Implicit default constructors**: Classes with no explicit constructor (`BytecodeInjector`,
   `ElementValidator`, `OverloadResolver`, `RawitAnnotationProcessor`) rely on the compiler-
   generated default constructor, which `maven-javadoc-plugin` 3.6.3 flags as undocumented.

3. **Missing `@return` tag**: `InvokerClassSpec.build()` has a Javadoc comment but no `@return`
   tag, which the plugin treats as a warning.

4. **Missing constructor Javadoc on non-default constructors**: Several classes
   (`InvokerClassSpec`, `JavaPoetGenerator`, `MergeTreeBuilder`, `StageInterfaceSpec`,
   `TerminalInterfaceSpec`) have explicit constructors with no Javadoc comment.

5. **Broken `{@link}` reference**: `OverloadResolver.resolve` documents its use of
   `Filer#getResource`, but `getResource` is declared on `JavaFileManager`, not `Filer`.
   `maven-javadoc-plugin` resolves `{@link}` tags strictly and emits a hard error.

## Correctness Properties

Property 1: Bug Condition — All Public Members in Main Sources Have Complete Javadoc

_For any_ public class, constructor, or non-void method in `src/main/java/rawit/`, the fixed
source SHALL have a Javadoc comment that includes all required tags (`@param` for each parameter,
`@return` for non-void methods), and no `{@link}` tag SHALL reference a method not declared on
the named type.

**Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13**

Property 2: Preservation — Runtime Behaviour Is Unchanged

_For any_ input to the annotation processor, validator, merge tree builder, bytecode injector,
or code generator, the fixed code SHALL produce the same result as the original code, because
the fix changes only Javadoc comments and adds no logic.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

All changes are confined to Javadoc comments. No method bodies, signatures, or logic are altered.

**File: `src/main/java/rawit/processors/model/Parameter.java`**
- Add `@param name` and `@param typeDescriptor` to the record-level Javadoc.

**File: `src/main/java/rawit/processors/model/AnnotatedMethod.java`**
- Add Javadoc comment to the compact constructor.
- Add `@param` tags to the 7-arg convenience constructor.
- Add `@param` tags to the 8-arg convenience constructor.

**File: `src/main/java/rawit/processors/inject/BytecodeInjector.java`**
- Add explicit `public BytecodeInjector() {}` with Javadoc.

**File: `src/main/java/rawit/processors/validation/ElementValidator.java`**
- Add explicit `public ElementValidator() {}` with Javadoc.

**File: `src/main/java/rawit/processors/inject/OverloadResolver.java`**
- Add explicit `public OverloadResolver() {}` with Javadoc.
- Fix `{@link javax.tools.Filer#getResource}` → `{@link javax.tools.JavaFileManager#getResource(javax.tools.JavaFileManager.Location, String, String)}`.

**File: `src/main/java/rawit/processors/RawitAnnotationProcessor.java`**
- Add explicit `public RawitAnnotationProcessor() {}` with Javadoc.

**File: `src/main/java/rawit/processors/codegen/InvokerClassSpec.java`**
- Add Javadoc to the existing constructor.
- Add `@return` tag to `build()`.

**File: `src/main/java/rawit/processors/codegen/JavaPoetGenerator.java`**
- Add Javadoc to the existing constructor.

**File: `src/main/java/rawit/processors/model/MergeNode.java`**
- Add Javadoc to `BranchingNode` compact constructor.
- Add Javadoc to `TerminalNode` compact constructor.

**File: `src/main/java/rawit/processors/merge/MergeTreeBuilder.java`**
- Add Javadoc to the existing constructor.

**File: `src/main/java/rawit/processors/model/OverloadGroup.java`**
- Add Javadoc to the compact constructor.

**File: `src/main/java/rawit/processors/codegen/StageInterfaceSpec.java`**
- Add Javadoc to the existing constructor.

**File: `src/main/java/rawit/processors/codegen/TerminalInterfaceSpec.java`**
- Add Javadoc to the existing constructor.

**File: `src/main/java/rawit/processors/validation/ValidationResult.java`**
- Add Javadoc to `Valid` compact constructor.
- Add Javadoc to `Invalid` compact constructor.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, confirm the bug is present on unfixed
code (exploratory), then verify the fix eliminates all warnings and preserves all behaviour.

### Exploratory Bug Condition Checking

**Goal**: Surface the exact Javadoc warnings/errors on the unfixed code to confirm the root cause
analysis. If the warnings differ from what is hypothesised, re-examine the affected files.

**Test Plan**: Run `mvn javadoc:javadoc` (or `mvn -P release`) on the unfixed code and capture
the output. Each warning/error maps to a specific requirement in section 1.x of `bugfix.md`.

**Test Cases**:
1. **Parameter record**: Run Javadoc on `Parameter.java` — expect `warning: no @param for name`
   and `warning: no @param for typeDescriptor` (will fail on unfixed code).
2. **OverloadResolver broken link**: Run Javadoc on `OverloadResolver.java` — expect
   `error: reference not found` for `Filer#getResource` (will fail on unfixed code).
3. **Default constructor classes**: Run Javadoc on `BytecodeInjector.java` — expect
   `warning: use of default constructor, which does not provide a comment` (will fail on unfixed code).
4. **InvokerClassSpec.build()**: Run Javadoc on `InvokerClassSpec.java` — expect
   `warning: no @return` (will fail on unfixed code).

**Expected Counterexamples**:
- Javadoc tool emits warnings for each missing tag/comment listed in requirements 1.2–1.13.
- Javadoc tool emits a hard error for the broken `{@link}` in requirement 1.10.

### Fix Checking

**Goal**: Verify that after the fix, `maven-javadoc-plugin` processes all affected files without
any warnings or errors.

**Pseudocode:**
```
FOR ALL sourceFile IN affectedFiles WHERE isBugCondition(sourceFile, member) DO
  result := runJavadoc(sourceFile_fixed)
  ASSERT result contains no warnings AND no errors for member
END FOR
```

### Preservation Checking

**Goal**: Verify that the fix introduces no runtime behaviour changes — all existing tests pass
unchanged.

**Pseudocode:**
```
FOR ALL test IN existingTestSuite DO
  ASSERT test(original_code) == test(fixed_code)
END FOR
```

**Testing Approach**: Because the fix is purely additive Javadoc, preservation is guaranteed by
the Java compiler (comments are stripped before compilation). Running the existing test suite
on the fixed code is sufficient. Property-based tests already cover the processor, validator,
merge tree builder, bytecode injector, and code generator.

**Test Cases**:
1. **Annotation processor preservation**: All tests in `RawitAnnotationProcessorIntegrationTest`,
   `RawitAnnotationProcessorPropertyTest`, and `RawitAnnotationProcessorConstructorPropertyTest`
   must pass unchanged.
2. **Validator preservation**: All tests in `ElementValidatorTest` and
   `ElementValidatorPropertyTest` must pass unchanged.
3. **Merge tree preservation**: All tests in `MergeTreeBuilderTest` and
   `MergeTreeBuilderPropertyTest` must pass unchanged.
4. **Bytecode injector preservation**: All tests in `BytecodeInjectorTest` and
   `BytecodeInjectorPropertyTest` must pass unchanged.
5. **Code generator preservation**: All tests in `InvokerClassSpecTest`,
   `InvokerClassSpecPropertyTest`, `StageInterfaceSpecTest`, `StageInterfaceSpecPropertyTest`,
   `TerminalInterfaceSpecTest`, `TerminalInterfaceSpecPropertyTest`, and
   `JavaPoetGeneratorTest` must pass unchanged.

### Unit Tests

- Verify `OverloadResolver.resolve` still returns the correct `Optional<Path>` after the
  constructor and `{@link}` fix (no logic changed, existing tests cover this).
- Verify `BytecodeInjector.inject` still produces correct bytecode after the constructor addition
  (no logic changed, existing tests cover this).
- Verify `Parameter`, `AnnotatedMethod`, `OverloadGroup` record construction still works after
  compact constructor Javadoc additions.

### Property-Based Tests

- Property 1 (Javadoc completeness): For every `.java` file under `src/main/java/rawit/`, parse
  the source and assert that every public constructor has a Javadoc comment, every public non-void
  method has a `@return` tag, and every record component has a `@param` tag. This can be
  implemented as a source-scanning test (similar to `SourceFilePackageConsistencyTest`).
- Property 2 (No broken `{@link}` references): For every `{@link T#m}` tag in
  `src/main/java/rawit/`, assert that method `m` is declared on type `T` (or a supertype).
  Simplest implementation: grep for `{@link javax.tools.Filer#getResource}` and assert it does
  not appear.
- Property 3 (Preservation): Run the full existing test suite — all property-based tests in
  `*PropertyTest.java` files must pass, confirming no runtime behaviour changed.

### Integration Tests

- Run `mvn javadoc:javadoc` on the fixed codebase and assert exit code 0 with no `warning:` or
  `error:` lines in the output.
- Run `mvn -P release -DskipTests` and assert the `attach-javadocs` goal completes successfully,
  producing a non-empty `*-javadoc.jar`.
- Run the full `mvn test` suite on the fixed codebase and assert all tests pass.
