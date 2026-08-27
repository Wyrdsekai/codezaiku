# Component Architecture

## When to use
- Building any UI with a component-based framework (React, Vue, Svelte, Solid, Web Components)
- Deciding how to decompose a page into reusable, testable units
- Choosing between server-rendered and client-interactive components

## Pattern

### Composition over inheritance
- Build complex UIs by nesting small, single-responsibility components
- Use slots / children / render props to let parents control layout without child awareness
- Prefer composition: `<Card><CardHeader /><CardBody /></Card>` over a monolithic `<Card title={} body={} />`

### Props vs State
- **Props**: data flowing down from parent; component treats them as read-only
- **State**: data owned and mutated locally by the component
- Lift state up only when two siblings need the same data; no higher
- Derived values should be computed from props/state, never stored as separate state

### Server vs Client components
- Default to server components (zero JS shipped, direct data access, smaller bundles)
- Promote to client component only when the component needs: event handlers, browser APIs, useState/useEffect, third-party client-only libs
- Keep the server/client boundary as high in the tree as possible — wrap a small interactive leaf, not a large subtree
- Pass serializable props across the boundary; no functions, classes, or streams

### Presentational vs Container split
- Presentational components receive data via props, emit events, own no side effects
- Container components fetch data, manage side effects, pass results down
- This split makes presentational components trivially testable and reusable

### Compound components
- Group related components under a shared context (e.g., `<Tabs>`, `<TabList>`, `<Tab>`, `<TabPanel>`)
- Internal state is shared via context, hidden from the consumer
- Provides a flexible API without prop-drilling dozens of options

## Gotchas / Anti-patterns
- **Prop drilling**: passing data through 4+ layers; use context or a state store instead
- **God components**: a single component doing layout, data fetching, validation, and rendering; split it
- **Premature abstraction**: creating a generic `<Widget>` before you have 3 concrete use cases
- **Putting client logic in server components**: hooks, event handlers, and browser globals break SSR silently or loudly depending on framework
- **Over-memoization**: wrapping every component in `memo`/`computed` without measuring; it adds complexity and can mask stale-data bugs

## References
- React docs: Thinking in React (component decomposition)
- Svelte docs: Component composition
- Web Components spec: shadow DOM, slots, custom elements
- Dan Abramov: "Presentational and Container Components" (historical context, pattern still useful even if hooks changed the ergonomics)
