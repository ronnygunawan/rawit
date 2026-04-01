# Requirements Document

## Introduction

This refactoring renames the `@Curry` annotation to `@Invoker` throughout the `rawit/curry`
codebase. The rename is purely mechanical: the annotation's semantics, the processor pipeline,
the generated API shape, and all test coverage remain unchanged. Every occurrence of the name
`Curry` (in source files, test files, documentation, configuration, and the processor's
supported-annotation registry) must be replaced with `Invoker`, and every occurrence of `curry`
(lower-case, in package-option keys, diagnostic strings, and comments) must be replaced with
`invoker` where it refers to the annotation by name.

The rename scope covers:

- `src/main/java/rg/rawit/Curry.java` → renamed to `Invoker.java` with updated class name and Javadoc.
- All import statements and annotation usages of `rg.rawit.Curry` across main and test sources.
- `RawitAnnotationProcessor`: the `CURRY_ANNOTATION_FQN` constant, the `@SupportedOptions` key
  `curry.debug`, all `isConstructorAnnotation` checks that reference `rg.rawit.Curry`, and all
  diagnostic message strings that say "curry" or "@Curry".
- `ElementValidator` and all other processor components that reference `@Curry` in comments or
  strings.
- `META-INF/services/javax.annotation.processing.Processor` — no change needed (class name is
  `RawitAnnotationProcessor`, which is not renamed).
- `README.md` and any other documentation files that mention `@Curry`.
- The processor option key `curry.debug` → `invoker.debug` (both the `@SupportedOptions`
  declaration and all reads of that option).
- The `@Generated` value string `"rawit.processors.RawitAnnotationProcessor"` — unchanged (the
  processor class is not renamed).
- The existing spec at `.kiro/specs/project-rawit-curry/` — updated to reflect the new annotation
  name wherever `@Curry` appears in prose, code examples, and requirement text.

## Glossary

- **Invoker_Annotation**: The `@rg.rawit.Invoker` source-retention annotation, the renamed form of
  the former `@Curry` annotation. Placed on a method or constructor to trigger staged-invocation
  code generation.
- **Curry_Annotation**: The former `@rg.rawit.Curry` annotation. After this refactoring it no
  longer exists in the codebase.
- **Rename_Target**: Any source file, test file, resource file, or documentation file that
  contains a reference to `@Curry`, `rg.rawit.Curry`, `curry.debug`, or the prose word "curry"
  when used as the annotation name.
- **Processor**: The `RawitAnnotationProcessor` that runs during `javac` compilation. Its class
  name is not changed by this refactoring.
- **Option_Key**: The processor option string used to enable debug logging. Renamed from
  `curry.debug` to `invoker.debug`.
- **FQN**: Fully-qualified name. The FQN of the annotation changes from `rg.rawit.Curry` to
  `rg.rawit.Invoker`.
- **CallerClassSpec**: The source file `CallerClassSpec.java` (and its class) that builds the
  `TypeSpec` for the generated Caller_Class. Renamed to `InvokerClassSpec` by this refactoring.
- **InvokerClassSpec**: The renamed form of `CallerClassSpec`. After this refactoring the file is
  `InvokerClassSpec.java` and the class is `InvokerClassSpec`.
- **StageCaller_Suffix**: The generated suffix `StageCaller` appended to stage interface names
  (e.g. `XStageCaller`). Renamed to `StageInvoker` by this refactoring (e.g. `XStageInvoker`).
- **StageInvoker_Suffix**: The new generated suffix `StageInvoker` that replaces `StageCaller` on
  all stage interface names after this refactoring.
- **InvokeStageCaller**: The former name of the terminal interface generated for `@Curry`/`@Invoker`
  chains. Renamed to `InvokeStageInvoker` by this refactoring.
- **InvokeStageInvoker**: The new name of the terminal interface for `@Invoker` chains after this
  refactoring.
- **ConstructStageCaller**: The former name of the terminal interface generated for `@Constructor`
  chains. Renamed to `ConstructStageInvoker` by this refactoring.
- **ConstructStageInvoker**: The new name of the terminal interface for `@Constructor` chains after
  this refactoring.
