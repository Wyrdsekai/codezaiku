# Window Management

## When to use
- Application has a primary workspace with auxiliary panels or dialogs
- Multiple document or multi-instance workflows are needed
- Application must behave correctly across different display configurations (multi-monitor, HiDPI)
- Tray-resident or background apps need careful window lifecycle management

## Pattern

### Main window
- Persist window position, size, and maximized state across sessions
- Restore to the last known position on launch; validate that the position is still on a visible screen
- Define a sensible minimum size that prevents layout breakage
- Handle DPI changes dynamically (monitor switch, scaling change) -- relayout without restart

### Dialogs
- Modal dialogs block interaction with the parent window; use for actions requiring immediate resolution
- Modeless dialogs float above the main window but allow continued interaction
- Center dialogs over the parent window, not the screen center
- Ensure dialogs are not larger than the parent window on small screens
- Every dialog must have a clear dismiss path: close button, Escape key, or Cancel action

### Multi-window
- Each window should be independently functional (not just a panel ripped out)
- Share application state via a central model, not by passing data between windows
- Closing the last window should prompt for unsaved changes, then exit the application
- On macOS, closing all windows keeps the app running (menu bar app convention)

### Window types
- **Document window**: one per file or workspace, standard title bar with close/minimize/maximize
- **Tool palette**: small floating window, always on top of document windows, no taskbar entry
- **Inspector/properties**: side panel or floating window showing details of the current selection
- **Splash screen**: shown during loading, no title bar, auto-dismissed, not focusable

### Multi-monitor support
- Detect available monitors and their geometries at startup and on configuration change
- Open new windows on the same monitor as the triggering action
- Do not assume a fixed DPI -- each monitor may have a different scale factor
- Test with mixed-DPI setups (e.g., 1x external + 2x laptop)

### Keyboard shortcuts
- Bind `Ctrl+W` (Cmd+W on macOS) to close the current window
- Bind `Ctrl+N` (Cmd+N) to open a new window
- Bind `Ctrl+Q` (Cmd+Q) to quit the application
- Follow platform conventions for minimize, maximize, and full-screen toggles

## Gotchas / Anti-patterns
- Restoring a window to a monitor that no longer exists (undocked laptop) -- window is invisible
- Not handling DPI changes -- text and images render blurry or at wrong size
- Modal dialogs that block the entire application instead of just the parent window
- Opening windows at (0,0) or screen center without considering multi-monitor layout
- Allowing windows to be resized smaller than the minimum usable layout
- Forgetting macOS convention: closing all windows should not quit the application

## References
- macOS Human Interface Guidelines (Windows): https://developer.apple.com/design/human-interface-guidelines/windows
- Windows App Design (Window Management): https://learn.microsoft.com/en-us/windows/apps/design/layout/
- GNOME Human Interface Guidelines: https://developer.gnome.org/hig/
- Qt Window Management: https://doc.qt.io/qt-6/application-windows.html
