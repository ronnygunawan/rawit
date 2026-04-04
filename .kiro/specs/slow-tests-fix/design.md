# Slow Tests Fix — Bugfix Design

## Overview

Four jqwik property-test classes invoke `javax.tools.JavaCompiler` (or a full 3-pass compilation
pipeline) on every single try. Because compilation is expensive, these classes dominate total test
time. The fix is a targeted reduction of the `@Property(tries = …)` value on each affected class
while leaving the pure in-memory property tests untouched.

Affected classes and their new `tries` values:

| Class | Old tries | New tries |
|---|---|---|
| `ElementValidatorPropertyTest` | 100 | 5 |
| `BytecodeInjectorPropertyTest` | 100 | 5 |
| `RawitAnnotationProcessorPropertyTest` | 10 | 5 |
| `RawitAnnotationProcessorConstructorPropertyTest` | 10 | 5 |

Unaffected (pure in-memory, stay at 100):
`InvokerClassSpecPropertyTest`, `StageInterfaceSpecPropertyTest`,
`TerminalInterfaceSpecPropertyTest`, `MergeTreeBuilderPropertyTest`.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the performance problem — a property-test
  class that invokes `JavaCompiler` (or a multi-pass compilation pipeline) on every try AND has
  a `tries` count that is higher than necessary for meaningful coverage.
- **Property (P)**: The desired state after the fix — the `tries` count is reduced to a value
  that still exercises the property meaningfully but avoids unnecessary compiler invocations.
- **Preservation**: The `tries` count on pure in-memory property tests must remain unchanged,
  and every property must still be executed at least once.
- **`@Property(tries = N)`**: The jqwik annotation attribute that controls how many random
  inputs are generated per property method.
- **compilation-heavy test**: A property test whose body calls `ToolProvider.getSystemJavaCompiler()`
  or a multi-pass pipeline (proc-only + generated-source compile + class load) on every try.
- **pure in-memory test**: A property test that operates entirely on in-memory data structures
  (no `JavaCompiler` calls), making 100 tries cheap.

## Bug Details

### Bug Condition

The bug manifests when a property-test class that invokes `JavaCompiler` on every try is
configured with a `tries` count that is unnecessarily high. The `@Property(tries = N)`
annotation on each affected method is set to a value that causes hundreds (or tens) of
full compiler invocations per test run, making the suite slow.

**Formal Specification:**
```
FUNCTION isBugCondition(testClass)
  INPUT: testClass — a jqwik property-test class
  OUTPUT: boolean

  RETURN testClass.invokesJavaCompilerPerTry = true
         AND testClass.triesCount > THRESHOLD(testClass)
         
  WHERE THRESHOLD(testClass) = 5   -- all compilation-heavy tests
END FUNCTION
```

### Examples

- `ElementValidatorPropertyTest` with `tries = 100`: 9 properties × 100 tries × 1 compiler
  invocation = up to 900 compiler calls. **Expected**: `tries = 5` → up to 45 calls.
- `BytecodeInjectorPropertyTest` with `tries = 100`: 5 properties × 100 tries × 1 compiler
  invocation = up to 500 compiler calls. **Expected**: `tries = 5` → up to 25 calls.
- `RawitAnnotationProcessorPropertyTest` with `tries = 10`: 3 properties × 10 tries × 3-pass
  pipeline = 30 full pipelines. **Expected**: `tries = 5` → 15 full pipelines.
- `RawitAnnotationProcessorConstructorPropertyTest` with `tries = 10`: same as above.
- `MergeTreeBuilderPropertyTest` with `tries = 100`: pure in-memory, no compiler calls.
  **Expected**: stays at 100 — no change needed.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Pure in-memory property tests (`InvokerClassSpecPropertyTest`, `StageInterfaceSpecPropertyTest`,
  `TerminalInterfaceSpecPropertyTest`, `MergeTreeBuilderPropertyTest`) must continue to run at
  100 tries.
- Every property in every affected class must still be executed (at least 1 try per property).
- When a property detects a violation, jqwik must still report a clear failure with the
  counterexample, regardless of the reduced try count.
- The full test suite must continue to cover all existing acceptance criteria.

**Scope:**
All inputs that do NOT involve the `tries` attribute of a compilation-heavy property test are
completely unaffected by this fix. This includes:
- The test logic itself (assertions, arbitraries, helper methods)
- The production source files under `src/main/`
- Any non-property tests (unit tests, integration tests)
- The `tries` values on pure in-memory property tests

## Hypothesized Root Cause

The `tries` counts were set to their current values when the tests were first written, likely
mirroring the default jqwik value of 1000 (reduced to 100 as a first pass) without accounting
for the per-try cost of invoking `JavaCompiler`. The root cause is simply that the annotation
`@Property(tries = 100)` (or `tries = 10`) was applied uniformly without distinguishing between
cheap in-memory tests and expensive compilation-based tests.

