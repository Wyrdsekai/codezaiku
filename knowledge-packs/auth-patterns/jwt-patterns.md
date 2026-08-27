# JWT Patterns

## When to use
- Stateless authentication for APIs where the server should not store session state
- Token-based auth across multiple services (microservices, API gateways)
- Short-lived authorization tokens paired with longer-lived refresh tokens

## Pattern

### Token structure
- **Header**: `{ "alg": "RS256", "typ": "JWT", "kid": "key-id-1" }` — algorithm and key identifier
- **Payload**: registered claims + custom claims
- **Signature**: signs header + payload; verifiable without contacting the issuer

### Essential claims
- `iss` (issuer): identifies who issued the token
- `sub` (subject): the user/entity the token represents
- `aud` (audience): intended recipient (your API's identifier)
- `exp` (expiration): Unix timestamp; reject tokens past this time
- `iat` (issued at): when the token was created
- `jti` (JWT ID): unique token identifier for revocation tracking
- Always validate `iss`, `aud`, and `exp` on every request

### Signing algorithms
- **RS256 / ES256 (asymmetric)**: sign with private key, verify with public key; preferred for multi-service architectures where verifiers should not have the signing key
- **HS256 (symmetric)**: shared secret signs and verifies; acceptable only when issuer and verifier are the same service
- Never use `alg: none`; reject tokens with `none` algorithm explicitly
- Use `kid` (key ID) in the header to support key rotation

### Access + refresh token pattern
- **Access token**: short-lived (5-15 minutes), sent with every API request, contains permissions
- **Refresh token**: longer-lived (hours to days), stored securely, used only to obtain new access tokens
- Refresh tokens are stored server-side (database) and can be revoked individually
- On refresh: issue a new access token and rotate the refresh token (one-time use)

### Token rotation
- Rotate refresh tokens on every use — if a stolen refresh token is used, the rotation detects it (both old and new tokens are invalidated)
- Rotate signing keys periodically; publish old public keys on JWKS endpoint during the transition window
- JWKS (JSON Web Key Set) endpoint: `/.well-known/jwks.json` — consumers fetch public keys for verification

### Token storage (client-side)
- **HttpOnly, Secure, SameSite=Strict cookie**: best for web apps — not accessible to JavaScript, sent automatically
- **In-memory variable**: acceptable for SPAs; lost on page refresh (re-authenticate via refresh token in HttpOnly cookie)
- **Never `localStorage`**: accessible to any JavaScript on the page; one XSS vulnerability exposes all tokens

### Revocation
- JWTs are stateless — they cannot be revoked unless you add a check
- Options: short expiration (accept the window), token blocklist (check `jti` against a fast store like Redis), or version counter (increment user's token version on logout, reject older versions)
- Refresh token revocation: delete from the database; access tokens remain valid until they expire

## Gotchas / Anti-patterns
- **Long-lived access tokens**: a 24-hour access token is a 24-hour window of exposure if stolen; keep access tokens short
- **Storing sensitive data in the payload**: JWTs are base64-encoded, not encrypted; anyone can decode and read the claims
- **Not validating audience**: a token meant for service A is accepted by service B; always check `aud`
- **Symmetric signing in multi-service setups**: every service that can verify can also forge tokens; use asymmetric keys
- **localStorage for tokens**: one XSS attack steals all tokens; use HttpOnly cookies

## References
- RFC 7519: JSON Web Token (JWT)
- RFC 7517: JSON Web Key (JWK)
- Auth0 docs: JWT best practices
- OWASP: JWT security cheat sheet
