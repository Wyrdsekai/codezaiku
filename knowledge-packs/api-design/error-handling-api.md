# API Error Handling

## When to use
- Designing consistent error responses across an API surface
- Returning actionable error information to API consumers
- Mapping internal exceptions to appropriate HTTP status codes

## Pattern

### Problem Details (RFC 9457)
- Standard error response format for HTTP APIs; use `Content-Type: application/problem+json`
- Required fields:
  - `type`: URI identifying the error type (e.g., `https://api.example.com/errors/insufficient-funds`)
  - `title`: short human-readable summary (e.g., "Insufficient Funds")
  - `status`: HTTP status code (e.g., 422)
- Recommended fields:
  - `detail`: human-readable explanation specific to this occurrence
  - `instance`: URI identifying the specific occurrence (e.g., request trace ID)
- Extension fields: add domain-specific data (e.g., `balance`, `required_amount`)

### Error response structure (if not using RFC 9457)
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      { "field": "email", "issue": "must be a valid email address" },
      { "field": "age", "issue": "must be at least 18" }
    ],
    "request_id": "req_abc123"
  }
}
```

### Status code selection
- `400`: syntactically invalid (malformed JSON, wrong types)
- `401`: no valid credentials provided
- `403`: credentials valid but insufficient permissions
- `404`: resource not found (also usable to hide existence from unauthorized users)
- `409`: conflict with current state (duplicate, concurrent modification)
- `422`: syntactically valid but semantically wrong (business rule violation)
- `429`: rate limited
- `500`: unexpected server error — log it, alert on it, never expose internals
- `502/503/504`: upstream failures, temporary unavailability

### Validation errors
- Return all validation errors at once, not one at a time
- Each error identifies the field (using JSON Pointer or dot notation) and the constraint violated
- Distinguish between field-level errors and cross-field errors (e.g., "end date must be after start date")

### Error codes
- Define a stable set of machine-readable error codes: `INVALID_INPUT`, `RESOURCE_NOT_FOUND`, `RATE_LIMITED`, `INTERNAL_ERROR`
- Codes are for programmatic handling; messages are for humans
- Document error codes in the API reference with causes and recommended actions

### Internal error handling
- Catch exceptions at the API boundary; map to appropriate status codes and error responses
- Never leak stack traces, SQL queries, or internal paths in production responses
- Log the full error with stack trace server-side; return a sanitized version to the client
- Include a request ID in every error response for support correlation

## Gotchas / Anti-patterns
- **200 with error body**: `{ "status": 200, "error": "not found" }` — clients cannot use status codes for control flow
- **Generic 500 for everything**: masks client errors as server errors; map exceptions correctly
- **One error at a time**: returning the first validation error, client fixes it, submits, gets the next — return all errors at once
- **Leaking internals**: `"error": "NullPointerException at UserService.java:42"` — sanitize before returning
- **Inconsistent error format**: different endpoints returning different error shapes; standardize across the entire API

## References
- RFC 9457: Problem Details for HTTP APIs
- RFC 9110: HTTP Semantics (status code definitions)
- Zalando RESTful API Guidelines: error handling section
- Google Cloud API Design Guide: errors
