# Requirements Document

## Introduction

This feature extends the Rawit annotation processor to support placing `@Constructor` directly on a Java record type declaration. Instead of annotating a constructor inside the record, the developer annotates the record type itself. The processor derives the canonical constructor parameters from the record's component declarations and generates the same staged construction API that `@Constructor`-on-constructor provides for regular classes. The existing behavior of `@Constructor` on explicit constructors in regular classes is preserved (backward compatible).

## Glossary

- **Processor**: The `RawitAnnotationProcessor` that processes `@Invoker` and `@Constructor` annotations
- **Validator**: The `ElementValidator` that checks annotated elements against structural rules
- **Record_Type**: A `TypeElement` whose `ElementKind` is `RECORD`
- **Record_Component**: A component declared in the record header (e.g., `int x` in `record Point(int x, int y)`)
- **Canonical_Constructor**: The compiler-generated constructor of a record whose parameter list matches the record components exactly
- **Injector**: The `BytecodeInjector` that injects parameterless overload methods into `.class` files using ASM
- **Caller_Class**: The generated top-level class (e.g., `Constructor`) containing stage interfaces and the terminal `construct()` method
- **Constructor_Annotation**: The `@Constructor` annotation defined in `rawit.Constructor`

## Requirements

### Requirement 1: Expand @Constructor Target to Include TYPE

**User Story:** As a developer, I want to place `@Constructor` on a record type declaration, so that I do not need to write an explicit constructor inside the record.

#### Acceptance Criteria

1. THE Constructor_Annotation SHALL declare `@Target({ElementType.CONSTRUCTOR, ElementType.TYPE})` to allow placement on both constructors and type declarations
2. WHEN `@Constructor` is placed on a regular class constructor, THE Processor SHALL continue to process the element using the existing constructor-annotation code path (backward compatibility)

#### Examples

Primary syntax — annotation on the record type:

```java
@Constructor
public record Point(int x, int y) { }

// Usage:
Point p = Point.constructor().x(1).y(2).construct();
```

Backward-compatible syntax — annotation on a regular class constructor:

```java
public class Pair {
    private final int a;
    private final int b;

    @Constructor
    public Pair(int a, int b) {
        this.a = a;
        this.b = b;
    }
}

// Usage:
Pair p = Pair.constructor().a(1).b(2).construct();
```

### Requirement 2: Validate @Constructor on Record Types

**User Story:** As a developer, I want clear compile-time errors when I misuse `@Constructor` on a type, so that I understand what went wrong.

#### Acceptance Criteria

1. WHEN `@Constructor` is placed on a Record_Type with at least one Record_Component, THE Validator SHALL accept the element as valid
2. WHEN `@Constructor` is placed on a Record_Type with zero Record_Components, THE Validator SHALL emit an error stating that staged construction requires at least one record component
3. WHEN `@Constructor` is placed on a type that is not a Record_Type (class, interface, or enum), THE Validator SHALL emit an error stating that `@Constructor` on a type is only supported for records
4. WHEN `@Constructor` is placed on a Record_Type that already declares a zero-parameter static method named `constructor`, THE Validator SHALL emit an error stating that a parameterless overload named 'constructor' already exists

#### Examples

Valid — record with components:

```java
@Constructor
public record Point(int x, int y) { }
```

Invalid — record with zero components:

```java
@Constructor
public record Empty() { }
// Error: staged construction requires at least one record component
```

Invalid — annotation on a regular class (not a record):

```java
@Constructor
public class NotARecord {
    private final int x;
    public NotARecord(int x) { this.x = x; }
}
// Error: @Constructor on a type is only supported for records
```

Invalid — annotation on an interface:

```java
@Constructor
public interface NotARecord { }
// Error: @Constructor on a type is only supported for records
```

Invalid — conflicting zero-param method:

```java
@Constructor
public record Conflict(int x) {
    public static Conflict constructor() {
        return new Conflict(0);
    }
}
// Error: a parameterless overload named 'constructor' already exists
```


### Requirement 3: Derive Constructor Parameters from Record Components

**User Story:** As a developer, I want the processor to automatically derive constructor parameters from the record header, so that I do not need to write an explicit constructor.

#### Acceptance Criteria

1. WHEN the Processor encounters a `@Constructor`-annotated Record_Type, THE Processor SHALL locate the Canonical_Constructor of the record
2. WHEN the Processor locates the Canonical_Constructor, THE Processor SHALL extract parameter names and types from the record's Record_Components in declaration order
3. WHEN the Processor builds an `AnnotatedMethod` for a `@Constructor`-annotated Record_Type, THE Processor SHALL set `isConstructor` to `true`, `isConstructorAnnotation` to `true`, and `methodName` to `<init>`
4. WHEN the Processor builds an `AnnotatedMethod` for a `@Constructor`-annotated Record_Type, THE Processor SHALL set `enclosingClassName` to the binary name of the Record_Type using the same slash-separated format as for regular classes

#### Examples

Given this annotated record:

```java
package com.example;

@Constructor
public record Point(int x, int y) { }
```

The Processor derives parameters from the record components `(int x, int y)` and builds an `AnnotatedMethod` equivalent to:

