---
id: python-pytest-mock-duck-typing
keys: python, pytest, mock, magicmock, isinstance, sqlalchemy, session, duck-typing, dispatch
priority: 85
---

## isinstance() vs MagicMock — the silent dispatch failure

`isinstance(MagicMock(), SomeClass)` returns `False`. Tests that pass `MagicMock()` to a
function will silently take the `else` branch of any `isinstance` check inside that function.
This produces the assertion failure shape "expected X, got empty/None/{}" with no traceback line
pointing at the dispatch — the most confusing failure class in Python repair work.

### The failure pattern in tests

```python
def test_get_predictions_with_data():
    mock_db = MagicMock()
    mock_db.query.return_value.all.return_value = [...]
    result = get_predictions(mock_db)        # MagicMock passed where Session expected
    assert result["total_emails"] == 5       # KeyError: 'total_emails'
```

### The bug it surfaces in production

```python
def get_predictions(db):
    if isinstance(db, Session):             # MagicMock fails this → falls through
        rows = db.query(Email).all()
    else:
        return {}                            # silent empty path — test sees {}, no clue why
    ...
```

### Three correct fixes (pick by context)

**1. Duck type the parameter (preferred for testable code).**
```python
def get_predictions(db):
    rows = db.query(Email).all()            # works for Session AND MagicMock
    ...
```
Use this when the function genuinely only needs the `query`/`add`/`commit` methods.

**2. `hasattr` guard if you really need a branch.**
```python
if hasattr(db, "query"):
    rows = db.query(Email).all()
```
Better than `isinstance` because MagicMock auto-creates attributes — `hasattr(MagicMock(), "anything")` is `True`.

**3. `Mock(spec=Session)` in the TEST (when test owns the mock setup).**
```python
mock_db = Mock(spec=Session)                # NOW isinstance(mock_db, Session) is True
```
Use this if the production code MUST keep `isinstance` for runtime safety. Requires editing the test, which Repair-mode usually FORBIDS — prefer fix 1 or 2.

### SQLAlchemy Session specifically

`isinstance(db, Session)` is a common antipattern in services that accept a session. SQLAlchemy
doesn't require it — `Session` is duck-typeable via `query`, `add`, `commit`, `refresh`, `close`.
If a test mocks a Session, the production function should duck-type. The forbidden pattern is:
`if isinstance(session, Session): ... else: <empty/None/default>` — that else branch is the bug.

### Quick diagnosis when a test returns `{}` or `None` you didn't expect

1. Look at the test — is the function-under-test receiving a `MagicMock`, `Mock`, or `Mock(spec=X)`?
2. If MagicMock (no spec): grep the production file for `isinstance(`. If found, that's the bug.
3. Fix by duck-typing (preferred) or by adding `spec=` to the test (only if you can't change prod).

### Anti-pattern: don't fix isinstance with type()

`type(mock_db) is Session` is even more broken than isinstance — fails for both MagicMock AND
subclasses. Never reach for `type(x) is Y` to escape this; duck-type instead.
