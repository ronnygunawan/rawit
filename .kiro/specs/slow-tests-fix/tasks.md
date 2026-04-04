# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Compilation-Heavy Tests Use Excessive Tries
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to the four concrete affected classes for reproducibility
  - For each affected class, read the `@Property(tries = N)` annotation value on every property method using reflection or source inspection
  - Assert that `ElementValidatorPropertyTest` has `tries = 5` on all 9 property methods (will FAIL — currently 100)
  - Assert that `BytecodeInjectorPropertyTest` has `tries = 5` on all 5 property methods (will FAIL — currently 100)
  - Assert that `RawitAnnotationProcessorPropertyTest` has `tries = 5` on all 3 property methods (will FAIL — currently 10)
  - Assert that `RawitAnnotationProcessorConstructorPropertyTest` has `tries = 5` on all 3 property methods (will FAIL — currently 10)
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bug exists)
  - Document counterexamples found: e.g. `ElementValidatorPropertyTest.property1_validInvokerMethod_producesNoErrors` has `tries=100` instead of `tries=5`
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Pure In-Memory Tests Retain tries=100
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: `MergeTreeBuilderPropertyTest` has `tries = 100` on all properties on unfixed code
  - Observe: `InvokerClassSpecPropertyTest` has `tries = 100` on all properties on unfixed code
  - Observe: `StageInterfaceSpecPropertyTest` has `tries = 100` on all properties on unfixed code
  - Observe: `TerminalInterfaceSpecPropertyTest` has `tries = 100` on all properties on unfixed code
  - Write property-based test: for all pure in-memory property test classes (isBugCondition = false), the `tries` value equals 100
  - Also verify test logic (assertions, arbitraries, helper methods) in affected classes is unchanged — only the annotation attribute differs
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 3. Fix: reduce @Property(tries) on compilation-heavy test classes

  - [x] 3.1 Implement the fix in ElementValidatorPropertyTest
    - Replace all 9 occurrences of `@Property(tries = 100)` with `@Property(tries = 5)` in `src/test/java/rawit/processors/validation/ElementValidatorPropertyTest.java`
    - No other changes to the file (test logic, assertions, arbitraries, helpers all unchanged)
    - _Bug_Condition: isBugCondition(testClass) where testClass.invokesJavaCompilerPerTry = true AND testClass.triesCount > 5_
    - _Expected_Behavior: @Property(tries = 5) on all 9 property methods_
    - _Preservation: test logic, assertions, and arbitraries remain identical_
    - _Requirements: 2.1, 3.1, 3.3, 3.4_

  - [x] 3.2 Implement the fix in BytecodeInjectorPropertyTest
    - Replace all 5 occurrences of `@Property(tries = 100)` with `@Property(tries = 5)` in `src/test/java/rawit/processors/inject/BytecodeInjectorPropertyTest.java`
    - No other changes to the file
    - _Bug_Condition: isBugCondition(testClass) where testClass.invokesJavaCompilerPerTry = true AND testClass.triesCount > 5_
    - _Expected_Behavior: @Property(tries = 5) on all 5 property methods_
    - _Preservation: test logic, assertions, and arbitraries remain identical_
    - _Requirements: 2.2, 3.1, 3.3, 3.4_

  - [x] 3.3 Implement the fix in RawitAnnotationProcessorPropertyTest
    - Replace all 3 occurrences of `@Property(tries = 10)` with `@Property(tries = 5)` in `src/test/java/rawit/processors/RawitAnnotationProcessorPropertyTest.java`
    - No other changes to the file
    - _Bug_Condition: isBugCondition(testClass) where testClass.invokesJavaCompilerPerTry = true AND testClass.triesCount > 5_
    - _Expected_Behavior: @Property(tries = 5) on all 3 property methods_
    - _Preservation: test logic, assertions, and arbitraries remain identical_
    - _Requirements: 2.3, 3.1, 3.3, 3.4_

  - [x] 3.4 Implement the fix in RawitAnnotationProcessorConstructorPropertyTest
    - Replace all 3 occurrences of `@Property(tries = 10)` with `@Property(tries = 5)` in `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`
    - No other changes to the file
    - _Bug_Condition: isBugCondition(testClass) where testClass.invokesJavaCompilerPerTry = true AND testClass.triesCount > 5_
    - _Expected_Behavior: @Property(tries = 5) on all 3 property methods_
    - _Preservation: test logic, assertions, and arbitraries remain identical_
    - _Requirements: 2.4, 3.1, 3.3, 3.4_

  - [x] 3.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Compilation-Heavy Tests Use Reduced Tries
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior (tries = 5 on all affected methods)
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed — all 20 affected annotations now read tries=5)
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Pure In-Memory Tests Retain tries=100
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — pure in-memory tests still at 100 tries)
    - Confirm all tests still pass after fix (no regressions)

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full test suite (`mvn test`) and verify all tests pass
  - Confirm compilation-heavy property tests still exercise their properties (at least 1 try each)
  - Confirm pure in-memory property tests still run at 100 tries
  - Ask the user if any questions arise
