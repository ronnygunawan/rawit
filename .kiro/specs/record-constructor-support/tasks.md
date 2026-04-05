# Implementation Plan: Record Constructor Support

## Overview

Extend the Rawit annotation processor to support `@Constructor` on Java record type declarations. Changes are concentrated in three areas: the `@Constructor` annotation target, `ElementValidator` record-type validation, and `RawitAnnotationProcessor` record-type dispatch and `AnnotatedMethod` construction. The downstream pipeline (OverloadGroup, MergeTreeBuilder, JavaPoetGenerator, BytecodeInjector) is unchanged.

## Tasks

- [x] 1. Expand @Constructor annotation target to include TYPE
  - [x] 1.1 Modify `@Target` in `src/main/java/rawit/Constructor.java`
    - Change `@Target(ElementType.CONSTRUCTOR)` to `@Target({ElementType.CONSTRUCTOR, ElementType.TYPE})`
    - No other changes to the annotation (retention stays SOURCE)
    - _Requirements: 1.1_

- [x] 2. Add record-type validation to ElementValidator
  - [x] 2.1 Refactor `validateConstructor` dispatch in `src/main/java/rawit/processors/validation/ElementValidator.java`
    - Rename existing `validateConstructor` to `validateConstructorOnExecutable`
    - Add new top-level dispatch: if element is a `TypeElement` with kind `RECORD`, call `validateConstructorOnType`; if element is a `CONSTRUCTOR`, call `validateConstructorOnExecutable`; otherwise emit error "@Constructor on a type is only supported for records" and return invalid
    - _Requirements: 2.1, 2.3, 4.1, 4.2_

  - [x] 2.2 Implement `validateConstructorOnType` method
    - Validate that the `TypeElement` has kind `RECORD` (reject CLASS, INTERFACE, ENUM with error "@Constructor on a type is only supported for records")
    - Validate that the record has ≥ 1 record component (reject zero-component records with error "staged construction requires at least one record component")
    - Scan `TypeElement.getEnclosedElements()` for an existing zero-parameter static method named `constructor` (reject with error "a parameterless overload named 'constructor' already exists")
    - Check all rules without short-circuiting (consistent with existing validator behavior)
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 2.3 Add unit tests for record-type validation in `src/test/java/rawit/processors/validation/ElementValidatorTest.java`
    - Test: `@Constructor` on a valid record with components → no errors
    - Test: `@Constructor` on a record with zero components → error about requiring at least one component
    - Test: `@Constructor` on a regular class (not a record) → error about records only
    - Test: `@Constructor` on an interface → error about records only
    - Test: `@Constructor` on a record with existing `constructor()` method → conflict error
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 2.4 Write property test: Validation accepts valid records
    - **Property 2: Validation accepts valid records**
    - For any `TypeElement` with kind `RECORD` that has at least one record component and no existing zero-parameter static method named `constructor`, the `ElementValidator` SHALL return `ValidationResult.Valid`
    - Add to `src/test/java/rawit/processors/validation/ElementValidatorPropertyTest.java`
    - **Validates: Requirements 2.1**

  - [x] 2.5 Write property test: Validation rejects non-record types
    - **Property 3: Validation rejects non-record types**
    - For any `TypeElement` with kind other than `RECORD` (CLASS, INTERFACE, ENUM) annotated with `@Constructor`, the `ElementValidator` SHALL return `ValidationResult.Invalid` and emit an error diagnostic
    - Add to `src/test/java/rawit/processors/validation/ElementValidatorPropertyTest.java`
    - **Validates: Requirements 2.3**

  - [x] 2.6 Write property test: Validation detects constructor() conflict on records
    - **Property 4: Validation detects constructor() conflict on records**
    - For any record type that already declares a zero-parameter static method named `constructor`, the `ElementValidator` SHALL return `ValidationResult.Invalid` and emit an error diagnostic mentioning the conflict
    - Add to `src/test/java/rawit/processors/validation/ElementValidatorPropertyTest.java`
    - **Validates: Requirements 2.4**

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Add record-type dispatch and AnnotatedMethod construction to RawitAnnotationProcessor
  - [x] 4.1 Add TypeElement dispatch fork in `process()` method of `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
    - After validation, check if element is `TypeElement` with kind `RECORD` → call new `buildAnnotatedMethodFromRecord(TypeElement)`
    - Otherwise, keep existing `ExecutableElement` path via `buildAnnotatedMethod(ExecutableElement)`
    - Remove the `if (!(element instanceof ExecutableElement exec)) continue;` guard and replace with the two-branch dispatch
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 4.2 Implement `buildAnnotatedMethodFromRecord(TypeElement)` method
    - Use `toBinaryName(recordElement)` for `enclosingClassName`
    - Iterate `recordElement.getRecordComponents()` in declaration order to build `List<Parameter>` using existing `toTypeDescriptor()` for each component's type
    - Set `methodName="<init>"`, `isStatic=false`, `isConstructor=true`, `isConstructorAnnotation=true`
    - Set `returnTypeDescriptor="V"`, `checkedExceptions=List.of()`
    - Derive `accessFlags` from the record type's modifiers (public → ACC_PUBLIC, protected → ACC_PROTECTED, etc.)
    - Add a private `resolveRecordAccessFlags(TypeElement)` helper method
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 4.3 Write property test: Record AnnotatedMethod construction correctness
    - **Property 1: Record AnnotatedMethod construction correctness**
    - For any record type with N ≥ 1 components, building an `AnnotatedMethod` from that record SHALL produce a model where `enclosingClassName` equals the slash-separated binary name of the record, `methodName` equals `"<init>"`, `isConstructor` is `true`, `isConstructorAnnotation` is `true`, and `parameters` is a list of N `Parameter` entries whose names and type descriptors match the record components in declaration order
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 3.2, 3.3, 3.4**

  - [x] 4.4 Write property test: Type descriptor correctness for record components
    - **Property 5: Type descriptor correctness for record components**
    - For any record component type — whether primitive, reference, or array — the `toTypeDescriptor()` method SHALL produce the correct JVM type descriptor
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 7.1, 7.2, 7.3**

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [-] 6. Add integration tests for record-type end-to-end processing
  - [x] 6.1 Add integration test: `@Constructor` on a record produces working staged API
    - Add test to `src/test/java/rawit/processors/RawitAnnotationProcessorIntegrationTest.java`
    - Compile `@Constructor public record Point(int x, int y) {}` through the three-pass pipeline
    - Verify `Point.constructor().x(1).y(2).construct()` returns a `Point` with `x=1, y=2`
    - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.2_

  - [x] 6.2 Add integration test: `@Constructor` on a record with mixed types
    - Add test to `src/test/java/rawit/processors/RawitAnnotationProcessorIntegrationTest.java`
    - Compile `@Constructor public record Config(String name, int port, boolean secure) {}`
    - Verify the staged API works with String, int, and boolean component types
    - _Requirements: 7.1, 7.2_

  - [x] 6.3 Add integration test: both record and regular class in same compilation round
    - Add test to `src/test/java/rawit/processors/RawitAnnotationProcessorIntegrationTest.java`
    - Compile a `@Constructor`-annotated record and a `@Constructor`-annotated regular class constructor in the same compilation unit
    - Verify both produce independent working staged APIs
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 6.4 Write property test: Code generation produces correct staged API for records
    - **Property 6: Code generation produces correct staged API for records**
    - For any record-derived `AnnotatedMethod` with N parameters, the `JavaPoetGenerator` pipeline SHALL produce a `Constructor` caller class containing N stage interfaces with setter methods matching the parameter names in order, and a `ConstructStageInvoker` terminal interface with a `construct()` method returning the record type
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 5.1, 5.2, 5.3**

  - [x] 6.5 Write property test: Pipeline integration for record-derived AnnotatedMethod
    - **Property 11: Pipeline integration for record-derived AnnotatedMethod**
    - For any `AnnotatedMethod` built from a record type, the model SHALL successfully pass through `OverloadGroup` construction, `MergeTreeBuilder.build()`, and `InvokerClassSpec.build()` without errors
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 4.3**

  - [x] 6.6 Write property test: Backward compatibility for regular class constructors
    - **Property 9: Backward compatibility for regular class constructors**
    - For any regular (non-record) class constructor annotated with `@Constructor`, the `ElementValidator` SHALL apply the same validation rules as before this feature, and the `RawitAnnotationProcessor` SHALL build the `AnnotatedMethod` using the existing `ExecutableElement`-based code path, producing identical output to the pre-feature behavior
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 1.2, 8.1, 8.2**

  - [x] 6.7 Write property test: Independent processing of records and regular classes
    - **Property 10: Independent processing of records and regular classes**
    - For any compilation round containing both a `@Constructor`-annotated record type and a `@Constructor`-annotated regular class constructor, the processor SHALL produce correct `AnnotatedMethod` models for both, and neither SHALL interfere with the other's overload grouping, code generation, or bytecode injection
    - Add to `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - **Validates: Requirements 8.3**

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [-] 8. Add bytecode injection tests for records
  - [x] 8.1 Write property test: Bytecode injection produces correct entry point for records
    - **Property 7: Bytecode injection produces correct entry point for records**
    - For any record whose `.class` file does not already contain a zero-parameter method named `constructor`, the `BytecodeInjector` SHALL inject a `public static constructor()` method whose bytecode instantiates and returns the generated `Constructor` caller class
    - Add to `src/test/java/rawit/processors/inject/BytecodeInjectorPropertyTest.java`
    - **Validates: Requirements 6.1, 6.2**

  - [x] 8.2 Write property test: Injection idempotency
    - **Property 8: Injection idempotency**
    - For any record whose `.class` file already contains a zero-parameter method named `constructor`, the `BytecodeInjector` SHALL skip injection for that overload group, leaving the existing method unchanged
    - Add to `src/test/java/rawit/processors/inject/BytecodeInjectorPropertyTest.java`
    - **Validates: Requirements 6.3**

- [x] 9. Update sample projects
  - [x] 9.1 Add record example to sample projects
    - Add a `@Constructor`-annotated record to `samples/gradle-sample/src/main/java/com/example/rawit/Point.java` (or update existing Point if it's a class)
    - Add corresponding test in `samples/gradle-sample/src/test/java/com/example/rawit/RawitSampleTest.java`
    - Mirror changes in `samples/maven-sample/`
    - _Requirements: 5.1, 5.2, 5.3_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The downstream pipeline (OverloadGroup, MergeTreeBuilder, JavaPoetGenerator, BytecodeInjector) requires no code changes — only new tests to verify record-derived models flow through correctly
