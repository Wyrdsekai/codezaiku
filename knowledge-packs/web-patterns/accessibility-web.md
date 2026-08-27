# Web Accessibility

## When to use
- Building any web interface (accessibility is not optional, it is a baseline requirement)
- Ensuring compliance with WCAG 2.2 AA standards
- Making interactive components usable via keyboard and assistive technology

## Pattern

### Semantic HTML first
- Use the correct element: `<button>` for actions, `<a>` for navigation, `<nav>`, `<main>`, `<aside>`, `<header>`, `<footer>` for landmarks
- Semantic elements provide built-in keyboard behavior, focus management, and screen reader announcements
- A `<div onclick>` is never a substitute for a `<button>` — it lacks focus, keyboard activation, and role

### ARIA roles and properties
- ARIA supplements HTML semantics — use it when no native element exists for the pattern
- **Rule of first resort**: if a native element does the job, do not add ARIA
- Common widget roles: `role="dialog"`, `role="tablist"`, `role="tab"`, `role="tabpanel"`, `role="menu"`, `role="alert"`
- States: `aria-expanded`, `aria-selected`, `aria-checked`, `aria-disabled`, `aria-hidden`
- Relationships: `aria-labelledby`, `aria-describedby`, `aria-controls`, `aria-owns`
- Live regions: `aria-live="polite"` for non-urgent updates, `aria-live="assertive"` for critical alerts

### Keyboard navigation
- All interactive elements must be reachable via Tab (or arrow keys within composite widgets)
- Visible focus indicator on every focusable element — never `outline: none` without a replacement
- Custom widgets follow WAI-ARIA Authoring Practices keyboard patterns:
  - Tabs: arrow keys switch tabs, Tab moves to panel content
  - Menus: arrow keys navigate items, Enter/Space activates, Escape closes
  - Dialogs: Tab cycles within the dialog (focus trap), Escape closes
- Skip links: first focusable element should be "Skip to main content"

### Screen reader patterns
- Every image has `alt` text (descriptive) or `alt=""` (decorative, hidden from AT)
- Form inputs have associated `<label>` elements (via `for`/`id` or wrapping)
- Group related form fields with `<fieldset>` and `<legend>`
- Status messages use `role="status"` or `aria-live` — do not rely solely on visual changes
- Page title updates on route change in SPAs

### Color and contrast
- Minimum contrast ratio: 4.5:1 for normal text, 3:1 for large text (WCAG AA)
- Do not convey information by color alone — pair with icons, text, or patterns
- Test with simulated color vision deficiencies (browser devtools, Stark plugin)

### Motion and animation
- Respect `prefers-reduced-motion` media query — disable or reduce animations
- No content that flashes more than 3 times per second
- Provide pause/stop controls for auto-playing carousels, videos, animations

## Gotchas / Anti-patterns
- **ARIA overuse**: adding `role="button"` to a `<div>` instead of using `<button>` — more ARIA does not mean more accessible
- **Hidden focus styles**: removing outlines for aesthetics; keyboard users lose all orientation
- **Auto-focus on page load**: unexpected focus shifts disorient screen reader users; auto-focus only inside modals
- **Missing form labels**: placeholder text is not a label; it disappears on input and is not reliably announced
- **Click-only interactions**: hover tooltips, drag-only reordering with no keyboard alternative

## References
- WAI-ARIA Authoring Practices Guide (APG): widget keyboard patterns
- WCAG 2.2: success criteria levels A and AA
- MDN: ARIA roles and attributes
- The A11Y Project: checklist and resources
- Deque axe-core: automated accessibility testing rules
