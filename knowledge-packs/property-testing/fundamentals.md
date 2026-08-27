# Property-Based Testing Fundamentals

## What Is Property-Based Testing?

Property-based testing generates random inputs to verify that properties (invariants) hold for all inputs, not just hand-picked examples. When a property fails, the framework **shrinks** the failing input to the smallest reproducing case.

**Key advantage**: Finds edge cases that example-based tests miss — boundary values, Unicode, empty collections, integer overflow, concurrent interleavings.

## Core Properties to Test

### Universal Properties

| Property | Description | Example |
|----------|-------------|---------|
| **Roundtrip** | encode then decode returns original | `deserialize(serialize(x)) == x` |
| **Idempotency** | applying twice same as once | `sort(sort(xs)) == sort(xs)` |
| **Commutativity** | order doesn't matter | `merge(a, b) == merge(b, a)` |
| **Associativity** | grouping doesn't matter | `f(f(a, b), c) == f(a, f(b, c))` |
| **Monotonicity** | order preserved | `if a <= b then f(a) <= f(b)` |
| **Invariant preservation** | structure invariant holds after operation | `isBalanced(insert(tree, x))` |
| **Oracle** | compare against reference implementation | `fastSort(xs) == referenceSort(xs)` |
| **Metamorphic** | relate outputs of related inputs | `count(filter(xs, p)) <= len(xs)` |

### Domain-Specific Properties

- **Parsers**: Roundtrip (parse then print = original), no crashes on arbitrary bytes
- **Serialization**: Roundtrip, schema conformance, version compatibility
- **Collections**: Size after add = size + 1, contains after add, order after sort
- **APIs**: Status codes in valid range, content-type matches, pagination totals consistent
- **Databases**: ACID properties, referential integrity after operations
- **Concurrent code**: Linearizability, no data races, no deadlocks

## Property Identification Heuristics

When examining code to test, look for:

1. **Functions with inverse**: serialize/deserialize, encode/decode, compress/decompress → test roundtrip
2. **Functions that sort/filter**: → test ordering, subset, length bounds
3. **State machines**: → test valid transitions, reachability, no invalid states
4. **Numeric computations**: → test bounds, special values (0, MAX, MIN, NaN, Inf)
5. **String processing**: → test empty, Unicode, very long, special chars
6. **Collection operations**: → test empty, single, large, duplicates

## Shrinking

Shrinking finds the minimal failing case. Good shrinking is crucial for debugging.

- **Integrated shrinking** (Hypothesis, jqwik): Shrinker built into generator, always works
- **Type-based shrinking** (QuickCheck classic): Separate shrinker per type, can miss cases

Prefer frameworks with integrated shrinking.
