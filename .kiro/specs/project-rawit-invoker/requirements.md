# Requirements Document

## Introduction

Project Rawit is an open source Java annotation processor library (Java 17+, Maven) that provides
compile-time staged-invocation APIs via two annotations: `@Invoker` and `@Constructor`. The processor
works like Lombok: it manipulates the *original* class's `.class` file in the build output folder
rather than generating a separate companion class.

When `@Invoker` is placed on a method `bar(int x, int y)` in class `Foo`, the processor injects into
`Foo`'s bytecode:

- A parameterless overload `bar()` that returns the first stage interface (`Bar.XStageInvoker`).
- A top-level class `Bar` (in the same package as `Foo`) that implements all stage interfaces and
  holds the accumulated arguments.
- A set of stage interfaces nested inside `Bar`, one per parameter, each named after the
  parameter (e.g. `XStageInvoker`, `YStageInvoker`). Each stage interface exposes a single method
  named after the parameter (e.g. `x(int x)`) that returns the next stage interface. The last
  stage's method returns an `InvokeStageInvoker` whose `invoke()` method calls the original
  annotated method and returns its result.

Usage after annotation processing:

```java
foo.bar().x(10).y(20).invoke();
```

When `@Constructor` is placed on a constructor `Foo(int id, String name)`, the processor injects
into `Foo`'s bytecode:

- A `public static` method `constructor()` that returns the first stage interface
  (`Constructor.IdStageConstructor`).
- A top-level class `Constructor` (in the same package as `Foo`) that implements all stage
  interfaces and holds the accumulated arguments.
- A set of stage interfaces nested inside `Constructor`, one per parameter, each named
  `<ParameterName>StageConstructor` in PascalCase (e.g. `IdStageConstructor`,
  `NameStageConstructor`). The last stage's method returns a `ConstructStageInvoker` whose
  `construct()` method calls `new Foo(...)` and returns the new instance.

Usage after annotation processing:

```java
var foo = Foo.constructor().id(1).name("bar").construct();
```

The pattern works for both instance methods and static methods, as well as constructors.

## Glossary

- **Invoker_Annotation**: The `@rawit.Invoker` source-retention annotation placed on a method or constructor.
- **Annotated_Element**: The method or constructor carrying `@Invoker`.
- **Processor**: The `RawitAnnotationProcessor` that runs during `javac` compilation and drives bytecode manipulation.
- **Bytecode_Manipulator**: The component (e.g. ASM or Javassist) that modifies the original `.class` file in the build output directory.
- **Caller_Class**: The generated top-level class (e.g. `Bar` for method `bar`) in the same package as the enclosing class. It implements all Stage_Interfaces and accumulates arguments.
- **Stage_Interface**: A generated single-method interface nested inside the Caller_Class, named `<ParameterName>StageInvoker` (e.g. `XStageInvoker`). One Stage_Interface is created per parameter.
- **Stage_Method**: The single method on a Stage_Interface, named after the parameter (e.g. `x(int x)`). It returns the next Stage_Interface, or the InvokeStageInvoker for the last stage.
- **Parameterless_Overload**: The zero-argument method injected into the original class that starts the staged invocation chain by returning the first Stage_Interface.
- **Entry_Stage**: The first Stage_Interface in the chain, returned by the Parameterless_Overload.
- **Final_Result**: The return type of the Annotated_Element (or the enclosing class type for constructors). For `void` methods `invoke()` also returns `void`.
- **InvokeStageInvoker**: A generated interface with a single zero-argument method `invoke()` that calls the original Annotated_Element with all accumulated arguments and returns the Final_Result. The last Stage_Method in every chain returns an InvokeStageInvoker rather than the Final_Result directly.
- **Chain**: The ordered sequence of Stage_Interfaces, one per parameter of the Annotated_Element, terminating in an InvokeStageInvoker.
- **Overload_Group**: The set of all `@Invoker`-annotated methods in the same class that share the same method name.
- **Shared_Prefix**: The longest leading sequence of parameters (by position) across all overloads in an Overload_Group where every overload agrees on both the parameter name and type.
- **Branching_Stage**: A Stage_Interface generated at the point where an Overload_Group diverges; it exposes one Stage_Method per distinct next-parameter variant rather than a single Stage_Method.
- **Prefix_Overload**: An overload whose full parameter list is a strict prefix of another overload in the same Overload_Group (same names and types up to the shorter length).
- **Invoke_Method**: The zero-argument `invoke()` method on an InvokeStageInvoker that calls the Annotated_Element and returns its result.
- **Round**: A single pass of annotation processing performed by `javac`.
- **Processing_Environment**: The `javax.annotation.processing.ProcessingEnvironment` provided to the Processor at init time.
- **Constructor_Annotation**: The `@rawit.Constructor` source-retention annotation placed on a constructor.
- **Constructor_Caller_Class**: The generated top-level class named `Constructor` (in the same package as the enclosing class). It implements all StageConstructor_Interfaces and accumulates arguments for the constructor call.
- **StageConstructor_Interface**: A generated single-method interface nested inside the Constructor_Caller_Class, named `<ParameterName>StageConstructor` in PascalCase (e.g. `IdStageConstructor`). One StageConstructor_Interface is created per parameter.
- **ConstructStageInvoker**: A generated interface with a single zero-argument method `construct()` that calls `new EnclosingClass(...)` with all accumulated arguments and returns the new instance. Analogous to InvokeStageInvoker for `@Invoker`.
- **Constructor_Entry_Point**: The `public static` method named `constructor()` (always this literal name) injected into the enclosing class that starts the staged construction chain by returning the first StageConstructor_Interface.
- **Constructor_Overload_Group**: The set of all `@Constructor`-annotated constructors in the same class. Merged using the same shared-prefix and branching rules as an Overload_Group.

