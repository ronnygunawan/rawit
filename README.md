# 🌶️ Rawit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.projectrawit/rawit)](https://central.sonatype.com/artifact/io.github.projectrawit/rawit)

> Compile-time staged invocation for Java — fluent, type-safe call chains at compile time.

Rawit is a Java 17+ annotation processor that transforms your methods and constructors into
fluent, type-safe call chains at compile time. No runtime overhead, no reflection magic —
just clean, compiler-enforced APIs generated straight into your `.class` files.

```java
// Before
foo.bar(10, 20);

// After @Invoker
foo.bar().x(10).y(20).invoke();
```

---

## ✨ Features

- **`@Invoker`** — turns any non-private method (instance or static) into a staged call chain ending with `.invoke()`
- **`@Constructor`** — injects a `public static constructor()` entry point for staged object construction ending with `.construct()`. Works on explicit constructors and directly on **record types**
- **`@Getter`** — generates public getter methods for annotated fields. Follows the Lombok `is`-prefix convention for primitive `boolean`, supports static fields, and handles field hiding with covariant return types
- **`@TaggedValue`** — meta-annotation for compile-time value type safety. Define your own tag annotations (e.g., `@UserId`, `@FirstName`) and get compiler warnings for unsafe assignments between mismatched or untagged values. Supports strict and lax modes, literal exemptions, and integrates with generated builder chains
- Works on **instance methods** and **static methods** (`@Invoker`), **constructors**, and **record type declarations** (`@Constructor`)
- Supports **overload groups** — multiple overloads with the same name share a single entry point and branch only where their signatures diverge
- **Zero runtime dependency** — the processor runs at compile time only
- Operates like **Lombok** — generates inner classes and interfaces via JavaPoet, and injects the parameterless entry-point overload directly into the original `.class` file using ASM
- Full **compile-time error reporting** — invalid usages are caught at `javac` time with clear messages

---

## 🚀 Quick Start

### 1. Add the dependency

#### Maven

```xml
<dependency>
    <groupId>io.github.projectrawit</groupId>
    <artifactId>rawit</artifactId>
    <version>VERSION</version>
</dependency>
```

#### Gradle Groovy DSL

```groovy
dependencies {
    annotationProcessor 'io.github.projectrawit:rawit:VERSION'
    compileOnly 'io.github.projectrawit:rawit:VERSION'
}
```

#### Gradle Kotlin DSL

```kotlin
dependencies {
    annotationProcessor("io.github.projectrawit:rawit:VERSION")
    compileOnly("io.github.projectrawit:rawit:VERSION")
}
```

That's it! When using `javac`, no multi-pass compiler configuration is needed. Rawit hooks into
javac's post-generate phase via a `TaskListener` and injects bytecode after each `.class` file is
written. On non-`javac` compilers (for example, ECJ), this single-pass injection path is not
guaranteed, so fallback behavior or additional compiler-specific configuration may be required.

### 2. Annotate your code

```java
import rawit.Invoker;
import rawit.Constructor;

public class Foo {

    @Invoker
    public int add(int x, int y) {
        return x + y;
    }

    @Invoker
    public static String greet(String name, String greeting) {
        return greeting + ", " + name + "!";
    }
}

public class Point {

    private final int x;
    private final int y;

    @Constructor
    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

// Records — just annotate the type, no explicit constructor needed
@Constructor
public record Coord(int x, int y) {}
```

### 3. Use the generated API

```java
Foo foo = new Foo();

// Instance method
int result = foo.add().x(3).y(4).invoke(); // == 7

// Static method
String msg = Foo.greet()
                .name("Alice")
                .greeting("Hello")
                .invoke(); // == "Hello, Alice!"

// Constructor
Point p = Point.constructor()
               .x(1)
               .y(2)
               .construct(); // == new Point(1, 2)

// Record constructor — same API, no boilerplate
Coord c = Coord.constructor()
               .x(5)
               .y(10)
               .construct(); // == new Coord(5, 10)
```

---

## 📖 Annotations

