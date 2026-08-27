# TLS Configuration

## When to use
- Securing any network communication (web servers, APIs, databases, message brokers)
- Configuring HTTPS endpoints for public-facing or internal services
- Managing SSL/TLS certificates across infrastructure
- Hardening transport security against downgrade and interception attacks

## Pattern

### Modern Cipher Suites
- **TLS 1.3 only** for new deployments: simpler, faster handshake, no legacy cipher negotiation
- TLS 1.3 cipher suites (all are AEAD): `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256`, `TLS_AES_128_GCM_SHA256`
- **TLS 1.2 minimum** if legacy clients require it: restrict to AEAD cipher suites with ECDHE key exchange
- Recommended TLS 1.2 suites: `ECDHE-ECDSA-AES256-GCM-SHA384`, `ECDHE-RSA-AES256-GCM-SHA384`, `ECDHE-ECDSA-CHACHA20-POLY1305`
- Disable: TLS 1.0, TLS 1.1, SSLv3, all CBC-mode ciphers, RC4, 3DES, RSA key exchange (no forward secrecy), export ciphers
- ECDHE for key exchange: provides forward secrecy; compromise of long-term key does not decrypt past traffic
- Prefer server cipher order: server chooses the strongest cipher the client supports

### Certificate Management
- **Certificate Authority**: use publicly trusted CAs for public-facing services; internal CA or ACME for internal services
- **ACME/Let's Encrypt**: automated certificate issuance and renewal; set up auto-renewal well before expiry (30 days)
- **Key size**: RSA 2048-bit minimum (4096 recommended); ECDSA P-256 or P-384 preferred (smaller, faster)
- **Certificate chain**: serve the full chain (leaf + intermediates); do not include the root
- **Renewal monitoring**: alert if a certificate is within 14 days of expiry; auto-renewal should handle this but monitor failures
- **Private key protection**: generate on the server, never transmit; restrict file permissions (0600, owned by service user)
- **Short-lived certificates**: prefer 90-day certs with automated renewal over 1-year certs with manual rotation

### HSTS (HTTP Strict Transport Security)
- `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`
- Instructs browsers to always use HTTPS for the domain; prevents SSL stripping attacks
- `includeSubDomains`: applies to all subdomains; ensure all subdomains support HTTPS before enabling
- `preload`: submit domain to browser HSTS preload lists for protection on first visit
- Start with a short `max-age` (300 seconds), verify no breakage, then increase to 1 year
- Irreversible commitment: once preloaded, removing HSTS requires browser vendor coordination; test thoroughly first

### Certificate Pinning
- **Generally not recommended** for web browsers: creates operational fragility; recovery from mis-pin requires new app deployment
- **Acceptable for mobile apps**: pin the CA or intermediate certificate, not the leaf (allows rotation without app update)
- **Certificate Transparency (CT)**: monitor CT logs for unauthorized certificates issued for your domain; no pinning needed
- If pinning: always include backup pins (at least one alternate key/CA); have a documented recovery plan
- Prefer CT monitoring over pinning for web services; it detects mis-issuance without the operational risk

### Internal Service TLS
- **Mutual TLS (mTLS)**: both client and server present certificates; authenticates both endpoints
- Service mesh (Istio, Linkerd): automates mTLS between services; handles cert rotation transparently
- Internal CA: run your own CA for internal certificates; distribute root CA to all internal services
- Short-lived certificates (hours to days) with automated rotation; reduces blast radius of compromise
- Do not skip TLS for internal traffic: lateral movement after perimeter breach is a primary attack path

### Testing and Verification
- SSL Labs (ssllabs.com): scan public-facing endpoints; aim for A+ rating
- `testssl.sh`: command-line scanner for comprehensive TLS configuration analysis
- Verify: protocol versions, cipher suites, certificate chain, HSTS header, OCSP stapling
- Automated testing in CI: use `testssl.sh` or similar against staging environments on every deployment
- Monitor certificate transparency logs: `certspotter`, `crt.sh` for your domains

## Gotchas / Anti-patterns
- **Disabling certificate verification**: `verify=false`, `InsecureSkipVerify`, `-k` in production; completely negates TLS
- **Self-signed certs without CA trust**: every client must individually trust the cert; use an internal CA instead
- **TLS 1.0/1.1 still enabled**: PCI DSS non-compliant since 2018; security scanners will flag it
- **Mixed content**: HTTPS page loading HTTP resources; browsers block or warn; audit all resource URLs
- **Wildcard certificate on many servers**: compromise of one server exposes the key for all services under that wildcard
- **No OCSP stapling**: clients fall back to OCSP responder check, adding latency and privacy leakage; enable stapling

## References
- Mozilla SSL Configuration Generator — https://ssl-config.mozilla.org/ (generates configs for all major servers)
- SSL Labs grading criteria — https://github.com/ssllabs/research
- RFC 8446 — TLS 1.3 specification
- NIST SP 800-52 Rev 2 — Guidelines for TLS implementations
- Let's Encrypt documentation — ACME protocol and best practices
