---
id: go-idioms
keys: go, golang, .go, gomod
priority: 95
---

## Go language idioms — the things that break the build if you guess

Go's compiler is strict in ways that surprise: unused imports and unused locals are **errors**, not warnings.

### Unused = compile ERROR (the #1 Go failure)
- An imported package you don't reference → `imported and not used` (build fails).
- A declared local variable you never read → `declared and not used` (build fails).
- Only import what you use; only declare what you read. `_ "pkg"` is the blank import for side effects.

### Module + entrypoint
- `go.mod` declares the module: `module example.com/app` + `go 1.22`. Run `go mod tidy` to sync deps.
- A runnable binary is `package main` with `func main()`. Library packages use any other name.
- Files in the same directory must share one package name.

### Error handling — explicit, every call
Go has no exceptions. Return `error` as the last value and check it immediately:

```go
func loadConfig(path string) (*Config, error) {
    data, err := os.ReadFile(path)
    if err != nil {
        return nil, fmt.Errorf("read config: %w", err)   // %w wraps for errors.Is/As
    }
    var c Config
    if err := json.Unmarshal(data, &c); err != nil {
        return nil, err
    }
    return &c, nil
}
```

- `if err != nil { return ..., err }` is the load-bearing idiom — never ignore an `err`.
- Exported identifiers are `Capitalized`; lowercase is package-private.
- Use `:=` for declare-and-assign inside funcs; `var` at package scope.
- Prefer the standard library; `net/http` covers servers/clients without a framework.
