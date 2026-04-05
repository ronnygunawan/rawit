# 🌶️ Rawit

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ronnygunawan/rawit)](https://central.sonatype.com/artifact/io.github.ronnygunawan/rawit)

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
- Works on **instance methods** and **static methods** (`@Invoker`), **constructors**, and **record type declarations** (`@Constructor`)
- Supports **overload groups** — multiple overloads with the same name share a single entry point and branch only where their signatures diverge
- **Zero runtime dependency** — the processor runs at compile time only
- Operates like **Lombok** — generates inner classes and interfaces via JavaPoet, and injects the parameterless entry-point overload directly into the original `.class` file using ASM
- Full **compile-time error reporting** — invalid usages are caught at `javac` time with clear messages

---

## 🚀 Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.ronnygunawan</groupId>
    <artifactId>rawit</artifactId>
    <version>VERSION</version>
</dependency>
```

#### Gradle Groovy DSL

```groovy
dependencies {
    annotationProcessor 'io.github.ronnygunawan:rawit:VERSION'
    compileOnly 'io.github.ronnygunawan:rawit:VERSION'
}
```

#### Gradle Kotlin DSL

```kotlin
dependencies {
    annotationProcessor("io.github.ronnygunawan:rawit:VERSION")
    compileOnly("io.github.ronnygunawan:rawit:VERSION")
}
```

> **Gradle two-pass compile:** Rawit injects entry points into existing `.class` files, so the
> declaring class must be compiled *before* annotation processing runs. Configure a three-pass
> compile in your `build.gradle` / `build.gradle.kts`:
>
> **Groovy DSL (`build.gradle`)**
> ```groovy
> // Pass 1: compile without annotation processing
> compileJava {
>     options.compilerArgs += ['-proc:none']
> }
>
> // Pass 2: full compile — generates stage interfaces and compiles them
> task processAnnotations(type: JavaCompile, dependsOn: compileJava) {
>     source = sourceSets.main.java
>     classpath = sourceSets.main.compileClasspath
>     destinationDirectory = sourceSets.main.java.classesDirectory
>     options.annotationProcessorPath = configurations.annotationProcessor
> }
>
> // Pass 3: re-inject bytecode overloads (overwritten by pass 2)
> task reinjectBytecode(type: JavaCompile, dependsOn: processAnnotations) {
>     source = sourceSets.main.java
>     classpath = sourceSets.main.compileClasspath
>     destinationDirectory = sourceSets.main.java.classesDirectory
>     options.compilerArgs += ['-proc:only']
>     options.annotationProcessorPath = configurations.annotationProcessor
> }
>
> classes.dependsOn reinjectBytecode
> ```
>
> **Kotlin DSL (`build.gradle.kts`)**
> ```kotlin
> tasks.compileJava {
>     options.compilerArgs.add("-proc:none")
> }
>
> val processAnnotations by tasks.registering(JavaCompile::class) {
>     dependsOn(tasks.compileJava)
>     source = sourceSets.main.get().java
>     classpath = sourceSets.main.get().compileClasspath
>     destinationDirectory.set(sourceSets.main.get().java.classesDirectory)
>     options.annotationProcessorPath = configurations.annotationProcessor.get()
> }
>
> val reinjectBytecode by tasks.registering(JavaCompile::class) {
>     dependsOn(processAnnotations)
>     source = sourceSets.main.get().java
>     classpath = sourceSets.main.get().compileClasspath
>     destinationDirectory.set(sourceSets.main.get().java.classesDirectory)
>     options.annotationProcessorPath = configurations.annotationProcessor.get()
>     options.compilerArgs = listOf("-proc:only")
> }
>
> tasks.classes {
>     dependsOn(reinjectBytecode)
> }
> ```

### 2. Annotate your methods

> **Maven two-pass compile:** Rawit injects generated entry points into existing `.class` files,
> which means the declaring class must be compiled *before* annotation processing runs. In a
> standard single-pass Maven compile, annotation processing runs before `.class` files are written,
> so injection is skipped silently on the first pass. To enable injection, configure a **three-pass
> compile** in your `pom.xml` (Gradle users: see the Gradle setup above):
>
> ```xml
> <!-- Pass 1: compile sources without annotation processing -->
> <plugin>
>   <groupId>org.apache.maven.plugins</groupId>
>   <artifactId>maven-compiler-plugin</artifactId>
>   <executions>
>     <execution>
>       <id>default-compile</id>
>       <configuration><compilerArgument>-proc:none</compilerArgument></configuration>
>     </execution>
>     <!-- Pass 2: full compile — generates stage interfaces and compiles them -->
>     <execution>
>       <id>process-annotations</id>
>       <phase>process-classes</phase>
>       <goals><goal>compile</goal></goals>
>     </execution>
>     <!-- Pass 3: re-inject bytecode overloads (overwritten by pass 2) -->
>     <execution>
>       <id>reinject-bytecode</id>
>       <phase>process-test-sources</phase>
>       <goals><goal>compile</goal></goals>
>       <configuration><compilerArgument>-proc:only</compilerArgument></configuration>
>     </execution>
>   </executions>
> </plugin>
> ```

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

---

## 🛠️ Building from Source

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
