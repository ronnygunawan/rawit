# Bugfix Requirements Document

## Introduction

Several property-based tests (jqwik) in the test suite invoke `javax.tools.JavaCompiler` on every
single try. Because compilation is expensive (JVM startup, disk I/O, class loading), tests that
run 100 tries × full compilation per try dominate total test time and make the inner dev loop with
Kiro noticeably slow.

The affected tests are:

- `ElementValidatorPropertyTest` — 9 properties × 100 tries, each spawning a full compiler
  invocation with the annotation processor active.
- `BytecodeInjectorPropertyTest` — 5 properties × 100 tries, each compiling a fresh `.java`
  source file to produce a `.class` file for injection.
- `RawitAnnotationProcessorPropertyTest` — 3 properties × 10 tries, each running a 3-pass
  compilation pipeline (proc-only + generated-source compile + class load).
- `RawitAnnotationProcessorConstructorPropertyTest` — 3 properties × 10 tries, same 3-pass
  pipeline.

The pure in-memory tests (`InvokerClassSpecPropertyTest`, `StageInterfaceSpecPropertyTest`,
`TerminalInterfaceSpecPropertyTest`, `MergeTreeBuilderPropertyTest`) are already fast and are
not affected.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `ElementValidatorPropertyTest` runs THEN the system invokes `JavaCompiler` once per
    try (100 tries × 9 properties = up to 900 compiler invocations), making the test class
    take tens of seconds.

1.2 WHEN `BytecodeInjectorPropertyTest` runs THEN the system compiles a fresh `.java` source
    file on every try (100 tries × 5 properties = up to 500 compiler invocations), making the
    test class take tens of seconds.

1.3 WHEN `RawitAnnotationProcessorPropertyTest` runs THEN the system runs a 3-pass compilation
    pipeline on every try (10 tries × 3 properties = 30 full pipelines), each pipeline being
    significantly more expensive than a single compilation.

1.4 WHEN `RawitAnnotationProcessorConstructorPropertyTest` runs THEN the system runs a 3-pass
    compilation pipeline on every try (10 tries × 3 properties = 30 full pipelines).

### Expected Behavior (Correct)

2.1 WHEN `ElementValidatorPropertyTest` runs THEN the system SHALL reduce the number of
    compiler invocations by lowering the `tries` count to 5 without sacrificing correctness.

2.2 WHEN `BytecodeInjectorPropertyTest` runs THEN the system SHALL reduce the number of
    compiler invocations by lowering the `tries` count to 5 without sacrificing correctness.

2.3 WHEN `RawitAnnotationProcessorPropertyTest` runs THEN the system SHALL reduce the number
    of full compilation pipelines by lowering the `tries` count to 5, since each try
    is already a heavyweight end-to-end integration scenario.

2.4 WHEN `RawitAnnotationProcessorConstructorPropertyTest` runs THEN the system SHALL reduce
    the number of full compilation pipelines by lowering the `tries` count to 5.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN any property test runs THEN the system SHALL CONTINUE TO execute at least one try
    per property, ensuring every property is still exercised.

3.2 WHEN the pure in-memory property tests run (`InvokerClassSpecPropertyTest`,
    `StageInterfaceSpecPropertyTest`, `TerminalInterfaceSpecPropertyTest`,
    `MergeTreeBuilderPropertyTest`) THEN the system SHALL CONTINUE TO run those tests at
    100 tries, since they are already fast.

3.3 WHEN a property test detects a violation THEN the system SHALL CONTINUE TO report a
    clear failure with the counterexample, regardless of the reduced try count.

3.4 WHEN the full test suite runs THEN the system SHALL CONTINUE TO cover all existing
    acceptance criteria (validation rules, bytecode injection correctness, end-to-end
    annotation processing).
