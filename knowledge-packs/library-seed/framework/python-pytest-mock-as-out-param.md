---
id: python-pytest-mock-as-out-param
keys: python, pytest, mock, magicmock, assertion, attribute, write, contract, out-param
priority: 85
---

## Mock-as-out-param — the "test asserts on what your function WRITES" pattern

A pytest test that mutates a MagicMock argument is contracting that your function will write
attributes onto that argument. The test reads the attribute AFTER calling you. Your function
must WRITE the attribute. This is the second most-common failure class in pytest repair.

### The pattern in tests

```python
def test_classify_email():
    mock_email = MagicMock()
    classifier = SomeClassifier()
    classify_email(classifier, mock_email)            # function-under-test
    assert mock_email.category == "work"              # the contract!
    assert mock_email.confidence_score > 0.8
```

`mock_email.category` is `None`-equivalent (`MagicMock`) until your function writes it.
The assert reads what was written. If your function returns `("work", 0.8)` and never touches
`mock_email`, the assertion fails as `assert <MagicMock> == "work"`.

### The bug it surfaces in production

```python
def classify_email(classifier, email):
    label = classifier.predict([email.body])[0]       # computes label
    score = classifier.predict_proba([email.body]).max()
    return (label, score)                              # RETURNS — does not WRITE
    # email.category never set → test reads <MagicMock>, fails
```

### The fix

```python
def classify_email(classifier, email):
    label = classifier.predict([email.body])[0]
    score = float(classifier.predict_proba([email.body]).max())
    email.category = label                             # WRITE to the arg
    email.confidence_score = score
    return (label, score)                              # OK to also return; test doesn't check
```

### How to detect this from the failing test

Read the test body and look for these shapes — they ALL mean "your function must write to the arg":

```python
mock_X.attr == "value"           # write `arg.attr = "value"`
mock_X.attr is not None          # write SOMETHING to arg.attr
mock_X.attr > 0                  # write a number to arg.attr
mock_X.method.assert_called()    # CALL arg.method(...) inside your function
mock_X.method.assert_called_with(a, b)  # CALL arg.method(a, b) specifically
```

These are STRUCTURAL signals about the function's contract that the test gives you for free.
The Cut C ASSERTION CONTRACT block surfaces these paths in your per-turn observation if it
fires — read it BEFORE editing.

### Calling signature is also load-bearing

```python
@patch("app.services.classifier.EmailClassifier")
def test_classify_email(MockClassifier):
    mock_email = MagicMock()
    classify_email(MockClassifier(), mock_email)      # 2 args: classifier, email
    assert mock_email.category == "work"
```

If your function is `def classify_email(email, classifier)` (reversed order), then
`MockClassifier()` (the patched class) gets bound to `email` and the real `mock_email` gets
bound to `classifier` — your function then tries `classifier.body` which is a no-op MagicMock
attribute, and never touches what the test thinks is the email. This is a SIGNATURE BUG hiding
behind an assertion failure. Fix: match the test's positional argument order.

### Why this confuses Python — the LLM intuition trap

LLMs often output "return a tuple" because that's the Pythonic shape for "compute and return
multiple values". For unit tests using mocks, the more idiomatic pattern is "mutate the
input arg" because the mock is the test's observation surface. Both can coexist — return AND
write. The test only fails when you don't write.