- **WithInvokeStageCaller**: The former combined-interface suffix for `@Invoker` chains with a
  continuation (e.g. `YWithInvokeStageCaller`). Renamed to `WithInvokeStageInvoker`.
- **WithInvokeStageInvoker**: The new combined-interface suffix for `@Invoker` chains with a
  continuation (e.g. `YWithInvokeStageInvoker`).
- **WithConstructStageCaller**: The former combined-interface suffix for `@Constructor` chains with
  a continuation (e.g. `YWithConstructStageCaller`). Renamed to `WithConstructStageInvoker`.
- **WithConstructStageInvoker**: The new combined-interface suffix for `@Constructor` chains with a
  continuation (e.g. `YWithConstructStageInvoker`).
- **isCurry_Field**: The boolean field `isCurry` in both `CallerClassSpec` (now `InvokerClassSpec`)
  and `StageInterfaceSpec` that selects between `@Invoker` and `@Constructor` suffixes. Renamed to
  `isInvoker` by this refactoring.

---

## Requirements

### Requirement 1: Rename the Annotation Source File

**User Story:** As a library maintainer, I want the annotation class to be named `Invoker` instead
of `Curry`, so that the public API reflects the intended semantics of staged invocation rather than
the functional-programming concept of currying.

#### Acceptance Criteria

1. THE Refactoring SHALL rename the file `src/main/java/rg/rawit/Curry.java` to
   `src/main/java/rg/rawit/Invoker.java`.
2. THE Refactoring SHALL rename the annotation type declaration from `public @interface Curry` to
   `public @interface Invoker` inside the renamed file.
3. THE Refactoring SHALL update the Javadoc comment in `Invoker.java` to replace all references to
   `@Curry` and "currying" with `@Invoker` and "staged invocation" respectively.
4. THE Refactoring SHALL preserve the `@Target`, `@Retention`, and all other meta-annotations on
   the annotation type unchanged.
5. WHEN the renamed `Invoker.java` is compiled, THE Compiler SHALL produce no errors or warnings
   related to the rename.

---

### Requirement 2: Update All Import Statements and Usages

**User Story:** As a library maintainer, I want every import of `rg.rawit.Curry` replaced with
`rg.rawit.Invoker`, so that the codebase compiles cleanly after the rename.

#### Acceptance Criteria

1. THE Refactoring SHALL replace every `import rg.rawit.Curry;` statement in all Java source and
   test files with `import rg.rawit.Invoker;`.
2. THE Refactoring SHALL replace every annotation usage `@Curry` in all Java source and test files
   with `@Invoker`.
3. THE Refactoring SHALL replace every reference to the class literal `Curry.class` with
   `Invoker.class`.
4. WHEN the full project is compiled after the refactoring, THE Compiler SHALL emit zero errors
   caused by unresolved references to `rg.rawit.Curry`.

---

### Requirement 3: Update RawitAnnotationProcessor

**User Story:** As a library maintainer, I want the processor to recognise `@Invoker` instead of
`@Curry`, so that the processor correctly handles the renamed annotation at compile time.

#### Acceptance Criteria

1. THE Refactoring SHALL rename the constant `CURRY_ANNOTATION_FQN` in `RawitAnnotationProcessor`
   to `INVOKER_ANNOTATION_FQN` and update its value from `"rg.rawit.Curry"` to
   `"rg.rawit.Invoker"`.
2. THE Refactoring SHALL update the `getSupportedAnnotationTypes()` method to return
   `"rg.rawit.Invoker"` instead of `"rg.rawit.Curry"`.
3. THE Refactoring SHALL update the `@SupportedOptions` annotation value from `"curry.debug"` to
   `"invoker.debug"`.
4. THE Refactoring SHALL update every read of the processor option key from `"curry.debug"` to
   `"invoker.debug"` (e.g. in `isDebugEnabled()`).
5. THE Refactoring SHALL update every `exec.getAnnotation(rg.rawit.Curry.class)` call to
   `exec.getAnnotation(rg.rawit.Invoker.class)`.
6. THE Refactoring SHALL update all diagnostic message strings in `RawitAnnotationProcessor` that
   contain the substring `"curry"` or `"@Curry"` to use `"invoker"` or `"@Invoker"` respectively.
