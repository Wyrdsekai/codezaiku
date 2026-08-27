# Multi-Factor Authentication Patterns

## When to use
- Adding a second authentication factor beyond passwords
- Protecting high-value accounts (admin, financial, infrastructure access)
- Implementing phishing-resistant authentication with WebAuthn/passkeys

## Pattern

### Factor categories
- **Something you know**: password, PIN
- **Something you have**: TOTP app, hardware key, phone (SMS)
- **Something you are**: biometrics (fingerprint, face)
- True MFA requires factors from at least two different categories

### TOTP (Time-based One-Time Password)
- Standard: RFC 6238; works with any authenticator app (Google Authenticator, Authy, 1Password, etc.)
- Server generates a shared secret; user scans a QR code containing `otpauth://totp/...`
- 6-digit code changes every 30 seconds; accept the current window +/- 1 (90-second tolerance)
- Store the shared secret encrypted (not hashed — you need the plaintext to verify codes)
- Rate-limit TOTP attempts: lock after 5 failed attempts in 5 minutes

### WebAuthn / Passkeys (phishing-resistant)
- Cryptographic authentication using public key pairs
- Private key stays on the user's device (hardware key, phone, laptop TPM); never transmitted
- **Registration**: server sends a challenge; device creates a key pair, returns the public key and signed challenge
- **Authentication**: server sends a challenge; device signs it with the private key; server verifies with the stored public key
- Phishing-resistant: the credential is bound to the origin (domain) — a fake site cannot trigger the real credential
- Passkeys: synced WebAuthn credentials (via iCloud Keychain, Google Password Manager, etc.) — cross-device availability

### SMS / Email OTP (weakest second factor)
- Send a 6-8 digit code via SMS or email; valid for 5-10 minutes
- SMS is vulnerable to SIM swapping, SS7 interception, and social engineering
- Email OTP is only as secure as the email account
- Acceptable as a fallback or for low-risk applications; not recommended as the primary second factor
- If using SMS: rate-limit sends, expire codes aggressively, detect SIM swap indicators

### Backup codes
- Generate 8-10 single-use codes during MFA setup (each 8+ alphanumeric characters)
- Store hashed (bcrypt or SHA-256); mark each as used after consumption
- Display once, instruct user to store securely offline
- Backup codes are the recovery path when the primary MFA device is lost

### Recovery flows
- Recovery must not bypass MFA entirely — that negates the security benefit
- Options: backup codes (preferred), recovery via a trusted secondary device, admin-assisted recovery with identity verification
- Account recovery should be as secure as account access — do not downgrade to email-only
- Log all recovery events; alert the user via a secondary channel

### Enrollment flow
- Prompt for MFA enrollment after account creation or on first login to a sensitive area
- Allow multiple MFA methods simultaneously (TOTP + WebAuthn + backup codes)
- Require re-authentication (password entry) before adding or removing MFA methods
- Grace period (optional): allow users N days before MFA becomes mandatory

### Step-up authentication
- Require MFA only for sensitive operations (not every login):
  - Changing password or email
  - Accessing billing or admin functions
  - Transferring funds or modifying permissions
- Cache the MFA verification for a short window (e.g., 15 minutes) to avoid repeated prompts

## Gotchas / Anti-patterns
- **SMS as the only second factor**: SIM swapping is a well-documented attack; offer TOTP or WebAuthn
- **No backup codes**: user loses their phone and is permanently locked out
- **MFA bypass in recovery flow**: "Forgot password" flow that resets MFA defeats the purpose
- **Storing TOTP secrets in plaintext**: encrypt them at rest; a database breach should not expose all TOTP secrets
- **No rate limiting on OTP entry**: attacker brute-forces a 6-digit code (1M possibilities) without throttling

## References
- RFC 6238: TOTP (Time-Based One-Time Password Algorithm)
- W3C WebAuthn specification
- FIDO Alliance: passkeys documentation
- NIST SP 800-63B: authenticator requirements and assurance levels
- OWASP: MFA Cheat Sheet
