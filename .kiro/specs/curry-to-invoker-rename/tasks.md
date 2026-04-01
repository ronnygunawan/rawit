# Implementation Plan: curry-to-invoker-rename

## Overview

Mechanical two-axis rename: (1) `@Curry` → `@Invoker` annotation and all its references, and
(2) `Caller`/`StageCaller` generated-name suffixes → `Invoker`/`StageInvoker`. Tasks are ordered
so the project compiles and tests pass at each checkpoint.

## Tasks

- [x] 1. Rename the annotation source file and update its content
  - Delete `src/main/java/rg/rawit/Curry.java` and create `src/main/java/rg/rawit/Invoker.java`
  - Change the type declaration from `public @interface Curry` to `public @interface Invoker`
  - Update the Javadoc: replace "currying" / "@Curry" with "staged invocation" / "@Invoker"
  - Preserve `@Target`, `@Retention`, and all other meta-annotations unchanged
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Update `ElementValidator` to reference `@Invoker`
  - [x] 2.1 Replace `import rg.rawit.Curry;` with `import rg.rawit.Invoker;`
    - Update `element.getAnnotation(Curry.class)` → `element.getAnnotation(Invoker.class)`
    - Update all Javadoc and inline comments: `@Curry` → `@Invoker`
    - Update diagnostic strings: `"@Curry"` / `"curry"` → `"@Invoker"` / `"invoker"` where they refer to the annotation
    - _Requirements: 4.1, 4.2, 4.4_

  - [x] 2.2 Write property test for valid `@Invoker` element produces no errors
    - **Property 1: Round-trip equivalence is preserved** (validator side — no errors on valid input)
    - **Validates: Requirements 8.1**
    - Update `ElementValidatorPropertyTest` and `ElementValidatorTest`: replace every `import rg.rawit.Curry;` and `@Curry` in inline source strings with `import rg.rawit.Invoker;` and `@Invoker`
    - _Requirements: 5.1, 5.2, 5.4, 5.5_

- [x] 3. Update `RawitAnnotationProcessor` to reference `@Invoker`
  - [x] 3.1 Rename constant and update FQN string
    - `CURRY_ANNOTATION_FQN` → `INVOKER_ANNOTATION_FQN`, value `"rg.rawit.Curry"` → `"rg.rawit.Invoker"`
    - Update `getSupportedAnnotationTypes()` to return `"rg.rawit.Invoker"`
    - Replace `import rg.rawit.Curry;` with `import rg.rawit.Invoker;`
    - Replace `exec.getAnnotation(rg.rawit.Curry.class)` → `exec.getAnnotation(rg.rawit.Invoker.class)`
    - _Requirements: 3.1, 3.2, 3.5_

  - [x] 3.2 Update option key and diagnostic strings
    - `@SupportedOptions("curry.debug")` → `@SupportedOptions("invoker.debug")`
    - All reads of `"curry.debug"` → `"invoker.debug"` (e.g. in `isDebugEnabled()`)
    - Debug log prefix `"[curry.debug]"` → `"[invoker.debug]"`
    - All diagnostic message strings containing `"curry"` or `"@Curry"` → `"invoker"` / `"@Invoker"`
    - _Requirements: 3.3, 3.4, 3.6, 3.7_

- [x] 4. Checkpoint — project compiles with `@Invoker` recognised
  - Ensure all tests pass, ask the user if questions arise.
  - At this point `Invoker.java` exists, `ElementValidator` and `RawitAnnotationProcessor` reference `Invoker.class`, and the processor recognises `rg.rawit.Invoker`.

- [x] 5. Rename `CallerClassSpec` → `InvokerClassSpec` (source file and class)
  - [x] 5.1 Create `InvokerClassSpec.java` as a renamed copy of `CallerClassSpec.java`
    - Rename the class declaration: `public class CallerClassSpec` → `public class InvokerClassSpec`
    - Rename the field `isCurry` → `isInvoker` and update all reads within the class
    - Update `terminalTypeName()`: `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
    - Update Javadoc: `CallerClassSpec` / `Caller_Class` / `@Curry` → `InvokerClassSpec` / `Invoker_Class` / `@Invoker`
    - Delete the old `CallerClassSpec.java`
    - _Requirements: 9.1, 9.2, 9.4, 9.5, 9.6_

  - [x] 5.2 Update `JavaPoetGenerator` to use `InvokerClassSpec`
    - Replace `import … CallerClassSpec;` with `import … InvokerClassSpec;`
    - Replace `new CallerClassSpec(tree)` → `new InvokerClassSpec(tree)`
    - Update Javadoc references: `CallerClassSpec` → `InvokerClassSpec`
    - _Requirements: 9.3_

- [x] 6. Update `StageInterfaceSpec` — rename suffixes and field
  - Rename field `isCurry` → `isInvoker` and update all reads within the class
  - `stageInterfaceName()`: suffix `"StageCaller"` → `"StageInvoker"`
  - `branchingInterfaceName()`: suffix `"StageCaller"` → `"StageInvoker"`
  - `terminalTypeName()`: `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
  - `combinedInterfaceName()`: `"WithInvokeStageCaller"` → `"WithInvokeStageInvoker"`, `"WithConstructStageCaller"` → `"WithConstructStageInvoker"`
  - Update constructor comment: `isCurry` → `isInvoker`
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.8, 10.9, 10.11_

