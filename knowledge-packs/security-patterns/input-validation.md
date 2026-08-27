# Input Validation

## When to use
- Processing any data that crosses a trust boundary (user input, API requests, file uploads, URL parameters)
- Building web applications, APIs, CLI tools, or any system that accepts external input
- Defending against injection attacks (SQL, command, XPath, LDAP, template)
- Ensuring data integrity before business logic processes it

## Pattern

### Allowlist vs Denylist
- **Prefer allowlists**: define exactly what is permitted; reject everything else
- Allowlist examples: regex for email format, enum of valid status values, set of allowed characters
- Denylists are incomplete by definition: you cannot enumerate all malicious inputs; attackers find bypasses
- Denylists as defense-in-depth only: add a denylist as a secondary check behind an allowlist, not as the primary defense
- Character allowlists: `[a-zA-Z0-9_-]` for identifiers; expand only as needed with documented justification

### Boundary Validation
- Validate at every trust boundary: between client and server, between services, between layers
- Do not rely on client-side validation alone; it can be bypassed; server must always re-validate
- Validate type, length, range, format, and business rules in order (cheapest checks first)
- Length limits: set maximum lengths on all string inputs; prevents buffer issues and resource exhaustion
- Numeric ranges: validate min/max before arithmetic; prevent integer overflow and underflow
- Reject early: fail and return a clear error at the first validation failure; do not process partially valid input

### Injection Prevention
- **SQL injection**: use parameterized queries / prepared statements exclusively; never concatenate user input into SQL strings
- **Command injection**: avoid shell commands with user input; if necessary, use safe APIs that take argument arrays (not shell strings)
- **XSS (Cross-Site Scripting)**: encode output for the specific context (HTML body, attribute, JavaScript, URL); use framework auto-escaping
- **Template injection**: do not pass user input as template code; separate data from template logic
- **Path traversal**: canonicalize file paths and verify they remain within the allowed directory; reject `../` sequences
- **LDAP injection**: escape special characters in LDAP filters; use parameterized LDAP queries where available

### Structured Input Validation
- Parse, don't validate: convert input into a strongly typed object; use the type system to enforce constraints
- JSON Schema, XML Schema, Protobuf: define the expected structure; reject non-conforming input before processing
- Deserialization safety: restrict allowed types during deserialization (Java, Python pickle, .NET); prevent deserialization-of-untrusted-data attacks
- File upload validation: check MIME type (from content, not just extension), file size, and scan for malware; store outside web root

### Normalization
- Normalize before validation: Unicode normalization (NFC), case folding, whitespace trimming
- Without normalization, visually identical inputs can bypass allowlists (homoglyph attacks, zero-width characters)
- URL normalization: decode percent-encoding, resolve path components, normalize scheme/host case
- Double encoding: validate after final decode; reject if decoding changes the input (indicates encoding attack)

## Gotchas / Anti-patterns
- **Validation only at the UI layer**: backend trusts frontend validation; attackers send direct API requests
- **Denylist-only approach**: blocking `<script>` but not `<img onerror=...>` or Unicode-encoded variants
- **String concatenation for queries**: parameterized queries exist in every language and framework; there is no excuse
- **Silent truncation**: silently truncating input to fit a field can cause logic errors; reject or explicitly handle
- **Validation after use**: checking input validity after it has already been processed or stored
- **Regex denial of service (ReDoS)**: complex regexes with nested quantifiers on untrusted input; test regex performance with adversarial strings

## References
- OWASP Input Validation Cheat Sheet — comprehensive guidance
- OWASP Injection Prevention Cheat Sheet — SQL, OS command, LDAP injection
- CWE-20 (Improper Input Validation) — common weakness enumeration
- OWASP ASVS (Application Security Verification Standard) — validation requirements by level
- Bobby Tables (bobby-tables.org) — parameterized query examples for every language
