# Design Document: curry-to-invoker-rename

## Overview

This is a purely mechanical refactoring with two orthogonal rename axes:

1. **Annotation rename**: `@Curry` → `@Invoker` (the annotation class, its FQN, all usages,
   processor constants, option keys, and diagnostic strings).
2. **Generated-name rename**: the `Caller` suffix in generated interface names → `Invoker`
   (e.g. `XStageCaller` → `XStageInvoker`, `InvokeStageCaller` → `InvokeStageInvoker`) and the
   source class `CallerClassSpec` → `InvokerClassSpec`.

No processor pipeline logic, no code-generation algorithm, and no test coverage strategy changes.
The only observable runtime difference is the names of the generated stage interfaces.

---

## Architecture

The processor pipeline is unchanged. The rename touches the following layers:

```
┌─────────────────────────────────────────────────────────────────┐
│  Public API (annotation)                                        │
│    rg.rawit.Curry.java  →  rg.rawit.Invoker.java               │
└────────────────────────────┬────────────────────────────────────┘
                             │ referenced by
┌────────────────────────────▼────────────────────────────────────┐
│  Annotation Processor                                           │
│    RawitAnnotationProcessor                                     │
│      CURRY_ANNOTATION_FQN  →  INVOKER_ANNOTATION_FQN           │
│      @SupportedOptions("curry.debug")  →  ("invoker.debug")    │
│      Curry.class refs  →  Invoker.class refs                   │
│      diagnostic strings updated                                 │
└────────────────────────────┬────────────────────────────────────┘
                             │ delegates to
┌────────────────────────────▼────────────────────────────────────┐
│  Validation                                                     │
│    ElementValidator — comment/string updates only               │
└────────────────────────────┬────────────────────────────────────┘
                             │ delegates to
┌────────────────────────────▼────────────────────────────────────┐
│  Code Generation                                                │
│    CallerClassSpec.java  →  InvokerClassSpec.java               │
│      isCurry field  →  isInvoker                               │
│      terminalTypeName(): "InvokeStageCaller" → "InvokeStageInvoker" │
│    StageInterfaceSpec.java                                      │
│      isCurry field  →  isInvoker                               │
│      suffix "StageCaller"  →  "StageInvoker"                   │
│      "InvokeStageCaller"  →  "InvokeStageInvoker"              │
│      "ConstructStageCaller"  →  "ConstructStageInvoker"        │
│      "WithInvokeStageCaller"  →  "WithInvokeStageInvoker"      │
│      "WithConstructStageCaller"  →  "WithConstructStageInvoker"│
│    TerminalInterfaceSpec.java                                   │
│      "InvokeStageCaller"  →  "InvokeStageInvoker"              │
│      "ConstructStageCaller"  →  "ConstructStageInvoker"        │
│    JavaPoetGenerator.java — comment updates only               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Components and Interfaces

### Files to rename (file system)

| Old path | New path |
|---|---|
| `src/main/java/rg/rawit/Curry.java` | `src/main/java/rg/rawit/Invoker.java` |
| `src/main/java/rg/rawit/processors/codegen/CallerClassSpec.java` | `src/main/java/rg/rawit/processors/codegen/InvokerClassSpec.java` |
| `src/test/java/rg/rawit/processors/codegen/CallerClassSpecTest.java` | `src/test/java/rg/rawit/processors/codegen/InvokerClassSpecTest.java` |
| `src/test/java/rg/rawit/processors/codegen/CallerClassSpecPropertyTest.java` | `src/test/java/rg/rawit/processors/codegen/InvokerClassSpecPropertyTest.java` |

### String substitutions (per file)

**`Invoker.java`** (formerly `Curry.java`):
- Class declaration: `public @interface Curry` → `public @interface Invoker`
- Javadoc: replace "currying" / "@Curry" with "staged invocation" / "@Invoker"

**`RawitAnnotationProcessor.java`**:
- `CURRY_ANNOTATION_FQN` → `INVOKER_ANNOTATION_FQN`, value `"rg.rawit.Curry"` → `"rg.rawit.Invoker"`
- `@SupportedOptions("curry.debug")` → `@SupportedOptions("invoker.debug")`
- `"curry.debug"` (option key read) → `"invoker.debug"`
- `rg.rawit.Curry.class` → `rg.rawit.Invoker.class`
- Diagnostic strings: `"curry"` / `"@Curry"` → `"invoker"` / `"@Invoker"`
- Import: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`
- Debug log prefix `"[curry.debug]"` → `"[invoker.debug]"`

