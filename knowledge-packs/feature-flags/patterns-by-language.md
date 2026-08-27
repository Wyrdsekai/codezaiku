# Feature Flag Patterns by Language

## Java

### Spring Boot with Property-Based Flags

The simplest approach uses Spring `@ConditionalOnProperty` or `@Value` injection.

```java
// Flag definition in application.yml
// feature:
//   async-checkout:
//     enabled: false

@Component
public class CheckoutService {

    @Value("${feature.async-checkout.enabled:false}")
    private boolean asyncCheckoutEnabled;

    public OrderResult checkout(Cart cart) {
        if (asyncCheckoutEnabled) {
            return asyncCheckout(cart);
        }
        return syncCheckout(cart);
    }
}
```

**Default value pattern**: Always specify the default after the colon (`${...:false}`). Missing properties with no default cause startup failures.

**Cleanup**: When removing the flag, delete the `@Value` field, the `if` branch, the unused method, and the YAML key. Search for the property name across all profiles (application-dev.yml, application-prod.yml, etc.).

### Togglz

Togglz is the most established Java feature flag library. Flags are defined as enums.

```java
public enum AppFeatures implements Feature {

    @Label("Async payment processing")
    @EnabledByDefault
    ASYNC_PAYMENT,

    @Label("Simplified onboarding flow")
    SIMPLIFIED_ONBOARDING;

    public boolean isActive() {
        return FeatureContext.getFeatureManager().isActive(this);
    }
}

// Usage
public class PaymentService {

    public void processPayment(Order order) {
        if (AppFeatures.ASYNC_PAYMENT.isActive()) {
            submitAsync(order);
        } else {
            processSync(order);
        }
    }
}
```

**Default value pattern**: Use `@EnabledByDefault` for flags that should be on by default. Omit the annotation for flags that default to off.

**Cleanup**: Remove the enum constant, all `isActive()` call sites, the losing code path, and any activation strategy configuration. Togglz persists state in a StateRepository; clear the persisted state entry too.

### Spring Feature Flags (Spring Modulith)

Spring Modulith (since 1.3) provides `@FeatureFlag` for module-level toggling.

```java
@Configuration
public class FeatureFlagConfig {

    @Bean
    @FeatureFlag(name = "new-search", enabledByDefault = false)
    public SearchService searchService(
            NewSearchService newSearch, LegacySearchService legacy) {
        // Spring selects the bean based on flag state
        return newSearch;
    }
}
```

**Cleanup**: Remove the `@FeatureFlag` bean, the legacy implementation class, and any flag configuration.

---

## Python

### flipper-client (Python port of Flipper)

```python
from flipper import FeatureFlipper

flipper = FeatureFlipper()

# Registration with default
flipper.create("experiment.onboarding.simplified_flow", default=False)

# Check
if flipper.is_enabled("experiment.onboarding.simplified_flow"):
    return simplified_onboarding(user)
else:
    return standard_onboarding(user)
```

**Default value pattern**: Pass `default=False` at registration. Never rely on implicit defaults.

**Cleanup**: Remove the `create()` call, the `is_enabled()` check, and the dead branch. Search for the flag string across the codebase.

### django-waffle

Waffle is the standard feature flag library for Django projects.

```python
import waffle

# In views
def checkout_view(request):
    if waffle.flag_is_active(request, "release.checkout.async_payment"):
        return async_checkout(request)
    return sync_checkout(request)

# In templates
# {% load waffle_tags %}
# {% flag "release.checkout.async_payment" %}
#   <div>New async checkout UI</div>
# {% else %}
#   <div>Standard checkout</div>
# {% endflag %}
```

**Default value pattern**: Flags default to off. The `everyone` field in the Flag model controls global enable/disable. Percentage and group fields control gradual rollout.

**Cleanup**: Remove the `flag_is_active()` calls, template `{% flag %}` blocks (keep only the winning branch), and the Flag row from the database via a data migration.

```python
# Data migration to clean up a retired flag
from django.db import migrations

def remove_stale_flag(apps, schema_editor):
    Flag = apps.get_model("waffle", "Flag")
    Flag.objects.filter(name="release.checkout.async_payment").delete()

class Migration(migrations.Migration):
    dependencies = [("myapp", "0042_remove_async_checkout_code")]
    operations = [migrations.RunPython(remove_stale_flag)]
```

### Python Pattern: Context Manager for Test Overrides

```python
# Test helper
from contextlib import contextmanager

@contextmanager
def override_flag(name: str, active: bool):
    original = flipper.is_enabled(name)
    flipper.set(name, active)
    try:
        yield
    finally:
        flipper.set(name, original)

# Usage in tests
def test_simplified_onboarding_enabled():
    with override_flag("experiment.onboarding.simplified_flow", True):
        result = onboard(test_user)
        assert result.flow == "simplified"

def test_simplified_onboarding_disabled():
    with override_flag("experiment.onboarding.simplified_flow", False):
        result = onboard(test_user)
        assert result.flow == "standard"
```

---

## Go

### go-feature-flag

go-feature-flag uses a file-based or remote configuration with evaluation contexts.

