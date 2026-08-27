# State Management

## When to use
- Deciding where UI state lives (component-local, shared, global, URL, server)
- Application has multiple components that need the same data
- Complex UI flows with many possible states and transitions

## Pattern

### State location hierarchy (prefer the highest applicable level)
1. **URL state**: current page, filters, search query — use the router; shareable and bookmarkable
2. **Server state**: data from APIs — use a server-cache library (TanStack Query, SWR, Apollo); not a global store
3. **Component-local state**: form input values, open/closed toggles — `useState` / reactive local variable
4. **Shared state (lifted)**: two siblings need the same value — lift to nearest common parent
5. **Global store**: truly app-wide state (auth user, theme, locale) — use a store (Zustand, Pinia, ngrx, Svelte stores)

### Server state is not client state
- Server data has its own lifecycle: loading, error, stale, revalidating
- Dedicated libraries handle caching, deduplication, background refetch, optimistic updates
- Putting server data into a Redux/Zustand store means reimplementing all of that manually

### State machines for complex UI
- Use finite state machines (XState, Robot, or manual reducer) when a component has:
  - More than 3 boolean flags that interact (`isLoading && !isError && isRetrying`)
  - States that should be mutually exclusive (idle | loading | success | error)
  - Transitions that depend on the current state (can only submit from "valid" state)
- Explicit states eliminate impossible state combinations

### Immutable updates
- Always produce new references when updating objects/arrays in state
- Spread operators for shallow updates; use Immer or structuredClone for deep nesting
- Immutability enables reliable change detection, undo/redo, and devtools time travel

### Selectors and derived state
- Compute derived values at read time, not write time
- Use selectors (or computed properties) to avoid storing redundant data
- Memoize expensive selectors; most selectors are cheap and do not need it

## Gotchas / Anti-patterns
- **Global store for everything**: putting form field values in Redux; keep transient UI state local
- **Duplicating server data**: copying API responses into a store and manually syncing; use a server-cache library
- **Boolean soup**: `isLoading`, `isError`, `isSuccess`, `isRetrying` as independent booleans; use a discriminated union or state machine
- **Mutable state updates**: `state.items.push(x)` breaks reactivity in most frameworks; always return a new reference
- **Over-subscribing**: every component connected to the entire store re-renders on any change; select only what you need

## References
- Kent C. Dodds: "Application State Management with React"
- TanStack Query docs: "Does this replace Redux?"
- XState docs: state machine concepts
- Zustand / Pinia / Svelte stores: respective framework docs
