# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Missing Javadoc in Main Sources
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate missing/broken Javadoc across the 13 affected files
  - **Scoped PBT Approach**: Scope the property to the concrete failing files listed in requirements 1.2–1.13
  - Create `src/test/java/rawit/JavadocCompletenessPropertyTest.java` (similar in style to `SourceFilePackageConsistencyTest`)
  - For each `.java` file under `src/main/java/rawit/`, parse the source text and assert:
    - Every public constructor has a Javadoc comment (`/** ... */` immediately before it)
    - Every public non-void method has a `@return` tag in its Javadoc
    - No `{@link javax.tools.Filer#getResource}` appears anywhere (broken link from requirement 1.10)
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS — counterexamples include `Parameter.java` (no @param), `OverloadResolver.java` (broken {@link}), `BytecodeInjector.java` (no constructor comment), `InvokerClassSpec.java` (no @return on build()), etc.
  - Document counterexamples found to confirm root cause matches requirements 1.2–1.13
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12, 1.13_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Runtime Behaviour Is Unchanged
  - **IMPORTANT**: Follow observation-first methodology — run existing tests on UNFIXED code first
  - Observe: all tests in `*PropertyTest.java` and `*Test.java` pass on unfixed code
  - The existing test suite already covers all runtime behaviour (annotation processing, validation, merge tree building, bytecode injection, code generation)
  - No new test files needed — the preservation property is: `mvn test` exits 0 on unfixed code
  - Run `mvn test` on UNFIXED code and record that all tests pass
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline runtime behaviour to preserve)
  - Mark task complete when baseline passing status is confirmed on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 3. Fix all 13 affected source files (Javadoc-only changes)

  - [x] 3.1 Fix `Parameter.java` — add @param tags to record compact constructor
    - Add `@param name` and `@param typeDescriptor` to the record-level Javadoc comment
    - _Bug_Condition: isBugCondition(Parameter.java, compact constructor) — no @param tags_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for Parameter.java_
    - _Preservation: record construction behaviour unchanged — comments stripped at compile time_
    - _Requirements: 1.2, 2.2_

  - [x] 3.2 Fix `AnnotatedMethod.java` — compact constructor + convenience constructor @param tags
    - Add brief Javadoc comment to the compact constructor: `/** Defensive copy constructor; copies {@code parameters} and {@code checkedExceptions}. */`
    - Add `@param` tags for all 7 parameters on the 7-arg convenience constructor
    - Add `@param` tags for all 8 parameters on the 8-arg convenience constructor
    - _Bug_Condition: isBugCondition(AnnotatedMethod.java, compact/convenience constructors) — missing @param and no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for AnnotatedMethod.java_
    - _Preservation: record construction behaviour unchanged_
    - _Requirements: 1.3, 2.3_

  - [x] 3.3 Fix `BytecodeInjector.java` — add explicit documented constructor
    - Add `/** Creates a new {@code BytecodeInjector}. */ public BytecodeInjector() {}`
    - _Bug_Condition: isBugCondition(BytecodeInjector.java, default constructor) — no explicit documented constructor_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for BytecodeInjector.java_
    - _Preservation: no logic change; inject() behaviour identical_
    - _Requirements: 1.4, 2.4_

  - [x] 3.4 Fix `ElementValidator.java` — add explicit documented constructor
    - Add `/** Creates a new {@code ElementValidator}. */ public ElementValidator() {}`
    - _Bug_Condition: isBugCondition(ElementValidator.java, default constructor) — no explicit documented constructor_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for ElementValidator.java_
    - _Preservation: no logic change; validate() behaviour identical_
    - _Requirements: 1.4, 2.4_

  - [x] 3.5 Fix `OverloadResolver.java` — add explicit documented constructor + fix broken {@link}
    - Add `/** Creates a new {@code OverloadResolver}. */ public OverloadResolver() {}`
    - Fix broken link: replace `{@link javax.tools.Filer#getResource}` with `{@link javax.tools.JavaFileManager#getResource(javax.tools.JavaFileManager.Location, String, String)}`
    - _Bug_Condition: isBugCondition(OverloadResolver.java, default constructor) AND isBugCondition(OverloadResolver.java, broken {@link}) — Filer does not declare getResource_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings or errors for OverloadResolver.java_
    - _Preservation: no logic change; resolve() behaviour identical_
    - _Requirements: 1.4, 1.10, 2.4, 2.10_

  - [x] 3.6 Fix `RawitAnnotationProcessor.java` — add explicit documented constructor
    - Add `/** Creates a new {@code RawitAnnotationProcessor}. */ public RawitAnnotationProcessor() {}`
    - _Bug_Condition: isBugCondition(RawitAnnotationProcessor.java, default constructor) — no explicit documented constructor_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for RawitAnnotationProcessor.java_
    - _Preservation: no logic change; process() behaviour identical_
    - _Requirements: 1.4, 2.4_

  - [x] 3.7 Fix `InvokerClassSpec.java` — add constructor Javadoc + @return on build()
    - Add Javadoc to the existing constructor with `@param tree the merge tree describing the overload group to generate code for`
    - Add `@return the fully constructed {@link TypeSpec} for the Invoker_Class` to `build()`
    - _Bug_Condition: isBugCondition(InvokerClassSpec.java, constructor) AND isBugCondition(InvokerClassSpec.java, build()) — no comment and no @return_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for InvokerClassSpec.java_
    - _Preservation: no logic change; build() return value identical_
    - _Requirements: 1.5, 2.5_

  - [x] 3.8 Fix `JavaPoetGenerator.java` — add constructor Javadoc
    - Add Javadoc to the existing constructor with `@param messager the compiler messager used to emit diagnostics`
    - _Bug_Condition: isBugCondition(JavaPoetGenerator.java, constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for JavaPoetGenerator.java_
    - _Preservation: no logic change; generate() behaviour identical_
    - _Requirements: 1.6, 2.6_

  - [x] 3.9 Fix `MergeNode.java` — add Javadoc to BranchingNode and TerminalNode compact constructors
    - Add `/** Defensive copy; makes {@code branches} unmodifiable. */` to `BranchingNode` compact constructor
    - Add `/** Defensive copy; makes {@code overloads} unmodifiable. */` to `TerminalNode` compact constructor
    - _Bug_Condition: isBugCondition(MergeNode.java, BranchingNode compact constructor) AND isBugCondition(MergeNode.java, TerminalNode compact constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for MergeNode.java_
    - _Preservation: record construction behaviour unchanged_
    - _Requirements: 1.7, 2.7_

  - [x] 3.10 Fix `MergeTreeBuilder.java` — add constructor Javadoc
    - Add Javadoc to the existing constructor with `@param messager the compiler messager used to emit conflict diagnostics`
    - _Bug_Condition: isBugCondition(MergeTreeBuilder.java, constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for MergeTreeBuilder.java_
    - _Preservation: no logic change; build() behaviour identical_
    - _Requirements: 1.8, 2.8_

  - [x] 3.11 Fix `OverloadGroup.java` — add compact constructor Javadoc
    - Add `/** Defensive copy; makes {@code members} unmodifiable. */` to the compact constructor
    - _Bug_Condition: isBugCondition(OverloadGroup.java, compact constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for OverloadGroup.java_
    - _Preservation: record construction behaviour unchanged_
    - _Requirements: 1.9, 2.9_

  - [x] 3.12 Fix `StageInterfaceSpec.java` — add constructor Javadoc
    - Add Javadoc to the existing constructor with `@param tree the merge tree to generate stage interfaces for`
    - _Bug_Condition: isBugCondition(StageInterfaceSpec.java, constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for StageInterfaceSpec.java_
    - _Preservation: no logic change; build() behaviour identical_
    - _Requirements: 1.11, 2.11_

  - [x] 3.13 Fix `TerminalInterfaceSpec.java` — add constructor Javadoc
    - Add Javadoc to the existing constructor with `@param method the representative annotated method for this terminal stage`
    - _Bug_Condition: isBugCondition(TerminalInterfaceSpec.java, constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for TerminalInterfaceSpec.java_
    - _Preservation: no logic change; build() behaviour identical_
    - _Requirements: 1.12, 2.12_

  - [x] 3.14 Fix `ValidationResult.java` — add Javadoc to Valid and Invalid compact constructors
    - Add `/** Creates a {@link Valid} result. */` to the `Valid` compact constructor
    - Add `/** Creates an {@link Invalid} result. */` to the `Invalid` compact constructor
    - _Bug_Condition: isBugCondition(ValidationResult.java, Valid compact constructor) AND isBugCondition(ValidationResult.java, Invalid compact constructor) — no comment_
    - _Expected_Behavior: mvn javadoc:javadoc emits no warnings for ValidationResult.java_
    - _Preservation: record construction behaviour unchanged_
    - _Requirements: 1.13, 2.13_

  - [x] 3.15 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - All Public Members in Main Sources Have Complete Javadoc
    - **IMPORTANT**: Re-run the SAME test from task 1 (`JavadocCompletenessPropertyTest`) — do NOT write a new test
    - The test from task 1 encodes the expected behavior (complete Javadoc, no broken {@link})
    - Run `mvn test -Dtest=JavadocCompletenessPropertyTest`
    - **EXPECTED OUTCOME**: Test PASSES (confirms all 13 files now have complete Javadoc)
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12, 2.13_

  - [x] 3.16 Verify preservation tests still pass
    - **Property 2: Preservation** - Runtime Behaviour Is Unchanged
    - **IMPORTANT**: Re-run the SAME test suite from task 2 — do NOT write new tests
    - Run `mvn test` (full suite)
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — Javadoc comments are stripped at compile time and cannot affect runtime behaviour)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 4. Checkpoint — Ensure all tests pass and Javadoc builds cleanly
  - Run `mvn javadoc:javadoc` and assert exit code 0 with no `warning:` or `error:` lines in output
  - Run `mvn test` and assert all tests pass
  - Ensure all tasks above are complete; ask the user if any questions arise
