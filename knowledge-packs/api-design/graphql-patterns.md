# GraphQL Patterns

## When to use
- Clients need flexible, client-driven data fetching (different views need different shapes)
- Multiple clients (web, mobile, third-party) consuming the same API with different data requirements
- APIs with deeply connected data (social graphs, content management, e-commerce catalogs)

## Pattern

### Schema design
- Design types around the domain, not the database: `type Order` with meaningful fields, not a mirror of the SQL table
- Use non-nullable fields by default (`String!`); make nullable only when absence is a valid state
- Connections (relay-style pagination): `type UserConnection { edges: [UserEdge!]!, pageInfo: PageInfo! }`
- Input types for mutations: `input CreateOrderInput { ... }` — separate from output types
- Enums for finite sets: `enum OrderStatus { PENDING, SHIPPED, DELIVERED, CANCELLED }`

### Resolver patterns
- Resolvers should be thin: delegate to data loaders or service layers, not contain business logic
- Parent resolver provides the ID; child resolver fetches related data only when the client requests it
- Use field-level resolvers sparingly — batch at the type level with data loaders

### N+1 problem and batching
- The N+1 problem: fetching a list of orders, then 1 query per order to get the customer = N+1 queries
- Solution: DataLoader pattern — collect all IDs requested in a single tick, batch into one query
- DataLoader is per-request scoped (fresh instance per request to avoid caching across users)
- Framework implementations: graphql-java DataLoader, Apollo Server dataSources, Strawberry dataloaders

### Subscriptions
- Use for real-time updates: chat messages, live notifications, collaborative editing
- Transport: WebSocket (most common), SSE (simpler, HTTP-compatible)
- Keep subscription payloads small — send the delta, not the entire object
- Always handle disconnection and re-subscription gracefully on the client

### Query complexity and depth limiting
- Set a maximum query depth (e.g., 10 levels) to prevent deeply nested attacks
- Assign cost to fields and enforce a per-query cost budget
- Disable introspection in production if the schema is not public

### Error handling
- GraphQL always returns `200`; errors are in the `errors` array alongside partial `data`
- Use typed error unions in mutations: `union CreateOrderResult = Order | ValidationError | NotFoundError`
- Include error codes and human-readable messages; avoid exposing stack traces

## Gotchas / Anti-patterns
- **God queries**: a single query fetching the entire object graph; enforce complexity limits
- **Forgetting DataLoader**: resolvers that query the database directly cause massive N+1 amplification
- **Exposing the database schema directly**: leaks implementation details; design the graph for consumers
- **Over-fetching in resolvers**: resolver loads the full object from DB even when the client requests 2 fields; use field selection to optimize
- **No persisted queries in production**: arbitrary client queries are a DoS vector; use persisted or allowlisted queries

## References
- GraphQL specification (graphql.org)
- Relay specification: connections, pagination, node interface
- Apollo docs: schema design, data sources, subscriptions
- graphql-java docs: DataLoader integration
