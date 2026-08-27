# Performance (Mobile)

## When to use
- App renders long scrollable lists or grids of content
- Images and media must load efficiently without excessive memory usage
- App runs on low-end devices with limited RAM and CPU
- Users expect smooth 60fps scrolling and responsive touch interactions

## Pattern

### List virtualization (recycling)
- Render only the visible items plus a small buffer above and below the viewport
- Recycle off-screen views by rebinding them with new data (ViewHolder pattern)
- Use the platform's built-in virtualized list: `RecyclerView` (Android), `UICollectionView` / `LazyVStack` (iOS), `FlatList` (React Native)
- Provide a stable, unique key for each item to enable efficient diff and reorder
- Estimate item heights to minimize layout jumps during scrolling (or use fixed heights)

### Image loading
- Never load full-resolution images into memory for thumbnails -- resize on decode
- Decode images off the main thread; display a placeholder until ready
- Use a three-tier cache: memory (LRU), disk, network
- Request server-side resized images when available (e.g., `?w=200&h=200`)
- Cancel in-flight image loads for views that scroll off screen
- Preferred formats: WebP (smaller than JPEG/PNG), AVIF (if platform supports it)
- Libraries: Coil/Glide (Android), SDWebImage/Kingfisher (iOS), or platform-native async image

### Memory management
- Monitor memory usage with platform profiling tools (Android Profiler, Instruments)
- Set memory budgets: keep peak usage under 200MB on low-end devices
- Release caches and non-visible resources on memory warnings (`onTrimMemory`, `didReceiveMemoryWarning`)
- Avoid retaining references to Activities/ViewControllers from long-lived objects (memory leaks)
- Use weak references for caches and observer registrations
- Profile for leaks regularly: LeakCanary (Android), Instruments Leaks (iOS)

### Rendering performance
- Target 16ms per frame (60fps); 8ms for 120Hz displays
- Keep the main/UI thread free of disk I/O, network calls, JSON parsing, and database queries
- Minimize view hierarchy depth -- flatten nested layouts using `ConstraintLayout` (Android) or stack-based layouts (iOS)
- Avoid overdraw: do not stack opaque views with backgrounds; use layout inspector to detect
- Use hardware-accelerated drawing for animations; avoid `clipToBounds`/`clipChildren` unnecessarily

### Startup performance
- Target cold start under 1 second to first meaningful content
- Defer initialization of non-essential services (analytics, sync, feature flags) until after first frame
- Use a splash screen that transitions seamlessly into the app content (not a blank white screen)
- Measure startup with `reportFullyDrawn()` (Android) or MetricKit (iOS)
- Lazy-load modules and features that the user has not navigated to

### Network efficiency
- Batch API calls where possible to reduce round trips
- Use HTTP caching headers (`ETag`, `Cache-Control`) to avoid re-downloading unchanged data
- Compress request and response bodies (gzip/brotli)
- Implement pagination for list data (cursor-based preferred over offset-based)
- Cancel in-flight requests when the user navigates away from the screen

### Background work
- Use platform work schedulers: `WorkManager` (Android), `BGTaskScheduler` (iOS)
- Batch background work to minimize CPU wake-ups and battery drain
- Avoid polling; prefer push notifications or server-sent events as triggers
- Respect battery saver and low-power modes -- reduce background activity

## Gotchas / Anti-patterns
- Loading a 4000x3000 image for a 100x100 thumbnail -- out-of-memory crash
- Parsing JSON on the main thread -- frame drops and jank
- Creating new objects in `onBindViewHolder` / `cellForRowAt` -- GC pressure during scrolling
- Not canceling image loads for recycled views -- wrong image flashes in recycled cells
- Deeply nested view hierarchies (10+ levels) -- expensive measure/layout passes
- Eager initialization of everything at startup -- slow launch, wasted resources
- Not testing on low-end devices -- works on flagship, crashes on budget phones

## References
- Android Performance Tips: https://developer.android.com/topic/performance
- iOS Performance Overview: https://developer.apple.com/documentation/xcode/improving-your-app-s-performance
- RecyclerView Best Practices: https://developer.android.com/develop/ui/views/layout/recyclerview
- Coil (Android image loading): https://coil-kt.github.io/coil/
- LeakCanary: https://square.github.io/leakcanary/
