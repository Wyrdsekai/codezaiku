# File Operations

## When to use
- Application creates, opens, saves, or manages files on the local filesystem
- Users drag files into the application from a file manager
- Application must watch for external changes to open files
- Recent file history improves the user's workflow

## Pattern

### Open and save dialogs
- Use OS-native file dialogs, not custom implementations -- users expect familiar behavior
- Set sensible default directories (last used directory, project root, or Documents)
- Pre-populate file type filters: `["YAML files (*.yaml, *.yml)", "All files (*)"]`
- Remember the last used directory per dialog type (open vs save, per file type)
- For "Save As", pre-fill the current filename in the dialog

### Atomic saves
- Write to a temporary file in the same directory, then rename to the target path
- This prevents data loss if the process crashes mid-write
- Preserve file permissions and ownership on the rename
- On Windows, use `MoveFileEx` with `REPLACE_EXISTING` for atomic replace

### File watching
- Watch open files for external modifications (editor, git checkout, sync tools)
- On change detection, prompt the user: "File has been modified externally. Reload?"
- Debounce file system events -- editors often trigger multiple writes for a single save
- Use OS-native APIs: `inotify` (Linux), `FSEvents` (macOS), `ReadDirectoryChangesW` (Windows)
- Handle file deletion gracefully: mark the buffer as unsaved with the original content

### Drag and drop
- Accept file drops on the main window or specific drop zones
- Show a visual indicator when a valid file is dragged over the drop zone
- Validate file type on drop; show a clear error for unsupported types
- Support dropping multiple files for batch operations
- On macOS, also support dropping from Finder's proxy icon in the title bar

### Recent files
- Maintain a list of recently opened files (10-20 items)
- Store as absolute paths with a last-opened timestamp
- Remove entries for files that no longer exist (check on menu display, not startup)
- Show the filename with a disambiguating parent directory if names collide
- On macOS, integrate with `NSDocumentController` recent documents; on Windows, with Jump Lists

### Unsaved changes
- Track dirty state per document/file
- Show a visual indicator for unsaved changes (dot in title bar, asterisk in tab)
- On close, prompt: "Save changes to <filename>?" with Save, Don't Save, Cancel
- On application quit, iterate all dirty documents and prompt for each (or "Save All")
- Periodically auto-save to a recovery file (separate from the original) for crash recovery

### File locking
- Use advisory locks (`flock` on Unix, `LockFileEx` on Windows) for files that must not be edited concurrently
- Show a clear message if the file is locked by another process
- Release locks promptly when the file is closed
- Do not hold locks across the entire application lifetime -- only when actively writing

## Gotchas / Anti-patterns
- Writing directly to the target file instead of using atomic save -- data loss on crash
- Custom file dialogs that lack OS features (favorites, network drives, recent places)
- Not debouncing file watch events -- dialog spam on every micro-write
- Storing recent files as relative paths -- breaks when the working directory changes
- Ignoring file permission errors -- "save failed" with no explanation
- Locking files and forgetting to release on crash -- requires manual cleanup

## References
- macOS File System Programming Guide: https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/
- Windows File Management: https://learn.microsoft.com/en-us/windows/win32/fileio/file-management
- inotify(7) Linux manual: https://man7.org/linux/man-pages/man7/inotify.7.html
- fsnotify (Go): https://github.com/fsnotify/fsnotify
