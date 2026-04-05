# Implementation Plan: @Getter Annotation

## Overview

Implement the `@Getter` annotation for the rawit annotation processor. The pipeline follows: annotation definition → field model → name resolver → validator → collision detector → bytecode injector → processor integration → META-INF registration. Each component is built incrementally so that dependencies are available before dependents. Property-based tests use jqwik (already in pom.xml).

## Tasks

- [ ] 1. Create `@Getter` annotation and `AnnotatedField` model
  - [ ] 1.1 Create `rawit.Getter` annotation
    - Create `src/main/java/rawit/Getter.java`
    - `@Target(ElementType.FIELD)`, `@Retention(RetentionPolicy.SOURCE)`
    - Follow the same pattern as `rawit.Invoker` and `rawit.Constructor`
    - _Requirements: 1.1, 1.2_

  - [ ] 1.2 Create `rawit.processors.model.AnnotatedField` record
    - Create `src/main/java/rawit/processors/model/AnnotatedField.java`
    - Fields: `enclosingClassName` (binary name), `fieldName`, `fieldTypeDescriptor`, `fieldTypeSignature` (nullable), `isStatic`, `getterName`
    - Add defensive copy in compact constructor (immutable record)
    - _Requirements: 2.1, 2.3, 3.1, 3.2, 9.1_

- [ ] 2. Implement `GetterNameResolver`
  - [ ] 2.1 Create `rawit.processors.getter.GetterNameResolver`
    - Create `src/main/java/rawit/processors/getter/GetterNameResolver.java`
    - Implement `String resolve(String fieldName, String fieldTypeDescriptor)`
    - Primitive boolean (`Z`): `is` + capitalize, unless name starts with `is` + uppercase letter (return as-is)
    - Primitive boolean (`Z`) with `is` + non-uppercase: `is` + capitalize(fieldName)
    - All other types (including boxed `Boolean`): `get` + capitalize(fieldName)
    - _Requirements: 2.2, 4.1, 4.2, 4.3, 5.1_

  - [ ] 2.2 Write property test `GetterNameResolverPropertyTest`
    - **Property 1: Getter name computation follows naming conventions**
    - Create `src/test/java/rawit/processors/getter/GetterNameResolverPropertyTest.java`
    - Generate random field names and type descriptors
    - Assert: primitive boolean without `is`+uppercase → `is` + capitalize
    - Assert: primitive boolean with `is`+uppercase → field name as-is
    - Assert: non-primitive-boolean → `get` + capitalize
    - **Validates: Requirements 2.2, 4.1, 4.2, 4.3, 5.1**

  - [ ] 2.3 Write unit test `GetterNameResolverTest`
    - Create `src/test/java/rawit/processors/getter/GetterNameResolverTest.java`
    - Test specific examples: `active`→`isActive`, `isActive`→`isActive`, `isinTimezone`→`isIsinTimezone`, `Boolean active`→`getActive`, `String name`→`getName`
    - _Requirements: 2.2, 4.1, 4.2, 4.3, 5.1_

- [ ] 3. Checkpoint - Verify name resolver
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. Implement `GetterValidator`
  - [ ] 4.1 Create `rawit.processors.validation.GetterValidator`
    - Create `src/main/java/rawit/processors/validation/GetterValidator.java`
    - Implement `ValidationResult validate(Element element, Messager messager)`
    - Reject fields with `volatile` modifier → emit ERROR `@Getter is not supported on volatile fields`
    - Reject fields with `transient` modifier → emit ERROR `@Getter is not supported on transient fields`
    - Reject fields inside anonymous classes → emit ERROR `@Getter is not supported inside anonymous classes`
    - Accept fields in enums, named inner classes, static inner classes, local classes, top-level classes
    - Reuse existing `ValidationResult` sealed interface
    - _Requirements: 10.1, 10.2, 11.1, 11.2, 11.3, 11.4, 11.5_

  - [ ] 4.2 Write property test `GetterValidatorPropertyTest`
    - Create `src/test/java/rawit/processors/validation/GetterValidatorPropertyTest.java`
    - **Property 5: Volatile and transient fields are rejected**
    - **Property 6: Anonymous class fields are rejected, all other class kinds accepted**
    - Generate random modifier combinations and enclosing class kinds
    - **Validates: Requirements 10.1, 10.2, 11.1, 11.2, 11.3, 11.4, 11.5**

  - [ ] 4.3 Write unit test `GetterValidatorTest`
    - Create `src/test/java/rawit/processors/validation/GetterValidatorTest.java`
    - Test specific examples: volatile rejected, transient rejected, anonymous class rejected, enum accepted, named inner class accepted
    - _Requirements: 10.1, 10.2, 11.1, 11.2, 11.3, 11.4, 11.5_