- [x] 7. Update `TerminalInterfaceSpec` — rename generated interface names
  - `buildInvokeStageCaller()`: interface name `"InvokeStageCaller"` → `"InvokeStageInvoker"`
  - `buildConstructStageCaller()`: interface name `"ConstructStageCaller"` → `"ConstructStageInvoker"`
  - Update Javadoc references to the old interface names
  - _Requirements: 10.6, 10.7, 10.9, 10.10_

- [x] 8. Update other processor components — comments and strings only
  - `MergeTreeBuilder.java`: update any Javadoc/comment references to `@Curry` → `@Invoker`
  - `BytecodeInjector.java`: same comment/string updates
  - `OverloadResolver.java`: same comment/string updates
  - `JavaPoetGenerator.java`: any remaining comment references to `@Curry` → `@Invoker`
  - Model classes (`AnnotatedMethod`, `MergeTree`, `MergeNode`, `OverloadGroup`, `Parameter`): update any `@Curry` references in Javadoc
  - _Requirements: 4.3, 4.4_

- [x] 9. Checkpoint — code generation produces `StageInvoker` names
  - Ensure all tests pass, ask the user if questions arise.
  - At this point the generator produces `XStageInvoker`, `InvokeStageInvoker`, `ConstructStageInvoker`.

- [x] 10. Update test files for `InvokerClassSpec` (rename + content)
  - [x] 10.1 Rename `CallerClassSpecTest.java` → `InvokerClassSpecTest.java`
    - Update class declaration: `class CallerClassSpecTest` → `class InvokerClassSpecTest`
    - Replace all `new CallerClassSpec(...)` → `new InvokerClassSpec(...)`
    - Replace all `{@link CallerClassSpec}` Javadoc references → `{@link InvokerClassSpec}`
    - Replace expected interface name strings: `"XStageCaller"` → `"XStageInvoker"`, `"YStageCaller"` → `"YStageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
    - Update assertion strings that contain `"StageCaller"` or `"InvokeStageCaller"`
    - _Requirements: 11.1, 11.3, 11.4, 11.5, 11.6, 11.8, 11.9_

  - [x] 10.2 Rename `CallerClassSpecPropertyTest.java` → `InvokerClassSpecPropertyTest.java`
    - Update class declaration: `class CallerClassSpecPropertyTest` → `class InvokerClassSpecPropertyTest`
    - Replace all `new CallerClassSpec(...)` → `new InvokerClassSpec(...)`
    - Replace all expected interface name strings: `"StageCaller"` → `"StageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`
    - Update property tag comments to reference `curry-to-invoker-rename` feature
    - _Requirements: 11.2, 11.3, 11.4, 11.5, 11.8, 11.9_

  - [x] 10.3 Write property test for Caller_Class name unchanged (Property 2)
    - **Property 2: Caller_Class name is unchanged**
    - **Validates: Requirements 8.3, 9.x**
    - Add a `@Property` in `InvokerClassSpecPropertyTest` that builds a `MergeTree` for any method name and asserts `spec.name == PascalCase(methodName)`

- [x] 11. Update `StageInterfaceSpecTest` and `StageInterfaceSpecPropertyTest`
  - [x] 11.1 Update `StageInterfaceSpecTest.java`
    - Replace all expected interface name strings: `"XStageCaller"` → `"XStageInvoker"`, `"YStageCaller"` → `"YStageInvoker"`, `"BarStageCaller"` → `"BarStageInvoker"`, `"BarXStageCaller"` → `"BarXStageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`
    - Leave `"IdStageConstructor"` and `"NameStageConstructor"` unchanged (Constructor suffix is not renamed)
    - Update assertion message strings that contain the old names
    - _Requirements: 11.5, 11.8, 11.9_

  - [x] 11.2 Update `StageInterfaceSpecPropertyTest.java`
    - Replace all expected interface name strings: `"StageCaller"` → `"StageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`
    - Update property tag comments to reference `curry-to-invoker-rename` feature
    - _Requirements: 11.5, 11.8, 11.9_

  - [x] 11.3 Write property test for StageInvoker suffix (Property 3)
    - **Property 3: Stage interfaces use StageInvoker suffix**
    - **Validates: Requirements 10.1, 10.8**
    - Add a `@Property` in `StageInterfaceSpecPropertyTest` that builds a linear `MergeTree` for any parameter list and asserts every interface name ends with `"StageInvoker"` (not `"StageCaller"`)