7. WHEN the processor is run against a class annotated with `@Invoker`, THE Processor SHALL
   process the element exactly as it previously processed elements annotated with `@Curry`.

---

### Requirement 4: Update ElementValidator and Other Processor Components

**User Story:** As a library maintainer, I want all processor components to reference `@Invoker`
in their comments, strings, and logic, so that the codebase is internally consistent after the
rename.

#### Acceptance Criteria

1. THE Refactoring SHALL update all Javadoc and inline comments in `ElementValidator.java` that
   mention `@Curry` to mention `@Invoker` instead.
2. THE Refactoring SHALL update all diagnostic message strings emitted by `ElementValidator` that
   contain `"@Curry"` or `"curry"` (when referring to the annotation) to use `"@Invoker"` or
   `"invoker"` respectively.
3. THE Refactoring SHALL apply the same comment and string updates to every other processor
   component (`MergeTreeBuilder`, `JavaPoetGenerator`, `CallerClassSpec`, `StageInterfaceSpec`,
   `TerminalInterfaceSpec`, `BytecodeInjector`, `OverloadResolver`, model classes) that references
   `@Curry` by name.
4. WHEN the full project is compiled after the refactoring, THE Compiler SHALL emit zero errors or
   warnings caused by the rename in any processor component.

---

### Requirement 5: Update Test Files

**User Story:** As a library maintainer, I want all test files to use `@Invoker` instead of
`@Curry`, so that the test suite continues to compile and pass after the rename.

#### Acceptance Criteria

1. THE Refactoring SHALL replace every `import rg.rawit.Curry;` in all test source files with
   `import rg.rawit.Invoker;`.
2. THE Refactoring SHALL replace every `@Curry` annotation usage in all test source files with
   `@Invoker`.
3. THE Refactoring SHALL replace every string literal `"rg.rawit.Curry"` in test files (e.g. in
   integration tests that compile source strings containing `@Curry`) with `"rg.rawit.Invoker"`.
4. THE Refactoring SHALL replace every inline Java source string in test files that contains
   `@Curry` (e.g. source strings passed to `javax.tools.JavaCompiler`) with `@Invoker`.
5. THE Refactoring SHALL update all test comments and Javadoc that mention `@Curry` to mention
   `@Invoker`.
6. WHEN the full test suite is executed after the refactoring, THE Test_Runner SHALL report zero
   test failures caused by the rename.

---

### Requirement 6: Update Documentation

**User Story:** As a library user reading the documentation, I want all references to `@Curry` in
the README and other docs to say `@Invoker`, so that the documentation accurately reflects the
current public API.

#### Acceptance Criteria

1. THE Refactoring SHALL replace every occurrence of `@Curry` in `README.md` with `@Invoker`.
2. THE Refactoring SHALL replace every occurrence of the prose word "curry" (when used as the
   annotation name or concept name) in `README.md` with "invoker" or "staged invocation" as
   appropriate to the context.
3. THE Refactoring SHALL update all code examples in `README.md` that use `import rg.rawit.Curry`
   or `@Curry` to use `import rg.rawit.Invoker` and `@Invoker`.
4. THE Refactoring SHALL update the existing spec documents under
   `.kiro/specs/project-rawit-curry/` to replace `@Curry` references with `@Invoker` in all prose,
   code examples, and requirement text.
5. WHEN a developer reads `README.md` after the refactoring, THE Document SHALL contain no
   references to `@Curry` as a current annotation name.

---

### Requirement 7: Preserve Backward-Compatibility Marker (Optional Deprecation Path)

**User Story:** As a library maintainer, I want to decide whether to keep a deprecated `@Curry`
alias or remove it entirely, so that existing users have a migration path if needed.

#### Acceptance Criteria

1. WHERE a deprecated alias is desired, THE Refactoring SHALL create a new
   `src/main/java/rg/rawit/Curry.java` that is annotated with `@Deprecated` and whose Javadoc
   states that `@Curry` is deprecated in favour of `@Invoker`.
2. WHERE a deprecated alias is desired, THE deprecated `@Curry` annotation SHALL carry the same
   `@Target` and `@Retention` meta-annotations as `@Invoker`.
3. WHERE no deprecated alias is desired, THE Refactoring SHALL delete `Curry.java` entirely and
   SHALL NOT leave any `rg.rawit.Curry` type in the compiled output.
