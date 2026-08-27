# Feature Flag Fundamentals

## Flag Categories

### Release Flags (Short-Lived)
Release flags gate incomplete or untested features behind a toggle so that trunk-based development can continue without long-lived branches. They exist only until the feature is fully rolled out or abandoned.

- **Expected lifetime**: days to weeks.
- **Owner**: the team shipping the feature.
- **Risk if stale**: dead code accumulates; conditional paths never exercised in production.

### Experiment Flags (Medium-Lived)
Experiment flags support A/B tests or multivariate experiments. They route users into cohorts and collect metrics to inform a decision.

- **Expected lifetime**: weeks to a few months.
- **Owner**: product or growth team running the experiment.
- **Risk if stale**: experiment conclusions never acted on; both code paths remain indefinitely.

### Ops Flags (Long-Lived)
Ops flags are circuit breakers, kill switches, or load-shedding toggles that operators use to change system behavior at runtime without a deploy.

- **Expected lifetime**: permanent, but should be reviewed periodically.
- **Owner**: platform or SRE team.
- **Risk if stale**: operators forget the flag exists; the guarded path rots.

### Permission Flags (Long-Lived)
Permission flags gate functionality by user role, tenant, entitlement, or plan tier. They are a form of authorization expressed as configuration.

- **Expected lifetime**: permanent while the tier/role distinction exists.
- **Owner**: product team that owns the entitlement model.
- **Risk if stale**: orphaned entitlement checks after pricing or role model changes.

---

## Lifecycle Management

A healthy feature flag follows a clear lifecycle:

```
CREATE --> ENABLE --> MEASURE --> REMOVE
```

### 1. Create
- Define the flag with a clear, consistent name (see Naming Conventions below).
- Document the flag's purpose, owner, expected lifetime, and category.
- Register it in a central flag inventory (config file, database, or flag service).
- Set the default value to the safe/off state.

### 2. Enable
- Roll out incrementally: internal users, then percentage ramp, then full.
- For experiment flags, configure cohort assignment and metric collection before enabling.
- For ops flags, verify that toggling on and off both work correctly under load.

### 3. Measure
- Collect the data or observation that the flag was created to support.
- For release flags: confirm the feature works, then commit to ship or revert.
- For experiment flags: reach statistical significance, then decide.
- For ops flags: periodically verify the flag is still needed and the guarded path still works.

### 4. Remove
- Remove the flag evaluation from code, leaving only the winning path.
- Remove the flag definition from the inventory.
- Remove any test fixtures or environment overrides that reference the flag.
- Remove associated metrics collection if it was flag-specific.

Failure to complete the REMOVE step is the single most common source of flag debt.

---

## Naming Conventions

Consistent naming makes flags discoverable, sortable, and auditable.

### Recommended Pattern
```
<category>.<scope>.<description>
```

Examples:
- `release.checkout.async-payment-processing`
- `experiment.onboarding.simplified-signup-flow`
- `ops.circuit-breaker.payment-gateway`
- `permission.plan.enterprise-sso`

### Rules
1. Use lowercase with hyphens for the description segment.
2. Prefix with the category so that stale-flag tooling can apply category-specific TTL policies.
3. Include enough context in the name that a reader unfamiliar with the codebase can guess the flag's purpose.
4. Never embed dates, ticket numbers, or author names in the flag name itself; track those in metadata.
5. Avoid negation in names (`disable-feature` leads to double-negative confusion when the flag is set to false).

---

## Flag-in-Test Coverage

Feature flags introduce conditional paths. Every conditional path needs test coverage.

### Both Branches Must Be Tested
A flag-guarded block produces at minimum two code paths: flag-on and flag-off. Tests must exercise both. If only one path is tested, the other path rots silently.

### Test Matrix Approach
For N independent flags that interact in a code region, the full matrix is 2^N states. In practice:
- Test each flag individually in both states (2N tests).
- Test known interaction pairs explicitly.
- Do not attempt full combinatorial coverage for more than 3 interacting flags; instead, refactor to reduce coupling.

### Test Utilities
- Provide a test helper that overrides flag state deterministically (no random cohort assignment in tests).
- Ensure the helper resets state after each test to prevent cross-test contamination.
- Integration tests should run with flags in the production-default state unless explicitly testing the flag itself.

### CI Pipeline Considerations
- Run the full test suite with all release flags on AND with all release flags off.
- If this is too expensive, at minimum run flag-specific tests in both states.

---

## Stale Flag Detection Heuristics

A stale flag is one that has outlived its usefulness. Stale flags increase complexity, slow onboarding, and hide dead code.

### Heuristic 1: Age Exceeds Category TTL
- Release flags older than 30 days are suspect.
- Experiment flags older than 90 days are suspect.
- Ops flags should be reviewed every 180 days.
- Permission flags should be reviewed when the entitlement model changes.

### Heuristic 2: Flag Is Always On or Always Off
If a flag has been 100% enabled (or 100% disabled) across all environments for longer than its category TTL, it is stale. The code should be simplified to remove the conditional.

### Heuristic 3: No Recent Toggle Events
If the flag has not been toggled in any environment within 2x its expected lifetime, it is likely forgotten. Ops flags are an exception but should still have a recent review timestamp.

### Heuristic 4: Single Code Path Reachable
Static analysis or coverage data shows that only one branch of the flag conditional is ever reached in production. The other branch is dead code.

### Heuristic 5: Owner No Longer Active
The team or individual listed as the flag owner has left the project, been reorganized, or no longer exists. Orphaned flags drift toward staleness faster.

### Heuristic 6: No Associated Metric or Decision
Experiment flags without an associated metric dashboard or decision document were likely created speculatively and never completed.

---

## What a Code Reviewer or Quality Agent Should Look For

1. **Is the flag registered in the central inventory?** Inline boolean constants masquerading as flags bypass all lifecycle tooling.
2. **Does the flag have a documented owner and category?** Without these, no one will clean it up.
3. **Is the default value safe?** A new flag should default to off (the pre-existing behavior).
4. **Are both branches tested?** Check for test cases covering flag-on and flag-off states.
5. **Is the flag name consistent with the naming convention?** Inconsistent names resist automated detection.
6. **Does the PR introduce a new flag without a cleanup ticket?** Every new flag should have a corresponding task to remove it later.
7. **Does the change touch a flag older than its expected TTL?** If so, consider whether the flag should be removed instead of modified.
8. **Is flag evaluation happening in a hot path?** Flag lookups should be cached or resolved at startup, not evaluated per-request via remote call.
9. **Are there nested flag checks?** `if flagA && flagB` suggests flag coupling, which exponentially increases the state space.
10. **Is the flag being used for permanent business logic?** If so, it should be a proper configuration value or authorization check, not a feature flag.
