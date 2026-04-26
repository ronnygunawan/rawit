# Implementation Plan: IDE Integration (Instant IDE Reflection)

## Overview

This document tracks the implementation tasks for the IDE integration feature that enables
Lombok-like source-visible entry-point methods directly on the original annotated class (not
on a separate generated class).

## Tasks

- [x] 1. Implement `JavacAstInjector` (new class)
  - [x] 1.1 Add `JavacAstInjector.tryCreate(ProcessingEnvironment)` factory
    - Uses `JavacTask.instance(env)` to get javac `Context` without coupling to internal types
    - Loads `TreeMaker`, `Names`, `JCClassDecl`, `JCMethodDecl`, `List` via `Class.forName`
    - Returns `null` silently on non-javac or inaccessible API
    - _Requirements: 1.1, 1.3_
  - [x] 1.2 Add `JavacAstInjector.inject(EntryPoint)` injection method
    - Builds `JCMethodDecl` via `TreeMaker.MethodDef(...)` with reflection
    - Appends to `JCClassDecl.defs`
    - _Requirements: 1.1, 1.2, 5.1_
  - [x] 1.3 Add `methodExists()` idempotency guard
    - Walks `JCClassDecl.defs` list to check for existing method by name
    - _Requirements: 1.2_
  - [x] 1.4 Ensure silent fallback on any exception
    - _Requirements: 1.3_

- [x] 2. Expose public static helpers from `BytecodeInjector`
  - [x] 2.1 Add `public static resolveEntryPointName(MergeTree)` to outer class
    - Moved from private inner `resolveOverloadName` — single source of truth
    - _Requirements: 2.2, 4.1, 4.2, 4.3_
  - [x] 2.2 Add `public static resolveCallerClassBinaryName(MergeTree)` to outer class
    - _Requirements: 1.1_
  - [x] 2.3 Add `public static isInstanceEntryPoint(MergeTree)` to outer class
    - _Requirements: 1.1_
  - [x] 2.4 Move `packagePrefix` → `resolvePackagePrefix`, `toPascalCase` to outer class statics
    - Inner class delegates to outer statics (DRY)

- [x] 3. Wire `JavacAstInjector` into `RawitAnnotationProcessor`
  - [x] 3.1 Add `JavacAstInjector astInjector` field
  - [x] 3.2 Initialize in `init()` via `JavacAstInjector.tryCreate(processingEnv)`
    - Done alongside `TaskListener` registration — same javac guard
    - _Requirements: 1.1, 1.3_
  - [x] 3.3 Add Stage 4b in `process()` — loop over `allTrees` and call `astInjector.inject()`
    - Resolves `TypeElement` for enclosing class via `getElementUtils().getTypeElement()`
    - Builds `EntryPoint` record from tree metadata
    - _Requirements: 1.1, 2.1_

- [x] 4. Add integration tests in `RawitAnnotationProcessorIntegrationTest`
  - [x] 4.1 `astInjection_constructorAnnotation_entryPointOnOriginalClass`
    - Verifies `constructor()` is on `AstCtorFoo` (not on a separate generated class)
    - Full chain: `AstCtorFoo.constructor().x(3).y(7).construct()`
    - _Requirements: 1.1, 2.1, 3.1_
  - [x] 4.2 `astInjection_instanceInvoker_entryPointOnOriginalClass`
    - Verifies instance `multiply()` is on `AstInvFoo`
    - Full chain: `new AstInvFoo().multiply().x(3).y(4).invoke()`
    - _Requirements: 1.1, 2.1, 3.1_
  - [x] 4.3 `astInjection_staticInvoker_entryPointOnOriginalClass`
    - Verifies static `greet()` is on `AstStaticFoo`
    - Full chain: `AstStaticFoo.greet().name("World").greeting("Hello").invoke()`
    - _Requirements: 1.1, 2.1, 3.1_

- [x] 5. Update spec documents
  - [x] 5.1 `.kiro/specs/ide-integration/requirements.md` — rewritten for Lombok-like approach
  - [x] 5.2 `.kiro/specs/ide-integration/design.md` — rewritten for AST injection approach
  - [x] 5.3 `.kiro/specs/ide-integration/tasks.md` (this file)

- [x] 6. Update `README.md` to document IDE integration

## Notes

- The Lombok-like approach puts entry-points on the original class — no generated-class reference
  needed in user code.
- Bytecode injection (`BytecodeInjector`) is unchanged; the `foo.bar()` / `Foo.constructor()`
  entry points on the original class continue to work on the javac path.
- ECJ/VS Code Java still lacks full support (needs a JDT plugin); the AST injection provides
  immediate value for IntelliJ IDEA and other javac-based tools.