- [ ] 5. Implement `GetterCollisionDetector`
  - [ ] 5.1 Create `rawit.processors.getter.GetterCollisionDetector`
    - Create `src/main/java/rawit/processors/getter/GetterCollisionDetector.java`
    - Implement `List<AnnotatedField> detect(List<AnnotatedField> fields, TypeElement enclosingClass, Messager messager, Types typeUtils)`
    - Check 1: Same-class zero-param method with same name → emit ERROR
    - Check 2: Inter-getter collision (two fields produce same getter name) → emit ERROR
    - Check 3: Inherited zero-param method from superclass (not @Getter-generated) → emit ERROR
    - Check 4: Covariant return type validation in field-hiding scenarios → emit ERROR if incompatible
    - Return list of fields that passed all collision checks
    - _Requirements: 6.1, 6.2, 8.6, 8.7, 12.1, 12.2_

  - [ ] 5.2 Write property test `GetterCollisionDetectorPropertyTest`
    - Create `src/test/java/rawit/processors/getter/GetterCollisionDetectorPropertyTest.java`
    - **Property 7: Same-class method collision detection**
    - **Property 8: Inter-getter collision detection**
    - **Property 9: Inherited method collision detection**
    - Generate random class structures with existing methods, multiple @Getter fields, inherited methods
    - **Validates: Requirements 6.1, 6.2, 12.1**

  - [ ] 5.3 Write property test `GetterCovariantReturnPropertyTest`
    - Create `src/test/java/rawit/processors/getter/GetterCovariantReturnPropertyTest.java`
    - **Property 10: Covariant return type validation in field hiding**
    - Generate random type hierarchies and field-hiding scenarios
    - **Validates: Requirements 8.6, 8.7**

  - [ ] 5.4 Write unit test `GetterCollisionDetectorTest`
    - Create `src/test/java/rawit/processors/getter/GetterCollisionDetectorTest.java`
    - Test specific examples: existing `getName()` + `@Getter String name` → error, two fields both producing `getName` → error, inherited method collision → error
    - _Requirements: 6.1, 6.2, 12.1_

- [ ] 6. Checkpoint - Verify validator and collision detector
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Implement `GetterBytecodeInjector`
  - [ ] 7.1 Create `rawit.processors.inject.GetterBytecodeInjector`
    - Create `src/main/java/rawit/processors/inject/GetterBytecodeInjector.java`
    - Implement `void inject(Path classFilePath, List<AnnotatedField> fields, ProcessingEnvironment env)`
    - Follow the same pattern as existing `BytecodeInjector`: read .class → ClassVisitor → write .class
    - Idempotency: skip if method with getter name and `()` descriptor already exists
    - For each `AnnotatedField`: add method via `ClassWriter.visitMethod`
    - Access: `ACC_PUBLIC` (+ `ACC_STATIC` if field is static)
    - Descriptor: `()` + field type descriptor
    - Signature: `()` + field type signature (if non-null, for generic type preservation)
    - Body: `ALOAD 0` + `GETFIELD` (instance) or `GETSTATIC` (static) + appropriate return opcode
    - Return opcode selection: `Z/B/C/S/I`→`IRETURN`, `J`→`LRETURN`, `F`→`FRETURN`, `D`→`DRETURN`, `L.../[...`→`ARETURN`
    - Verify bytecode with `CheckClassAdapter` before writing, preserve original on failure
    - _Requirements: 2.1, 2.3, 2.4, 2.5, 3.1, 3.2, 7.1, 9.1, 9.2_

  - [ ] 7.2 Write property test `GetterBytecodeInjectorPropertyTest`
    - Create `src/test/java/rawit/processors/inject/GetterBytecodeInjectorPropertyTest.java`
    - **Property 2: Static/instance modifier matching**
    - **Property 3: Return type matches field type including generics**
    - **Property 4: Generated getter is always public**
    - Generate random `AnnotatedField` instances, inject into synthetic `.class` files, read back via ASM
    - Assert: static field → static getter, instance field → instance getter
    - Assert: getter return descriptor matches field type descriptor
    - Assert: getter generic signature matches `()` + field type signature
    - Assert: getter access is always `ACC_PUBLIC` (possibly | `ACC_STATIC`)
    - **Validates: Requirements 2.3, 2.5, 3.1, 3.2, 7.1, 9.1, 9.2**

  - [ ] 7.3 Write unit test `GetterBytecodeInjectorTest`
    - Create `src/test/java/rawit/processors/inject/GetterBytecodeInjectorTest.java`
    - Inject a getter into a real `.class` file, load the class, invoke the getter via reflection, verify the returned value
    - Test instance field getter, static field getter, primitive boolean getter, generic field getter
    - _Requirements: 2.1, 2.3, 2.4, 3.1, 3.2, 9.1_

