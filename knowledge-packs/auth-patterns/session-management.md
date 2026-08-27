# Session Management

## When to use
- Server-rendered web applications where the server tracks user state
- Applications requiring immediate session invalidation (logout, account compromise)
- Simpler alternative to JWT-based auth when all requests go through a single backend

## Pattern

### Server-side session store
- On login, create a session record (database, Redis, or in-memory store) keyed by a random session ID
- Send the session ID to the client as an HttpOnly cookie
- On each request, look up the session by ID; reject if not found or expired
- Store minimal data in the session: user ID, roles, CSRF token, expiration

### Cookie configuration
- `HttpOnly`: prevents JavaScript access — mitigates XSS token theft
- `Secure`: cookie only sent over HTTPS — prevents transmission over plaintext
- `SameSite=Lax`: prevents cross-site request submission in most cases; use `Strict` for sensitive operations
- `Path=/`: scope to the entire application (or narrow to `/api` if appropriate)
- `Max-Age` or `Expires`: set a reasonable session duration; sliding expiration extends on activity
- `Domain`: set explicitly for subdomain sharing, omit for single-domain

### Session ID generation
- Use a cryptographically secure random generator (CSPRNG): `SecureRandom` (Java), `crypto.randomBytes` (Node), `secrets.token_urlsafe` (Python)
- Minimum 128 bits of entropy (32 hex characters or 22 base64url characters)
- Session IDs must be unpredictable — never sequential, never derived from user data

### Session fixation prevention
- Regenerate the session ID immediately after successful authentication
- Invalidate the old session ID — do not transfer it
- This prevents an attacker from pre-setting a session ID (via URL or cookie injection) and then waiting for the victim to authenticate

### Session lifecycle
1. **Creation**: on successful login; generate new session ID, store server-side
2. **Validation**: on every request; look up session, check expiration, check user status
3. **Renewal**: on activity, extend the expiration (sliding window); regenerate ID on privilege escalation
4. **Termination**: on logout, delete the session server-side and clear the cookie; on timeout, auto-expire

### Distributed session stores
- In-memory sessions do not survive server restarts and do not work with multiple server instances
- Use Redis, Memcached, or a database for shared session storage
- Set a TTL on the session record matching the desired session duration
- For Redis: use `SET session:{id} {data} EX {seconds}`

### Absolute and idle timeouts
- **Idle timeout**: session expires after N minutes of inactivity (e.g., 30 minutes); reset on each request
- **Absolute timeout**: session expires after N hours regardless of activity (e.g., 8 hours); forces re-authentication
- Both are needed: idle timeout limits exposure from unattended browsers, absolute timeout limits exposure from stolen sessions

## Gotchas / Anti-patterns
- **Session ID in URL**: `?sid=abc123` appears in logs, referrer headers, and browser history — always use cookies
- **No session regeneration on login**: enables session fixation attacks
- **Long-lived sessions without absolute timeout**: a stolen session cookie is valid indefinitely
- **Storing large objects in sessions**: sessions should hold IDs and metadata, not full user objects or query results
- **Not clearing server-side session on logout**: clearing only the cookie leaves the session valid if the ID is known

## References
- OWASP: Session Management Cheat Sheet
- OWASP: Session Fixation vulnerability
- RFC 6265: HTTP State Management Mechanism (cookies)
- NIST SP 800-63B: session management requirements
