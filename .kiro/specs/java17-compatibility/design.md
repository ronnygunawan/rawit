# Design Document: Java 17 Compatibility

## Overview

This feature lowers Rawit's minimum supported Java version from 21 to 17. The change has three
dimensions: build configuration (lower the `--release` flag), source compatibility (replace Java
21-only language features with Java 17-compatible equivalents), and documentation (update the
README).

The core challenge is that the processor source currently uses **pattern switch** (`switch (node) {
case SharedNode shared -> ... }`) in four places. Pattern matching in `switch` expressions and
statements was a preview feature in Java 17 and only became standard in Java 21. Under
`--release 17` the compiler will reject these constructs. They must be rewritten using
`if`/`else if` chains with `instanceof` pattern variables, which have been standard since Java 16.

All other Java 21 features in use — sealed interfaces, records, `instanceof` pattern variables,
enhanced switch expressions on enums and strings — are available at `--release 17`.

---

## Architecture

The change is purely internal to the processor jar. No public API surface changes. The pipeline
remains:

```
javac (user project)
  └─ RawitAnnotationProcessor
       ├─ ElementValidator          (validation)
       ├─ MergeTreeBuilder          (merge tree construction)
       ├─ JavaPoetGenerator         (source generation via JavaPoet)
       │    ├─ InvokerClassSpec     (pattern switch → if/else if)
       │    └─ StageInterfaceSpec   (pattern switch → if/else if)
       └─ BytecodeInjector          (ASM bytecode injection)
```

The only structural change is the rewrite of pattern switch expressions in `InvokerClassSpec` and
`StageInterfaceSpec`. Everything else (sealed interfaces, records, string switch, enum switch) is
already Java 17-compatible.

---

## Components and Interfaces

### 1. `pom.xml` — Build Configuration

Change `<java.version>21</java.version>` to `<java.version>17</java.version>`. The Maven Compiler
Plugin already uses `${java.version}` for both `<source>` and `<target>`, so this single property
change propagates everywhere.

Rationale for `<source>`/`<target>` vs `<release>`: the existing configuration uses
`<source>`/`<target>`. Switching to `<release>17</release>` would be cleaner (it also gates the
bootstrap classpath), but is a separate concern. The requirement only mandates that the emitted
bytecode version is at most 61; `<source>17</source><target>17</target>` satisfies this.

### 2. `StageInterfaceSpec` — Pattern Switch Rewrites

Three pattern switch sites:

**Site A — `buildInterfaces()`**
```java
// Before (Java 21 pattern switch)
switch (node) {
    case SharedNode shared -> { ... }
    case BranchingNode branching -> { ... }
    case TerminalNode terminal -> { ... }
}

// After (Java 17 — instanceof pattern variables)
if (node instanceof SharedNode shared) {
    ...
} else if (node instanceof BranchingNode branching) {
    ...
} else if (node instanceof TerminalNode terminal) {
    ...
}
```

**Site B — `nextTypeName()`**
```java
// Before
return switch (nextNode) {
    case SharedNode shared -> { ... yield ...; }
    case BranchingNode branching -> { ... yield ...; }
    case TerminalNode terminal -> { ... yield ...; }
};

// After
if (nextNode instanceof SharedNode shared) {
    ...
    return ...;
} else if (nextNode instanceof BranchingNode branching) {
    return ...;
} else if (nextNode instanceof TerminalNode terminal) {
    ...
    return ...;
}
// unreachable — sealed interface exhausted
throw new IllegalStateException("Unexpected MergeNode type: " + nextNode.getClass());
```

**Site C — `buildCombinedInterface()`**
Same pattern as Site A.

### 3. `InvokerClassSpec` — Pattern Switch Rewrite

**Site D — `buildStageImplementations()`**
Same pattern as Site A. The `switch (node)` with three sealed-type cases becomes an
`if`/`else if` chain.

### 4. `RawitAnnotationProcessor` — No Change Required

`toTypeDescriptor()` uses a switch expression on `TypeKind` (an enum) with arrow cases — this is
a standard switch expression available since Java 14, not a pattern switch. No change needed.

The `instanceof` pattern variable usages (`if (result instanceof ValidationResult.Invalid)`,
`if (!(enclosing instanceof TypeElement typeElement))`, etc.) are Java 16 features and are fine
under `--release 17`.

### 5. `TerminalInterfaceSpec` — No Change Required

`descriptorToTypeName()` uses a switch expression on a `String` with arrow cases — standard since
Java 14. No change needed.

### 6. `README.md` — Documentation Update

- Replace "Java 21 annotation processor" with "Java 17+ annotation processor" in the introduction.
- Replace "Requires **Java 21** and **Maven 3.8+**" with "Requires **Java 17** and **Maven 3.8+**"
  in the "Building from Source" section.
- Remove any other references to Java 21 as a minimum requirement.

---

## Data Models

No data model changes. All model classes (`AnnotatedMethod`, `MergeNode`, `MergeTree`,
`OverloadGroup`, `Parameter`, `ValidationResult`) use records and sealed interfaces, both of which
are standard Java 17 features.

The `MergeNode` sealed interface is the key type involved in the pattern switch rewrites:

```
MergeNode (sealed)
├─ SharedNode    (record)
├─ BranchingNode (record)
└─ TerminalNode  (record)
```

