---
applies_to: [android, kotlin, jetpack-compose]
domain: mobile
tags: [android, jetpack-compose, kotlin, compose, mobile-ui]
---

# Android Native — Jetpack Compose Patterns

## State management: hoisting + ViewModel + StateFlow

```kotlin
// ViewModel (state owner)
class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    fun increment() = _state.update { it.copy(counter = it.counter + 1) }
}

// Composable (state consumer)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    HomeContent(state = state, onIncrement = viewModel::increment)
}

// Stateless leaf
@Composable
fun HomeContent(state: HomeState, onIncrement: () -> Unit) {
    Button(onClick = onIncrement) { Text("Count: ${state.counter}") }
}
```

Rules:
- ONE ViewModel per screen.
- Composables that own state get `viewModel()`. Composables that render are
  stateless: take state + callbacks as params.
- LiveData is deprecated in Compose contexts — use StateFlow.
- `mutableStateOf { ... }` is for **local** UI state (text field input,
  expanded/collapsed toggle). ViewModel state is for **screen** state.

## Navigation: NavHostController + NavHost

```kotlin
val navController = rememberNavController()
NavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen(navController) }
    composable("detail/{id}") { backStackEntry ->
        val id = backStackEntry.arguments?.getString("id")
        DetailScreen(id = id, navController = navController)
    }
}
// To navigate: navController.navigate("detail/42")
// To go back: navController.popBackStack()
```

Multiple-Activity is anti-pattern in modern Android. Single-Activity +
Compose Navigation is the standard.

## Side effects

- `LaunchedEffect(key)` — runs a coroutine when `key` changes; cancels on leave.
- `DisposableEffect(key)` — cleanup on leave (close resources, unregister listeners).
- `produceState` — convert non-Compose async into Compose state.
- NEVER call `viewModelScope.launch { }` directly inside a Composable —
  recompositions create multiple jobs. Use ViewModel methods instead.

## Lifecycle awareness

```kotlin
@Composable
fun ScreenWithLifecycle() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { /* ... */ }
                Lifecycle.Event.ON_PAUSE -> { /* ... */ }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
```

## Theming (Material 3)

```kotlin
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
```

Wrap your top-level content in `AppTheme { ... }`. Use `MaterialTheme.colorScheme.primary`
etc. inside Composables — never hardcode colors.

## Common pitfalls

- **`@Composable` inside `if/else`** — fine, but wrap with `key()` if the same
  Composable type can appear in different branches; otherwise Compose's diffing
  may misidentify state.
- **Mutating Compose state outside main thread** — `_state.update { }` is
  thread-safe; `state.value = ...` is NOT. Always use `update`.
- **Forgetting `remember`** — every recomposition re-creates locals. Use
  `remember { ... }` for objects that must survive recomposition.
- **`LaunchedEffect(Unit)`** — runs once. `LaunchedEffect(key)` — re-runs when
  key changes. Easy to misuse: passing a recomputed value as key restarts on
  every recomposition.
