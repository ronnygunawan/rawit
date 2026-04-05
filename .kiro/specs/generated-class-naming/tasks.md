# Implementation Plan: Generated Class Naming

## Overview

Change the naming scheme for generated classes and relocate them to a `.generated` subpackage. Three production methods must be updated in sync (`InvokerClassSpec.resolveCallerClassName()`, `JavaPoetGenerator.resolvePackageName()`, `BytecodeInjector.resolveCallerClassBinaryName()` + `packagePrefix()`), followed by updating all test assertions to match the new naming convention.

## Tasks

- [x] 1. Update InvokerClassSpec naming logic
  - [x] 1.1 Modify `resolveCallerClassName()` in `src/main/java/rawit/processors/codegen/InvokerClassSpec.java`
    - Extract enclosing simple name from `tree.group().enclosingClassName()` (after last `/`)
    - `@Constructor` → `<EnclosingSimpleName>Constructor` (e.g., `PointConstructor`)
    - `@Invoker` on `<init>` → `<EnclosingSimpleName>Invoker` (unchanged pattern, e.g., `FooInvoker`)
    - `@Invoker` on method → `<EnclosingSimpleName><PascalCaseMethodName>Invoker` (e.g., `FooBarInvoker`)
    - _Requirements: 1.1, 2.1, 3.1_

  - [x] 1.2 Update `InvokerClassSpecTest` in `src/test/java/rawit/processors/codegen/InvokerClassSpecTest.java`
    - Update `callerClassNamedAfterMethodInPascalCase` → assert `"FooBarInvoker"` instead of `"Bar"`
    - Update `callerClassNamedConstructorForConstructorAnnotation` → assert `"FooConstructor"` instead of `"Constructor"`
    - Update `callerClassIsPublicStatic` assertion string to match `"FooBarInvoker"`
    - Update `generate_writtenSourceContainsCallerClass` and other assertions referencing old class names
    - _Requirements: 7.1_

  - [x] 1.3 Write property test for InvokerClassSpec naming correctness
    - **Property 1: InvokerClassSpec naming correctness**
    - Update `property2_callerClassNameIsUnchanged` in `InvokerClassSpecPropertyTest.java` to assert the new `<EnclosingSimpleName><PascalCaseMethodName>Invoker` pattern
    - Update `property8_constructorCallerClassCarriesGeneratedAnnotation` to assert `"FooConstructor"` instead of `"Constructor"`
    - **Validates: Requirements 1.1, 2.1, 3.1**

- [x] 2. Update JavaPoetGenerator package resolution
  - [x] 2.1 Modify `resolvePackageName()` in `src/main/java/rawit/processors/codegen/JavaPoetGenerator.java`
    - Append `.generated` to the extracted package name
    - Return `"generated"` when the enclosing class is in the default (empty) package
    - _Requirements: 4.1, 4.3_

  - [x] 2.2 Update `JavaPoetGeneratorTest` in `src/test/java/rawit/processors/codegen/JavaPoetGeneratorTest.java`
    - Update assertions to expect `.generated` in written file keys (e.g., `com.example.generated.FooBarInvoker`)
    - Update `generate_writtenSourceContainsCallerClass` to check for new class name pattern
    - Update `generate_writtenSourceContainsStageInterfaces` assertions
    - Update `generate_emitsNoteOnSuccess` to expect new class name in NOTE message
    - _Requirements: 4.1, 7.1_

  - [x] 2.3 Write property test for JavaPoetGenerator package correctness
    - **Property 3: JavaPoetGenerator package correctness**
    - Generate random binary class names and assert `resolvePackageName()` appends `.generated`
    - Assert default package produces `"generated"`
    - **Validates: Requirements 4.1, 4.3**

- [x] 3. Checkpoint - Ensure source generation tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Update BytecodeInjector naming logic
  - [x] 4.1 Modify `packagePrefix()` and `resolveCallerClassBinaryName()` in `src/main/java/rawit/processors/inject/BytecodeInjector.java`
    - `packagePrefix()`: insert `generated/` after existing package prefix; return `"generated/"` for default package
    - `resolveCallerClassBinaryName()`: use `<EnclosingSimpleName><PascalCaseMethodName>Invoker` for `@Invoker` on methods, `<EnclosingSimpleName>Constructor` for `@Constructor`, `<EnclosingSimpleName>Invoker` for `@Invoker` on constructors
    - _Requirements: 1.2, 2.2, 3.2, 4.2, 4.4_

  - [x] 4.2 Update `BytecodeInjectorPropertyTest` in `src/test/java/rawit/processors/inject/BytecodeInjectorPropertyTest.java`
    - Update `property4_parameterlessOverloadReturnsEntryStageType` to expect return descriptor with `generated/` prefix and `<EnclosingSimpleName><PascalCaseMethodName>Invoker` pattern
    - Update `property7_bytecodeInjectionProducesCorrectEntryPointForRecords` to expect `generated/<RecordName>Constructor` in return descriptor
    - _Requirements: 5.3, 7.3_

  - [x] 4.3 Write property test for BytecodeInjector binary name correctness
    - **Property 2: BytecodeInjector binary name correctness**
    - Generate random `MergeTree` instances and assert `resolveCallerClassBinaryName()` produces `<pkg>/generated/<SimpleClassName>`
    - **Validates: Requirements 1.2, 2.2, 3.2, 4.2, 5.3**

- [x] 5. Checkpoint - Ensure bytecode injection tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Update end-to-end and integration tests
  - [x] 6.1 Update `RawitAnnotationProcessorPropertyTest` in `src/test/java/rawit/processors/RawitAnnotationProcessorPropertyTest.java`
    - Update `property16_multipleAnnotationsProduceSeparateCallerClasses` to load generated classes from `generated.<ClassName><MethodPascalCase>Invoker` instead of `<PascalCaseMethod>`
    - Update `compileWithProcessor` to walk subdirectories for generated `.java` files (they now live in a `generated/` subdirectory)
    - _Requirements: 7.4_

  - [x] 6.2 Update `RawitAnnotationProcessorIntegrationTest` in `src/test/java/rawit/processors/RawitAnnotationProcessorIntegrationTest.java`
    - Update `compileWithProcessor` to walk subdirectories for generated `.java` files
    - No changes needed to chain invocation assertions (bridge methods on original class are unchanged)
    - _Requirements: 7.6_

  - [x] 6.3 Update `RawitAnnotationProcessorConstructorPropertyTest` in `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - Update `property26_constructorCallerClassIsPublic` to load `generated.<ClassName>Constructor` instead of `Constructor`
    - Update `compileWithProcessor` to walk subdirectories for generated `.java` files
    - _Requirements: 7.5_

- [x] 7. Checkpoint - Ensure all processor tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Verify sample projects
  - [x] 8.1 Run `mvn test` in `samples/maven-sample/` and `gradlew.bat test` in `samples/gradle-sample/` to verify sample tests still pass
    - The call-site syntax (`Point.constructor().x(10).y(20).construct()`) does not change
    - Only verify — no code changes expected in sample test files
    - _Requirements: 6.1, 6.2_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Run `mvn test` from the project root to verify the full test suite passes.
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- The three production changes (tasks 1.1, 2.1, 4.1) must stay in sync — the simple class name from InvokerClassSpec must match the simple name portion of BytecodeInjector's binary name, and the package from JavaPoetGenerator must match the package portion of the binary name
- Property tests validate universal correctness properties from the design document
- Checkpoints ensure incremental validation after each component change
