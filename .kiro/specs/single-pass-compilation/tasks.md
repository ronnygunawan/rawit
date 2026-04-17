# Implementation Plan: Single-Pass Compilation

## Overview

Enable single-pass compilation for the Rawit annotation processor by using javac's `TaskListener` API to defer bytecode injection until after `.class` files are written. The implementation modifies `RawitAnnotationProcessor` (new fields, `init()` TaskListener registration, `createPostGenerateListener()`, three-way branching), adds `resolvePath()` to `OverloadResolver`, adds single-pass integration tests, simplifies sample build configs, and updates the README.

## Tasks

- [x] 1. Add `resolvePath()` to `OverloadResolver` and refactor `resolve()`
  - [x] 1.1 Implement `resolvePath()` method on `OverloadResolver`
    - Add `resolvePath(String binaryClassName, ProcessingEnvironment env)` that returns `Optional<Path>` without checking `Files.exists()`
    - Use `Filer.getResource(CLASS_OUTPUT, packageName, simpleName)` to obtain the URI
    - Return `Optional.empty()` for non-`file:` URI schemes, `IOException`, or `IllegalArgumentException`
    - _Requirements: 5.1, 5.3, 5.4_

  - [x] 1.2 Refactor `resolve()` to delegate to `resolvePath()`
    - Change `resolve()` to call `resolvePath(binaryClassName, env).filter(Files::exists)`
    - Preserve backward compatibility — existing callers see identical behavior
    - _Requirements: 5.2_

  - [x] 1.3 Write property test for `resolvePath()` (Property 4)
    - **Property 4: resolvePath returns path without existence check**
    - For any valid binary class name mapping to a `file:` URI, `resolvePath()` returns a non-empty `Optional<Path>` ending with `{simpleName}.class`
    - **Validates: Requirements 5.1**

  - [x] 1.4 Write property test for `resolve()` delegation (Property 5)
    - **Property 5: resolve delegates to resolvePath with existence filter**
    - For any binary class name, `resolve(name, env)` equals `resolvePath(name, env).filter(Files::exists)`
    - **Validates: Requirements 5.2**

- [x] 2. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Add deferred injection fields and TaskListener registration to `RawitAnnotationProcessor`
  - [x] 3.1 Add pending injection maps and `useTaskListener` flag
    - Add `private final Map<String, List<MergeTree>> pendingInvokerInjections = new LinkedHashMap<>()`
    - Add `private final Map<String, List<AnnotatedField>> pendingGetterInjections = new LinkedHashMap<>()`
    - Add `private boolean useTaskListener = false`
    - _Requirements: 2.2, 3.2_

  - [x] 3.2 Register TaskListener in `init()`
    - In `init()`, after existing initialization, attempt `JavacTask.instance(processingEnv)` and call `addTaskListener(createPostGenerateListener())`
    - Set `useTaskListener = true` on success
    - Catch `IllegalArgumentException` silently (non-javac compiler)
    - Catch generic `Exception`, log diagnostic note when `invoker.debug=true`, continue without TaskListener
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 3.3 Implement `createPostGenerateListener()` factory method
    - Return a `TaskListener` that only responds to `TaskEvent.Kind.GENERATE`
    - On GENERATE: extract `TypeElement`, compute binary name via `toBinaryName()`
    - Process getter injections first: `pendingGetterInjections.remove(binaryName)` → `overloadResolver.resolve()` → `getterBytecodeInjector.inject()`
    - Process invoker injections: `pendingInvokerInjections.remove(binaryName)` → `overloadResolver.resolve()` → `bytecodeInjector.inject()`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 3.4 Write property test for pending injection merge (Property 1)
    - **Property 1: Pending injection merge accumulates all entries**
    - For any sequence of lists targeting the same binary class name, `Map.merge()` with list concatenation produces a single list containing all entries in order
    - **Validates: Requirements 2.4, 3.4**

  - [x] 3.5 Write property test for TaskListener event filtering (Property 2)
    - **Property 2: TaskListener only responds to GENERATE events**
    - For any `TaskEvent` with a kind other than GENERATE, the listener does not invoke injectors or modify pending maps
    - **Validates: Requirements 4.4**

  - [x] 3.6 Write property test for TaskListener consume-on-remove (Property 3)
    - **Property 3: TaskListener removes pending entries after processing**
    - After the TaskListener processes a GENERATE event for a class, the pending map no longer contains that class name
    - **Validates: Requirements 4.5**

