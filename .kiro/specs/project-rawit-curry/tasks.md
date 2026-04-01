# Implementation Plan: Project Rawit Curry

## Overview

Implement the `@Curry` and `@Constructor` annotation processor in Java 21 / Maven. The pipeline
is: validate → model → merge tree → JavaPoet code generation → ASM bytecode injection → wire into
`RawitAnnotationProcessor`. Each task builds on the previous and ends with everything wired
together.

## Tasks

- [x] 1. Add dependencies and create `@Constructor` annotation
  - [x] 1.1 Add ASM, jqwik, and JUnit 5 dependencies to `pom.xml`
    - Add `org.ow2.asm:asm` (latest stable) as a compile dependency
    - Add `net.jqwik:jqwik` and `org.junit.jupiter:junit-jupiter` as test dependencies
    - Add `maven-surefire-plugin` configured for JUnit Platform so jqwik tests run with `mvn test`
    - _Requirements: none (build infrastructure)_
  - [x] 1.2 Create `rawit/Constructor.java` annotation
    - Mirror `@Curry` but target `ElementType.CONSTRUCTOR` only, `RetentionPolicy.SOURCE`
    - _Requirements: 15.1_
  - [x] 1.3 Update `RawitAnnotationProcessor.getSupportedAnnotationTypes()` to include `rawit.Constructor`
    - Add `@SupportedOptions("curry.debug")` to the class
    - _Requirements: 15, 16_

- [x] 2. Implement `ElementValidator`
  - [x] 2.1 Create `processors/validation/ElementValidator.java`
    - Validate `@Curry`: element kind is METHOD or CONSTRUCTOR, param count ≥ 1, not private
    - Validate `@Constructor`: element kind is CONSTRUCTOR only, param count ≥ 1, not private
    - Check for existing zero-param overload with same name (conflict detection)
    - Return a `ValidationResult` (sealed interface: `Valid` / `Invalid`) carrying the `Messager` diagnostics
    - Emit exactly one `ERROR` per violated rule referencing the offending element
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.7, 13.1, 15.1, 15.2, 15.3, 15.4_
  - [x] 2.2 Write unit tests for `ElementValidator`
    - One test per validation rule: zero params, private, wrong element kind, conflict
    - Use mock `Element` / `Messager` or compile small source snippets via `javax.tools.JavaCompiler`
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 15.1, 15.2, 15.3_
  - [x] 2.3 Write property test for `ElementValidator` — Property 1 and Property 20
    - **Property 1: Valid element produces no errors**
    - **Property 20: Exactly one error per violated validation rule**
    - **Validates: Requirements 1.3, 2.3, 10.2, 15.4**

- [x] 3. Implement data model (`model` package)
  - [x] 3.1 Create `processors/model/AnnotatedMethod.java` and `Parameter.java`
    - `AnnotatedMethod`: record with `enclosingClassName`, `methodName`, `isStatic`, `isConstructor`,
      `List<Parameter> parameters`, `returnTypeDescriptor`, `List<String> checkedExceptions`
    - `Parameter`: record with `name` and `typeDescriptor`
    - _Requirements: 4, 5, 6_
  - [x] 3.2 Create `processors/model/OverloadGroup.java`
    - Record with `enclosingClassName`, `groupName`, `List<AnnotatedMethod> members`
    - _Requirements: 7.4, 11.1_
  - [x] 3.3 Create `processors/model/MergeNode.java` and `MergeTree.java`
    - `MergeNode`: sealed interface permitting `SharedNode`, `BranchingNode`, `TerminalNode`
    - `SharedNode`: record with `paramName`, `typeDescriptor`, `MergeNode next`
    - `BranchingNode`: record with `List<Branch> branches`
    - `Branch`: record with `paramName`, `typeDescriptor`, `MergeNode next`
    - `TerminalNode`: record with `List<AnnotatedMethod> overloads`, `MergeNode continuation`
    - `MergeTree`: wraps the root `MergeNode` plus the `OverloadGroup` metadata
    - _Requirements: 11, 12, 14_

- [x] 4. Implement `MergeTreeBuilder`
  - [x] 4.1 Create `processors/merge/MergeTreeBuilder.java`
    - Implement Algorithm 1 from the design: `buildNode(overloads, position)` recursively
    - Partition into terminals vs continuations at each position
    - Group continuations by `(name, type)` — single group → `SharedNode`, multiple → `BranchingNode`
    - Detect same-name-different-type conflict and emit `ERROR` via `Messager`; return `null` on conflict
    - _Requirements: 11.1, 11.3, 11.4, 12.1, 14.1, 14.2, 14.4, 14.5, 22.1, 22.4, 22.5_
  - [x] 4.2 Write unit tests for `MergeTreeBuilder`
    - Test: single overload, shared prefix, branching at first param, branching at second param,
      prefix overload (shorter is prefix of longer), same-name-different-type conflict
    - _Requirements: 11.1, 11.3, 11.4, 12.1, 14.1_
  - [x] 4.3 Write property test for `MergeTreeBuilder` — Properties 21, 22, 23, 24
    - **Property 21: Shared prefix is correctly computed for overload groups**
    - **Property 22: Single parameterless overload for an overload group**
    - **Property 23: Branching stage is generated at divergence points**
    - **Property 24: Prefix overload stage exposes both terminal and continuation**
    - **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 12.1, 12.2, 12.3, 14.4, 20.2, 20.4, 20.5, 20.6**

