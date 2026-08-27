# Form Handling

## When to use
- Building any user input form (login, registration, search, CRUD, multi-step wizards)
- Choosing between controlled and uncontrolled inputs
- Implementing validation that works with and without JavaScript

## Pattern

### Progressive enhancement first
- Use a standard `<form>` with `method="POST"` and a server-side action
- The form must work with JavaScript disabled: server validates, returns errors, re-renders
- Layer on client-side validation and UX improvements (instant feedback, optimistic UI) as enhancement
- Server actions (Next.js, SvelteKit, Remix) close the loop: single function handles both JS-on and JS-off

### Controlled vs uncontrolled inputs
- **Controlled**: component state drives the input value; use when you need real-time derived behavior (live search, formatting, conditional fields)
- **Uncontrolled**: `FormData` extracted on submit via `new FormData(event.target)`; simpler, less re-rendering, sufficient for most forms
- Prefer uncontrolled for simple forms; switch to controlled only when needed

### Validation strategy
- **Client-side**: immediate feedback using HTML5 attributes (`required`, `pattern`, `min`, `max`, `type="email"`) and schema validation (Zod, Valibot, Yup)
- **Server-side**: always validate on the server; client validation is a UX convenience, not a security boundary
- Share a single validation schema between client and server (Zod works in both environments)
- Validate on blur for individual fields, on submit for the full form

### Error display
- Inline errors next to the field, associated via `aria-describedby` pointing to the error element
- Summary error banner at the top of the form for screen reader users (`role="alert"`)
- Persist user input on validation failure — never clear the form on error

### Multi-step forms (wizards)
- Each step is its own sub-form; store intermediate data in session, URL params, or a parent state container
- Allow backward navigation without data loss
- Validate each step independently before advancing
- Show progress (step 2 of 4) and allow jumping to completed steps

### Optimistic updates
- Submit the form and immediately update the UI assuming success
- Roll back if the server returns an error
- Combine with a pending state indicator so the user knows the operation is in-flight

## Gotchas / Anti-patterns
- **Client-only validation**: skipping server validation; any HTTP client can bypass the browser
- **Clearing inputs on error**: user loses all progress and has to re-type; preserve values
- **Blocking submit on any error**: validate on submit, not on every keystroke for the full form; field-level validation on blur is fine
- **No loading state on submit**: double-click sends duplicate requests; disable the button and show a spinner
- **Deeply nested controlled state**: managing 20 controlled fields via individual `useState` calls; use a form library or `useReducer` or just use `FormData`

## References
- MDN: Client-side form validation, Constraint Validation API
- Remix docs: form handling and progressive enhancement
- Zod docs: schema declaration and parsing
- web.dev: best practices for form design
