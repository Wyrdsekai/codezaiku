---
id: javascript-eslint-idioms
keys: javascript, js, typescript, ts, node, eslint, .js, .ts
priority: 92
---

## JS/TS idioms harvested from ESLint — write it the idiomatic way

Each item is an ESLint rule (curated "you wrote X, do Y") distilled to its evergreen idiom.
Language-level and version-independent. Prefer the RIGHT column.

### Equality & types
- **eqeqeq** — `==` does type coercion; use `===` / `!==`. (`x == null` is the ONE accepted exception:
  it matches both `null` and `undefined`.)
- **no NaN compare** — `x === NaN` is always false; use `Number.isNaN(x)`.
- **typeof** — `typeof x === "undefined"` is the safe undefined check.

### Declarations
- **no-var** — never `var` (function-scoped, hoisted). Use `const` by default, `let` only when reassigned.
- **prefer-const** — if a `let` is never reassigned, make it `const`.
- **no-unused-vars** — remove unused bindings/imports (CI error even if it runs).
- **no-shadow / no-param-reassign** — don't reuse an outer name in an inner scope; don't mutate parameters.

### Strings / objects / functions
- **prefer-template** — `"Hello " + name + "!"` → `` `Hello ${name}!` ``.
- **destructuring** — `const x = obj.x; const y = obj.y` → `const { x, y } = obj`.
- **arrow callbacks** — `arr.map(function (x) { return x*2 })` → `arr.map(x => x * 2)`.
- **object shorthand** — `{ x: x, run: function(){} }` → `{ x, run() {} }`.
- **spread over apply** — `f.apply(null, args)` → `f(...args)`  ·  clone: `{ ...obj }` / `[ ...arr ]`.

### Iteration & async
- **for…of, not index loops** — `for (let i=0;i<a.length;i++) a[i]` → `for (const x of a)` (or `.map`/`.forEach`).
- **async/await over .then chains** — `p.then(a).then(b)` → `const x = await p; ...` (wrap in `try/catch`).
- **no floating promises** — `await` (or `.catch`) every promise; an unhandled rejection crashes Node.

### Null-safety (modern, stable)
- **optional chaining** — `a && a.b && a.b.c` → `a?.b?.c`.
- **nullish coalescing** — `x !== null && x !== undefined ? x : d` → `x ?? d` (NOT `||`, which also catches `0`/`""`).

### Modules (the #1 "cannot use import" / "require is not defined" error)
- Pick ONE system per package: ESM (`import`/`export`, needs `"type":"module"`) OR CommonJS
  (`require`/`module.exports`). Do NOT mix — Node loads one or the other per `package.json`.

**Maintenance note:** harvested from ESLint + typescript-eslint recommended rule sets — re-harvest on change.