**`ElementValidator.java`**:
- Import: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`
- `element.getAnnotation(Curry.class)` → `element.getAnnotation(Invoker.class)`
- All Javadoc/comment references to `@Curry` → `@Invoker`
- Diagnostic strings: `"@Curry"` / `"curry"` → `"@Invoker"` / `"invoker"`

**`InvokerClassSpec.java`** (formerly `CallerClassSpec.java`):
- Class declaration: `public class CallerClassSpec` → `public class InvokerClassSpec`
- Field: `private final boolean isCurry` → `private final boolean isInvoker`
- All reads of `isCurry` → `isInvoker`
- `terminalTypeName()`: `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
- Javadoc: `CallerClassSpec` / `Caller_Class` / `@Curry` → `InvokerClassSpec` / `Invoker_Class` / `@Invoker`

**`StageInterfaceSpec.java`**:
- Field: `private final boolean isCurry` → `private final boolean isInvoker`
- All reads of `isCurry` → `isInvoker`
- `stageInterfaceName()`: suffix `"StageCaller"` → `"StageInvoker"`
- `branchingInterfaceName()`: suffix `"StageCaller"` → `"StageInvoker"`
- `terminalTypeName()`: `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
- `combinedInterfaceName()`: `"WithInvokeStageCaller"` → `"WithInvokeStageInvoker"`, `"WithConstructStageCaller"` → `"WithConstructStageInvoker"`
- Constructor comment: `isCurry` → `isInvoker`

**`TerminalInterfaceSpec.java`**:
- `buildInvokeStageCaller()`: interface name `"InvokeStageCaller"` → `"InvokeStageInvoker"`
- `buildConstructStageCaller()`: interface name `"ConstructStageCaller"` → `"ConstructStageInvoker"`
- Javadoc: update interface name references

**`JavaPoetGenerator.java`**:
- Comment/Javadoc references to `CallerClassSpec` → `InvokerClassSpec`
- Import: `CallerClassSpec` → `InvokerClassSpec`
- Instantiation: `new CallerClassSpec(tree)` → `new InvokerClassSpec(tree)`

**`InvokerClassSpecTest.java`** (formerly `CallerClassSpecTest.java`):
- Class declaration: `class CallerClassSpecTest` → `class InvokerClassSpecTest`
- All `new CallerClassSpec(...)` → `new InvokerClassSpec(...)`
- All expected interface name strings: `"XStageCaller"` → `"XStageInvoker"`, `"YStageCaller"` → `"YStageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"ConstructStageCaller"` → `"ConstructStageInvoker"`
- Javadoc: `CallerClassSpec` → `InvokerClassSpec`

**`InvokerClassSpecPropertyTest.java`** (formerly `CallerClassSpecPropertyTest.java`):
- Class declaration: `class CallerClassSpecPropertyTest` → `class InvokerClassSpecPropertyTest`
- All `new CallerClassSpec(...)` → `new InvokerClassSpec(...)`
- All expected interface name strings updated to `StageInvoker` variants
- Property tag comments updated

**`StageInterfaceSpecTest.java`**:
- All expected interface name strings: `"XStageCaller"` → `"XStageInvoker"`, `"YStageCaller"` → `"YStageInvoker"`, `"BarStageCaller"` → `"BarStageInvoker"`, `"BarXStageCaller"` → `"BarXStageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`, `"IdStageConstructor"` → `"IdStageConstructor"` (unchanged — Constructor suffix is not renamed), `"NameStageConstructor"` → `"NameStageConstructor"` (unchanged)