### `@Invoker`

Place on any **non-private** method (instance or static) with **at least one parameter**.

```java
@Invoker
public void sendEmail(String to, String subject, String body) { ... }
```

The processor injects:
- A parameterless overload `sendEmail()` on the enclosing class returning the caller type
- A top-level caller class `SendEmail` generated in the same package as the enclosing class
- Nested stage interfaces on `SendEmail`: `ToStageInvoker`, `SubjectStageInvoker`, `BodyStageInvoker`, and `InvokeStageInvoker`

```java
mailer.sendEmail()
      .to("alice@example.com")
      .subject("Hello")
      .body("World")
      .invoke();
```

### `@Constructor`

Place on any **non-private** constructor with **at least one parameter**, or directly on a **record type declaration**.

#### On a constructor

```java
@Constructor
public User(int id, String name) { ... }
```

The processor injects:
- A `public static constructor()` method on the enclosing class
- A generated `Constructor` class in the same package implementing all stage interfaces
- Stage interfaces `IdStageConstructor`, `NameStageConstructor`, and `ConstructStageInvoker`

```java
User user = User.constructor()
                .id(42)
                .name("Alice")
                .construct();
```

#### On a record type

```java
@Constructor
public record Config(String name, int port, boolean secure) {}
```

The processor derives parameters from the record's components automatically — no explicit constructor needed. The generated API is identical:

```java
Config cfg = Config.constructor()
                   .name("server")
                   .port(8080)
                   .secure(true)
                   .construct();
```

Records with zero components are rejected at compile time (`staged construction requires at least one record component`). Placing `@Constructor` on a non-record type (class, interface, enum) is also a compile-time error.

### `@Getter`

Place on any **field** (instance or static, any visibility) to generate a public getter method.

```java
import rawit.Getter;

public class User {

    @Getter private String name;
    @Getter private static int instanceCount;
    @Getter private boolean active;
    @Getter private Boolean verified;
}
```

The processor injects getter methods directly into the `.class` file:

```java
User user = new User();
user.getName();              // String getter
User.getInstanceCount();     // static getter
user.isActive();             // primitive boolean → is-prefix
user.getVerified();          // boxed Boolean → get-prefix
```

#### Primitive `boolean` naming rules

| Field Name | Getter Name | Rule |
|---|---|---|
| `active` | `isActive()` | No `is` prefix → `is` + capitalize |
| `isActive` | `isActive()` | `is` + uppercase letter → use field name as-is |
| `isinTimezone` | `isIsinTimezone()` | `is` + non-uppercase letter → `is` + capitalize |

Boxed `Boolean` and all other types always use the `get` prefix.

#### Field hiding in inheritance

When a subclass hides a superclass field and both are annotated with `@Getter`, the processor generates an overriding getter on the subclass. Covariant return types are supported (e.g., base returns `Number`, derived returns `Integer`). Incompatible return types produce a compile-time error.

```java
public class Base {
    @Getter protected Number value;
}

public class Derived extends Base {
    @Getter protected Integer value; // covariant override — OK
}
```

### `@TaggedValue`

A meta-annotation for lightweight, compile-time value type safety without wrapper types. Annotate your own annotation declarations with `@TaggedValue` to create tag annotations, then apply those tags to fields, parameters, local variables, and record components. The analyzer emits compiler warnings for unsafe assignments between tagged and untagged values.

#### Defining tag annotations

```java
import rawit.TaggedValue;

@TaggedValue(strict = true)   // strict: warns on tagged↔untagged assignments
public @interface UserId { }

@TaggedValue                   // lax (default): only warns on tag mismatches
public @interface FirstName { }

@TaggedValue
public @interface LastName { }
```

#### Using tag annotations

```java
@UserId long taggedId = 42;              // OK — literals are always exempt
long rawId = getUserId();
@UserId long taggedId2 = rawId;          // WARNING: untagged → strict tagged

@FirstName String first = "John";
@LastName String last = first;           // WARNING: tag mismatch (@FirstName → @LastName)

String rawName = "John";
@FirstName String name = rawName;        // OK — lax mode, no warning

@FirstName String name1 = getFirst();
@FirstName String name2 = name1;         // OK — same tag, no warning
```

