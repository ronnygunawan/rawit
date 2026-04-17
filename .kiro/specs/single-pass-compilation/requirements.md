# Requirements Document

## Introduction

This feature enables single-pass compilation for the Rawit annotation processor by leveraging javac's `TaskListener` API. Previously, bytecode injection required multi-pass compiler configuration (compile → process → recompile) because the processor needed `.class` files to exist before it could inject parameterless overloads. With single-pass compilation, the processor defers bytecode injection until after javac writes each `.class` file (the GENERATE phase), eliminating the need for multi-pass build setups in both Maven and Gradle. The processor falls back gracefully to multi-pass mode when running under non-javac compilers (e.g., ECJ).

## Glossary

- **Rawit_Processor**: The `RawitAnnotationProcessor` that handles `@Invoker`, `@Constructor`, and `@Getter` annotations, generating source files and injecting bytecode into `.class` files.
- **TaskListener**: A javac compiler API (`com.sun.source.util.TaskListener`) that receives callbacks at various compilation phases, including after `.class` file generation (GENERATE phase).
- **JavacTask**: The javac compiler API (`com.sun.source.util.JavacTask`) used to obtain a handle on the current compilation task and register a TaskListener.
- **GENERATE_Phase**: The javac compilation phase during which `.class` files are written to disk. The TaskListener's `finished()` callback fires after each class completes this phase.
- **Single_Pass_Mode**: A compilation mode where annotation processing and bytecode injection occur within a single `javac` invocation, with no multi-pass compiler configuration required.
- **Multi_Pass_Mode**: A compilation mode requiring multiple compiler executions (e.g., compile → process → recompile) so that `.class` files exist before the processor can inject bytecode.
- **Deferred_Injection**: The strategy of storing pending bytecode injections in memory during annotation processing and executing them later when the TaskListener fires after the GENERATE phase.
- **Pending_Invoker_Injections**: A map of binary class names to lists of `MergeTree` objects representing `@Invoker` and `@Constructor` bytecode injections awaiting the GENERATE phase.
- **Pending_Getter_Injections**: A map of binary class names to lists of `AnnotatedField` objects representing `@Getter` bytecode injections awaiting the GENERATE phase.
- **OverloadResolver**: The component that resolves the `.class` file path for a given binary class name from the build output directory.
- **Bytecode_Injector**: The component (`BytecodeInjector`) that injects parameterless overload methods into `.class` files for `@Invoker` and `@Constructor` annotations.
- **Getter_Bytecode_Injector**: The component (`GetterBytecodeInjector`) that injects getter methods into `.class` files for `@Getter` annotations.
- **Non_Javac_Compiler**: A Java compiler other than javac (e.g., Eclipse Compiler for Java / ECJ) that does not support the `com.sun.source.util.JavacTask` API.

## Requirements

### Requirement 1: TaskListener Registration During Initialization

**User Story:** As a developer using Rawit with javac, I want the processor to automatically register a TaskListener during initialization, so that single-pass compilation works without any build configuration changes.

#### Acceptance Criteria

1. WHEN the Rawit_Processor initializes under javac, THE Rawit_Processor SHALL obtain a JavacTask instance from the ProcessingEnvironment and register a TaskListener.
2. WHEN the TaskListener is successfully registered, THE Rawit_Processor SHALL set an internal flag indicating that Single_Pass_Mode is active.
3. IF the Rawit_Processor cannot obtain a JavacTask instance (e.g., running under a Non_Javac_Compiler), THEN THE Rawit_Processor SHALL catch the `IllegalArgumentException` and continue initialization without a TaskListener.
4. IF an unexpected exception occurs during TaskListener registration, THEN THE Rawit_Processor SHALL catch the exception, log a diagnostic note when debug mode is enabled, and continue initialization without a TaskListener.
5. WHEN TaskListener registration fails for any reason, THE Rawit_Processor SHALL remain in Multi_Pass_Mode and process annotations using the existing immediate-injection strategy.

### Requirement 2: Deferred Bytecode Injection for @Invoker and @Constructor

**User Story:** As a developer, I want the processor to defer bytecode injection when `.class` files do not yet exist during annotation processing, so that single-pass compilation produces correct results.

#### Acceptance Criteria

1. WHEN the Rawit_Processor processes `@Invoker` or `@Constructor` annotations and the `.class` file for the enclosing class already exists, THE Rawit_Processor SHALL inject bytecode immediately (Multi_Pass_Mode compatible).
2. WHEN the Rawit_Processor processes `@Invoker` or `@Constructor` annotations and the `.class` file does not yet exist and Single_Pass_Mode is active, THE Rawit_Processor SHALL store the pending injection in the Pending_Invoker_Injections map keyed by the binary class name.
3. WHEN the Rawit_Processor processes `@Invoker` or `@Constructor` annotations and the `.class` file does not yet exist and Single_Pass_Mode is not active, THE Rawit_Processor SHALL skip bytecode injection silently (non-javac single-pass not supported).
4. WHEN multiple overload groups target the same enclosing class, THE Rawit_Processor SHALL merge the pending injection lists for that class name.

### Requirement 3: Deferred Bytecode Injection for @Getter

**User Story:** As a developer, I want `@Getter` bytecode injection to also be deferred in single-pass mode, so that getter methods are correctly injected regardless of compilation mode.

#### Acceptance Criteria