---

## Requirements

### Requirement 1: Validate @Invoker on Methods

**User Story:** As a library user, I want the compiler to reject invalid `@Invoker` usages with a
clear error message, so that I catch mistakes at compile time rather than at runtime.

#### Acceptance Criteria

1. WHEN `@Invoker` is placed on a method with zero parameters, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` message stating that `@Invoker` requires at least one parameter, referencing
   the offending element.
2. WHEN `@Invoker` is placed on a private method, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` message stating that `@Invoker` requires at least package-private
   visibility, referencing the offending element.
3. WHEN `@Invoker` is placed on a valid method (instance or static) with one or more non-private
   parameters, THE Processor SHALL emit no error diagnostics for that element.

---

### Requirement 2: Validate @Invoker on Constructors

**User Story:** As a library user, I want to use staged invocation on constructors as well as methods, so that I can
apply the same compile-time-safe construction pattern to any class.

#### Acceptance Criteria

1. WHEN `@Invoker` is placed on a constructor with zero parameters, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` message stating that `@Invoker` requires at least one parameter, referencing
   the offending element.
2. WHEN `@Invoker` is placed on a private constructor, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` message stating that `@Invoker` requires at least package-private
   visibility, referencing the offending element.
3. WHEN `@Invoker` is placed on a valid constructor with one or more parameters, THE Processor SHALL
   emit no error diagnostics for that element.

---

### Requirement 3: Inject Parameterless Overload into Original Class

**User Story:** As a library user, I want a zero-argument overload of my annotated method to be
available on the original class, so that I can start the staged invocation chain with `foo.bar()` without
any separate factory class.

#### Acceptance Criteria

1. WHEN the Processor processes a valid Annotated_Element named `bar` on class `Foo`, THE
   Bytecode_Manipulator SHALL inject a Parameterless_Overload method `bar()` into `Foo`'s `.class`
   file in the build output directory.
2. THE Parameterless_Overload SHALL have the same access modifier as the Annotated_Element (public,
   protected, or package-private).
3. THE Parameterless_Overload SHALL return the Entry_Stage interface type (`Foo.Bar.XStageInvoker`
   where `x` is the name of the first parameter).
4. WHEN the Annotated_Element is a static method, THE Parameterless_Overload SHALL also be static.
5. WHEN the Annotated_Element is an instance method, THE Parameterless_Overload SHALL be an instance
   method that captures `this` and passes it to the Caller_Class constructor.
6. WHEN the Annotated_Element is a constructor, THE Parameterless_Overload SHALL be a `public static`
   factory method on the enclosing class named after the class (e.g. `Foo.foo()`) returning the
   Entry_Stage interface.
7. WHEN a Parameterless_Overload with the same name already exists on the class, THEN THE Processor
   SHALL emit a `Diagnostic.Kind.ERROR` indicating a naming conflict and SHALL NOT inject the overload.

---

### Requirement 4: Generate Caller Class

**User Story:** As a library user, I want a generated inner class to accumulate my staged invocation arguments
and invoke the original method at the final step, so that the chain is self-contained and immutable.

#### Acceptance Criteria

1. THE Bytecode_Manipulator SHALL inject a `public static` inner class named after the Annotated_Element
   in PascalCase (e.g. `Bar` for method `bar`) into the enclosing class's bytecode.
2. THE Caller_Class SHALL implement all Stage_Interfaces for the Annotated_Element.
3. WHEN the Annotated_Element is an instance method, THE Caller_Class SHALL hold a `private final`
   reference to the enclosing class instance (`this`) passed at construction time.
4. THE Caller_Class SHALL NOT expose any public mutable fields; all accumulated argument fields SHALL
   be `private final`.
