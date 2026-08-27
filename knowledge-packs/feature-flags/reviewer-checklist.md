# Feature Flag Reviewer Checklist

This checklist is intended for code reviewers and quality agents evaluating changes that introduce, modify, or interact with feature flags.

---

## 1. Stale Flag Detection

### 1.1 Flag Age vs Category TTL
- [ ] Is this flag older than its expected lifetime? Release flags over 30 days, experiment flags over 90 days, and ops flags not reviewed in 180 days are candidates for removal.
- [ ] If the PR modifies an old flag rather than removing it, ask whether the flag should be retired instead.

### 1.2 No Recent Toggle Events
- [ ] Has the flag been toggled in any environment in the last 60 days?
- [ ] If the flag has been stuck at a single value across all environments for longer than its TTL, it is stale. The code should be simplified to remove the conditional entirely.

### 1.3 Only One Code Path Active
- [ ] Is the flag currently 100% enabled or 100% disabled in production? If so, the other code path is dead code and the flag should be removed.
- [ ] Check coverage reports or production telemetry: if only one branch of the flag conditional has been exercised in the last 30 days, the other branch is effectively dead.

---

## 2. Dead Code Behind Disabled Flags

- [ ] Does the PR introduce code behind a flag that is currently disabled in all environments? If so, when is the flag expected to be enabled? Is there a rollout plan?
- [ ] Are there entire classes, functions, components, or modules that are only reachable through a disabled flag? These should be flagged as dead code risk.
- [ ] When a flag is being removed and set to permanently on, verify that all code from the off branch is also removed. Partial cleanup is worse than no cleanup: it leaves orphan code that appears live.

---

## 3. Flag Naming Consistency

- [ ] Does the new flag follow the project's established naming convention (e.g., `<category>.<scope>.<description>`)?
- [ ] Is the name descriptive enough that someone unfamiliar with the feature can infer the flag's purpose?
- [ ] Does the name avoid negation? Flag names like `disable-new-checkout` or `no-async` lead to double-negative confusion when the flag value is `false`.
- [ ] Is the name unique? Check for near-duplicates (e.g., `async-checkout` vs `checkout-async` vs `new-checkout-flow`) that could cause confusion.
- [ ] Does the flag name use consistent casing and delimiter style with existing flags?

---

## 4. Flag-Guarded Code Test Coverage

- [ ] Are there tests for BOTH the flag-on and flag-off code paths?
- [ ] If the flag is new, does the PR include tests that explicitly set the flag to each state and verify the expected behavior?
- [ ] Is there a test that verifies the default value (flag off) produces the pre-existing behavior? This is the safety net: if the flag system fails, the default must be safe.
- [ ] Are flag overrides in tests properly scoped and cleaned up? Look for test pollution where one test's flag state leaks into subsequent tests.
- [ ] For interacting flags (flag A and flag B both checked in the same code path), are the important combinations tested? At minimum, test each flag independently; for tightly coupled flags, test the cross-product.

---

## 5. Flag Coupling

Flag coupling occurs when flags depend on each other, either explicitly or implicitly.

- [ ] Does the code contain nested flag checks (`if flagA { if flagB { ... } }`)? Nested flags create an exponential state space. Prefer refactoring into a single flag or a multi-variant flag.
- [ ] Does enabling one flag only make sense if another flag is also enabled? If so, this dependency should be documented and ideally enforced (e.g., enabling the child flag requires the parent flag to be on).
- [ ] Are there flags that are mutually exclusive? If flags A and B should never both be on, this constraint should be validated at startup or toggle time, not left to convention.
- [ ] Count the total flags evaluated in the changed code path. More than 2-3 flag checks in a single request path is a strong signal that flag management has become a liability.

---

## 6. Flag Documentation

- [ ] Is the flag registered in the project's central flag inventory (config file, database table, or flag service)? Inline boolean constants that behave like flags but bypass the inventory are a red flag.
- [ ] Does the flag definition include:
  - **Owner**: who is responsible for the flag's lifecycle?
  - **Category**: release, experiment, ops, or permission?
  - **Expected lifetime**: when should this flag be reviewed for removal?
  - **Purpose**: one sentence explaining why this flag exists.
- [ ] If the flag is an experiment flag, is there a link to the experiment design document or hypothesis?
- [ ] If the flag is an ops flag, is there a runbook entry explaining when and how to toggle it?

---

## 7. Cleanup Tracking

- [ ] Does the PR that introduces a new flag also create a cleanup ticket or task? Every flag should have a corresponding removal task from day one.
- [ ] Is the cleanup ticket linked to the flag definition (in code comments, flag metadata, or the ticket tracker)?
- [ ] Does the cleanup ticket have a due date consistent with the flag's expected lifetime?
- [ ] When reviewing a PR that removes a flag, verify completeness:
  - [ ] Flag evaluation removed from all call sites.
  - [ ] Flag definition removed from inventory/config.
  - [ ] Test overrides and fixtures removed.
  - [ ] Environment-specific config (YAML, env vars, ConfigMaps) cleaned up.
  - [ ] Dead code from the losing branch fully removed.
  - [ ] Monitoring dashboards or alerts referencing the flag updated or removed.

---

## 8. Implementation Quality

### 8.1 Default Value Safety
- [ ] Does the flag default to the safe/pre-existing behavior (typically off/false)?
- [ ] Is the default explicitly specified at every evaluation site, not relying on implicit library defaults?
- [ ] What happens if the flag system is unavailable (network error, config file missing)? The code should fall back to the default, not throw or behave unpredictably.

### 8.2 Performance
- [ ] Is flag evaluation happening in a hot path (tight loop, per-request, per-row)?
- [ ] If so, is the flag value cached or resolved at startup rather than evaluated on every invocation?
- [ ] Does the flag evaluation require a remote call? If so, is there a local cache with a sensible TTL?

### 8.3 Scope and Granularity
- [ ] Is the flag guarding an appropriately sized piece of functionality? A single flag controlling hundreds of lines across many files is hard to reason about and harder to clean up.
- [ ] Would this be better expressed as a configuration value rather than a feature flag? Permanent behavioral switches (log level, batch size, timeout) are configuration, not flags.
- [ ] Is the flag being used for authorization? If so, it should be a proper permission check, not a feature flag evaluated against user context.

### 8.4 Observability
- [ ] Are flag evaluation events logged or emitted as metrics? This enables stale flag detection (heuristic: no evaluations = dead flag).
- [ ] For experiment flags, are the metric collection hooks in place to actually measure the experiment's outcome?
- [ ] Can an operator determine the current state of all flags in a running system without reading code or config files?

---

## Summary: Red Flags in a PR

The following patterns in a pull request should prompt further discussion:

| Signal | Concern |
|--------|---------|
| New flag with no cleanup ticket | Flag debt accumulation |
| Flag name inconsistent with convention | Discovery and automation difficulties |
| Only one branch tested | Silent regression in the untested path |
| Nested or coupled flag checks | Exponential state space, hard to reason about |
| Flag older than its TTL being modified | Should be removed, not extended |
| Inline boolean acting as a flag | Bypasses lifecycle tooling |
| Flag evaluation in a hot loop | Performance degradation |
| No documented owner or category | No one will clean it up |
| Flag default is the new behavior (on) | Unsafe: failures enable untested code |
| Dead code left behind after flag removal | Incomplete cleanup |