- [x] 5. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement JavaPoet code generation (`codegen` package)
  - [x] 6.1 Create `processors/codegen/TerminalInterfaceSpec.java`
    - Build `TypeSpec` for `InvokeStageCaller` (`@Curry`) and `ConstructStageCaller` (`@Constructor`)
    - Single zero-arg `invoke()` / `construct()` method with correct return type
    - Propagate checked exceptions in `throws` clause
    - Annotate with `@FunctionalInterface`
    - _Requirements: 5.5, 6.5, 18.4, 19.1, 19.2, 19.5_
  - [x] 6.2 Write unit tests for `TerminalInterfaceSpec`
    - Assert on `JavaFile.toString()` / `TypeSpec.toString()` for `invoke()` return type, `@FunctionalInterface`,
      `void` return, checked exceptions
    - _Requirements: 5.5, 5.8, 6.5, 19.2_
  - [x] 6.3 Write property test for `TerminalInterfaceSpec` — Properties 12, 13, 14
    - **Property 12: Terminal interface is generated**
    - **Property 13: Stage interfaces carry @FunctionalInterface**
    - **Property 14: Checked exceptions are propagated through the chain**
    - **Validates: Requirements 5.5, 5.8, 6.5, 18.6, 19.1, 19.2, 19.5**
  - [x] 6.4 Create `processors/codegen/StageInterfaceSpec.java`
    - Build `TypeSpec` for each `<PascalParam>StageCaller` / `<PascalParam>StageConstructor` interface
    - Single stage method named after the parameter, returning the next stage interface or terminal
    - Use primitive types directly (no boxing)
    - Annotate with `@FunctionalInterface`
    - Apply Algorithm 2 (stage interface name resolution) from the design
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.6, 5.8, 11.5, 18.1, 18.2, 18.3, 18.5, 18.6_
  - [x] 6.5 Write unit tests for `StageInterfaceSpec`
    - Assert on generated source: interface name, method name, parameter type (primitive), return type,
      `@FunctionalInterface`, branching stage name at position 0 vs position n
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.8_
  - [x] 6.6 Write property test for `StageInterfaceSpec` — Properties 9, 10, 11, 13
    - **Property 9: One Stage_Interface per parameter**
    - **Property 10: Each Stage_Interface declares exactly one method named after its parameter**
    - **Property 11: Chain structure — each stage method returns the correct next type**
    - **Property 13: Stage interfaces carry @FunctionalInterface**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.6, 5.8, 18.1, 18.2, 18.3, 18.5, 18.6**
  - [x] 6.7 Create `processors/codegen/CallerClassSpec.java`
    - Build `TypeSpec` for the `Caller_Class` (`Bar` for `@Curry`, `Constructor` for `@Constructor`)
    - `public static` class implementing all stage interfaces
    - `private final` fields for enclosing instance (instance methods) and accumulated args
    - Annotate with `@javax.annotation.processing.Generated("rawit.processors.RawitAnnotationProcessor")`
    - Nested stage interface `TypeSpec`s built via `StageInterfaceSpec` and `TerminalInterfaceSpec`
    - Per-stage inner accumulator classes (e.g. `Bar$WithX`, `Bar$WithXY`) or flat approach
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.1, 6.2, 6.3, 6.4, 8.3, 17.1, 17.2, 17.3, 17.4_
  - [x] 6.8 Write unit tests for `CallerClassSpec`
    - Assert on generated source: class name, `public static`, `@Generated`, `private final` fields,
      implements clause, `invoke()` body delegates to captured instance / static call / `new`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.1, 6.2, 6.3, 6.4_
  - [x] 6.9 Write property test for `CallerClassSpec` — Properties 5, 6, 7, 8
    - **Property 5: Caller_Class is injected as a public static inner class**
    - **Property 6: Caller_Class implements all Stage_Interfaces**
    - **Property 7: All Caller_Class fields are private and final**
    - **Property 8: Caller_Class carries the @Generated annotation**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 8.3, 17.1, 17.2, 17.3, 17.4**
  - [x] 6.10 Create `processors/codegen/JavaPoetGenerator.java`
    - Orchestrate `CallerClassSpec`, `StageInterfaceSpec`, `TerminalInterfaceSpec` per `MergeTree`
    - Write via `JavaFile.builder(...).build().writeTo(env.getFiler())`
    - Catch `FilerException` (already exists) and log as `NOTE` (idempotency guard)
    - _Requirements: 7.1, 7.2, 9.1, 9.2, 10.1_
  - [x] 6.11 Write unit tests for `JavaPoetGenerator`
    - Use a mock `Filer` to capture written `JavaFile` content; assert on source strings
    - Test idempotency: second call with same group does not throw, logs NOTE
    - _Requirements: 7.1, 7.2, 9.1, 9.2_