#### Strict vs. lax mode

| Source | Target | Literal? | Strict? | Warning? |
|---|---|---|---|---|
| Untagged | Untagged | — | — | No |
| Tagged(A) | Tagged(A) | — | — | No (same tag) |
| Tagged(A) | Tagged(B) | — | — | Yes: tag mismatch |
| Untagged | Tagged(A) | Yes | — | No (literal exempt) |
| Untagged | Tagged(A) | No | `true` | Yes |
| Untagged | Tagged(A) | No | `false` | No (lax) |
| Tagged(A) | Untagged | — | `true` | Yes |
| Tagged(A) | Untagged | — | `false` | No (lax) |

#### Integration with `@Constructor` and `@Invoker`

Tag annotations on constructor/method parameters are propagated onto the generated stage method parameters. The analyzer checks tag safety at builder chain call sites:

```java
@Constructor
public record TaggedUser(
    @UserId long userId,
    @FirstName String firstName,
    @LastName String lastName
) { }

@FirstName String name = "John";
TaggedUser user = TaggedUser.constructor()
    .userId(10)            // OK — literal exempt
    .firstName(name)       // OK — @FirstName → @FirstName
    .lastName(name)        // WARNING: tag mismatch (@FirstName → @LastName)
    .construct();
```

All warnings use `Diagnostic.Kind.WARNING` — compilation always succeeds. No runtime overhead; the analyzer operates entirely at compile time.

---

## 🔀 Overload Groups

When multiple methods share the same name, Rawit merges them into a single entry point and
branches only where their signatures diverge.

```java
@Invoker public void log(String message) { ... }
@Invoker public void log(String message, int level) { ... }
```

```java
// Shared entry point, branches at the end
logger.log().message("hello").invoke();              // calls log(String)
logger.log().message("hello").level(2).invoke();     // calls log(String, int)
```

---

## ⚠️ Compile-Time Errors

Rawit catches mistakes early. You'll get a clear `javac` error for:

| Mistake | Error |
|---|---|
| `@Invoker` on a zero-parameter method | `@Invoker requires at least one parameter` |
| `@Invoker` on a `private` method | `@Invoker requires at least package-private visibility` |
| `@Constructor` on a non-constructor element | `@Constructor may only target constructors` |
| `@Constructor` on a zero-parameter constructor | `staged construction requires at least one parameter` |
| `@Constructor` on a non-record type (class, interface, enum) | `@Constructor on a type is only supported for records` |
| `@Constructor` on a record with zero components | `staged construction requires at least one record component` |
| `@Constructor` on a record with existing `constructor()` method | `a parameterless overload named 'constructor' already exists` |
| A `bar()` overload already exists | `a parameterless overload named 'bar' already exists` |
| Same parameter name with conflicting types across overloads | conflict error with details |
| `@Getter` on a `volatile` field | `@Getter is not supported on volatile fields` |
| `@Getter` on a `transient` field | `@Getter is not supported on transient fields` |
| `@Getter` on a field inside an anonymous class | `@Getter is not supported inside anonymous classes` |
| `@Getter` field conflicts with existing same-class method | `getter 'name()' conflicts with existing method in Class` |
| Two `@Getter` fields produce the same getter name | `getter 'name()' conflicts with another @Getter field in Class` |
| `@Getter` field conflicts with inherited method | `getter 'name()' conflicts with inherited method from SuperClass` |
| Incompatible return type in field-hiding override | `getter 'name()' in Derived cannot override getter in Base: incompatible return types` |

---

## 💡 IDE / IntelliSense Setup

Rawit's annotation processor generates source files (e.g. `CalculatorAddInvoker.java`) and
injects methods directly into `.class` files at compile time. Getting full IntelliSense
(auto-complete for both the generated builder chain **and** the entry-point method on the
original class) requires a few extra steps depending on your IDE and build tool.

