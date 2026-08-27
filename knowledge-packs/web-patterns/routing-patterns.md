# Routing Patterns

## When to use
- Building a multi-page or multi-view web application
- Choosing between file-based and config-based routing
- Implementing layouts, guards, loading states, or parallel routes

## Pattern

### File-based routing conventions
- Directory structure maps to URL segments: `app/products/[id]/page.tsx` -> `/products/123`
- Colocation: page, layout, loading, error, and not-found files live alongside each other
- Groups (parenthesized folders like `(auth)`) organize code without affecting the URL
- Catch-all segments (`[...slug]`) handle arbitrary depth paths (docs, CMS content)

### Nested routes and layouts
- Layouts wrap child routes and persist across navigations (no re-mount, no state loss)
- Use a root layout for app shell (nav, footer), nested layouts for section-specific chrome
- Each layout level can have its own loading and error boundary — failures are scoped

### Dynamic segments
- Single dynamic: `/users/[id]` — validate and parse the param at the route level
- Optional segments: `/products/[[category]]` — render a default when the segment is absent
- Catch-all: `/docs/[...path]` — join segments and look up content by the full path

### Middleware and route guards
- Middleware runs before the route handler: auth checks, redirects, header injection, logging
- Keep middleware small and fast — it runs on every matched request
- Auth guards: redirect to login if no session; redirect to dashboard if already authenticated
- Role-based guards: check permissions at the route level, not inside the component

### Parallel and intercepting routes
- Parallel routes render multiple pages in the same layout simultaneously (dashboard panels)
- Intercepting routes show a lightweight view (modal) while preserving the full-page URL for direct navigation
- Useful for preview modals, inline editing, photo galleries

### Data loading
- Colocate data fetching with the route (loader functions, server components, `getServerSideProps`)
- Fetch in parallel when a route needs multiple independent data sources
- Streaming: send the shell immediately, stream in data as it resolves (improves TTFB)

## Gotchas / Anti-patterns
- **Client-side-only routing in an SSR app**: URLs return 404 on direct navigation or refresh
- **Fetching in components instead of routes**: causes request waterfalls; prefer route-level loaders
- **Catch-all routes swallowing 404s**: a `[...slug]` without proper validation silently renders broken pages
- **Heavy middleware**: doing database queries or slow external calls in middleware delays every request
- **Deep nesting without layouts**: 5 levels of nested routes each re-rendering the full page; use persistent layouts

## References
- Next.js App Router: routing conventions, parallel routes, intercepting routes
- Remix/React Router v7: nested routes, loaders, actions
- SvelteKit: filesystem routing, layout groups
- Nuxt: file-based routing, middleware directory