4. THE Processor SHALL NOT be updated to process the deprecated `@Curry` alias; only `@Invoker`
   SHALL be processed after the rename.

---

### Requirement 8: No Functional Regression

**User Story:** As a library maintainer, I want the rename to introduce zero unintended functional
changes, so that all existing behaviour is preserved and no new bugs are introduced.

#### Acceptance Criteria

1. FOR ALL valid `@Invoker`-annotated elements, THE Processor SHALL generate the same bytecode and
   source files as it previously generated for the equivalent `@Curry`-annotated elements, except
   for the intentional generated-name changes described in Requirements 9 and 10.
2. FOR ALL valid `@Invoker`-annotated elements, completing the full staged chain and calling
   `.invoke()` SHALL produce a result equal to calling the Annotated_Element directly with the
   same arguments (round-trip equivalence — unchanged from the pre-rename behaviour).
3. THE Refactoring SHALL NOT change the name of any generated Caller_Class (e.g. `Bar` remains
   `Bar`) or any injected terminal method name (`invoke()`, `construct()` remain unchanged).
4. THE Refactoring SHALL change the generated stage interface names from the `StageCaller` suffix
   to the `StageInvoker` suffix (e.g. `XStageCaller` → `XStageInvoker`); this is an intentional
   part of the rename and is NOT considered a regression.
5. THE Refactoring SHALL change the generated terminal interface names from `InvokeStageCaller` to
   `InvokeStageInvoker` and from `ConstructStageCaller` to `ConstructStageInvoker`; this is
   intentional and is NOT considered a regression.
6. THE Refactoring SHALL NOT change the `@Generated` value string
   `"rg.rawit.processors.RawitAnnotationProcessor"` on any generated class.
7. WHEN the full test suite is executed after the refactoring, THE Test_Runner SHALL report the
   same number of passing tests as before the refactoring.

---

### Requirement 9: Rename CallerClassSpec to InvokerClassSpec

**User Story:** As a library maintainer, I want the `CallerClassSpec` source file and class to be
renamed to `InvokerClassSpec`, so that the internal code-generation class name is consistent with
the `@Invoker` annotation name.

#### Acceptance Criteria

1. THE Refactoring SHALL rename the file
   `src/main/java/rg/rawit/processors/codegen/CallerClassSpec.java` to
   `src/main/java/rg/rawit/processors/codegen/InvokerClassSpec.java`.
2. THE Refactoring SHALL rename the class declaration from `public class CallerClassSpec` to
   `public class InvokerClassSpec` inside the renamed file.
3. THE Refactoring SHALL update every reference to `CallerClassSpec` in all main source files
   (e.g. `JavaPoetGenerator.java`) to `InvokerClassSpec`.
4. THE Refactoring SHALL rename the field `isCurry` in `InvokerClassSpec` to `isInvoker` and
   update all reads of that field within the class.
5. THE Refactoring SHALL update the Javadoc comment in `InvokerClassSpec.java` to replace all
   references to `CallerClassSpec`, `Caller_Class`, and `@Curry` with `InvokerClassSpec`,
   `Invoker_Class`, and `@Invoker` respectively.
6. WHEN the renamed `InvokerClassSpec.java` is compiled, THE Compiler SHALL produce no errors or
   warnings related to the rename.

---

### Requirement 10: Rename Generated Stage Interface Suffixes

**User Story:** As a library maintainer, I want all generated stage interface names to use the
`StageInvoker` suffix instead of `StageCaller`, so that the generated public API is consistent
with the `@Invoker` annotation name.

#### Acceptance Criteria

1. THE Refactoring SHALL update `StageInterfaceSpec.java` to replace the suffix string
   `"StageCaller"` with `"StageInvoker"` in the `stageInterfaceName()` and
   `branchingInterfaceName()` methods, so that generated stage interfaces are named
   `XStageInvoker` instead of `XStageCaller`.
2. THE Refactoring SHALL update `StageInterfaceSpec.java` to replace `"InvokeStageCaller"` with
   `"InvokeStageInvoker"` in the `terminalTypeName()` method.