### Entry-point methods

Every generated Invoker/Constructor class ships a static **`of()`** factory method that mirrors
the bytecode-injected entry point on the original class.  Using `of()` is the recommended way
to start a chain when you need IntelliSense before the first full build:

```java
// Bytecode-injected entry point (available after a full build):
int result = calc.add().x(3).y(4).invoke();

// Source-level factory — always visible to IntelliSense, same behaviour:
int result = CalculatorAddInvoker.of(calc).x(3).y(4).invoke();

// Static @Invoker (no instance needed):
String msg = FooGreetInvoker.of().name("Alice").greeting("Hello").invoke();

// @Constructor:
Point p = PointConstructor.of().x(1).y(2).construct();
```

The generated `of()` method is in the same package as the generated class
(e.g. `com.example.generated.CalculatorAddInvoker`).

### IntelliJ IDEA

1. Open **Settings → Build, Execution, Deployment → Compiler → Annotation Processors**.
2. Enable **"Enable annotation processing"**.
3. Set **"Processor path"** (or leave at default to use the project classpath).
4. **Rebuild** the project once — IntelliJ will discover the generated sources and add them to
   the source path automatically.

After the first build the generated `*Invoker` / `*Constructor` classes (with their `of()`
factory methods) are visible to IntelliSense.  If you also want the bytecode-injected entry
points (`calc.add()`, `Point.constructor()`, etc.) to appear in completion, add the following
JVM argument to **Settings → Build, Execution, Deployment → Compiler → Java Compiler →
"Additional command line parameters"**:

```
-J--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED -J--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED -J--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED -J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -J--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
```

### Maven

Add `annotationProcessorPaths` so the processor is discovered by the Maven compiler plugin:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.projectrawit</groupId>
                <artifactId>rawit</artifactId>
                <version>VERSION</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

To also enable the AST-level injection of bytecode entry points (so `calc.add()` etc. appear
in IntelliSense), pass the required `--add-opens` flags to the javac JVM via the `-J` prefix:

```xml
<configuration>
    <compilerArgs>
        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED</arg>
        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED</arg>
        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED</arg>
        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
    </compilerArgs>
</configuration>
```

### Gradle

```groovy
dependencies {
    annotationProcessor 'io.github.projectrawit:rawit:VERSION'
    compileOnly 'io.github.projectrawit:rawit:VERSION'
}

// Optional: enable AST-level entry-point injection
tasks.withType(JavaCompile).configureEach {
    options.forkOptions.jvmArgs += [
        '--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED',
        '--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED',
        '--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED',
        '--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED',
        '--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED'
    ]
    options.fork = true
}
```

> **Note:** The `--add-opens` flags enable Rawit to inject the entry-point method stubs
> directly into the original class's javac AST during annotation processing so that IDEs
> can resolve `calc.add()` from source analysis alone. Without these flags, Rawit falls
> back gracefully — the `of()` factory method on the generated class always works, and
> the bytecode-injected entry points remain fully functional at runtime.

---



Requires **Java 17** and **Maven 3.8+**.

```bash
git clone https://github.com/ronnygunawan/rawit.git
cd rawit
mvn compile
```

Run tests:

```bash
mvn test
```

---

## 🧪 Testing

Rawit uses a dual testing approach:

- **Unit tests** (JUnit 5) — verify specific validation rules, merge tree construction, and code generation output
- **Property-based tests** (jqwik) — verify universal correctness properties across many generated inputs, including shared-prefix computation, branching, checked exception propagation, and `@FunctionalInterface` annotation

---

## 🤝 Contributing

Contributions are welcome! Here's how to get started:

1. Fork the repo and create a branch from `develop`
2. Make your changes with tests
3. Open a pull request against `develop`

Please keep PRs focused — one feature or fix per PR makes review much easier.

---

## 📄 License

MIT — see [LICENSE](LICENSE) for details.

---

<p align="center">Made with ❤️ and a little spice 🌶️</p>
