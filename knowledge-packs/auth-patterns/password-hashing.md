# Password Hashing

## When to use
- Storing user passwords in any application that handles authentication
- Migrating from a legacy hashing algorithm to a modern one
- Selecting work factors that balance security and login latency

## Pattern

### Recommended algorithms (in order of preference)

**Argon2id** (preferred)
- Memory-hard: resistant to GPU and ASIC attacks
- Hybrid of Argon2i (side-channel resistant) and Argon2d (GPU resistant)
- Parameters: memory (64MB+), iterations (3+), parallelism (1-4)
- Winner of the Password Hashing Competition (2015); current best practice

**bcrypt** (widely supported, proven)
- CPU-hard with adjustable work factor (cost parameter)
- Work factor 10-12 for interactive logins (~100-400ms)
- 72-byte password limit — truncates silently; pre-hash with SHA-256 if needed (but see gotchas)
- Mature, available in every language, well-understood security properties

**scrypt**
- Memory-hard like Argon2; predates it
- Parameters: N (CPU/memory cost), r (block size), p (parallelism)
- Good alternative when Argon2 is not available

### Work factor selection
- Target 200-500ms per hash on your production hardware
- Benchmark on the actual server: `time` a hash operation with candidate parameters
- Increase the work factor as hardware gets faster — re-hash on next successful login
- Do not optimize for speed — password hashing is intentionally slow

### Salt handling
- Every password gets a unique, random salt (minimum 16 bytes)
- Modern algorithms (bcrypt, Argon2) generate and embed the salt automatically
- Never use a global salt — it enables precomputation attacks across all users
- The salt is stored alongside the hash (it is not secret)

### Hash storage format
- Store the full output string: `$argon2id$v=19$m=65536,t=3,p=4$salt$hash`
- The format embeds algorithm, parameters, salt, and hash — self-describing
- Allows transparent parameter upgrades without data migration

### Migration strategy (legacy to modern)
- **On login**: verify against the old hash; if valid, re-hash with the new algorithm, store the new hash
- **Wrap hashing**: `new_hash = argon2id(old_hash)` — allows migration without user interaction, but adds complexity
- Track which algorithm each user's hash uses (the stored format string already encodes this)
- Set a deadline: after N months, force remaining users to reset their password

### Pepper (optional additional layer)
- A server-side secret key applied before or after hashing: `hash(pepper + password + salt)` or HMAC
- Stored in application config or HSM, not in the database
- Protects against database-only breaches (attacker has hashes but not the pepper)
- Must be rotated carefully — requires re-hashing all passwords

## Gotchas / Anti-patterns
- **MD5 / SHA-1 / SHA-256 alone**: fast hashes are not password hashing algorithms; they can be brute-forced at billions of hashes per second
- **No salt**: identical passwords produce identical hashes; enables rainbow table attacks
- **Hardcoded work factor**: setting cost=10 once and never updating; increase as hardware improves
- **bcrypt + pre-hashing pitfalls**: pre-hashing with SHA-256 to bypass the 72-byte limit can introduce null bytes; use HMAC-SHA-256 or base64-encode the intermediate hash
- **Logging passwords**: never log plaintext passwords, even in debug mode; hash immediately on receipt

## References
- OWASP: Password Storage Cheat Sheet
- RFC 9106: Argon2 Memory-Hard Function
- bcrypt original paper: Provos & Mazieres, "A Future-Adaptable Password Scheme"
- NIST SP 800-63B: memorized secret verifiers (password requirements)
