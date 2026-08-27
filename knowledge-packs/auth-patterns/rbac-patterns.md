# RBAC and Access Control Patterns

## When to use
- Controlling what authenticated users can do in an application
- Implementing role hierarchies, permission checks, and dynamic access policies
- Choosing between RBAC, ABAC, and hybrid approaches

## Pattern

### Role-Based Access Control (RBAC)
- Assign roles to users: `admin`, `editor`, `viewer`
- Assign permissions to roles: `admin` -> `[create, read, update, delete]`
- Check permissions, not roles, in application code: `if user.hasPermission("orders:write")` not `if user.role == "admin"`
- This decouples the permission check from the role definition — roles can change without touching code

### Role hierarchy
- Define inheritance: `admin` inherits all permissions from `editor`, which inherits from `viewer`
- Implement as a directed acyclic graph or ordered list
- When checking permissions, walk up the hierarchy: viewer permissions are automatically included in editor
- Keep the hierarchy shallow (2-3 levels); deep hierarchies become hard to reason about

### Permission structure
- Use `resource:action` format: `orders:read`, `orders:write`, `users:delete`, `reports:export`
- Wildcard support (optional): `orders:*` grants all actions on orders
- Resource-scoped permissions: `orders:read:own` (only the user's own orders) vs `orders:read:all`

### Attribute-Based Access Control (ABAC)
- Decisions based on attributes of the user, resource, action, and environment
- Example policy: "A user can edit a document if they are in the same department AND the document is not archived AND it is during business hours"
- More flexible than RBAC but more complex to implement and audit
- Use when RBAC cannot express the required policies (multi-tenant, data-level access, time-based)

### Hybrid approach (RBAC + ABAC)
- Use RBAC for coarse-grained access (which features a role can access)
- Use ABAC for fine-grained decisions within those features (which specific records)
- Example: role `support_agent` has `tickets:read` permission; ABAC policy restricts to tickets in their assigned region

### Implementation patterns

**Middleware / interceptor check**
- Check permissions at the API boundary (route middleware, controller interceptor)
- Reject early with `403 Forbidden` before business logic executes
- Centralizes access control; prevents scattered permission checks

**Policy engine**
- External policy engine (Open Policy Agent, Cedar, Casbin) evaluates rules
- Policies are declarative and versionable (code review, audit trail)
- Decouples policy from application code — update policies without redeploying

**Data-level filtering**
- For row-level security: filter database queries based on user attributes
- `WHERE tenant_id = :user_tenant_id` applied automatically via query middleware
- Prevents data leakage even if application code forgets a check

### Permission assignment workflow
- Roles assigned by admins through a management interface, not hardcoded
- Support for role + scope: "editor of project X" (not global editor)
- Audit log: record who granted/revoked which role, when, and why

## Gotchas / Anti-patterns
- **Checking roles instead of permissions**: `if role == "admin"` scatters role knowledge throughout the codebase; check permissions
- **Too many roles**: creating a role per user or per feature combination; keep roles meaningful and composable
- **No default deny**: forgetting to deny access when no rule matches; default should always be deny
- **Permission checks only at the UI level**: hiding a button is not security; always enforce on the server
- **Hardcoded permissions in code**: changing access requires a code deploy; externalize to a policy engine or configuration

## References
- NIST: RBAC model (INCITS 359-2012)
- OWASP: Access Control Cheat Sheet
- Open Policy Agent (OPA) documentation
- AWS Cedar: policy language for authorization
- Casbin: authorization library supporting RBAC, ABAC, and more
