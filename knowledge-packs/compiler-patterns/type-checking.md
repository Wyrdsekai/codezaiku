# Type Checking Patterns

## When to use
- Implementing a type system for a programming language or typed DSL
- Adding type inference to reduce annotation burden
- Designing error messages that help users fix type errors
- Building incremental type checking for IDE responsiveness

## Pattern

### Type Inference (Hindley-Milner style)
- **Constraint generation**: walk the AST and emit equality constraints between type variables and concrete types
- **Constraint solving (unification)**: repeatedly substitute equal types until no more substitutions apply
- Algorithm W: top-down, substitution-passing; Algorithm J: uses mutable union-find for efficiency
- Let-polymorphism: generalize type variables at `let` bindings, instantiate fresh variables at each use site
- For languages beyond HM (subtyping, row polymorphism, dependent types), extend with constraint inequalities

### Unification
- Maintain a union-find (disjoint set) structure mapping type variables to their resolved types
- `unify(T1, T2)`: if both are variables, union them; if one is a variable, bind it to the other; if both are constructors, unify recursively
- **Occurs check**: before binding `a = List<a>`, verify that `a` does not appear in `List<a>` — prevents infinite types
- Path compression in union-find keeps unification nearly O(1) amortized

### Bidirectional Type Checking
- Two modes: **check** (push an expected type down) and **infer** (synthesize a type up)
- Literals and variables infer their type; function arguments check against the parameter type
- Reduces annotation requirements: only top-level declarations need explicit types
- Subsumption rule: if `inferred <: expected`, checking succeeds (for languages with subtyping)

### Subtyping
- `A <: B` means any value of type `A` can be used where `B` is expected
- Covariance (outputs), contravariance (inputs), invariance (mutable containers)
- Function subtyping: `(A2 -> B1) <: (A1 -> B2)` when `A1 <: A2` and `B1 <: B2`
- Structural subtyping (duck typing): check field-by-field; nominal subtyping: check declared relationships

### Error Messages
- Point to the exact source span where the mismatch occurs
- Show both the expected type and the actual type, with the *reason* the expected type was expected
- For unification failures, trace back to the origin of each constraint: "expected `Int` because the function's return type is `Int` (line 5)"
- Suggest fixes: "did you mean to call `.toString()`?" or "add a type annotation here"

### Incremental Type Checking
- Cache type information per function/module; re-check only changed functions and their dependents
- Track dependencies: if function `f` calls function `g`, a change to `g`'s signature invalidates `f`
- Use file-level or function-level granularity — finer granularity is more complex but faster for large files
- Salsa-style incremental computation: memoize queries, invalidate on input change

### Common Type System Features
- **Generics/parametric polymorphism**: type parameters on functions and types, instantiated at call sites
- **Union/intersection types**: `A | B` (value is one of), `A & B` (value satisfies both)
- **Nullable types**: `T?` or `Optional<T>` — track nullability in the type system, not at runtime
- **Algebraic data types**: sum types (enums with data) + product types (structs/records)
- **Type aliases**: transparency matters — is the alias a new type (nominal) or a shorthand (structural)?

## Gotchas / Anti-patterns
- **Missing occurs check**: allows `a = List<a>`, leading to infinite loops during type expansion
- **Global type inference**: inferring types across module boundaries makes compilation non-modular and error messages non-local
- **Opaque unification errors**: "Cannot unify T1 with T2" — useless without showing where T1 and T2 came from
- **Ignoring variance**: treating generic containers as covariant when they are mutable causes runtime type holes
- **Exponential blowup**: some type inference algorithms (e.g., full ML with first-class polymorphism) can be exponential — bound the depth
- **Leaking internal type variables**: showing users `?T42` instead of a meaningful description

## References
- Pierce, B. "Types and Programming Languages" (TAPL), MIT Press, 2002
- Dunfield & Krishnaswami, "Complete and Easy Bidirectional Typechecking for Higher-Rank Polymorphism" (2013)
- Hindley-Milner inference tutorial: https://eli.thegreenplace.net/2018/type-inference/
- Salsa incremental computation: https://salsa-rs.github.io/salsa/
- rust-analyzer type inference internals: https://github.com/rust-lang/rust-analyzer/tree/master/crates/hir-ty