1. WHEN the Rawit_Processor processes `@Getter` annotations and the `.class` file for the enclosing class already exists, THE Rawit_Processor SHALL inject getter bytecode immediately (Multi_Pass_Mode compatible).
2. WHEN the Rawit_Processor processes `@Getter` annotations and the `.class` file does not yet exist and Single_Pass_Mode is active, THE Rawit_Processor SHALL store the pending injection in the Pending_Getter_Injections map keyed by the binary class name.
3. WHEN the Rawit_Processor processes `@Getter` annotations and the `.class` file does not yet exist and Single_Pass_Mode is not active, THE Rawit_Processor SHALL skip getter bytecode injection silently.
4. WHEN multiple `@Getter` fields target the same enclosing class across processing rounds, THE Rawit_Processor SHALL merge the pending field lists for that class name.

### Requirement 4: Post-GENERATE TaskListener Execution

**User Story:** As a developer, I want the TaskListener to inject bytecode into `.class` files immediately after they are written by javac, so that the final `.class` files contain all generated methods.

#### Acceptance Criteria

1. WHEN the TaskListener receives a GENERATE phase `finished` event for a class with Pending_Getter_Injections, THE TaskListener SHALL resolve the `.class` file path and invoke the Getter_Bytecode_Injector.
2. WHEN the TaskListener receives a GENERATE phase `finished` event for a class with Pending_Invoker_Injections, THE TaskListener SHALL resolve the `.class` file path and invoke the Bytecode_Injector.
3. WHEN the TaskListener receives a GENERATE phase `finished` event for a class with no pending injections, THE TaskListener SHALL take no action.
4. THE TaskListener SHALL only respond to events of kind `GENERATE`; events of other kinds SHALL be ignored.
5. WHEN the TaskListener processes a pending injection, THE TaskListener SHALL remove the entry from the pending injections map so that the injection is not repeated.

### Requirement 5: OverloadResolver Path Resolution Without Existence Check

**User Story:** As a developer, I want the OverloadResolver to provide a method that returns the expected `.class` file path without checking whether the file exists, so that deferred injection can determine the target path before the file is written.

#### Acceptance Criteria

1. THE OverloadResolver SHALL provide a `resolvePath` method that returns the expected `.class` file path for a given binary class name without verifying that the file exists on disk.
2. THE existing `resolve` method SHALL delegate to `resolvePath` and add a `Files.exists` check, preserving backward compatibility.
3. WHEN the `.class` file URI scheme is not `file:`, THE `resolvePath` method SHALL return an empty Optional.
4. IF an IOException or IllegalArgumentException occurs during path resolution, THEN THE `resolvePath` method SHALL return an empty Optional.

### Requirement 6: Source File Generation Independence from .class File Existence

**User Story:** As a developer, I want source file generation (stage interfaces, caller classes) to run regardless of whether the `.class` file exists, so that javac can compile the generated sources in the same pass.

#### Acceptance Criteria

1. THE Rawit_Processor SHALL generate source files via JavaPoetGenerator for all valid MergeTrees regardless of whether the enclosing class `.class` file exists.
2. THE Rawit_Processor SHALL only gate bytecode injection (not source generation) on `.class` file existence or deferred injection.

### Requirement 7: Build Configuration Simplification

**User Story:** As a developer, I want to use Rawit with a standard single-pass build configuration, so that I do not need multi-pass compiler plugin executions or custom Gradle tasks.

#### Acceptance Criteria

1. WHEN using Maven with javac, THE sample Maven project SHALL require only a standard `maven-compiler-plugin` configuration with no multi-pass execution blocks.
2. WHEN using Gradle with javac, THE sample Gradle project SHALL require only standard `annotationProcessor` and `compileOnly` dependency declarations with no custom compile tasks.
3. THE README SHALL document that no multi-pass compiler configuration is needed when using javac.

### Requirement 8: Backward Compatibility with Multi-Pass Mode

**User Story:** As a developer with an existing multi-pass build setup, I want the processor to continue working correctly in multi-pass mode, so that upgrading does not break existing builds.

#### Acceptance Criteria

1. WHEN the `.class` file already exists during annotation processing (multi-pass setup), THE Rawit_Processor SHALL inject bytecode immediately as in previous versions.
2. WHEN running under a Non_Javac_Compiler, THE Rawit_Processor SHALL fall back to Multi_Pass_Mode without errors or warnings (unless debug mode is enabled).
3. THE Rawit_Processor SHALL produce identical bytecode output regardless of whether injection occurs immediately or via Deferred_Injection.

### Requirement 9: Single-Pass Integration Testing

**User Story:** As a developer, I want integration tests that verify single-pass compilation produces correct results for all annotation types, so that the feature is validated end-to-end.

#### Acceptance Criteria

1. THE integration test suite SHALL include a `compileSinglePassAndLoad` helper that compiles source code with the Rawit_Processor in a single javac invocation (no multi-pass setup).
2. WHEN an `@Invoker`-annotated instance method is compiled in Single_Pass_Mode, THE generated invoker chain SHALL produce the same result as direct method invocation.
3. WHEN an `@Invoker`-annotated static method is compiled in Single_Pass_Mode, THE generated invoker chain SHALL produce the same result as direct static method invocation.
4. WHEN a `@Constructor`-annotated constructor is compiled in Single_Pass_Mode, THE generated constructor chain SHALL produce an instance equivalent to direct constructor invocation.
5. WHEN `@Getter`-annotated fields are compiled in Single_Pass_Mode, THE generated getter methods SHALL return the correct field values.
