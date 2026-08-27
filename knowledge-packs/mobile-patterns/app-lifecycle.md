# App Lifecycle

## When to use
- App must handle transitions between foreground and background without data loss
- State must be preserved across process death (OS-initiated kill for memory reclamation)
- Background tasks must complete reliably within platform time limits
- Battery optimization constraints affect background behavior

## Pattern

### Lifecycle states
- **Cold start**: app process does not exist; full initialization required
- **Warm start**: process exists but Activity/ViewController was destroyed; partial initialization
- **Hot start**: app was in background with UI intact; resume immediately
- **Foreground**: app is visible and interactive
- **Background**: app is not visible; limited CPU time before suspension
- **Suspended**: process is alive but receives no CPU; OS may kill it at any time
- **Terminated**: process is dead; all in-memory state is lost

### State preservation
- Save essential UI state on every transition to background, not just on destruction
- On Android: use `onSaveInstanceState()` for transient UI state (scroll position, form input, selected tab)
- On Android: use `ViewModel` for data that survives configuration changes but not process death
- On Android: use `SavedStateHandle` for data that must survive process death
- On iOS: save state in `applicationDidEnterBackground` or `sceneDidEnterBackground`
- On iOS: use `NSUserActivity` or custom state serialization for scene-based state restoration
- Keep saved state small (under 1MB on Android) -- serialize IDs, not full objects

### Background transitions
- Pause ongoing work gracefully: stop animations, pause media playback, release camera
- Save any pending user input (draft text, unsaved edits) to local storage
- Cancel or pause network requests that are not essential
- Disconnect from real-time channels (WebSocket); reconnect on foreground
- Release large memory allocations (image caches, decoded bitmaps) in response to memory warnings

### Foreground transitions
- Refresh stale data: re-fetch if the background duration exceeds a threshold (e.g., 5 minutes)
- Recheck permissions -- the user may have changed them in Settings while backgrounded
- Reconnect real-time channels (WebSocket, SSE)
- Re-validate authentication state (token expiry)
- Resume paused media, animations, or location tracking

### Background task completion
- **Android**: use `WorkManager` for deferrable tasks; use foreground services for ongoing tasks (music, navigation)
- **iOS**: use `BGTaskScheduler` for background refresh and processing tasks
- **iOS**: request `beginBackgroundTask` for short tasks (up to 30 seconds) when entering background
- Always complete or checkpoint work before the OS time limit -- the process will be killed
- Avoid long-running background work on mobile -- it drains battery and triggers OS throttling

### Process death handling
- The OS can kill a backgrounded app at any time without calling any lifecycle method
- On Android, test with "Don't keep activities" developer option enabled
- On iOS, test by force-killing the app from the app switcher and relaunching
- Assume all in-memory state is lost on cold start; only persisted state survives
- Navigation stack, scroll positions, and form data must be restorable from saved state

### Configuration changes (Android)
- Screen rotation, language change, dark mode toggle, and display size change trigger Activity recreation
- Use `ViewModel` to retain data across configuration changes without re-fetching
- Declare `configChanges` in the manifest only as a last resort (forces manual handling of everything)
- Test all screens with rotation and dark mode toggle

### Battery optimization
- Respect Doze mode (Android): network access, jobs, and alarms are deferred while Doze is active
- Use `setExactAndAllowWhileIdle()` sparingly; most alarms can be inexact
- On iOS, background fetch frequency is determined by the OS based on user behavior patterns
- Avoid wake locks (`PARTIAL_WAKE_LOCK`) unless absolutely necessary (music playback, navigation)
- Monitor battery usage in Settings and respond to user reports of excessive drain

### Multi-window and split-screen
- On Android, the app may be visible but not focused in multi-window mode
- Use `onTopResumedActivityChanged()` to determine if the app is the primary foreground app
- On iPad, support split-screen and Slide Over; test with different size classes
- Do not assume full-screen layout -- adapt to any window size

## Gotchas / Anti-patterns
- Assuming the app will not be killed in the background -- it will, regularly
- Saving state only in `onDestroy` -- the OS may skip `onDestroy` entirely on process kill
- Keeping the camera, GPS, or Bluetooth active in the background -- drains battery, triggers warnings
- Not testing process death -- state loss bugs surface only in production under memory pressure
- Long-running `AsyncTask` or coroutine leaking an Activity reference after the Activity is destroyed
- Ignoring Doze mode -- scheduled network calls silently fail on dozing devices

## References
- Android Activity Lifecycle: https://developer.android.com/guide/components/activities/activity-lifecycle
- iOS App Lifecycle: https://developer.apple.com/documentation/uikit/app-and-environment/managing-your-app-s-life-cycle
- Android Process Death: https://developer.android.com/topic/libraries/architecture/saving-states
- iOS Background Execution: https://developer.apple.com/documentation/backgroundtasks
- Android Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
