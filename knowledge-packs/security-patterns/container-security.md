# Container Security

## When to use
- Building and deploying containerized applications (Docker, OCI)
- Hardening container images for production environments
- Configuring container runtime security policies
- Meeting compliance requirements for containerized workloads

## Pattern

### Minimal Images
- Use minimal base images: `distroless`, `scratch`, `alpine`, or `-slim` variants
- **Distroless**: no shell, no package manager, no utilities; smallest attack surface; debugging via ephemeral debug containers
- **Multi-stage builds**: build in a full image (compilers, dev tools), copy only the artifact to a minimal runtime image
- Remove unnecessary files in final stage: docs, man pages, cache directories, package manager state
- Image size directly correlates with attack surface: fewer binaries = fewer potential vulnerabilities
- Scan base images for vulnerabilities before adopting; pin base image digests (not just tags) for reproducibility

### Non-Root Execution
- Run the application process as a non-root user inside the container
- Dockerfile: `RUN addgroup -S app && adduser -S app -G app` then `USER app`
- Verify: `docker run --rm image whoami` should return the non-root user
- File permissions: ensure application files are readable by the non-root user; config and data directories are writable if needed
- `runAsNonRoot: true` in Kubernetes pod security context; rejects containers that try to run as root
- Drop all Linux capabilities: `--cap-drop=ALL`; add back only specific capabilities if needed (`NET_BIND_SERVICE` for port 80)

### Read-Only Filesystem
- Mount the root filesystem as read-only: `--read-only` (Docker) or `readOnlyRootFilesystem: true` (Kubernetes)
- Application writes go to explicitly mounted volumes or tmpfs mounts for temp directories
- Common tmpfs mounts: `/tmp`, `/var/run`, application cache directories
- Prevents: runtime modification of binaries, config file tampering, web shell deployment
- Test thoroughly: some applications write to unexpected locations (PID files, lock files, logs)

### Seccomp Profiles
- Restrict system calls the container can make; block dangerous calls (ptrace, mount, reboot, kexec)
- Default Docker seccomp profile blocks ~44 dangerous syscalls; always use at least the default
- Custom profiles: audit required syscalls with `strace` or seccomp logging mode, then build an allowlist
- Kubernetes: `seccompProfile.type: RuntimeDefault` uses the container runtime's default profile
- Strict mode: deny all syscalls except those explicitly allowed; most secure but requires careful testing

### AppArmor / SELinux
- **AppArmor**: define per-container profiles restricting file access, network, and capabilities (Ubuntu, Debian, SUSE)
- **SELinux**: mandatory access control labels on processes and files; `container_t` type for containers (RHEL, CentOS, Fedora)
- Default profiles: container runtimes apply default confinement profiles; verify they are not disabled
- Custom profiles: restrict container to only the files, network ports, and operations it legitimately needs
- Kubernetes: `appArmorProfile` or `seLinuxOptions` in security context

### Image Supply Chain
- Sign container images: Cosign (Sigstore) for keyless signing tied to CI identity
- Verify signatures before deployment: admission controllers (Kyverno, OPA Gatekeeper) enforce signature policies
- Scan images in CI: Trivy, Grype, Snyk Container; fail builds on critical/high vulnerabilities
- Registry security: private registries with authentication; no anonymous push; enable vulnerability scanning on push
- Base image updates: automate rebuilds when base images are updated (security patches)

### Runtime Security
- Network policies: restrict container-to-container communication to only required paths (default deny)
- Resource limits: set CPU and memory limits to prevent resource exhaustion (denial of service)
- No privileged mode: `--privileged` gives full host access; never use in production
- No host namespace sharing: `--pid=host`, `--network=host`, `--ipc=host` break isolation
- Immutable infrastructure: do not exec into production containers to make changes; redeploy

## Gotchas / Anti-patterns
- **Latest tag**: `FROM ubuntu:latest` is non-reproducible and may pull a new version with breaking changes or new vulnerabilities
- **Secrets in image layers**: `COPY .env .` or `ARG PASSWORD=secret` persists in layer history; use runtime injection
- **Running as root by default**: most base images default to root; explicitly set a non-root user
- **Disabled security profiles**: `--security-opt apparmor=unconfined` or `--security-opt seccomp=unconfined` removes protections
- **Ignoring scan results**: scanning but never acting on findings; establish SLA for vulnerability remediation
- **Docker socket mounting**: `-v /var/run/docker.sock:/var/run/docker.sock` gives container full control of the host Docker daemon

## References
- Docker security best practices — https://docs.docker.com/engine/security/
- CIS Docker Benchmark — configuration hardening checklist
- Google Distroless images — https://github.com/GoogleContainerTools/distroless
- Kubernetes Pod Security Standards — baseline and restricted profiles
- Trivy documentation — container and filesystem vulnerability scanner
