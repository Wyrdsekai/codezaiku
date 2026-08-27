# Responsive Design

## When to use
- Building interfaces that work across phones, tablets, laptops, and large screens
- Choosing a breakpoint strategy and layout approach
- Implementing fluid typography and component-level responsiveness

## Pattern

### Mobile-first approach
- Write base styles for the smallest viewport; add complexity via `min-width` media queries
- Forces prioritization: what is essential on a small screen? Everything else is enhancement
- Results in less CSS overall — small screens get only base styles, not overrides
- Base: single column, stacked layout. Wider: multi-column, sidebar, expanded navigation

### Breakpoint strategy
- Breakpoints based on content, not devices — when the layout breaks, add a breakpoint
- Common reference points: ~480px (small phone), ~768px (tablet), ~1024px (small desktop), ~1280px (large desktop)
- Use a small, consistent set (3-5 breakpoints); avoid per-component magic numbers
- Define breakpoints as design tokens / CSS custom properties for consistency

### Container queries
- Size a component based on its container, not the viewport
- Enables truly reusable components that adapt when placed in a sidebar vs main content area
- Use `container-type: inline-size` on the parent, `@container (min-width: 400px)` in the child
- Prefer container queries for component-level responsiveness; reserve media queries for page-level layout

### Fluid typography
- Scale font size smoothly between a minimum and maximum using `clamp()`
- Example: `font-size: clamp(1rem, 0.5rem + 1.5vw, 1.5rem)` — scales from 16px to 24px
- Apply to headings and body text; maintain a consistent type scale ratio (e.g., 1.2 minor third)
- Ensures readability at all sizes without abrupt jumps at breakpoints

### Flexible layouts
- Use CSS Grid for two-dimensional page layouts; Flexbox for one-dimensional component layouts
- `grid-template-columns: repeat(auto-fit, minmax(250px, 1fr))` — responsive grid without media queries
- Avoid fixed widths; use `max-width` with percentage or `fr` units
- Aspect ratio: `aspect-ratio: 16 / 9` for media containers to prevent layout shift

### Responsive images and media
- `srcset` + `sizes`: let the browser choose the optimal image resolution
- `<picture>` element: art direction — different crops for different viewpoints
- Video: `max-width: 100%; height: auto` or container with `aspect-ratio`
- Use `object-fit: cover` or `contain` to control image scaling within containers

### Touch targets
- Minimum 44x44 CSS pixels for interactive elements (WCAG 2.2)
- Add padding rather than increasing the visible element size
- Space touch targets with at least 8px gap to prevent mis-taps

## Gotchas / Anti-patterns
- **Desktop-first CSS**: starts with the full layout then hides/stacks on small screens; results in bloated styles and poor mobile performance
- **Device-specific breakpoints**: targeting "iPhone 14" pixel widths; devices change yearly, content-based breakpoints do not
- **Viewport units for all typography**: `font-size: 3vw` alone causes text to be unreadable on phones and enormous on large screens; always use `clamp()`
- **Hiding content on mobile**: using `display: none` to remove features from small screens; either the content matters (show it differently) or it does not (remove it for everyone)
- **Fixed-height containers**: `height: 500px` breaks when content varies or the viewport changes; use `min-height` or let content determine height

## References
- MDN: CSS container queries, clamp(), media queries
- web.dev: responsive design fundamentals
- Every Layout (Heydon Pickering & Andy Bell): intrinsic layout patterns
- WCAG 2.2: target size success criterion
