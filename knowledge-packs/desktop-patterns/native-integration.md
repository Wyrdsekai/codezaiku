# Native Integration

## When to use
- Application must feel like a first-class citizen on each target OS
- Features depend on OS services: notifications, clipboard, file associations, deep links
- Users expect system-level behaviors like copy/paste, keyboard shortcuts, and accessibility
- Application needs to integrate with OS-level workflows (share sheets, services menus)

## Pattern

### OS notifications
- Use the native notification API: `NSUserNotification` / `UNUserNotificationCenter` (macOS), `ToastNotification` (Windows), `libnotify` / `org.freedesktop.Notifications` (Linux)
- Set the application name and icon so notifications are correctly attributed
- Support notification actions (buttons) where the OS allows them
- Handle notification click: bring the relevant window/context to the foreground
- On macOS 13+, request notification permission explicitly (`UNUserNotificationCenter.requestAuthorization`)
- Respect system DND/Focus modes -- the OS will suppress delivery automatically

### Clipboard
- Support copy, cut, and paste for text, rich text, images, and custom MIME types
- Use standard keyboard shortcuts: `Ctrl+C/V/X` (Windows/Linux), `Cmd+C/V/X` (macOS)
- When copying structured data, put both plain text and rich format on the clipboard simultaneously
- Do not clear or overwrite the clipboard without explicit user action
- For sensitive data (passwords), optionally set a clipboard expiry (macOS `NSPasteboard` supports concealed content)

### File associations
- Register file type associations during installation (not at runtime)
- Associate by file extension and MIME type
- Provide a document icon for each associated file type
- Handle the "open file" event on application launch and while already running
- On macOS, implement `application:openFile:` or `application:openURLs:`
- On Windows, register in the Windows Registry under `HKEY_CLASSES_ROOT`
- On Linux, provide a `.desktop` file with `MimeType=` entries

### Deep links / URL schemes
- Register a custom URL scheme: `myapp://action/param`
- Validate and sanitize all URL parameters -- deep links are untrusted input
- If the app is not running, launch it and handle the URL on startup
- If the app is running, bring it to the foreground and navigate to the target
- On macOS, register in `Info.plist` under `CFBundleURLTypes`
- On Windows, register under `HKEY_CLASSES_ROOT\myapp`
- On Linux, register in the `.desktop` file with `x-scheme-handler/myapp`

### System theme
- Detect light/dark mode on startup and on change
- Adapt colors, icons, and assets to match the system theme
- On macOS: observe `effectiveAppearance` changes
- On Windows: read `AppsUseLightTheme` registry key and listen for `WM_SETTINGCHANGE`
- On Linux: query `org.freedesktop.portal.Settings` for `color-scheme`

### Accessibility
- Expose all UI elements to the platform accessibility tree
- Label interactive elements with accessible names and roles
- Support keyboard navigation for all features (no mouse-only interactions)
- Test with VoiceOver (macOS), Narrator (Windows), Orca (Linux)
- Respect system font size / zoom settings

### Platform-specific integration points
- **macOS**: Services menu, Touch Bar (legacy), Handoff, Shortcuts app integration
- **Windows**: Jump Lists, taskbar progress bar, Windows Hello for authentication
- **Linux**: D-Bus services, XDG portals for sandboxed apps (Flatpak/Snap)

## Gotchas / Anti-patterns
- Custom notification UI instead of OS notifications -- missed DND, no grouping, no action center
- Implementing a custom clipboard instead of using the OS clipboard -- breaks system paste
- Registering file associations at runtime rather than install time -- requires elevated privileges
- Not sanitizing deep link parameters -- injection and path traversal risks
- Hardcoding light theme only -- unusable in dark mode
- Ignoring accessibility -- excludes users with disabilities and fails compliance requirements

## References
- macOS Human Interface Guidelines (Technologies): https://developer.apple.com/design/human-interface-guidelines/technologies
- Windows App Integration: https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/
- freedesktop.org Desktop Entry Spec: https://specifications.freedesktop.org/desktop-entry-spec/latest/
- XDG Desktop Portal: https://flatpak.github.io/xdg-desktop-portal/