- [x] 12. Update `TerminalInterfaceSpecTest` and `TerminalInterfaceSpecPropertyTest`
  - [x] 12.1 Update `TerminalInterfaceSpecTest.java`
    - Replace `"InvokeStageCaller"` → `"InvokeStageInvoker"` in all assertions and comments
    - Replace `"ConstructStageCaller"` → `"ConstructStageInvoker"` in all assertions and comments
    - _Requirements: 11.6, 11.8, 11.9_

  - [x] 12.2 Update `TerminalInterfaceSpecPropertyTest.java`
    - Replace `"InvokeStageCaller"` → `"InvokeStageInvoker"` in all assertions and property tag comments
    - Replace `"ConstructStageCaller"` → `"ConstructStageInvoker"` in all assertions and property tag comments
    - _Requirements: 11.6, 11.8, 11.9_

  - [x] 12.3 Write property tests for terminal interface names (Properties 4 and 5)
    - **Property 4: Terminal interface is InvokeStageInvoker for @Invoker chains**
    - **Property 5: Terminal interface is ConstructStageInvoker for @Constructor chains**
    - **Validates: Requirements 10.2, 10.6, 10.9 and 10.3, 10.7, 10.10**
    - Add `@Property` tests in `TerminalInterfaceSpecPropertyTest` asserting `spec.name == "InvokeStageInvoker"` for `@Invoker` methods and `spec.name == "ConstructStageInvoker"` for `@Constructor` methods

- [x] 13. Update integration and processor-level test files
  - [x] 13.1 Update `RawitAnnotationProcessorIntegrationTest.java`
    - Replace every inline source string: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`, `@Curry` → `@Invoker`
    - Update Javadoc and comments: `@Curry` → `@Invoker`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 13.2 Update `RawitAnnotationProcessorPropertyTest.java`
    - Replace every inline source string: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`, `@Curry` → `@Invoker`
    - Update Javadoc and comments: `@Curry` → `@Invoker`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 13.3 Update `RawitAnnotationProcessorConstructorPropertyTest.java`
    - Same inline source string and comment updates as above
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 13.4 Write property test for round-trip equivalence with `@Invoker` (Property 1)
    - **Property 1: Round-trip equivalence is preserved**
    - **Validates: Requirements 8.2**
    - Add or update a `@Property` in `RawitAnnotationProcessorPropertyTest` that compiles a class with `@Invoker` on `add(int x, int y)` and asserts `add().x(x).y(y).invoke() == add(x, y)` for random int values

- [x] 14. Checkpoint — full test suite passes with `@Invoker`
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. Update `README.md` and spec documentation
  - [x] 15.1 Update `README.md`
    - Replace every `@Curry` → `@Invoker`
    - Replace every `import rg.rawit.Curry` → `import rg.rawit.Invoker`
    - Replace prose "curry" (as annotation concept) → "invoker" or "staged invocation" as appropriate
    - Replace option key `curry.debug` → `invoker.debug` in documentation
    - Update the compile-time error table: `@Curry` error messages → `@Invoker`
    - _Requirements: 6.1, 6.2, 6.3, 6.5_

  - [x] 15.2 Update `.kiro/specs/project-rawit-curry/` spec files
    - Replace all `@Curry` references in prose and code examples with `@Invoker` across `requirements.md`, `design.md`, and `tasks.md`
    - _Requirements: 6.4_

- [x] 16. Final checkpoint — zero references to `@Curry` remain (except deprecated alias if desired)
  - Ensure all tests pass, ask the user if questions arise.
  - Verify no remaining `rg.rawit.Curry` references in main or test sources (grep check).
  - Verify no remaining `"StageCaller"` suffix in generated-name assertions in test files.
  - If a deprecated `@Curry` alias is desired, create `src/main/java/rg/rawit/Curry.java` annotated with `@Deprecated` pointing to `@Invoker` (Requirement 7.1–7.2); otherwise confirm deletion is complete (Requirement 7.3).

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Tasks 1–4 handle the annotation rename axis; tasks 5–9 handle the generated-name rename axis; tasks 10–14 update all test files; task 15 updates documentation
- The `StageConstructor` suffix on `@Constructor` stage interfaces is NOT renamed — only `StageCaller` → `StageInvoker`
- The `@Generated` value string `"rg.rawit.processors.RawitAnnotationProcessor"` is NOT changed
- Generated Caller_Class names (e.g. `Bar`, `Constructor`) and terminal method names (`invoke()`, `construct()`) are NOT changed
- Property tests validate universal correctness properties; unit tests validate specific structural examples
