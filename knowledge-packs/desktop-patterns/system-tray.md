# System Tray

## When to use
- Application runs persistently in the background (sync agents, monitors, VPN clients)
- User needs quick access to status and common actions without opening the full window
- Notifications need a persistent anchor point in the OS chrome
- Application should remain accessible after the main window is closed

## Pattern

### Tray icon
- Provide a monochrome icon that adapts to light/dark system themes
- Use icon variants to indicate state: normal, active/syncing, error, paused
- Keep the icon simple and recognizable at 16x16, 22x22, and 32x32 pixel sizes
- On macOS, use a template image (monochrome with alpha) for automatic theme adaptation
- On Linux, support both `StatusNotifierItem` (modern) and `XEmbedded` (legacy) protocols

### Tray menu
- Right-click (or single-click on macOS) opens a context menu
- Top item: status line or application name (non-clickable or opens main window)
- Common structure: Status, separator, primary actions, separator, Preferences, Quit
- Keep the menu shallow -- no nested submenus beyond one level
- Include "Show/Hide Window" as the first actionable item
- Double-click on the tray icon should show/focus the main window

### Status communication
- Use icon overlays or badges for at-a-glance status (green checkmark, red dot)
- Include a brief text status in the menu: "Last synced: 2 minutes ago"
- Animate the icon only for transient states (syncing); never animate permanently
- On systems that support tray tooltips, show a concise status on hover

### Notifications from the tray
- Use OS-native notification APIs, not custom popup windows
- Clicking a notification should bring the relevant window/context to the foreground
- Group related notifications to avoid flooding the user
- Respect system Do Not Disturb / Focus mode settings
- Provide a "Mute notifications" option in the tray menu

### Lifecycle
- Closing the main window should minimize to tray, not quit (if the app is tray-resident)
- Clearly communicate this behavior on first close: "Application will continue running in the system tray"
- Provide a Preferences toggle: "Close to tray" vs "Close quits application"
- "Quit" in the tray menu must fully exit the process -- no orphaned background processes

### Platform differences
- **macOS**: Menu bar icon (NSStatusItem); no system tray concept, but behavior is identical
- **Windows**: System tray / notification area; register with Shell_NotifyIcon
- **Linux**: fragmented -- GNOME removed tray by default; extensions (AppIndicator) or TopIcons needed
- **Linux fallback**: if no tray is available, keep the main window open instead of hiding to tray

## Gotchas / Anti-patterns
- Colored icons that clash with or are invisible against the system tray background
- Permanently animating the tray icon -- distracting and wastes CPU
- Quitting the app when the window closes without warning (if tray-resident is expected)
- Not providing a Quit option in the tray menu -- user cannot exit without a task manager
- Assuming a tray is available on Linux -- headless, Wayland, and minimal DEs may lack one
- Showing notifications despite Do Not Disturb being enabled

## References
- macOS NSStatusItem: https://developer.apple.com/documentation/appkit/nsstatusitem
- Windows Shell_NotifyIcon: https://learn.microsoft.com/en-us/windows/win32/api/shellapi/nf-shellapi-shell_notifyiconw
- StatusNotifierItem spec (Linux/freedesktop): https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/
- libappindicator (Linux): https://launchpad.net/libappindicator
