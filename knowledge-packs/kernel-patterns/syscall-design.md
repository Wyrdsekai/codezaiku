# System Call Design Patterns

## When to use
- Adding new kernel-userspace interfaces to an operating system
- Designing stable ABIs that must remain backward-compatible for decades
- Wrapping kernel functionality for safe userspace consumption
- Extending an existing syscall family with new capabilities

## Pattern

### Interface Design
- Each syscall does one thing — composite operations belong in userspace libraries
- Use file descriptors as handles to kernel objects — leverage existing open/close/read/write/ioctl/poll model
- Prefer extensible structs with size field over fixed argument lists — allows future additions
- Return negative errno on failure, zero or positive value on success
- Flags parameter with defined bits and reserved-must-be-zero — allows future extension without new syscalls

### Backward Compatibility
- Syscall numbers are permanent — never reuse a number, never remove a syscall
- New fields appended to structs; kernel checks size field to handle old and new userspace
- Unknown flags must be rejected (return EINVAL) — if ignored, old userspace can't detect new features
- Versioned ioctls: encode version in ioctl number or use extensible struct pattern
- Wrapper syscalls (e.g., `openat2` extending `openat`) when existing interface is too constrained

### Error Handling
- Use errno codes consistently — EINVAL for bad arguments, EPERM for permission, ENOSYS for unimplemented
- Partial success must be clearly defined — does the syscall report how much was done?
- Restartable syscalls: return EINTR on signal, let userspace retry
- Never silently succeed with degraded behavior — fail explicitly so userspace can adapt

### Argument Validation
- Validate all pointers with `copy_from_user` / `copy_to_user` — never dereference userspace pointers directly
- Check all numeric arguments for range, overflow, and signedness
- Validate flags: reject any unknown bits set — prevents forward-compatibility bugs
- File descriptors must be validated at point of use, not cached
- Size arguments must be checked against reasonable bounds before allocating kernel memory

### Security Boundaries
- Capability checks at syscall entry — don't defer authorization to internal helpers
- Namespace-aware: syscall behavior may differ based on caller's namespace context
- No information leaks: zero padding bytes in structs returned to userspace
- Resource limits enforced: rlimits, cgroup limits, memory accounting
- Audit logging for security-sensitive operations

### Testing
- Selftests in the kernel tree exercising each syscall path
- Fuzzing with syzkaller — covers argument combinations humans wouldn't think to test
- Seccomp filter testing: verify syscall is correctly filterable
- Cross-architecture testing: argument passing conventions differ (32-bit compat on 64-bit)
- Strace integration: verify syscall is decodable for debugging

## Gotchas / Anti-patterns
- Multiplexer syscalls (one number, sub-operations via argument) — hard to seccomp filter, audit, or trace
- Leaking kernel pointers to userspace — information disclosure and KASLR bypass
- Time-of-check-to-time-of-use (TOCTOU) on userspace memory — copy in, then validate
- Using int where off_t or size_t is needed — 32-bit overflow on large files
- Accepting but ignoring unknown flags — prevents future extension (caller thinks flag is in effect)
- Blocking syscalls without cancellation mechanism — unkillable processes
- Kernel memory allocated proportional to userspace input without bounds — OOM vector

## References
- Linux syscall conventions: https://man7.org/linux/man-pages/man2/syscalls.2.html
- LWN: Designing kernel interfaces: https://lwn.net/Articles/724267/
- Syzkaller (kernel fuzzer): https://github.com/google/syzkaller
- Michael Kerrisk, "The Linux Programming Interface" (syscall reference)
- OpenBSD pledge/unveil (alternative syscall restriction model): https://man.openbsd.org/pledge