**`StageInterfaceSpecPropertyTest.java`**:
- All expected interface name strings: `"StageCaller"` → `"StageInvoker"`, `"InvokeStageCaller"` → `"InvokeStageInvoker"`
- Property tag comments updated

**`TerminalInterfaceSpecTest.java`**:
- `"InvokeStageCaller"` → `"InvokeStageInvoker"`
- `"ConstructStageCaller"` → `"ConstructStageInvoker"`

**`RawitAnnotationProcessorIntegrationTest.java`**:
- All inline source strings: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`, `@Curry` → `@Invoker`
- Javadoc/comments: `@Curry` → `@Invoker`

**`RawitAnnotationProcessorPropertyTest.java`**:
- All inline source strings: `import rg.rawit.Curry;` → `import rg.rawit.Invoker;`, `@Curry` → `@Invoker`
- Javadoc/comments: `@Curry` → `@Invoker`

**`RawitAnnotationProcessorConstructorPropertyTest.java`**:
- Same inline source string updates as above.

**`ElementValidatorTest.java`** and **`ElementValidatorPropertyTest.java`**:
- Any `@Curry` references in inline source strings or comments → `@Invoker`

**`README.md`**:
- All `@Curry` → `@Invoker`
- All `import rg.rawit.Curry` → `import rg.rawit.Invoker`
- Prose "curry" (as annotation concept) → "invoker" or "staged invocation"
- Option key `curry.debug` → `invoker.debug`

**`.kiro/specs/project-rawit-curry/` spec files**:
- All `@Curry` references in prose and code examples → `@Invoker`

**`META-INF/services/javax.annotation.processing.Processor`**:
- No change needed (processor class name is unchanged).

### Unchanged items

- `RawitAnnotationProcessor` class name — not renamed.
- `@Constructor` annotation — not renamed.
- `ConstructStageCaller` → `ConstructStageInvoker` (this IS renamed, see above).
- `StageConstructor` suffix on `@Constructor` stage interfaces — NOT renamed (only `StageCaller` → `StageInvoker`).
- `@Generated` value string `"rg.rawit.processors.RawitAnnotationProcessor"` — unchanged.
- Generated Caller_Class names (e.g. `Bar`, `Constructor`) — unchanged.
- Terminal method names `invoke()` and `construct()` — unchanged.
- All processor pipeline logic, merge tree algorithms, bytecode injection — unchanged.

---

## Data Models

No data model changes. The `AnnotatedMethod`, `MergeTree`, `MergeNode`, `OverloadGroup`, and
`Parameter` model classes are unchanged. The `isConstructorAnnotation` flag semantics are
unchanged — it still distinguishes `@Constructor` from `@Invoker` (formerly `@Curry`).

The only field-level rename is `isCurry` → `isInvoker` in `InvokerClassSpec` (formerly
`CallerClassSpec`) and `StageInterfaceSpec`. This is a private implementation detail with no
effect on the public API or serialized form.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Round-trip equivalence is preserved

*For any* valid `@Invoker`-annotated method and any valid argument values, completing the full
staged chain and calling `.invoke()` must produce a result equal to calling the annotated method
directly with the same arguments.

**Validates: Requirements 8.2**

### Property 2: Caller_Class name is unchanged

*For any* method name, the generated top-level class produced by `InvokerClassSpec` must have the
same PascalCase name as it did under `CallerClassSpec` (e.g. method `bar` → class `Bar`).

**Validates: Requirements 8.3, 9.x**

### Property 3: Stage interfaces use StageInvoker suffix

*For any* parameter name, the stage interface generated by `StageInterfaceSpec` for a
`@Invoker`-annotated method must be named `<PascalParam>StageInvoker` (not `<PascalParam>StageCaller`).

**Validates: Requirements 10.1, 10.8**

### Property 4: Terminal interface is InvokeStageInvoker for @Invoker chains

*For any* `@Invoker`-annotated method, the terminal interface generated by `TerminalInterfaceSpec`
must be named `InvokeStageInvoker` (not `InvokeStageCaller`).

**Validates: Requirements 10.2, 10.6, 10.9**

### Property 5: Terminal interface is ConstructStageInvoker for @Constructor chains

*For any* `@Constructor`-annotated constructor, the terminal interface generated by
`TerminalInterfaceSpec` must be named `ConstructStageInvoker` (not `ConstructStageCaller`).

**Validates: Requirements 10.3, 10.7, 10.10**

---

## Error Handling

This refactoring introduces no new error conditions. All existing error paths in
`ElementValidator` and `MergeTreeBuilder` are preserved; only the diagnostic message strings
that mention `@Curry` are updated to say `@Invoker`.

The processor option key change (`curry.debug` → `invoker.debug`) means that any existing build
scripts passing `-Acurry.debug=true` will silently stop enabling debug logging after the rename.
This is an intentional breaking change to the option key and should be documented in the README.

---

## Testing Strategy

### Dual testing approach

Both unit tests and property-based tests are required. Unit tests verify specific structural
examples (file names, class names, string literals). Property-based tests verify universal
correctness properties across many generated inputs.

### Unit tests (JUnit 5)

Each renamed/updated test file provides unit test coverage for its corresponding source class.
The key structural assertions are:

- `InvokerClassSpecTest`: verifies that generated class names, field modifiers, `@Generated`
  annotation, and stage method signatures are correct for specific example inputs.
- `StageInterfaceSpecTest`: verifies that interface names use `StageInvoker` suffix, that
  `InvokeStageInvoker` is the terminal type, and that `@FunctionalInterface` is present.
- `TerminalInterfaceSpecTest`: verifies that `InvokeStageInvoker` and `ConstructStageInvoker`
  are generated with the correct method signatures.
- `RawitAnnotationProcessorIntegrationTest`: end-to-end compilation tests using `@Invoker` in
  inline source strings.

### Property-based tests (jqwik, minimum 100 iterations per property)

Each correctness property from the design document is implemented as a single jqwik property test.

**Property 1 — Round-trip equivalence** (`RawitAnnotationProcessorPropertyTest`):
- Generate random int values; compile a class with `@Invoker` on `add(int x, int y)`; verify
  `add().x(x).y(y).invoke() == add(x, y)`.
- Tag: `Feature: curry-to-invoker-rename, Property 1: Round-trip equivalence is preserved`

**Property 2 — Caller_Class name unchanged** (`InvokerClassSpecPropertyTest`):
- For any method name from a fixed set, build a `MergeTree` and call `InvokerClassSpec.build()`;
  verify `spec.name == PascalCase(methodName)`.
- Tag: `Feature: curry-to-invoker-rename, Property 2: Caller_Class name is unchanged`

**Property 3 — StageInvoker suffix** (`StageInterfaceSpecPropertyTest`):
- For any parameter list, build a linear `MergeTree` and call `StageInterfaceSpec.buildAll()`;
  verify every interface name ends with `StageInvoker` (not `StageCaller`).
- Tag: `Feature: curry-to-invoker-rename, Property 3: Stage interfaces use StageInvoker suffix`

**Property 4 — InvokeStageInvoker terminal** (`TerminalInterfaceSpecPropertyTest`):
- For any `@Invoker` method, call `TerminalInterfaceSpec.build()`; verify `spec.name == "InvokeStageInvoker"`.
- Tag: `Feature: curry-to-invoker-rename, Property 4: Terminal interface is InvokeStageInvoker for @Invoker chains`

**Property 5 — ConstructStageInvoker terminal** (`TerminalInterfaceSpecPropertyTest`):
- For any `@Constructor` method, call `TerminalInterfaceSpec.build()`; verify `spec.name == "ConstructStageInvoker"`.
- Tag: `Feature: curry-to-invoker-rename, Property 5: Terminal interface is ConstructStageInvoker for @Constructor chains`

Properties 4 and 5 can be combined into a single property test that generates both `@Invoker` and
`@Constructor` methods and checks the correct terminal interface name for each.
