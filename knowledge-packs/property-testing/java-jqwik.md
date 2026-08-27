# Property-Based Testing in Java with jqwik

## Setup

```xml
<!-- Maven -->
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.9.2</version>
    <scope>test</scope>
</dependency>
```

```groovy
// Gradle
testImplementation 'net.jqwik:jqwik:1.9.2'
```

## Basic Property

```java
import net.jqwik.api.*;

class StringProperties {
    @Property
    void reversingTwiceGivesOriginal(@ForAll String s) {
        String reversed = new StringBuilder(s).reverse().toString();
        String doubleReversed = new StringBuilder(reversed).reverse().toString();
        assertThat(doubleReversed).isEqualTo(s);
    }

    @Property
    void concatenationLength(@ForAll String a, @ForAll String b) {
        assertThat(a.concat(b).length()).isEqualTo(a.length() + b.length());
    }
}
```

## Custom Generators (Arbitraries)

```java
@Provide
Arbitrary<Person> validPersons() {
    Arbitrary<String> names = Arbitraries.strings()
        .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(50);
    Arbitrary<Integer> ages = Arbitraries.integers().between(0, 150);
    return Combinators.combine(names, ages).as(Person::new);
}

@Property
void personAgeIsNonNegative(@ForAll("validPersons") Person p) {
    assertThat(p.age()).isGreaterThanOrEqualTo(0);
}
```

## Stateful Testing

```java
@Property
void stackBehavesCorrectly(@ForAll("stackActions") ActionSequence<Stack<Integer>> actions) {
    actions.run(new Stack<>());
}

@Provide
ActionSequenceArbitrary<Stack<Integer>> stackActions() {
    return Arbitraries.sequences(
        Arbitraries.oneOf(
            Arbitraries.integers().map(PushAction::new),
            Arbitraries.just(new PopAction())
        )
    );
}

class PushAction implements Action<Stack<Integer>> {
    final int value;
    PushAction(int value) { this.value = value; }

    @Override
    public Stack<Integer> run(Stack<Integer> stack) {
        stack.push(value);
        assertThat(stack.peek()).isEqualTo(value);
        return stack;
    }
}
```

## Common Patterns

### Roundtrip
```java
@Property
void jsonRoundtrip(@ForAll("validModels") MyModel model) {
    String json = objectMapper.writeValueAsString(model);
    MyModel restored = objectMapper.readValue(json, MyModel.class);
    assertThat(restored).isEqualTo(model);
}
```

### Idempotency
```java
@Property
void normalizationIsIdempotent(@ForAll String input) {
    String once = normalize(input);
    String twice = normalize(once);
    assertThat(twice).isEqualTo(once);
}
```

### Oracle
```java
@Property
void customSortMatchesStdLib(@ForAll List<Integer> xs) {
    List<Integer> expected = new ArrayList<>(xs);
    Collections.sort(expected);
    List<Integer> actual = mySort(new ArrayList<>(xs));
    assertThat(actual).isEqualTo(expected);
}
```

## Configuration

```java
@Property(tries = 1000, shrinking = ShrinkingMode.FULL)
void intensiveTest(@ForAll @IntRange(min = -1000, max = 1000) int n) {
    // ...
}
```

- `tries`: Number of random inputs (default 1000)
- `shrinking`: FULL (default), BOUNDED, OFF
- `seed`: Fixed seed for reproducibility
- `maxDiscardRatio`: How many invalid inputs before failing