```go
// Flag definition (flags.yaml)
// async-payment:
//   variations:
//     enabled: true
//     disabled: false
//   defaultRule:
//     variation: disabled
//   targeting:
//     - query: key eq "internal"
//       variation: enabled

import (
    ffclient "github.com/thomaspoignant/go-feature-flag"
    "github.com/thomaspoignant/go-feature-flag/ffcontext"
)

func ProcessPayment(ctx context.Context, order Order) error {
    evalCtx := ffcontext.NewEvaluationContextBuilder("user-123").
        AddCustom("plan", "enterprise").
        Build()

    asyncEnabled, _ := ffclient.BoolVariation("async-payment", evalCtx, false)
    if asyncEnabled {
        return processAsync(ctx, order)
    }
    return processSync(ctx, order)
}
```

**Default value pattern**: The third argument to `BoolVariation` is the fallback default. Always pass `false` for new flags so that evaluation errors produce the safe/off behavior.

**Cleanup**: Remove the `BoolVariation` call, the dead branch, and the flag definition from the YAML file.

### Go Pattern: Flag as Dependency Injection

Instead of checking flags inline, inject the behavior at startup.

```go
type PaymentProcessor interface {
    Process(ctx context.Context, order Order) error
}

func NewPaymentProcessor(flags *ffclient.GoFeatureFlag) PaymentProcessor {
    evalCtx := ffcontext.NewEvaluationContext("system")
    async, _ := flags.BoolVariation("async-payment", evalCtx, false)
    if async {
        return &AsyncPaymentProcessor{}
    }
    return &SyncPaymentProcessor{}
}
```

This pattern confines the flag check to a single location, making cleanup trivial: replace the factory with a direct instantiation of the winning implementation.

### Go Pattern: Test Helper

```go
func withFlag(t *testing.T, name string, value bool) {
    t.Helper()
    // Set flag override in test config
    testFlags.Set(name, value)
    t.Cleanup(func() { testFlags.Reset(name) })
}

func TestAsyncPayment(t *testing.T) {
    withFlag(t, "async-payment", true)
    err := ProcessPayment(ctx, testOrder)
    assert.NoError(t, err)
}
```

---

## JavaScript / TypeScript

### Unleash Client

Unleash is a self-hosted feature flag service with official clients for JS/TS.

```typescript
import { initialize, isEnabled } from "unleash-client";

const unleash = initialize({
  url: "https://unleash.internal/api",
  appName: "checkout-service",
  customHeaders: { Authorization: process.env.UNLEASH_API_TOKEN },
});

// Synchronous check (uses local cache)
function getCheckoutFlow(user: User): CheckoutFlow {
  const context = { userId: user.id, properties: { plan: user.plan } };

  if (isEnabled("release.checkout.async-payment", context, false)) {
    return new AsyncCheckoutFlow(user);
  }
  return new StandardCheckoutFlow(user);
}
```

**Default value pattern**: The third argument to `isEnabled` is the default when the flag is unknown or the client cannot reach the server. Always pass `false`.

**Cleanup**: Remove the `isEnabled` call, the dead branch, and the flag definition in the Unleash admin UI. Search for the flag name string across frontend and backend codebases.

### LaunchDarkly SDK

```typescript
import * as ld from "@launchdarkly/node-server-sdk";

const client = ld.init(process.env.LD_SDK_KEY);

async function renderDashboard(user: User): Promise<Dashboard> {
  const ldUser = { key: user.id, custom: { plan: user.plan } };

  const showNewDashboard = await client.variation(
    "experiment.dashboard.redesign",
    ldUser,
    false, // default
  );

  if (showNewDashboard) {
    return renderRedesignedDashboard(user);
  }
  return renderClassicDashboard(user);
}
```

**Default value pattern**: The third argument to `variation` is the fallback. For boolean flags, always use `false`. For multivariate flags, use the most conservative variant.

### React Pattern: Flag-Gated Components

```tsx
import { useFlags } from "@unleash/proxy-client-react";

function CheckoutButton({ cart }: { cart: Cart }) {
  const { isEnabled } = useFlags();

  if (isEnabled("release.checkout.one-click")) {
    return <OneClickCheckout cart={cart} />;
  }
  return <StandardCheckout cart={cart} />;
}
```

**Cleanup**: Remove the `useFlags` hook call, the conditional, and the unused component. Also remove the component's file, tests, and any associated styles.

### TypeScript Pattern: Flag-Gated Module Loading

For large features, use dynamic imports to avoid bundling dead code.

```typescript
async function loadEditor(flags: FeatureFlags): Promise<EditorModule> {
  if (flags.isEnabled("release.editor.v2")) {
    return import("./editor-v2/EditorV2");
  }
  return import("./editor-v1/EditorV1");
}
```

**Cleanup**: Remove the dynamic import conditional, delete the losing module directory, and update the import to a static import of the winning module.

---

## Cross-Language Cleanup Checklist

Regardless of language, removing a flag requires touching all of these:

1. **Flag evaluation call sites** -- the `if` checks in application code.
2. **Flag definition/registration** -- enum constants, YAML keys, database rows, admin UI entries.
3. **Test overrides** -- test helpers, fixtures, environment variables that reference the flag.
4. **Configuration files** -- application.yml, .env, docker-compose overrides, Kubernetes ConfigMaps.
5. **Monitoring and alerting** -- dashboards, alerts, or metrics that reference the flag name.
6. **Documentation** -- READMEs, runbooks, or architecture docs that mention the flag.
7. **Dead code** -- the entire losing code path, including any helper functions, components, or classes used only by that path.