5. THE Caller_Class SHALL be annotated with `@javax.annotation.processing.Generated` carrying the
   value `"rawit.processors.RawitAnnotationProcessor"`.

---

### Requirement 5: Generate Stage Interfaces

**User Story:** As a library user, I want each parameter of my annotated method to correspond to a
named stage interface, so that the compiler guides me through each argument in order with
self-documenting method names.

#### Acceptance Criteria

1. THE Bytecode_Manipulator SHALL generate one Stage_Interface per parameter of the Annotated_Element,
   nested inside the Caller_Class, named `<ParameterName>StageInvoker` in PascalCase
   (e.g. `XStageInvoker` for parameter `x`).
2. Each Stage_Interface SHALL declare exactly one Stage_Method named after the parameter (e.g. `x(int x)`).
3. For all Stage_Interfaces except the last, the Stage_Method SHALL return the next Stage_Interface
   in the Chain.
4. For the last Stage_Interface, the Stage_Method SHALL return an InvokeStageInvoker (not the
   Final_Result directly).
5. THE Bytecode_Manipulator SHALL generate one InvokeStageInvoker interface per Annotated_Element
   (or per distinct terminal sub-chain in an Overload_Group), with a single zero-argument method
   `invoke()` that returns the Final_Result. WHEN the Annotated_Element returns `void`, `invoke()`
   SHALL also return `void`.
6. WHEN a parameter type is a primitive, THE Stage_Method SHALL use the primitive type directly
   (no auto-boxing to wrapper types).
7. THE Bytecode_Manipulator SHALL preserve generic type parameters from the Annotated_Element's
   enclosing class and method signature in the generated Stage_Interfaces.
8. THE Bytecode_Manipulator SHALL annotate each Stage_Interface with `@FunctionalInterface`.

---

### Requirement 6: Chain Invocation and Final Result

**User Story:** As a library user, I want to call `.invoke()` at the end of the chain to trigger
the original method with all accumulated arguments and return its result, so that the staged invocation chain
is functionally equivalent to calling the original directly.

#### Acceptance Criteria

1. THE last Stage_Method in the Caller_Class SHALL return an InvokeStageInvoker whose `invoke()`
   method invokes the Annotated_Element with all accumulated arguments and returns the Final_Result.
2. WHEN the Annotated_Element is a constructor, THE `invoke()` implementation SHALL use
   `new EnclosingClass(...)` to construct and return the instance.
3. WHEN the Annotated_Element is a static method, THE `invoke()` implementation SHALL delegate to
   `EnclosingClass.methodName(...)` and return its result.
4. WHEN the Annotated_Element is an instance method, THE `invoke()` implementation SHALL delegate
   to the captured instance reference and return its result.
5. THE Bytecode_Manipulator SHALL propagate any checked exceptions declared on the Annotated_Element
   through every Stage_Interface's Stage_Method signature and through the `invoke()` method of the
   InvokeStageInvoker.
6. FOR ALL valid Annotated_Elements, completing the full Chain and calling `.invoke()` SHALL produce
   a result equal to calling the Annotated_Element directly with the same arguments (round-trip
   equivalence).

---

### Requirement 7: Support Multiple @Invoker Annotations on the Same Class

**User Story:** As a library user, I want to annotate multiple methods or constructors in the same
class, so that I can use staged invocation on several methods without conflicts.

#### Acceptance Criteria

1. WHEN multiple Annotated_Elements belong to the same enclosing class, THE Bytecode_Manipulator
   SHALL inject a separate Caller_Class and Stage_Interfaces for each Annotated_Element into the
   same enclosing class bytecode.
2. THE Caller_Class names SHALL be unique within the enclosing class; each is derived from the
   Annotated_Element's name in PascalCase.
3. WHEN two Annotated_Elements in the same class share the same name and identical parameter lists
   (same count, names, and types), THEN THE Processor SHALL emit a `Diagnostic.Kind.ERROR` message
   indicating an ambiguous invoker target and SHALL NOT generate code for the conflicting elements.
4. WHEN multiple Annotated_Elements in the same class share the same name but differ in parameter
   lists, THE Processor SHALL treat them as an Overload_Group and apply overload-merging rules
   (see Requirements 11–14) rather than emitting an error.

---

### Requirement 8: Code Generation Correctness

**User Story:** As a contributor, I want the generated staged invocation API to be semantically equivalent to
calling the original method directly, so that adopting `@Invoker` does not change program behaviour.

#### Acceptance Criteria

1. FOR ALL valid Annotated_Elements, calling the Parameterless_Overload, completing the full
   Chain, and calling `.invoke()` SHALL produce a result equal to calling the Annotated_Element
   directly with the same arguments.
2. THE Bytecode_Manipulator SHALL produce `.class` files that pass `javac` verification and load
   without `VerifyError`.
