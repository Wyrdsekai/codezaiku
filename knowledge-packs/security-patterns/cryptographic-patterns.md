# Cryptographic Patterns

## When to use
- Encrypting data at rest or in transit beyond TLS
- Hashing passwords or deriving encryption keys from user-supplied secrets
- Implementing message authentication to detect tampering
- Managing encryption keys across application components or environments

## Pattern

### Authenticated Encryption
- **AES-256-GCM**: AES in Galois/Counter Mode; provides confidentiality + integrity + authentication in one operation
  - Nonce: 96-bit, must be unique per key; random nonces are safe for up to ~2^32 encryptions per key
  - Authentication tag: 128-bit; always verify tag before using decrypted data
  - Hardware-accelerated (AES-NI) on modern CPUs; high throughput
- **ChaCha20-Poly1305**: stream cipher + MAC; equivalent security to AES-256-GCM
  - Preferred when AES hardware acceleration is unavailable (embedded, mobile, older ARM)
  - 96-bit nonce; same uniqueness requirement as GCM
  - Constant-time by design; no timing side channels from lookup tables
- **XChaCha20-Poly1305**: extended nonce (192-bit); safe to generate nonces randomly even with very high message counts
- Never use unauthenticated encryption modes (ECB, CBC without HMAC) for any purpose

### Key Derivation
- **HKDF (HMAC-based Key Derivation Function)**: derive one or more keys from a high-entropy secret (e.g., Diffie-Hellman shared secret)
  - Extract step: concentrate entropy from input key material
  - Expand step: generate output key material of desired length with context/label separation
  - Not for passwords: HKDF is fast; password hashing must be slow
- **Argon2id**: recommended password hashing function; memory-hard, resistant to GPU/ASIC attacks
  - Parameters: memory (64MB+ for interactive, 1GB+ for offline), iterations (3+), parallelism (match available cores)
  - Salt: 16+ bytes, random per password; stored alongside the hash
  - Output: hash + salt + parameters stored as single string ($argon2id$...)
- **bcrypt / scrypt**: acceptable alternatives to Argon2id; bcrypt is widely deployed, scrypt is memory-hard
- Never use MD5, SHA-1, or plain SHA-256 for password hashing; they are too fast

### Envelope Encryption
- Generate a random data encryption key (DEK) per data item; encrypt data with DEK
- Encrypt the DEK with a key encryption key (KEK) stored in a key management service (KMS)
- Store encrypted DEK alongside encrypted data; KEK never leaves the KMS
- Benefits: rotate KEK without re-encrypting all data; limit KMS calls (one per DEK, not per data record)
- Key hierarchy: root key (HSM) -> KEK (KMS) -> DEK (application); each layer protects the one below

### Digital Signatures
- Sign with private key; verify with public key; proves authenticity and integrity
- **Ed25519**: fast, small signatures (64 bytes), small keys (32 bytes); preferred for most applications
- **ECDSA (P-256/P-384)**: widely supported; required by some compliance regimes; ensure deterministic nonce (RFC 6979)
- **RSA-PSS**: use when RSA is required; minimum 2048-bit keys; PKCS#1 v1.5 signing is deprecated
- Hash-then-sign: always hash the message before signing; do not sign raw data with RSA

### Randomness
- Use cryptographically secure random number generators (CSPRNG): `/dev/urandom`, `SecureRandom`, `crypto.getRandomValues()`
- Never use `Math.random()`, `rand()`, or other non-cryptographic PRNGs for security-sensitive values
- Seed entropy: ensure the system has sufficient entropy at boot (especially VMs and containers)

## Gotchas / Anti-patterns
- **Nonce reuse with AES-GCM**: reusing a nonce with the same key completely breaks confidentiality and authentication
- **Rolling your own crypto**: implementing custom encryption schemes instead of using vetted libraries
- **ECB mode**: encrypts identical blocks to identical ciphertext; leaks patterns; never use
- **Storing passwords with reversible encryption**: passwords should be hashed (one-way), not encrypted
- **Hard-coded keys**: encryption keys in source code; use environment variables, KMS, or vault
- **MD5/SHA-1 for integrity**: broken collision resistance; use SHA-256 minimum for hashing

## References
- NIST SP 800-38D — AES-GCM specification
- RFC 8439 — ChaCha20-Poly1305
- RFC 5869 — HKDF specification
- OWASP Password Storage Cheat Sheet — hashing recommendations
- Latacora, "Cryptographic Right Answers" (updated regularly) — modern algorithm recommendations