Because `MergeNode` is sealed and all three permits are exhausted by the `if`/`else if` chains,
the compiler can verify exhaustiveness at the call sites (via the `throw` fallback). The behavior
is identical to the pattern switch — the sealed hierarchy guarantees no other subtypes exist.

### Bytecode Version Reference

| Java Version | Class File Major Version |
|---|---|
| 17 | 61 |
| 18 | 62 |
| 19 | 63 |
| 20 | 64 |
| 21 | 65 |

`BytecodeInjector` uses `ClassWriter(reader, COMPUTE_FRAMES | COMPUTE_MAXS)` and passes the
original class through `ClassReader → ClassVisitor → ClassWriter`. The `visit()` callback
receives the original `version` field and passes it through to `super.visit(...)` unchanged.
ASM 9.x supports reading and writing class files from Java 1 through Java 23+, so no ASM version
bump is needed.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Processor class files have bytecode version ≤ 61

*For any* `.class` file in the compiled processor jar (all files under the `rawit` package in the
build output), the `major_version` field in the class file header SHALL be at most 61 (Java 17).

**Validates: Requirements 1.1**

### Property 2: BytecodeInjector preserves bytecode version through injection

*For any* `.class` file with a given `major_version` (including version 61 for Java 17), after
`BytecodeInjector` injects a parameterless overload, the resulting `.class` file SHALL have the
same `major_version` as the original.

**Validates: Requirements 3.3**

### Property 3: BytecodeInjector produces verifiable bytecode for Java 17 class files

*For any* valid Java 17 `.class` file (major_version = 61), after `BytecodeInjector` injects a
parameterless overload, the resulting `.class` file SHALL pass ASM's `CheckClassAdapter`
verification without errors.

**Validates: Requirements 3.1, 3.2**

---

## Error Handling

### Pattern Switch Rewrite — Exhaustiveness

The original pattern switch on `MergeNode` was exhaustive by virtue of the sealed hierarchy. The
rewritten `if`/`else if` chains must include a terminal `throw new IllegalStateException(...)` to
preserve this guarantee and satisfy the compiler's definite-assignment analysis (for the
`return`-based rewrites in `nextTypeName()`). This throw is unreachable at runtime but required
for compilation.

### ASM Version Compatibility

ASM 9.9 (already in use) supports class files up to Java 23. No changes to ASM usage are needed.
`BytecodeInjector` already passes the original `version` through `super.visit(version, ...)`,
so Java 17 class files (major_version 61) are handled transparently.

### Build Failure on Pattern Switch

Setting `<java.version>17</java.version>` in `pom.xml` will cause the Maven Compiler Plugin to
reject any remaining pattern switch usage with a compile error. This is the intended enforcement
mechanism — the build itself acts as the regression guard for Requirement 2.1 and 2.2.

---

## Testing Strategy

### Dual Testing Approach

Both unit tests and property-based tests are used. Unit tests verify specific examples and
configuration correctness; property-based tests verify universal correctness properties across
many generated inputs.

### Unit Tests (JUnit 5)

New unit tests to add in `src/test/java/rawit/`:

- **`Java17CompatibilityTest`** — verifies:
  - `pom.xml` has `<java.version>17</java.version>` (Req 1.3)
  - `RawitAnnotationProcessor.getSupportedSourceVersion()` returns `SourceVersion.latestSupported()` (Req 4.2)
  - `README.md` contains "Java 17" as the minimum requirement (Req 5.1, 5.2)
  - `README.md` does not contain "Java 21" as a minimum requirement (Req 5.3)

### Property-Based Tests (jqwik)

The project already uses jqwik for property-based testing. New property tests to add in
`src/test/java/rawit/processors/inject/`:

- **`BytecodeVersionPropertyTest`** — verifies Properties 1, 2, and 3:

  **Property 1 — Processor class files have bytecode version ≤ 61**
  Tag: `Feature: java17-compatibility, Property 1: Processor class files have bytecode version <= 61`
  Generate: iterate over all `.class` files in the build output under the `rawit` package.
  Assert: `major_version` (bytes 6–7 of the class file) ≤ 61.
  Note: This is a deterministic check over a fixed set of files, not a randomized property.
  Implement as a `@Test` (JUnit) rather than a jqwik `@Property` since the input set is fixed.

  **Property 2 — BytecodeInjector preserves bytecode version**
  Tag: `Feature: java17-compatibility, Property 2: BytecodeInjector preserves bytecode version through injection`
  Generate: synthetic `.class` files with `major_version` drawn from {61, 62, 63, 64, 65} (Java 17–21),
  containing a simple class with one method.
  Assert: after injection, `major_version` equals the original value.
  Minimum iterations: 100 (jqwik default with version enumeration).

  **Property 3 — BytecodeInjector produces verifiable bytecode for Java 17 class files**
  Tag: `Feature: java17-compatibility, Property 3: BytecodeInjector produces verifiable bytecode for Java 17 class files`
  Generate: synthetic Java 17 `.class` files (major_version = 61) with random method names and
  parameter lists (using ASM to construct them programmatically).
  Assert: after injection, `CheckClassAdapter.verify(new ClassReader(result), false, pw)` does not
  throw and the `PrintWriter` output is empty.
  Minimum iterations: 100.

### Existing Tests

All existing unit and property-based tests must continue to pass unchanged after the Java 17
migration. The integration tests in `RawitAnnotationProcessorIntegrationTest` exercise the full
two-pass compile and bytecode injection pipeline and serve as the primary regression guard for
Requirement 6.