1. **Uniform tries count**: All property tests were given the same `tries` value regardless of
   per-try cost. Compilation-heavy tests need a lower value.

2. **No per-class cost model**: There is no mechanism in the codebase to enforce different
   `tries` budgets for different cost tiers of tests.

3. **Incremental growth**: As more compilation-heavy properties were added, the cumulative
   cost grew but the `tries` values were never revisited.

## Correctness Properties

Property 1: Bug Condition — Compilation-Heavy Tests Use Reduced Tries

_For any_ property-test class where the bug condition holds (invokes `JavaCompiler` per try
AND current `tries` exceeds the threshold), the fixed class SHALL have its `@Property(tries = N)`
reduced to 5.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

Property 2: Preservation — Pure In-Memory Tests and Test Logic Are Unchanged

_For any_ property-test class where the bug condition does NOT hold (either pure in-memory OR
already at/below the threshold), the fixed codebase SHALL leave the `tries` count and all test
logic identical to the original, preserving full coverage for fast tests.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct, the fix is four targeted annotation-attribute edits.

**File**: `src/test/java/rawit/processors/validation/ElementValidatorPropertyTest.java`

**Change**: Replace every `@Property(tries = 100)` with `@Property(tries = 5)`.
There are 9 such annotations (one per property method in the class).

---

**File**: `src/test/java/rawit/processors/inject/BytecodeInjectorPropertyTest.java`

**Change**: Replace every `@Property(tries = 100)` with `@Property(tries = 5)`.
There are 5 such annotations (one per property method in the class).

---

**File**: `src/test/java/rawit/processors/RawitAnnotationProcessorPropertyTest.java`

**Change**: Replace every `@Property(tries = 10)` with `@Property(tries = 5)`.
There are 3 such annotations (one per property method in the class).

---

**File**: `src/test/java/rawit/processors/RawitAnnotationProcessorConstructorPropertyTest.java`

**Change**: Replace every `@Property(tries = 10)` with `@Property(tries = 5)`.
There are 3 such annotations (one per property method in the class).

**No other files are modified.**

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, confirm the current slow behavior
(exploratory), then verify the fix reduces tries counts without breaking any test logic.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix.
Confirm or refute the root cause analysis.

**Test Plan**: Inspect the `@Property(tries = N)` annotations in each affected class and
assert that the current value exceeds the threshold. Run the test suite and observe that
compilation-heavy classes take disproportionately long.

**Test Cases**:
1. **ElementValidatorPropertyTest tries check**: Assert `tries = 100` on all 9 properties
   (will fail after fix, confirming the fix was applied).
2. **BytecodeInjectorPropertyTest tries check**: Assert `tries = 100` on all 5 properties.
3. **RawitAnnotationProcessorPropertyTest tries check**: Assert `tries = 10` on all 3 properties.
4. **RawitAnnotationProcessorConstructorPropertyTest tries check**: Assert `tries = 10` on all 3 properties.

**Expected Counterexamples**:
- The `tries` attribute on compilation-heavy properties is higher than the threshold.
- Possible causes: uniform tries policy, no cost-tier distinction.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed annotation
attribute equals the threshold value.

**Pseudocode:**
```
FOR ALL testClass WHERE isBugCondition(testClass) DO
  result := inspect @Property(tries) on each method in testClass
  ASSERT result = THRESHOLD(testClass)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed
codebase produces the same result as the original.

**Pseudocode:**
```
FOR ALL testClass WHERE NOT isBugCondition(testClass) DO
  ASSERT @Property(tries) in fixed = @Property(tries) in original
  ASSERT test logic in fixed = test logic in original
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain.
- It catches edge cases that manual unit tests might miss.
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs.

**Test Plan**: Observe that pure in-memory tests still pass at 100 tries after the fix.

**Test Cases**:
1. **MergeTreeBuilderPropertyTest preservation**: Verify `tries = 100` is unchanged and all
   properties still pass.
2. **InvokerClassSpecPropertyTest preservation**: Verify `tries = 100` is unchanged.
3. **StageInterfaceSpecPropertyTest preservation**: Verify `tries = 100` is unchanged.
4. **TerminalInterfaceSpecPropertyTest preservation**: Verify `tries = 100` is unchanged.

### Unit Tests

- Verify each affected class has the correct reduced `tries` value after the fix.
- Verify each unaffected class retains its original `tries` value.
- Verify that all property methods in affected classes still compile and run without errors.

### Property-Based Tests

- Generate random valid inputs for each affected property and verify the property still holds
  with the reduced tries count (i.e., no regressions in the test logic itself).
- Verify that the pure in-memory properties continue to pass across 100 tries.
- Verify that jqwik still reports counterexamples correctly when a property is violated.

### Integration Tests

- Run the full test suite after the fix and verify all tests pass.
- Measure total test time before and after to confirm a meaningful reduction.
- Verify that switching between compilation-heavy and in-memory tests in the same run
  produces correct results for both.