```java
new AnnotatedMethod(
    "com/example/Point",  // enclosingClassName
    "<init>",             // methodName
    false,                // isStatic
    true,                 // isConstructor
    true,                 // isConstructorAnnotation
    List.of(new Parameter("x", "I"), new Parameter("y", "I")),
    "V",                  // returnTypeDescriptor
    List.of(),            // checkedExceptions
    0x0001                // ACC_PUBLIC
);
```

### Requirement 4: Handle TypeElement in Processor Pipeline

**User Story:** As a developer, I want the processor to handle `@Constructor` on a `TypeElement` (not just `ExecutableElement`), so that record type annotations are processed correctly.

#### Acceptance Criteria

1. WHEN the Processor encounters an element annotated with `@Constructor` that is a `TypeElement` with kind `RECORD`, THE Processor SHALL process the element through the record-type code path instead of the constructor code path
2. WHEN the Processor encounters an element annotated with `@Constructor` that is an `ExecutableElement`, THE Processor SHALL continue to process the element through the existing constructor code path
3. WHEN the Processor processes a `@Constructor`-annotated Record_Type, THE Processor SHALL produce an `AnnotatedMethod` model that integrates with the existing overload grouping, merge tree building, and code generation pipeline

### Requirement 5: Generate Staged Construction API for Records

**User Story:** As a developer, I want the generated staged API for records to follow the same pattern as regular classes, so that the usage is consistent.

#### Acceptance Criteria

1. WHEN a Record_Type is annotated with `@Constructor`, THE Processor SHALL generate a `Constructor` Caller_Class in the same package as the record
2. WHEN a Record_Type is annotated with `@Constructor`, THE Processor SHALL generate stage interfaces with setter methods matching each Record_Component name in declaration order
3. WHEN a Record_Type is annotated with `@Constructor`, THE Processor SHALL generate a terminal interface with a `construct()` method that returns the Record_Type

#### Examples

Given this annotated record:

```java
package com.example;

@Constructor
public record Point(int x, int y) { }
```

The Processor generates a Caller_Class with stage interfaces, and the resulting staged API usage is:

```java
Point p = Point.constructor()  // returns stage 1 interface
    .x(10)                     // returns stage 2 interface
    .y(20)                     // returns terminal interface
    .construct();              // calls new Point(10, 20)
```

The generated Caller_Class structure (in `com.example.Constructor`):

```java
package com.example;

public final class Constructor {
    public interface Stage1_x { Stage2_y x(int x); }
    public interface Stage2_y { Terminal y(int y); }
    public interface Terminal  { Point construct(); }

    // Implementation wiring omitted for brevity
}
```

### Requirement 6: Inject Static Entry Point into Record Class Files

**User Story:** As a developer, I want a `public static constructor()` method injected into my record's `.class` file, so that I can use the staged API seamlessly.

#### Acceptance Criteria

1. WHEN the Injector processes a Record_Type with a `@Constructor` annotation, THE Injector SHALL inject a `public static constructor()` method into the record's `.class` file
2. WHEN the Injector injects a `constructor()` method into a record's `.class` file, THE Injector SHALL generate bytecode that instantiates and returns the first stage interface of the Caller_Class
3. IF the record's `.class` file already contains a zero-parameter method named `constructor`, THEN THE Injector SHALL skip injection for that overload group

#### Examples

After injection, the record's `.class` file behaves as if the following method were declared in source:

```java
@Constructor
public record Point(int x, int y) {
    // Injected by BytecodeInjector — not present in source
    public static Constructor.Stage1_x constructor() {
        return new Constructor(/* wiring */);
    }
}
```

This enables the seamless call chain:

```java
Point p = Point.constructor().x(1).y(2).construct();
```

### Requirement 7: Support Records with Various Component Types

**User Story:** As a developer, I want `@Constructor` on records to work with all valid record component types, so that the feature is not limited to primitive types.

#### Acceptance Criteria

1. WHEN a `@Constructor`-annotated Record_Type has components with primitive types, THE Processor SHALL generate correct JVM type descriptors for each component
2. WHEN a `@Constructor`-annotated Record_Type has components with reference types (including generics), THE Processor SHALL generate correct JVM type descriptors using erased types
3. WHEN a `@Constructor`-annotated Record_Type has components with array types, THE Processor SHALL generate correct JVM array type descriptors

#### Examples

Record with mixed component types:

```java
@Constructor
public record Config(String name, int port, boolean secure, String[] aliases) { }

// Usage:
Config c = Config.constructor()
    .name("server")
    .port(8080)
    .secure(true)
    .aliases(new String[]{"s1", "s2"})
    .construct();
```

Record with generic component types (erased to Object):

```java
@Constructor
public record Wrapper<T>(T value, String label) { }

// Usage:
Wrapper<Integer> w = Wrapper.constructor()
    .value(42)
    .label("answer")
    .construct();
```

### Requirement 8: Preserve Backward Compatibility

**User Story:** As a developer, I want existing `@Constructor` usage on regular class constructors to continue working unchanged, so that upgrading does not break my code.

#### Acceptance Criteria

1. WHEN `@Constructor` is placed on a constructor of a regular class (not a record), THE Validator SHALL apply the existing constructor validation rules without change
2. WHEN `@Constructor` is placed on a constructor of a regular class, THE Processor SHALL build the `AnnotatedMethod` model using the existing `ExecutableElement`-based code path
3. WHEN both a `@Constructor`-annotated Record_Type and a `@Constructor`-annotated regular class constructor exist in the same compilation round, THE Processor SHALL process both independently without interference
