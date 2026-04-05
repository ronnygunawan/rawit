# Implementation Plan: Java 17 Compatibility

## Overview

Lower Rawit's minimum supported Java version from 21 to 17 by updating the build configuration,
rewriting the four pattern switch sites in `InvokerClassSpec` and `StageInterfaceSpec` to use
`if`/`else if` chains with `instanceof` pattern variables, updating the README, and adding tests
that verify the bytecode version constraint and `BytecodeInjector` version-preservation behaviour.

## Tasks

- [x] 1. Update `pom.xml` to target Java 17
  - Change `<java.version>21</java.version>` to `<java.version>17</java.version>` in `pom.xml`
  - This single property change propagates to both `<source>` and `<target>` in the Maven Compiler Plugin
  - After this change the build will fail until the pattern switch rewrites in tasks 2 and 3 are done
  - _Requirements: 1.1, 1.3_

- [x] 2. Rewrite pattern switch in `StageInterfaceSpec`
  - [x] 2.1 Rewrite Site A — `buildInterfaces()` switch on `MergeNode`
    - Replace `switch (node) { case SharedNode shared -> ... case BranchingNode branching -> ... case TerminalNode terminal -> ... }` with an `if`/`else if` chain using `instanceof` pattern variables
    - _Requirements: 2.1, 2.2_

  - [x] 2.2 Rewrite Site B — `nextTypeName()` switch expression on `MergeNode`
    - Replace `return switch (nextNode) { case SharedNode ... case BranchingNode ... case TerminalNode ... }` with an `if`/`else if` chain; add `throw new IllegalStateException(...)` at the end for exhaustiveness
    - _Requirements: 2.1, 2.2_

  - [x] 2.3 Rewrite Site C — `buildCombinedInterface()` switch on `continuation`
    - Replace `switch (continuation) { case SharedNode ... case BranchingNode ... case TerminalNode ... }` with an `if`/`else if` chain
    - _Requirements: 2.1, 2.2_

- [x] 3. Rewrite pattern switch in `InvokerClassSpec`
  - [x] 3.1 Rewrite Site D — `buildStageImplementations()` switch on `MergeNode`
    - Replace `switch (node) { case SharedNode shared -> ... case BranchingNode branching -> ... case TerminalNode terminal -> ... }` with an `if`/`else if` chain using `instanceof` pattern variables
    - _Requirements: 2.1, 2.2_

- [x] 4. Checkpoint — verify the build compiles cleanly
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Update `README.md` to document Java 17 as the minimum version
  - Replace "Java 21 annotation processor" with "Java 17+ annotation processor" in the introduction
  - Replace "Requires **Java 21** and **Maven 3.8+**" with "Requires **Java 17** and **Maven 3.8+**" in the "Building from Source" section
  - Remove any remaining references to Java 21 as a minimum requirement
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 6. Add `Java17CompatibilityTest` unit test class
  - Create `src/test/java/rawit/Java17CompatibilityTest.java`
  - Test that `pom.xml` contains `<java.version>17</java.version>` (Req 1.3)
  - Test that `RawitAnnotationProcessor.getSupportedSourceVersion()` returns `SourceVersion.latestSupported()` (Req 4.2)
  - Test that `README.md` contains "Java 17" as the minimum requirement (Req 5.1, 5.2)
  - Test that `README.md` does not contain "Java 21" as a minimum requirement (Req 5.3)
  - _Requirements: 1.3, 4.2, 5.1, 5.2, 5.3_

- [x] 7. Add `BytecodeVersionPropertyTest` property-based test class
  - Create `src/test/java/rawit/processors/inject/BytecodeVersionPropertyTest.java`

  - [x] 7.1 Implement Property 1 — processor class files have bytecode version ≤ 61
    - Iterate over all `.class` files under `target/classes/rawit/`; read bytes 6–7 (`major_version`); assert each is ≤ 61
    - Implement as a `@Test` (JUnit 5) since the input set is fixed, not randomised
    - **Property 1: Processor class files have bytecode version ≤ 61**
    - **Validates: Requirements 1.1**

  - [x] 7.2 Write property test for bytecode version preservation (Property 2)
    - Generate synthetic `.class` files with `major_version` drawn from {61, 62, 63, 64, 65} using ASM `ClassWriter`; run `BytecodeInjector.inject()`; assert `major_version` is unchanged
    - Use jqwik `@Property` with `@ForAll` over the version set; minimum 100 tries
    - **Property 2: BytecodeInjector preserves bytecode version through injection**
    - **Validates: Requirements 3.3**

  - [x] 7.3 Write property test for verifiable bytecode on Java 17 class files (Property 3)
    - Generate synthetic Java 17 `.class` files (major_version = 61) with random method names and parameter lists using ASM; run `BytecodeInjector.inject()`; assert `CheckClassAdapter.verify(...)` produces no output
    - Use jqwik `@Property`; minimum 100 tries
    - **Property 3: BytecodeInjector produces verifiable bytecode for Java 17 class files**
    - **Validates: Requirements 3.1, 3.2**

- [x] 8. Final checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- The build itself enforces Requirement 2.1 — any remaining pattern switch will cause a compile error under `--release 17`
- The `throw new IllegalStateException(...)` fallback in Site B is unreachable at runtime but required for definite-assignment analysis under Java 17
- ASM 9.9 (already in use) supports class files up to Java 23; no ASM version bump is needed
