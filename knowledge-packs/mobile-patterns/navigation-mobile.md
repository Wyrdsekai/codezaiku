# Navigation (Mobile)

## When to use
- Mobile app has multiple screens or content areas
- App structure requires hierarchical, lateral, or modal navigation
- Deep linking from notifications, URLs, or other apps must land on the correct screen
- Navigation state must survive process death and configuration changes

## Pattern

### Stack navigation
- Push screens onto a stack; back button or swipe-back pops to the previous screen
- Each screen receives parameters (IDs, filters) via navigation arguments, not global state
- Keep the stack shallow (3-5 levels); deep stacks signal a flat hierarchy is more appropriate
- Preserve scroll position and form state when returning to a screen
- On Android, respect the system back button; on iOS, support swipe-from-edge to go back

### Tab navigation
- Use for top-level sections of equal importance (3-5 tabs maximum)
- Place tabs at the bottom of the screen (iOS convention, also common on Android)
- Each tab maintains its own independent navigation stack
- Switching tabs preserves the state of each tab's stack (do not reset)
- Highlight the active tab with a filled icon or color; use outlined icons for inactive tabs
- Badge tabs with unread counts where relevant (notifications, messages)

### Drawer navigation
- Use for apps with many top-level sections (5+) or infrequently accessed settings
- Triggered by a hamburger icon or swipe from the left edge
- Show the current user identity or account at the top of the drawer
- Group menu items by category with section headers
- Avoid nesting within the drawer -- keep it a flat list of destinations

### Modal navigation
- Use for self-contained tasks: compose, create, edit, confirm
- Present modally (slides up from bottom or fades in)
- Provide a clear dismiss action: "Cancel" button, "X" close, or swipe down
- Prevent accidental dismissal if the modal contains unsaved changes (confirm discard)
- Modals should not launch other modals -- keep the flow linear

### Deep linking
- Define a URL scheme (`myapp://`) and universal/app links (`https://myapp.com/path`)
- Map URL paths to specific screens with parameters: `/items/123` opens item detail for ID 123
- Handle deep links on cold start (app not running) and warm start (app in background)
- Validate all parameters from deep links -- they are untrusted external input
- If authentication is required, redirect to login and then navigate to the deep link target
- Register link handlers: iOS `Associated Domains` + `apple-app-site-association`; Android `intent-filter` + `assetlinks.json`

### Navigation state persistence
- Save the navigation stack to local storage on backgrounding or process death
- Restore the full stack on relaunch (not just the top screen)
- Handle invalid saved state gracefully -- fall back to the home screen
- Persist navigation state independently from data state

## Gotchas / Anti-patterns
- More than 5 bottom tabs -- cluttered; consider a drawer instead
- Resetting the tab stack when the user taps an already-selected tab (unless intentional scroll-to-top)
- Not handling deep links during cold start -- user sees home instead of the target screen
- Passing large objects as navigation arguments instead of IDs (leads to serialization issues)
- Blocking navigation with synchronous data fetches -- show a skeleton screen and load async
- Ignoring the Android system back button -- breaks user expectations

## References
- Material Design Navigation: https://m3.material.io/foundations/navigation/overview
- iOS Human Interface Guidelines (Navigation): https://developer.apple.com/design/human-interface-guidelines/navigation-and-search
- Android Navigation Component: https://developer.android.com/guide/navigation
- Universal Links (iOS): https://developer.apple.com/documentation/xcode/supporting-universal-links-in-your-app
- Android App Links: https://developer.android.com/training/app-links