- [x] 4. Implement three-way injection branching in `process()`
  - [x] 4.1 Add three-way branching for `@Invoker`/`@Constructor` injection
    - After `overloadResolver.resolve()`: if present → inject immediately; else if `useTaskListener` → `pendingInvokerInjections.merge()` with list concatenation; else → skip silently
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.2 Add three-way branching for `@Getter` injection
    - Same pattern: if present → inject immediately; else if `useTaskListener` → `pendingGetterInjections.merge()`; else → skip silently
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 4.3 Ensure source generation is independent of `.class` file existence
    - Verify `JavaPoetGenerator.generate()` runs for all valid MergeTrees regardless of `.class` file existence — only bytecode injection is gated
    - _Requirements: 6.1, 6.2_

- [x] 5. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Add single-pass integration tests
  - [x] 6.1 Add `compileSinglePassAndLoad` helper to `RawitAnnotationProcessorIntegrationTest`
    - Compile source with the processor in a single javac invocation (no multi-pass setup)
    - Use standard `compile()` with `List.of(new RawitAnnotationProcessor())` and `-classpath` option
    - Return a `URLClassLoader` rooted at the output directory
    - _Requirements: 9.1_

  - [x] 6.2 Add single-pass integration test for instance `@Invoker`
    - Compile `int add(int x, int y)` in single-pass mode
    - Verify `add().x(3).y(4).invoke() == 7` and equals direct invocation
    - _Requirements: 9.2_

  - [x] 6.3 Add single-pass integration test for static `@Invoker`
    - Compile `static int add(int x, int y)` in single-pass mode
    - Verify `add().x(5).y(6).invoke() == 11` and equals direct invocation
    - _Requirements: 9.3_

  - [x] 6.4 Add single-pass integration test for `@Constructor`
    - Compile a class with `@Constructor` in single-pass mode
    - Verify `constructor().x(1).y(2).construct()` creates correct instance
    - _Requirements: 9.4_

  - [x] 6.5 Add single-pass integration test for `@Getter`
    - Compile a class with `@Getter` fields in single-pass mode
    - Verify generated getter methods return correct field values
    - _Requirements: 9.5_

  - [x] 6.6 Write property test for bytecode equivalence (Property 6)
    - **Property 6: Bytecode equivalence between immediate and deferred injection**
    - Compile annotated sources in both multi-pass and single-pass modes; compare `.class` files for identical injected method signatures
    - **Validates: Requirements 8.3**

- [x] 7. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Simplify sample build configurations and update README
  - [x] 8.1 Simplify Maven sample `pom.xml`
    - Ensure standard `maven-compiler-plugin` with `<release>17</release>` and no multi-pass execution blocks
    - Bump rawit version if needed
    - _Requirements: 7.1_

  - [x] 8.2 Simplify Gradle sample `build.gradle`
    - Ensure standard `annotationProcessor` and `compileOnly` dependency declarations with no custom compile tasks
    - Bump rawit version if needed
    - _Requirements: 7.2_

  - [x] 8.3 Update README to document single-pass compilation
    - Document that no multi-pass compiler configuration is needed when using javac
    - Mention that Rawit hooks into javac's post-generate phase via a `TaskListener`
    - Remove any remaining multi-pass documentation
    - _Requirements: 7.3_

  - [x] 8.4 Add `User.java` sample model with `@Getter` fields to both sample projects
    - Add `samples/maven-sample/src/main/java/com/example/model/User.java` with `@Getter`-annotated fields
    - Add `samples/gradle-sample/src/main/java/com/example/model/User.java` with `@Getter`-annotated fields
    - _Requirements: 9.5_

  - [x] 8.5 Update `RawitSampleTest.java` in both sample projects
    - Add test assertions for `@Getter` functionality on the `User` model
    - _Requirements: 9.5_

- [x] 9. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document using jqwik
- Unit tests validate specific examples and edge cases
- The implementation uses Java (the project's existing language) throughout