3. THE Refactoring SHALL update `StageInterfaceSpec.java` to replace `"ConstructStageCaller"` with
   `"ConstructStageInvoker"` in the `terminalTypeName()` method.
4. THE Refactoring SHALL update `StageInterfaceSpec.java` to replace `"WithInvokeStageCaller"`
   with `"WithInvokeStageInvoker"` and `"WithConstructStageCaller"` with
   `"WithConstructStageInvoker"` in the `combinedInterfaceName()` method.
5. THE Refactoring SHALL rename the field `isCurry` in `StageInterfaceSpec` to `isInvoker` and
   update all reads of that field within the class.
6. THE Refactoring SHALL update `TerminalInterfaceSpec.java` to replace `"InvokeStageCaller"` with
   `"InvokeStageInvoker"` in the `buildInvokeStageCaller()` method and its Javadoc.
7. THE Refactoring SHALL update `TerminalInterfaceSpec.java` to replace `"ConstructStageCaller"`
   with `"ConstructStageInvoker"` in the `buildConstructStageCaller()` method and its Javadoc.
8. WHEN the processor generates code for a method annotated with `@Invoker`, THE Generator SHALL
   produce stage interfaces named `<PascalParam>StageInvoker` (e.g. `XStageInvoker`,
   `YStageInvoker`) instead of `<PascalParam>StageCaller`.
9. WHEN the processor generates code for a method annotated with `@Invoker`, THE Generator SHALL
   produce a terminal interface named `InvokeStageInvoker` instead of `InvokeStageCaller`.
10. WHEN the processor generates code for a constructor annotated with `@Constructor`, THE
    Generator SHALL produce a terminal interface named `ConstructStageInvoker` instead of
    `ConstructStageCaller`.
11. WHEN the processor generates a combined interface for a chain with a continuation, THE
    Generator SHALL name it `<Param>WithInvokeStageInvoker` or `<Param>WithConstructStageInvoker`
    instead of `<Param>WithInvokeStageCaller` or `<Param>WithConstructStageCaller`.

---

### Requirement 11: Update Test Files for CallerClassSpec and Generated Name Changes

**User Story:** As a library maintainer, I want all test files that reference `CallerClassSpec` or
the old generated interface names to be updated, so that the test suite compiles and passes after
the rename.

#### Acceptance Criteria

1. THE Refactoring SHALL rename the file
   `src/test/java/rg/rawit/processors/codegen/CallerClassSpecTest.java` to
   `src/test/java/rg/rawit/processors/codegen/InvokerClassSpecTest.java`.
2. THE Refactoring SHALL rename the file
   `src/test/java/rg/rawit/processors/codegen/CallerClassSpecPropertyTest.java` to
   `src/test/java/rg/rawit/processors/codegen/InvokerClassSpecPropertyTest.java`.
3. THE Refactoring SHALL update the class declarations inside those files from
   `class CallerClassSpecTest` and `class CallerClassSpecPropertyTest` to
   `class InvokerClassSpecTest` and `class InvokerClassSpecPropertyTest` respectively.
4. THE Refactoring SHALL replace every reference to `CallerClassSpec` in the renamed test files
   with `InvokerClassSpec`.
5. THE Refactoring SHALL replace every string literal `"StageCaller"` and every occurrence of
   `StageCaller` as part of an expected interface name (e.g. `"XStageCaller"`,
   `"InvokeStageCaller"`) in all test files with the corresponding `StageInvoker` name (e.g.
   `"XStageInvoker"`, `"InvokeStageInvoker"`).
6. THE Refactoring SHALL replace every occurrence of `"ConstructStageCaller"` in all test files
   with `"ConstructStageInvoker"`.
7. THE Refactoring SHALL replace every occurrence of `"WithInvokeStageCaller"` and
   `"WithConstructStageCaller"` in all test files with `"WithInvokeStageInvoker"` and
   `"WithConstructStageInvoker"` respectively.
8. THE Refactoring SHALL update all Javadoc and inline comments in the renamed test files that
   mention `CallerClassSpec`, `StageCaller`, `InvokeStageCaller`, or `ConstructStageCaller` to use
   the new names.
9. WHEN the full test suite is executed after the refactoring, THE Test_Runner SHALL report zero
   test failures caused by the CallerClassSpec rename or the generated-name changes.
