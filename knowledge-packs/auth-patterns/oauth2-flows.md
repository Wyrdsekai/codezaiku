# OAuth 2.0 / 2.1 Flows

## When to use
- Allowing users to grant third-party apps limited access to their resources
- Implementing "Sign in with X" (delegated authentication via OpenID Connect)
- Machine-to-machine API access without user involvement
- Device-based authentication (smart TVs, CLI tools, IoT)

## Pattern

### Authorization Code + PKCE (recommended for all clients)
1. Client generates a `code_verifier` (random string) and `code_challenge` (SHA-256 hash of verifier)
2. Client redirects user to authorization server: `/authorize?response_type=code&code_challenge=...&code_challenge_method=S256`
3. User authenticates and consents
4. Authorization server redirects back with an authorization `code`
5. Client exchanges `code` + `code_verifier` for tokens at the `/token` endpoint
6. PKCE prevents authorization code interception — required even for confidential clients in OAuth 2.1

### Client Credentials (machine-to-machine)
- No user involved; the client authenticates directly with its own credentials
- `POST /token` with `grant_type=client_credentials`, `client_id`, `client_secret`
- Use for: backend service-to-service calls, cron jobs, infrastructure automation
- Scope the token narrowly to only the permissions the service needs

### Device Authorization Grant (device flow)
1. Device requests a device code and user code from `/device/authorize`
2. Device displays the user code and a verification URL to the user
3. User visits the URL on a separate device (phone/laptop), enters the code, authenticates
4. Device polls `/token` until the user completes authorization (or the code expires)
- Use for: CLI tools, smart TVs, IoT devices — anything without a browser

### Refresh tokens
- Issued alongside access tokens when `offline_access` scope is requested
- Used to obtain new access tokens without re-prompting the user
- Rotate on every use; store securely server-side
- Bind refresh tokens to the client: different client ID = different refresh token

### Scopes
- Define granular scopes: `read:orders`, `write:orders`, `admin:users`
- Request the minimum scopes needed; users see what they are granting
- Validate scopes on every API request — the token's scope must cover the requested operation

### OpenID Connect (OIDC) layer
- Adds identity to OAuth 2.0: the `/token` response includes an `id_token` (JWT with user info)
- Standard claims: `sub`, `name`, `email`, `email_verified`, `picture`
- Discovery: `/.well-known/openid-configuration` provides all endpoint URLs and supported features
- Use OIDC for authentication ("who is the user"); use OAuth for authorization ("what can they do")

## Gotchas / Anti-patterns
- **Implicit flow**: deprecated in OAuth 2.1; tokens in URL fragments are exposed in browser history and referrer headers
- **PKCE omitted for public clients**: without PKCE, authorization codes can be intercepted on mobile and SPA clients
- **Overly broad scopes**: requesting `admin` when `read:profile` suffices; violates principle of least privilege
- **Storing client secrets in frontend code**: public clients (SPAs, mobile apps) cannot keep secrets; use PKCE instead
- **Not validating the `state` parameter**: omitting CSRF protection in the authorization flow enables cross-site request forgery

## References
- RFC 6749: OAuth 2.0 Authorization Framework
- RFC 7636: PKCE (Proof Key for Code Exchange)
- RFC 8628: Device Authorization Grant
- RFC 9728: OAuth 2.1 Authorization Framework
- OpenID Connect Core 1.0 specification
