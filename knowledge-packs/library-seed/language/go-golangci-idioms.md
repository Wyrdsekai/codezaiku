---
id: go-golangci-idioms
keys: go, golang, golangci, gofmt, .go
priority: 92
---

## Go idioms harvested from golangci-lint (errcheck/govet/staticcheck) — write it the idiomatic way

Each item is a golangci-lint / staticcheck rule (curated "you wrote X, do Y") distilled to its evergreen
idiom. Language-level and version-independent. Prefer the RIGHT column.

### Errors (the #1 Go discipline)
- **errcheck** — NEVER ignore a returned `error`. Handle it immediately:
  `f, err := os.Open(p); if err != nil { return err }`. Don't `_ = doThing()` away a real error.
- **error wrapping** — add context and preserve the chain: `fmt.Errorf("open %s: %w", p, err)` (`%w`, not `%v`).
- **error strings** — lowercase, no trailing punctuation: `errors.New("not found")`, not `"Not found."`.
- **check, don't panic** — return errors from library code; reserve `panic` for truly unrecoverable state.

### Declarations & assignment
- **`:=` vs `=`** — `:=` declares+assigns a NEW var; `=` assigns existing. Re-`:=` in a new scope shadows.
- **ineffassign** — don't assign a value that's never read before the next assignment.
- **zero values are useful** — `var buf bytes.Buffer` / `var xs []T` are ready to use; don't over-initialize.

### Collections
- **nil map write panics** — a `nil` map can be read but not written. Initialize: `m := make(map[K]V)`.
- **slice capacity** — known size? `make([]T, 0, n)` then `append`, to avoid reallocs.
- **range** — `for i := range xs` gives the index; `for i, x := range xs` gives index+value; `for _, x := range xs`
  for value only. Iterating a map is unordered.

### Idioms & structure
- **defer for cleanup** — right after acquiring: `f, err := os.Open(p); if err != nil {…}; defer f.Close()`.
- **accept interfaces, return structs** — params take the narrow interface you use; return concrete types.
- **context first** — `func Do(ctx context.Context, …)` is the first parameter, by convention.
- **exported = doc-commented** — every exported `Func`/`Type` starts with a `// Name …` comment (golint).
- **gofmt is non-negotiable** — formatting is mechanical; never hand-fight it.

**Maintenance note:** harvested from golangci-lint's default linters (errcheck, govet, staticcheck, ineffassign)
— re-harvest when the rule set changes.
