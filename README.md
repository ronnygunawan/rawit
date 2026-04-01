# 🌶️ Rawit

> Compile-time staged invocation for Java — like currying, but with types.

Rawit is a Java 21 annotation processor that transforms your methods and constructors into
fluent, type-safe call chains at compile time. No runtime overhead, no reflection magic —
just clean, compiler-enforced APIs generated straight into your `.class` files.

```java
// Before
foo.bar(10, 20);

// After @Curry
foo.bar().x(10).y(20).invoke();
```

---

## ✨ Features

- **`@Curry`** — turns any non-private method (instance or static) into a staged call chain ending with `.invoke()`
- **`@Constructor`** — injects a `public static constructor()` entry point for staged object construction ending with `.construct()`
- Works on **instance methods** and **static methods** (`@Curry`), and **constructors** (`@Constructor`)
- Supports **overload groups** — multiple overloads with the same name share a single entry point and branch only where their signatures diverge
- **Zero runtime dependency** — the processor runs at compile time only
- Operates like **Lombok** — generates inner classes and interfaces via JavaPoet, and injects the parameterless entry-point overload directly into the original `.class` file using ASM
- Full **compile-time error reporting** — invalid usages are caught at `javac` time with clear messages

---

## 🚀 Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>rg.projectrawit</groupId>
    <artifactId>rg-projectrawit</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. Annotate your methods

```java
import rawit.Curry;
import rawit.Constructor;

public class Foo {

    @Curry
    public int add(int x, int y) {
        return x + y;
    }

    @Curry
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
```

### 3. Use the generated API

```java
Foo foo = new Foo();

// Instance method
int result = foo.add().x(3).y(4).invoke(); // == 7

// Static method
String msg = Foo.greet().name("Alice").greeting("Hello").invoke(); // == "Hello, Alice!"

// Constructor
Point p = Point.constructor().x(1).y(2).construct(); // == new Point(1, 2)
```

---

## 📖 Annotations

### `@Curry`

Place on any **non-private** method (instance or static) with **at least one parameter**.

```java
@Curry
public void sendEmail(String to, String subject, String body) { ... }
```

The processor injects:
- A parameterless overload `sendEmail()` on the enclosing class
- An inner class `SendEmail` implementing all stage interfaces
- Stage interfaces `ToStageCaller`, `SubjectStageCaller`, `BodyStageCaller`, and `InvokeStageCaller`

```java
mailer.sendEmail()
      .to("alice@example.com")
      .subject("Hello")
      .body("World")
      .invoke();
```

### `@Constructor`

Place on any **non-private** constructor with **at least one parameter**.

```java
@Constructor
public User(int id, String name) { ... }
```

The processor injects:
- A `public static constructor()` method on the enclosing class
- An inner class `Constructor` implementing all stage interfaces
- Stage interfaces `IdStageConstructor`, `NameStageConstructor`, and `ConstructStageCaller`

```java
User user = User.constructor().id(42).name("Alice").construct();
```

---

## 🔀 Overload Groups

When multiple methods share the same name, Rawit merges them into a single entry point and
branches only where their signatures diverge.

```java
@Curry public void log(String message) { ... }
@Curry public void log(String message, int level) { ... }
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
| `@Curry` on a zero-parameter method | `currying requires at least one parameter` |
| `@Curry` on a `private` method | `@Curry requires at least package-private visibility` |
| `@Constructor` on a non-constructor element | `@Constructor may only target constructors` |
| `@Constructor` on a zero-parameter constructor | `staged construction requires at least one parameter` |
| A `bar()` overload already exists | `a parameterless overload named 'bar' already exists` |
| Same parameter name with conflicting types across overloads | conflict error with details |

---

## 🛠️ Building from Source

Requires **Java 21** and **Maven 3.8+**.

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
