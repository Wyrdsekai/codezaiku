# CSRF Protection

## When to use
- Any web application that uses cookies for authentication (session cookies, JWT in cookies)
- Protecting state-changing operations (POST, PUT, DELETE) from cross-site request forgery
- Understanding when CSRF protection is and is not necessary

## Pattern

### How CSRF works
- The browser automatically sends cookies with every request to a domain, including requests initiated by other sites
- An attacker's site can trigger a form submission or API request to your domain; the browser attaches the victim's session cookie
- The server sees a valid session and processes the request — the user unknowingly performed an action
- CSRF only affects cookie-based authentication; bearer tokens in headers are not automatically attached

### SameSite cookies (primary defense)
- `SameSite=Lax`: cookie sent on top-level navigations (clicking a link) but not on cross-site POST/PUT/DELETE or embedded requests (iframes, images, AJAX)
- `SameSite=Strict`: cookie never sent on cross-site requests, even top-level navigations; breaks "open in new tab" from external links
- `SameSite=Lax` is sufficient for most applications and is the default in modern browsers
- Set explicitly: `Set-Cookie: session=abc; SameSite=Lax; Secure; HttpOnly`

### Synchronizer token pattern
- Server generates a random CSRF token per session, stores it server-side
- Token is embedded in forms as a hidden field: `<input type="hidden" name="_csrf" value="token">`
- On form submission, server verifies the token matches the session's stored token
- Attacker cannot read the token from another origin (blocked by same-origin policy)
- For AJAX: send the token in a custom header (`X-CSRF-Token`) — custom headers cannot be set by cross-origin forms

### Double submit cookie pattern
- Server sets a CSRF token as a cookie AND expects it in a request header or form field
- On submit, the client reads the cookie value (JavaScript) and sends it in a header
- Server verifies the cookie value matches the header/field value
- Works because the attacker can cause the cookie to be sent but cannot read it (same-origin policy)
- Advantage: no server-side token storage needed
- Caveat: vulnerable if the attacker can set cookies on a subdomain; use `__Host-` cookie prefix

### Origin / Referer checking
- Check the `Origin` header (or `Referer` as fallback) on state-changing requests
- Reject requests where the origin does not match your domain
- Supplementary defense — some browsers omit these headers in certain scenarios
- Do not rely on this as the sole CSRF protection

### When CSRF protection is NOT needed
- **Token-based auth via headers**: `Authorization: Bearer <token>` is not sent automatically; no CSRF risk
- **SameSite=Strict cookies**: cross-site requests never include the cookie
- **GET requests**: should be safe and idempotent by design — CSRF on GET is a design flaw, not a CSRF problem
- **APIs consumed only by non-browser clients**: server-to-server calls do not send cookies

### Framework integration
- Most frameworks have built-in CSRF middleware — enable it, do not roll your own
- Django: `{% csrf_token %}` template tag + `CsrfViewMiddleware`
- Spring Security: `CsrfFilter` enabled by default for session-based auth
- Express: `csurf` middleware (deprecated) or custom implementation with `csrf-csrf` package
- Rails: `protect_from_forgery` enabled by default

## Gotchas / Anti-patterns
- **CSRF protection on GET requests**: if your GET requests have side effects, fix the endpoint (make it POST), do not add CSRF tokens to GET
- **Token per request with back button**: generating a new token on every page load invalidates the token on the previous page; use per-session tokens
- **SameSite=None without a reason**: explicitly disables the browser's default CSRF protection; only needed for legitimate cross-site embedding
- **Forgetting AJAX endpoints**: protecting form submissions but not API endpoints called via `fetch` — protect all state-changing endpoints
- **Subdomain cookie injection**: double-submit pattern is vulnerable if an attacker controls a subdomain; use `__Host-` prefix to prevent this

## References
- OWASP: Cross-Site Request Forgery Prevention Cheat Sheet
- MDN: SameSite cookies
- RFC 6265bis: cookie SameSite attribute
- OWASP: CSRF vulnerability description and attack scenarios