- [x] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement bytecode injection (`inject` package)
  - [x] 8.1 Create `processors/inject/OverloadResolver.java`
    - Resolve the `.class` file path from the build output directory using `ProcessingEnvironment`
    - Convert binary class name (e.g. `com/example/Foo`) to file path
    - Return `Optional<Path>` — empty if file not found (emit `ERROR` at call site)
    - _Requirements: 3.1, 9.1_
  - [x] 8.2 Create `processors/inject/BytecodeInjector.java`
    - Use ASM `ClassReader` + `ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)`
    - `InjectionClassVisitor` passes through all existing members
    - In `visitEnd()`, for each `MergeTree` in the group: check idempotency (method with overload
      name + zero params already present → skip); otherwise call `visitMethod` to add the
      parameterless overload
    - Overload body: for instance methods load `this`, call `new Bar(this)`, return cast to first
      stage interface; for static methods call `new Bar()`; for `@Constructor` call `new Constructor()`
    - Write `ClassWriter.toByteArray()` back to the `.class` file path
    - Preserve original `.class` on `VerifyError` (emit `ERROR`)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 9.2, 16.1, 16.2, 16.3, 16.4_
  - [x] 8.3 Write unit tests for `BytecodeInjector`
    - Compile a minimal class to a temp dir, run injector, load via `URLClassLoader`, reflectively
      verify the parameterless overload is present and returns the correct type
    - Test idempotency: run injector twice, assert no duplicate method
    - _Requirements: 3.1, 3.3, 9.2_
  - [x] 8.4 Write property test for `BytecodeInjector` — Properties 2, 3, 4, 17, 19
    - **Property 2: Parameterless overload is injected**
    - **Property 3: Parameterless overload preserves access modifier**
    - **Property 4: Parameterless overload returns the Entry_Stage type**
    - **Property 17: Generated .class files load without VerifyError**
    - **Property 19: Injection idempotency — re-running the injector is a no-op**
    - **Validates: Requirements 3.1, 3.2, 3.3, 8.2, 9.2**

- [ ] 9. Wire everything into `RawitAnnotationProcessor`
  - [ ] 9.1 Update `RawitAnnotationProcessor.process()` to use all components
    - Collect elements annotated with `@Curry` and `@Constructor` from `roundEnv`
    - Delegate each element to `ElementValidator`; skip invalid elements
    - Build `AnnotatedMethod` models from valid elements
    - Group into `OverloadGroup` instances by enclosing class + method name
    - Call `MergeTreeBuilder.build()` per group; skip groups with merge errors
    - Call `JavaPoetGenerator.generate()` with all trees and `ProcessingEnvironment`
    - Call `BytecodeInjector.inject()` once per enclosing class
    - Emit `NOTE` on success per requirement 10.1 and 16.5
    - Emit additional `NOTE` messages per stage when `curry.debug=true` (requirement 10.3)
    - Return `false` from `process()` (requirement 9.3)
    - _Requirements: 7.1, 7.2, 9.1, 9.3, 10.1, 10.3, 16.5_
  - [ ]* 9.2 Write end-to-end integration tests
    - Use `javax.tools.JavaCompiler` to compile small annotated source strings with the processor
    - Load resulting `.class` files via `URLClassLoader`
    - Reflectively invoke the parameterless overload, chain all stage methods, call `.invoke()` /
      `.construct()`, assert result equals direct invocation
    - Cover: instance method, static method, constructor (`@Curry`), `@Constructor`, overload group
      with branching, prefix overload
    - _Requirements: 6.6, 8.1, 8.2, 12.4, 15, 16, 19.3, 19.4_
  - [ ]* 9.3 Write property test for end-to-end pipeline — Properties 15, 16, 18
    - **Property 15: Round-trip equivalence — chain invocation equals direct invocation**
    - **Property 16: Multiple annotations produce separate Caller_Classes**
    - **Property 18: Invoke idempotency — calling invoke() multiple times produces equal results**
    - **Validates: Requirements 6.6, 7.1, 7.2, 8.1, 8.4, 12.4, 19.3, 19.4, 20.7**
  - [ ]* 9.4 Write property test for constructor pipeline — Properties 25, 26, 27
    - **Property 25: constructor() entry point is public static**
    - **Property 26: Constructor_Caller_Class is injected as a public static inner class named Constructor**
    - **Property 27: ConstructStageCaller has a construct() method returning the enclosing type**
    - **Validates: Requirements 16.1, 16.2, 17.1, 19.2**

- [ ] 10. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each task references specific requirements for traceability
- Property tests use jqwik with a minimum of 100 iterations per property
- Each property test must include the comment `// Feature: project-rawit-curry, Property N: <text>`
- Unit tests assert on `JavaFile.toString()` / `TypeSpec.toString()` — no compilation needed for codegen tests
- Integration tests (9.2) use `javax.tools.JavaCompiler` and `URLClassLoader` for full pipeline validation
- The design document contains pseudocode algorithms and generated source examples to guide implementation
