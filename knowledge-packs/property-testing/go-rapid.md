# Property-Based Testing in Go with rapid

## Setup

```bash
go get pgregory.net/rapid
```

## Basic Property

```go
package mypackage

import (
    "sort"
    "testing"

    "pgregory.net/rapid"
)

func TestSortIdempotent(t *testing.T) {
    rapid.Check(t, func(t *rapid.T) {
        xs := rapid.SliceOf(rapid.Int()).Draw(t, "xs")
        sort.Ints(xs)
        sorted := make([]int, len(xs))
        copy(sorted, xs)
        sort.Ints(xs)
        if !slicesEqual(xs, sorted) {
            t.Fatalf("sort not idempotent: %v", xs)
        }
    })
}

func TestReverseRoundtrip(t *testing.T) {
    rapid.Check(t, func(t *rapid.T) {
        s := rapid.String().Draw(t, "s")
        reversed := reverse(reverse(s))
        if s != reversed {
            t.Fatalf("roundtrip failed for %q", s)
        }
    })
}
```

## Custom Generators

```go
func userGenerator() *rapid.Generator[User] {
    return rapid.Custom[User](func(t *rapid.T) User {
        return User{
            Name:  rapid.StringMatching(`[a-z]{1,50}`).Draw(t, "name"),
            Age:   rapid.IntRange(0, 150).Draw(t, "age"),
            Email: rapid.StringMatching(`[a-z]+@[a-z]+\.[a-z]{2,4}`).Draw(t, "email"),
        }
    })
}

func TestUserJSONRoundtrip(t *testing.T) {
    rapid.Check(t, func(t *rapid.T) {
        user := userGenerator().Draw(t, "user")
        data, err := json.Marshal(user)
        if err != nil {
            t.Fatal(err)
        }
        var restored User
        if err := json.Unmarshal(data, &restored); err != nil {
            t.Fatal(err)
        }
        if user != restored {
            t.Fatalf("roundtrip failed: %v != %v", user, restored)
        }
    })
}
```

## Stateful Testing

```go
func TestMapStateful(t *testing.T) {
    rapid.Check(t, func(t *rapid.T) {
        m := NewMyMap()           // System under test
        ref := map[string]int{}   // Reference implementation

        nOps := rapid.IntRange(1, 100).Draw(t, "nOps")
        for i := 0; i < nOps; i++ {
            op := rapid.IntRange(0, 2).Draw(t, "op")
            key := rapid.StringMatching(`[a-z]{1,10}`).Draw(t, "key")

            switch op {
            case 0: // Put
                val := rapid.Int().Draw(t, "val")
                m.Put(key, val)
                ref[key] = val
            case 1: // Get
                got, ok1 := m.Get(key)
                expected, ok2 := ref[key]
                if ok1 != ok2 || got != expected {
                    t.Fatalf("Get(%q): got (%d, %v), want (%d, %v)", key, got, ok1, expected, ok2)
                }
            case 2: // Delete
                m.Delete(key)
                delete(ref, key)
            }
        }

        if m.Len() != len(ref) {
            t.Fatalf("size mismatch: %d != %d", m.Len(), len(ref))
        }
    })
}
```

## Common Generators

```go
// Built-in generators
rapid.Int()                          // any int
rapid.IntRange(0, 100)               // bounded int
rapid.Float64()                      // any float64
rapid.String()                       // any string
rapid.StringMatching(`[a-z]+`)       // regex-based
rapid.Bool()                         // true/false
rapid.Byte()                         // single byte
rapid.SliceOf(rapid.Int())           // []int
rapid.SliceOfN(rapid.Int(), 1, 10)   // bounded length
rapid.MapOf(rapid.String(), rapid.Int()) // map[string]int
rapid.SampledFrom([]string{"a", "b", "c"}) // one of
rapid.Just(42)                       // constant
rapid.Ptr(rapid.Int(), true)         // *int (nullable)
```

## Integration with go test

rapid works with standard `go test`:

```bash
# Default (100 iterations)
go test ./...

# More iterations
go test -rapid.checks=1000 ./...

# Reproduce with seed
go test -rapid.seed=12345 ./...

# Verbose (show generated values)
go test -v -rapid.v ./...
```

Failed cases are automatically minimized (shrunk) to the smallest reproducing input.
