---
applies_to: [kmp, kotlin-multiplatform, kotlin, mobile, android, ios]
domain: mobile
tags: [kmp, expect-actual, shared-code, cross-platform-mobile]
---

# Kotlin Multiplatform — Architecture & Patterns

## Layering: what goes shared vs. platform-specific

- **commonMain (shared)** — pure Kotlin business logic, data classes, repositories,
  use-cases, validation, formatting, networking (via ktor-client), JSON
  serialization (kotlinx-serialization), state management (StateFlow), domain
  models. Aim for >80% of business logic here.
- **androidMain / iosMain** — only the parts that genuinely require platform
  APIs: file paths, system info, push notifications, biometric auth, deep links.
  Use the `expect/actual` mechanism to declare these in commonMain and
  implement per-platform.
- **androidApp / iosApp** (separate modules) — UI shell, dependency injection
  wiring, app-lifecycle entry points. Even with Compose Multiplatform, keeping
  the shell modules separate lets each platform bring its own theming and
  navigation idioms.

## expect/actual pattern

```kotlin
// shared/src/commonMain/kotlin/com/example/Platform.kt
expect class Platform {
    val name: String
    val osVersion: String
}

// shared/src/androidMain/kotlin/com/example/Platform.android.kt
actual class Platform {
    actual val name: String = "Android"
    actual val osVersion: String = android.os.Build.VERSION.RELEASE
}

// shared/src/iosMain/kotlin/com/example/Platform.ios.kt
actual class Platform {
    actual val name: String = "iOS"
    actual val osVersion: String = platform.UIKit.UIDevice.currentDevice.systemVersion
}
```

Rules:
- `expect` declarations live in commonMain ONLY.
- Every `expect` MUST have an `actual` in EVERY target source set.
- File names use `.android.kt` / `.ios.kt` suffix by convention (not required,
  helpful for IDE).

## Network: ktor-client engines

ktor-client is the canonical KMP HTTP library. The `core` artifact is shared;
the engine is per-platform:

```kotlin
// commonMain
val client = HttpClient {
    install(ContentNegotiation) { json() }
}

// androidMain depends on ktor-client-okhttp
// iosMain depends on ktor-client-darwin
// jsMain depends on ktor-client-js (if KMP-Web target)
```

Never reference an engine directly from commonMain — that breaks the
multiplatform contract.

## Async: kotlinx.coroutines

Suspend functions and Flow work identically across platforms. `Dispatchers.IO`
is available on all targets (renamed from `Dispatchers.Main` on iOS — use
`Dispatchers.Main` for UI updates, `Dispatchers.Default` for CPU work,
`Dispatchers.IO` for blocking I/O on Android/JVM only — on iOS, IO and
Default are equivalent).

## State: StateFlow > LiveData

Use `MutableStateFlow<T>` exposed as `StateFlow<T>` for screen state. LiveData
is Android-only; StateFlow is multiplatform. Compose collects StateFlow via
`.collectAsState()`; SwiftUI collects via the KMP-iOS bridge (typically a
`Combine.Publisher` adapter).

## Common pitfalls

- **Mixing JVM-only deps in commonMain** — kotlinx.coroutines-android, OkHttp
  directly, java.io.File: all break iOS compilation. Use multiplatform
  equivalents (kotlinx.coroutines-core, ktor-client, okio for files).
- **Long-running Tasks on iOS without GCD bridge** — iOS `Dispatchers.Main`
  is the GCD main queue; spawning many concurrent suspends can starve. Use
  structured concurrency with `coroutineScope { }`.
- **expect/actual mismatch** — adding a member to `expect` without updating
  every `actual` is a compile error. Build all targets early to catch this.
- **iOS framework export** — `binaries.framework { baseName = "shared" }`
  is required for Xcode to consume; `isStatic = true` avoids dynamic linking
  issues. Re-run `:shared:assembleSharedXCFramework` after API changes.
