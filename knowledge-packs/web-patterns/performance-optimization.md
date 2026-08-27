# Performance Optimization

## When to use
- Improving Core Web Vitals scores (LCP, INP, CLS)
- Reducing bundle size and initial page load time
- Diagnosing performance regressions in a web application

## Pattern

### Core Web Vitals targets
- **LCP (Largest Contentful Paint)**: < 2.5s — optimize the critical rendering path for the hero image or heading
- **INP (Interaction to Next Paint)**: < 200ms — keep main thread work per interaction under 50ms
- **CLS (Cumulative Layout Shift)**: < 0.1 — reserve space for dynamic content, set explicit dimensions on images/embeds

### Code splitting
- Split at the route level: each page loads only its own code
- Lazy-load heavy components below the fold: `React.lazy()`, dynamic `import()`, framework equivalents
- Split large third-party libraries: import only what you use (`import { debounce } from 'lodash-es'` not `import _ from 'lodash'`)
- Analyze bundles: use `source-map-explorer`, `webpack-bundle-analyzer`, or `rollup-plugin-visualizer`

### Image optimization
- Use modern formats: WebP or AVIF with `<picture>` fallback to JPEG/PNG
- Serve responsive sizes: `srcset` and `sizes` attributes; let the browser pick
- Lazy-load below-fold images: `loading="lazy"` (native) or Intersection Observer
- Set explicit `width` and `height` attributes to prevent layout shift
- Use a CDN with on-the-fly image transformation (resize, format conversion)

### Font optimization
- Subset fonts to only the characters used (Latin, etc.)
- Use `font-display: swap` to avoid invisible text during load
- Preload the primary font: `<link rel="preload" as="font" crossorigin>`
- Prefer variable fonts — one file replaces multiple weights

### Reducing JavaScript execution
- Defer non-critical scripts: `<script defer>` or `<script type="module">`
- Remove unused code: tree-shaking (ESM imports), dead code elimination
- Move heavy computation off the main thread: Web Workers for parsing, sorting, crypto
- Avoid layout thrashing: batch DOM reads and writes

### Server-side rendering and streaming
- SSR reduces time-to-first-meaningful-content: HTML arrives ready to display
- Streaming SSR sends the shell immediately; data-dependent parts stream in as they resolve
- Selective hydration: hydrate interactive components first, defer non-critical ones

### Resource hints
- `<link rel="preconnect">`: establish early connections to critical origins (API, CDN, font provider)
- `<link rel="dns-prefetch">`: lighter alternative when preconnect is too aggressive
- `<link rel="preload">`: fetch critical resources early (hero image, primary font, above-fold CSS)
- `<link rel="prefetch">`: fetch resources likely needed for the next navigation (low priority)

## Gotchas / Anti-patterns
- **Premature optimization**: profiling must come first; optimize what the data shows, not what you guess
- **Loading all JS upfront**: a single monolithic bundle forces the user to download everything before interacting
- **Unoptimized images**: a 4MB hero image dominates LCP; resize, compress, and serve modern formats
- **Render-blocking CSS**: inlining massive CSS blocks first paint; extract critical CSS, defer the rest
- **Layout shift from late-loading content**: ads, images, and embeds without reserved dimensions shift the page

## References
- web.dev: Core Web Vitals, performance auditing guides
- MDN: Resource hints, lazy loading
- Lighthouse documentation: performance audit categories
- Chrome DevTools: Performance panel, Coverage tool