3. THE generated Caller_Class SHALL NOT introduce any mutable state; all intermediate values SHALL
   be captured in `private final` fields set at each stage transition.
4. FOR ALL valid Annotated_Elements, completing the Chain with the same arguments and calling
   `.invoke()` multiple times SHALL produce equal results (idempotent with respect to the
   underlying method's own semantics).

---

### Requirement 9: Processor Lifecycle and Incremental Compilation

**User Story:** As a build engineer, I want the annotation processor to behave correctly across
incremental builds and multi-round processing, so that it does not produce duplicate or stale files.

#### Acceptance Criteria

1. THE Processor SHALL perform bytecode manipulation only during the first Round in which the
   relevant annotations are present, and SHALL skip manipulation in subsequent Rounds.
2. WHEN the target `.class` file has already been modified in a previous incremental build run,
   THE Bytecode_Manipulator SHALL detect the injected members and SHALL NOT inject them again.
3. THE Processor SHALL return `false` from `process()` to allow other annotation processors to
   observe `@Invoker`-annotated elements.

---

### Requirement 10: Processor Observability

**User Story:** As a developer debugging a build, I want the processor to emit structured notes
during bytecode manipulation, so that I can trace what was generated and why.

#### Acceptance Criteria

1. WHEN the Processor successfully injects a Parameterless_Overload and Caller_Class, THE Processor
   SHALL emit a `Diagnostic.Kind.NOTE` message identifying the modified class and the source element.
2. WHEN the Processor skips manipulation due to a validation error, THE Processor SHALL emit exactly
   one `Diagnostic.Kind.ERROR` per violated rule, referencing the offending element.
3. WHERE the processor option `invoker.debug` is set to `true`, THE Processor SHALL emit additional
   `Diagnostic.Kind.NOTE` messages describing each Stage_Interface and Stage_Method generated.

---

### Requirement 11: Overload Branching in Stage Interfaces

**User Story:** As a library user, I want overloaded `@Invoker` methods with the same name to share
a single staged invocation entry point and diverge into separate branches only where their signatures differ,
so that the generated API is minimal and non-redundant.

#### Acceptance Criteria

1. WHEN an Overload_Group contains two or more overloads, THE Processor SHALL compute the
   Shared_Prefix across all overloads in the group.
2. THE Bytecode_Manipulator SHALL generate a single Parameterless_Overload for the Overload_Group
   (not one per overload) that returns the first shared Stage_Interface.
3. WHILE parameters at a given position share the same name and type across all overloads in the
   group, THE Bytecode_Manipulator SHALL generate a single Stage_Interface for that position shared
   by all overloads.
4. WHEN overloads diverge at a given position (at least two overloads differ in parameter name or
   type at that position), THE Bytecode_Manipulator SHALL generate a Branching_Stage at that
   position that exposes one Stage_Method per distinct parameter variant among the diverging
   overloads.
5. THE Branching_Stage SHALL be named after the last shared parameter using the
   `<ParameterName>StageInvoker` convention; IF divergence occurs at the very first parameter, THE
   Branching_Stage SHALL be named `<MethodName>StageInvoker` (e.g. `BarStageInvoker`).
6. Each Stage_Method on the Branching_Stage SHALL return the next Stage_Interface (or an
   InvokeStageInvoker if it is the terminal stage of that sub-chain) appropriate for the sub-chain
   it continues.

**Example A — diverge at second parameter:**
```java
@Invoker public void bar(int x, int y) {}
@Invoker public void bar(int x, String name) {}
```
`bar()` → `XStageInvoker.x(int)` → `BarXStageInvoker` with both `y(int)` (returns InvokeStageInvoker)
and `name(String)` (returns InvokeStageInvoker).

**Example B — diverge at first parameter:**
```java
@Invoker public void bar(int x, int y) {}
@Invoker public void bar(String name, int z) {}
```
`bar()` → `BarStageInvoker` with both `x(int x)` and `name(String name)` as first-step methods.

---

### Requirement 12: Prefix Overload — Shared Terminal Stage

**User Story:** As a library user, I want to call a shorter overload mid-chain without being forced
to continue to a longer overload, so that both overloads remain accessible from the same fluent
entry point.

#### Acceptance Criteria

1. WHEN an Overload_Group contains a Prefix_Overload, the stage reached after the last parameter of
   the shorter overload SHALL expose both an InvokeStageInvoker (via the last Stage_Method) and the
   Stage_Method(s) for the next parameter of the longer overload(s).
2. Calling `.invoke()` on the InvokeStageInvoker at that stage SHALL invoke the Prefix_Overload with
   all accumulated arguments and return its Final_Result.
3. Calling the next-parameter Stage_Method at that stage SHALL continue the chain toward the longer
   overload, ultimately ending in its own InvokeStageInvoker.
4. FOR ALL valid Prefix_Overloads, calling `.invoke()` at the appropriate stage SHALL produce a
   result equal to calling the Prefix_Overload directly with the same arguments (round-trip
   equivalence).

**Example:**
```java
@Invoker public void bar(int x, int y) {}
@Invoker public void bar(int x, int y, int z) {}
```
`bar()` → `XStageInvoker.x(int)` → `YStageInvoker.y(int)` → stage that exposes:
- `.invoke()` — calls `bar(10, 20)`, returns `void`
- `.z(int z)` — continues to InvokeStageInvoker whose `.invoke()` calls `bar(10, 20, 30)`

```java
foo.bar().x(10).y(20).invoke()         // calls bar(10, 20)
foo.bar().x(10).y(20).z(30).invoke()   // calls bar(10, 20, 30)
```

---

### Requirement 13: Error — Parameterless Overload Already Exists

**User Story:** As a library user, I want a clear compile-time error when `@Invoker` would generate
a `bar()` overload that conflicts with an existing zero-parameter method, so that I can resolve the
naming clash before it causes a silent runtime issue.

#### Acceptance Criteria

1. WHEN the Processor would inject a Parameterless_Overload named `bar` but a method with that name
   and zero parameters already exists on the class, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` at the `@Invoker` annotation site stating that a parameterless overload
   with that name already exists.
2. WHEN the error in criterion 1 is triggered, THE Processor SHALL NOT inject any Caller_Class or
   Stage_Interfaces for that Overload_Group.
3. THE error message SHALL include the method name so the developer can identify which `@Invoker`
   annotation caused the conflict.

---

### Requirement 14: Error — Same Parameter Name, Different Types Across Overloads

**User Story:** As a library user, I want a clear compile-time error when two overloads in the same
Overload_Group use the same parameter name with different types, so that the processor does not
silently generate an ambiguous or incorrect stage tree.

#### Acceptance Criteria

1. WHEN building the merged stage tree for an Overload_Group, IF two overloads contribute a
   parameter at the same position with the same name but different types, THEN THE Processor SHALL
   emit a `Diagnostic.Kind.ERROR` at the `@Invoker` annotation site of each conflicting overload.
2. WHEN the error in criterion 1 is triggered, THE Processor SHALL NOT generate any Caller_Class or
   Stage_Interfaces for the conflicting Overload_Group.
3. THE error message SHALL identify the conflicting parameter name and list all types involved in
   the conflict so the developer can resolve the ambiguity.
4. WHEN two overloads at the same position have the same name AND the same type, THE Processor SHALL
   treat them as a shared parameter (no error) and continue merging.
5. WHEN two overloads at the same position have different names (regardless of type), THE Processor
   SHALL treat them as a divergence point and generate a Branching_Stage (no error).

---

### Requirement 15: Validate @Constructor Usage

**User Story:** As a library user, I want the compiler to reject invalid `@Constructor` usages with
a clear error message, so that I catch mistakes at compile time rather than at runtime.

#### Acceptance Criteria

1. WHEN `@Constructor` is placed on any element that is not a constructor, THEN THE Processor SHALL
   emit a `Diagnostic.Kind.ERROR` message stating that `@Constructor` may only target constructors,
   referencing the offending element.
2. WHEN `@Constructor` is placed on a constructor with zero parameters, THEN THE Processor SHALL
   emit a `Diagnostic.Kind.ERROR` message stating that staged construction requires at least one
   parameter, referencing the offending element.
3. WHEN `@Constructor` is placed on a private constructor, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` message stating that `@Constructor` requires at least package-private
   visibility, referencing the offending element.
4. WHEN `@Constructor` is placed on a valid constructor (non-private, one or more parameters), THE
   Processor SHALL emit no error diagnostics for that element.

---

### Requirement 16: Inject Constructor Entry Point into Enclosing Class

**User Story:** As a library user, I want a `public static` method named `constructor()` to be
available on my class, so that I can start the staged construction chain with
`MyClass.constructor()` without any separate factory class.

#### Acceptance Criteria

1. WHEN the Processor processes a valid `@Constructor`-annotated constructor on class `Foo`, THE
   Bytecode_Manipulator SHALL inject a Constructor_Entry_Point method `constructor()` into `Foo`'s
   `.class` file in the build output directory.
2. THE Constructor_Entry_Point SHALL always be `public static`, regardless of the visibility of the
   annotated constructor.
3. THE Constructor_Entry_Point SHALL always be named `constructor` (the literal string), never
   derived from the class name or any other source.
4. THE Constructor_Entry_Point SHALL return the first StageConstructor_Interface type
   (`Foo.Constructor.IdStageConstructor` where `id` is the name of the first parameter).
5. WHEN the Processor successfully injects the Constructor_Entry_Point, THE Processor SHALL emit a
   `Diagnostic.Kind.NOTE` message identifying the modified class and the source constructor.

---

### Requirement 17: Generate Constructor Caller Class

**User Story:** As a library user, I want a generated inner class named `Constructor` to accumulate
my staged arguments and invoke `new Foo(...)` at the final step, so that the construction chain is
self-contained and compile-time enforced.

#### Acceptance Criteria

1. THE Bytecode_Manipulator SHALL inject a `public static` inner class named `Constructor` into the
   enclosing class's bytecode.
2. THE Constructor_Caller_Class SHALL implement all StageConstructor_Interfaces for the annotated
   constructor (or Constructor_Overload_Group).
3. THE Constructor_Caller_Class SHALL NOT expose any public mutable fields; all accumulated
   argument fields SHALL be `private final`.
4. THE Constructor_Caller_Class SHALL be annotated with `@javax.annotation.processing.Generated`
   carrying the value `"rawit.processors.RawitAnnotationProcessor"`.
5. WHEN a `Constructor` inner class already exists on the enclosing class (injected by a previous
   build), THE Bytecode_Manipulator SHALL detect the existing class and SHALL NOT inject a
   duplicate.

---

### Requirement 18: Generate StageConstructor Interfaces

**User Story:** As a library user, I want each constructor parameter to correspond to a named stage
interface, so that the compiler guides me through each argument in order with self-documenting
method names.

#### Acceptance Criteria

1. THE Bytecode_Manipulator SHALL generate one StageConstructor_Interface per parameter of the
   annotated constructor, nested inside the Constructor_Caller_Class, named
   `<ParameterName>StageConstructor` in PascalCase (e.g. `IdStageConstructor` for parameter `id`).
2. Each StageConstructor_Interface SHALL declare exactly one method named after the parameter
   (e.g. `id(int id)`).
3. For all StageConstructor_Interfaces except the last, the stage method SHALL return the next
   StageConstructor_Interface in the chain.
4. For the last StageConstructor_Interface, the stage method SHALL return a ConstructStageInvoker
   (not the enclosing class instance directly).
5. WHEN a parameter type is a primitive, THE stage method SHALL use the primitive type directly
   (no auto-boxing to wrapper types).
6. THE Bytecode_Manipulator SHALL annotate each StageConstructor_Interface with
   `@FunctionalInterface`.

---

### Requirement 19: Terminal ConstructStageInvoker and .construct() Method

**User Story:** As a library user, I want to call `.construct()` at the end of the chain to
instantiate the class with all accumulated arguments, so that the staged chain is functionally
equivalent to calling `new Foo(...)` directly.

#### Acceptance Criteria

1. THE Bytecode_Manipulator SHALL generate one ConstructStageInvoker interface per annotated
   constructor (or per distinct terminal sub-chain in a Constructor_Overload_Group), nested inside
   the Constructor_Caller_Class.
2. THE ConstructStageInvoker SHALL declare exactly one zero-argument method named `construct()` that
   returns the enclosing class type (e.g. `Foo construct()`).
3. THE `construct()` implementation in the Constructor_Caller_Class SHALL invoke
   `new EnclosingClass(arg1, arg2, ...)` with all accumulated arguments and return the new instance.
4. FOR ALL valid `@Constructor`-annotated constructors, completing the full chain and calling
   `.construct()` SHALL produce an instance equal to calling `new Foo(...)` directly with the same
   arguments (round-trip equivalence).
5. THE Bytecode_Manipulator SHALL propagate any checked exceptions declared on the annotated
   constructor through every StageConstructor_Interface's stage method signature and through the
   `construct()` method of the ConstructStageInvoker.

---

### Requirement 20: Overload Merging for Multiple @Constructor-Annotated Constructors

**User Story:** As a library user, I want multiple `@Constructor`-annotated constructors in the
same class to share a single `constructor()` entry point and diverge into separate branches only
where their signatures differ, so that the generated API is minimal and non-redundant.

#### Acceptance Criteria

1. WHEN a Constructor_Overload_Group contains two or more annotated constructors, THE Processor
   SHALL compute the Shared_Prefix across all constructors in the group.
2. THE Bytecode_Manipulator SHALL generate a single Constructor_Entry_Point `constructor()` for the
   Constructor_Overload_Group (not one per constructor).
3. WHILE parameters at a given position share the same name and type across all constructors in the
   group, THE Bytecode_Manipulator SHALL generate a single StageConstructor_Interface for that
   position shared by all constructors.
4. WHEN constructors diverge at a given position (at least two constructors differ in parameter
   name or type at that position), THE Bytecode_Manipulator SHALL generate a branching
   StageConstructor_Interface at that position that exposes one stage method per distinct parameter
   variant among the diverging constructors.
5. WHEN a Constructor_Overload_Group contains a Prefix_Overload (one constructor's parameter list
   is a strict prefix of another's), the stage reached after the last parameter of the shorter
   constructor SHALL expose both a ConstructStageInvoker (via `.construct()`) and the stage
   method(s) for the next parameter of the longer constructor(s).
6. Calling `.construct()` at the prefix stage SHALL invoke the shorter constructor with all
   accumulated arguments and return the new instance.
7. FOR ALL valid Constructor_Overload_Groups, calling `.construct()` at the appropriate stage SHALL
   produce an instance equal to calling the corresponding constructor directly with the same
   arguments (round-trip equivalence).

---

### Requirement 21: Error — constructor() Method Already Exists

**User Story:** As a library user, I want a clear compile-time error when `@Constructor` would
generate a `constructor()` method that conflicts with an existing method of the same name, so that
I can resolve the naming clash before it causes a silent runtime issue.

#### Acceptance Criteria

1. WHEN the Processor would inject a Constructor_Entry_Point named `constructor` but a method with
   that name already exists on the enclosing class, THEN THE Processor SHALL emit a
   `Diagnostic.Kind.ERROR` at the `@Constructor` annotation site stating that a `constructor()`
   method already exists on the class.
2. WHEN the error in criterion 1 is triggered, THE Processor SHALL NOT inject any
   Constructor_Caller_Class or StageConstructor_Interfaces for that Constructor_Overload_Group.
3. THE error message SHALL include the enclosing class name so the developer can identify which
   `@Constructor` annotation caused the conflict.

---

### Requirement 22: Error — Same Parameter Name, Different Types Across Constructor Overloads

**User Story:** As a library user, I want a clear compile-time error when two constructors in the
same Constructor_Overload_Group use the same parameter name with different types, so that the
processor does not silently generate an ambiguous or incorrect stage tree.

#### Acceptance Criteria

1. WHEN building the merged stage tree for a Constructor_Overload_Group, IF two constructors
   contribute a parameter at the same position with the same name but different types, THEN THE
   Processor SHALL emit a `Diagnostic.Kind.ERROR` at the `@Constructor` annotation site of each
   conflicting constructor.
2. WHEN the error in criterion 1 is triggered, THE Processor SHALL NOT generate any
   Constructor_Caller_Class or StageConstructor_Interfaces for the conflicting
   Constructor_Overload_Group.
3. THE error message SHALL identify the conflicting parameter name and list all types involved in
   the conflict so the developer can resolve the ambiguity.
4. WHEN two constructors at the same position have the same name AND the same type, THE Processor
   SHALL treat them as a shared parameter (no error) and continue merging.
5. WHEN two constructors at the same position have different names (regardless of type), THE
   Processor SHALL treat them as a divergence point and generate a branching
   StageConstructor_Interface (no error).

---


## Introduction

Rawit is currently compiled and tested against Java 21. This feature lowers the minimum supported
Java version to Java 17, so that projects still on Java 17 LTS can use Rawit as an annotation
processor without upgrading their JDK.

The change has three dimensions:

1. **Build configuration** — lower the Maven `source`/`target` (or `release`) level from 21 to 17
   so the processor jar is loadable on a Java 17 JVM.
2. **Source compatibility** — replace Java 21-only language features (pattern matching in `switch`
   expressions, guarded patterns) with Java 17-compatible equivalents (`instanceof` pattern
   variables introduced in Java 16 are fine; `switch` pattern matching is not).
3. **Documentation** — update the README to reflect Java 17 as the new minimum requirement.

The processor must continue to work correctly when used by a project compiled at any source level
from Java 17 through the latest supported version.

## Glossary

- **Processor**: The `RawitAnnotationProcessor` and all supporting classes in the `rawit.processors`
  package tree that together form the annotation processor jar.
- **Host_JVM**: The Java virtual machine that runs `javac` (and therefore the Processor) during a
  user's build. This is the JVM whose version determines whether the Processor jar can be loaded.
- **User_Project**: A Maven or Gradle project that declares Rawit as an annotation processor
  dependency and compiles its own sources at some Java source level (17–latest).
- **Bytecode_Version**: The `major_version` field in a `.class` file header. Java 17 class files
  have major version 61; Java 21 class files have major version 65.
- **Release_Flag**: The Maven Compiler Plugin `<release>` (or `<source>`/`<target>`) configuration
  that controls the bytecode version emitted for the Processor's own `.class` files.
- **Pattern_Switch**: A Java 21 language feature (`switch (x) { case Foo f -> ... }`) that is not
  available under `--release 17`.
- **ASM**: The bytecode manipulation library (OW2 ASM) used by `BytecodeInjector` to read and
  rewrite user `.class` files.

---

## Requirements

### Requirement 1: Processor Jar Loadable on Java 17

**User Story:** As a developer on a Java 17 project, I want to add Rawit as an annotation processor
dependency and have it work without upgrading my JDK, so that I can adopt staged invocation without
a Java version migration.

#### Acceptance Criteria

1. THE Processor SHALL be compiled with `--release 17` (or equivalent `<source>17</source>` /
   `<target>17</target>`) so that all `.class` files in the Processor jar have a Bytecode_Version
   of at most 61 (Java 17).
2. WHEN the Processor jar is loaded by a Host_JVM running Java 17, THE Host_JVM SHALL load the
   Processor without throwing `UnsupportedClassVersionError`.
3. THE Maven `pom.xml` `java.version` property SHALL be set to `17`.

---

### Requirement 2: No Java 21-Only Language Features in Processor Source

**User Story:** As a contributor, I want the Processor source code to compile cleanly under
`--release 17`, so that the Java 17 compatibility guarantee is enforced at build time and does not
silently regress.

#### Acceptance Criteria

1. THE Processor source code SHALL NOT use Pattern_Switch (`switch` expressions or statements with
   type-pattern cases), which require `--release 21`.
2. WHEN the Processor source is compiled with `--release 17`, THE Maven Compiler Plugin SHALL
   report zero errors and zero warnings related to unsupported language features.
3. THE Processor MAY use `instanceof` pattern variables (e.g. `if (x instanceof Foo f)`) because
   that feature is available since Java 16 and is within the `--release 17` language level.
4. THE Processor MAY use `sealed` classes and interfaces if they are already present, because
   sealed types are available since Java 17.

---

### Requirement 3: Processor Correctly Handles Java 17 User Class Files

**User Story:** As a developer on a Java 17 project, I want the bytecode injector to read and
rewrite my Java 17 `.class` files without errors, so that the staged invocation entry points are
injected correctly regardless of my project's Java version.

#### Acceptance Criteria

1. WHEN the Processor's `BytecodeInjector` reads a `.class` file with Bytecode_Version 61
   (Java 17), THE `BytecodeInjector` SHALL parse it without error using ASM.
2. WHEN the Processor injects a parameterless overload into a Java 17 `.class` file, THE
   `BytecodeInjector` SHALL write back a `.class` file that passes ASM's `CheckClassAdapter`
   verification.
3. THE `BytecodeInjector` SHALL preserve the original Bytecode_Version of the user's `.class`
   file; it SHALL NOT upgrade the version to Java 21 or any other version during injection.

---

### Requirement 4: Processor Accepts Java 17 Source in User Projects

**User Story:** As a developer on a Java 17 project, I want the annotation processor to run
without errors when `javac` compiles my Java 17 source files, so that I get the same compile-time
validation and code generation as Java 21 users.

#### Acceptance Criteria

1. WHEN `javac` invokes the Processor with `--release 17` (or `--source 17`) on a User_Project,
   THE Processor SHALL complete annotation processing without emitting any `Diagnostic.Kind.ERROR`
   diagnostics caused by the Java version.
2. THE Processor's `getSupportedSourceVersion()` SHALL return `SourceVersion.latestSupported()` so
   that `javac` does not emit a warning about the processor not supporting the current source
   version.
3. WHEN the Processor generates source files via JavaPoet for a User_Project compiled at Java 17,
   THE generated source files SHALL compile without errors under `--release 17`.

---

### Requirement 5: README Documents Java 17 as Minimum Supported Version

**User Story:** As a developer evaluating Rawit, I want the README to clearly state the minimum
Java version required, so that I know whether Rawit is compatible with my project before adding
the dependency.

#### Acceptance Criteria

1. THE `README.md` SHALL state that Rawit requires Java 17 or later (replacing the current
   "Java 21" statement).
2. THE `README.md` "Building from Source" section SHALL state that building from source requires
   Java 17 and Maven 3.8+.
3. THE `README.md` SHALL NOT contain any reference to Java 21 as a minimum requirement.

---

### Requirement 6: Existing Tests Pass Under Java 17 Compilation Target

**User Story:** As a contributor, I want all existing unit and property-based tests to continue
passing after the Java 17 compatibility change, so that no existing behaviour is regressed.

#### Acceptance Criteria

1. WHEN `mvn test` is run with the updated `pom.xml` targeting Java 17, ALL existing tests SHALL
   pass without modification.
2. THE integration tests in `RawitAnnotationProcessorIntegrationTest` SHALL continue to compile
   and run correctly, exercising the full two-pass compile and bytecode injection pipeline.
3. FOR ALL property-based tests (jqwik), the properties SHALL continue to hold after the source
   compatibility change.