- [ ] 8. Checkpoint - Verify bytecode injector
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Integrate into `RawitAnnotationProcessor` and register
  - [ ] 9.1 Add `@Getter` processing branch to `RawitAnnotationProcessor`
    - Modify `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
    - Add `GETTER_ANNOTATION_FQN = "rawit.Getter"` constant
    - Add `rawit.Getter` to `getSupportedAnnotationTypes()`
    - In `process()`, after existing `@Invoker`/`@Constructor` processing, add `@Getter` branch:
      - Collect `@Getter`-annotated field elements from round environment
      - Validate each via `GetterValidator`; skip invalid
      - Build `AnnotatedField` model from valid `VariableElement` (extract enclosing class binary name, field name, type descriptor, type signature, static flag, compute getter name via `GetterNameResolver`)
      - Group fields by enclosing class
      - Run `GetterCollisionDetector.detect()` per class
      - Resolve `.class` file path via `OverloadResolver`
      - Call `GetterBytecodeInjector.inject()` per class
    - Initialize `GetterValidator`, `GetterNameResolver`, `GetterCollisionDetector`, `GetterBytecodeInjector` in `init()`
    - _Requirements: 1.1, 2.1, 6.1, 6.2, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 12.1_

  - [ ] 9.2 Register `@Getter` in META-INF/services
    - The processor is already registered in `src/main/resources/META-INF/services/javax.annotation.processing.Processor`
    - Verify that `rawit.processors.RawitAnnotationProcessor` is listed (it already handles multiple annotations via `getSupportedAnnotationTypes()`)
    - No file change needed if the processor class name is unchanged; just confirm the registration covers `@Getter` via the updated `getSupportedAnnotationTypes()`
    - _Requirements: 1.1_

- [ ] 10. Write integration test
  - [ ] 10.1 Write `RawitAnnotationProcessorGetterIntegrationTest`
    - Create `src/test/java/rawit/processors/RawitAnnotationProcessorGetterIntegrationTest.java`
    - End-to-end: compile a test source file with `@Getter` fields (instance, static, primitive boolean, boxed Boolean, generic)
    - Verify generated getters are callable and return correct values
    - Test field-hiding scenario with covariant return types
    - Test collision detection emits expected errors
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 4.1, 4.2, 4.3, 5.1, 6.1, 6.2, 7.1, 8.6, 8.7, 9.1, 9.2, 10.1, 10.2, 11.1, 12.1_

- [ ] 11. Update README.md with `@Getter` documentation
  - [ ] 11.1 Add `@Getter` to the Features section
    - Add `@Getter` bullet point alongside `@Invoker` and `@Constructor` in the `✨ Features` section
    - Mention: generates public getter methods for annotated fields, follows Lombok `is`-prefix convention for primitive `boolean`, supports static fields, field hiding with covariant return types

  - [ ] 11.2 Add `@Getter` annotation section to `📖 Annotations`
    - Add a new `### @Getter` subsection after `### @Constructor`
    - Show usage example: annotating fields with `@Getter` (instance, static, primitive boolean, boxed Boolean)
    - Show the generated getter usage
    - Document the primitive boolean naming rules (3 edge cases)
    - Document field hiding behavior in inheritance

  - [ ] 11.3 Add `@Getter` compile-time errors to the `⚠️ Compile-Time Errors` table
    - Add rows for: `@Getter` on volatile field, transient field, anonymous class field, getter name collision (same-class, inter-getter, inherited), incompatible covariant return type in field hiding

- [ ] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The existing `BytecodeInjector` and `ElementValidator` patterns are followed for consistency
- `GetterBytecodeInjector` is a separate class from `BytecodeInjector` since getter injection has different logic (field accessors vs parameterless overloads)
