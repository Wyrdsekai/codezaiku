# Property-Based Testing in Python with Hypothesis

## Setup

```bash
pip install hypothesis
```

## Basic Property

```python
from hypothesis import given, strategies as st

@given(st.text())
def test_reversing_twice_gives_original(s):
    assert s == s[::-1][::-1]

@given(st.lists(st.integers()))
def test_sort_is_idempotent(xs):
    assert sorted(sorted(xs)) == sorted(xs)

@given(st.lists(st.integers(), min_size=1))
def test_min_is_in_list(xs):
    assert min(xs) in xs
```

## Custom Strategies

```python
from hypothesis import strategies as st
from dataclasses import dataclass

@dataclass
class User:
    name: str
    age: int
    email: str

users = st.builds(
    User,
    name=st.text(min_size=1, max_size=50, alphabet=st.characters(whitelist_categories=("L",))),
    age=st.integers(min_value=0, max_value=150),
    email=st.emails(),
)

@given(users)
def test_user_serialization_roundtrip(user):
    data = user_to_dict(user)
    restored = user_from_dict(data)
    assert restored == user
```

## Stateful Testing

```python
from hypothesis.stateful import RuleBasedStateMachine, rule, initialize, invariant

class DatabaseModel(RuleBasedStateMachine):
    def __init__(self):
        super().__init__()
        self.model = {}  # Reference implementation
        self.db = Database()  # System under test

    @initialize()
    def setup(self):
        self.db.clear()
        self.model.clear()

    @rule(key=st.text(min_size=1), value=st.integers())
    def put(self, key, value):
        self.db.put(key, value)
        self.model[key] = value

    @rule(key=st.text(min_size=1))
    def get(self, key):
        db_result = self.db.get(key)
        model_result = self.model.get(key)
        assert db_result == model_result

    @rule(key=st.text(min_size=1))
    def delete(self, key):
        self.db.delete(key)
        self.model.pop(key, None)

    @invariant()
    def size_matches(self):
        assert self.db.size() == len(self.model)

TestDatabase = DatabaseModel.TestCase
```

## Common Patterns

### Roundtrip
```python
@given(st.binary())
def test_compression_roundtrip(data):
    assert decompress(compress(data)) == data
```

### Metamorphic
```python
@given(st.lists(st.integers()), st.integers())
def test_filter_reduces_size(xs, threshold):
    filtered = [x for x in xs if x > threshold]
    assert len(filtered) <= len(xs)
```

### Fuzzing Parsers
```python
@given(st.binary())
def test_parser_no_crash(data):
    try:
        parse(data)
    except ParseError:
        pass  # Expected for invalid input
    # No other exceptions should occur
```

## Settings and Profiles

```python
from hypothesis import settings, Phase

# Per-test settings
@settings(max_examples=500, deadline=None)
@given(st.lists(st.integers()))
def test_intensive(xs):
    ...

# CI profile
settings.register_profile("ci", max_examples=1000, deadline=None)
settings.register_profile("dev", max_examples=50)
settings.load_profile(os.getenv("HYPOTHESIS_PROFILE", "dev"))
```

## Database of Examples

Hypothesis maintains a database of failing examples. Store in version control:

```ini
# pytest.ini or setup.cfg
[tool:hypothesis]
database_backend = directory
database = .hypothesis/examples
```

This ensures previously-found bugs are re-checked on every run.
