# Auto-Update

## When to use
- Desktop application is distributed outside a package manager (direct download, installer)
- Users need security patches and bug fixes without manual intervention
- Application must balance update urgency with user autonomy and workflow interruption
- Rollback capability is needed for broken updates

## Pattern

### Update checking
- Check for updates on application launch and periodically (every 4-24 hours)
- Check against a well-known HTTPS endpoint that returns the latest version metadata
- Version metadata includes: version string, release notes URL, download URL, checksum, minimum OS version
- Run the check in the background; never block application startup
- Respect metered connections -- skip or defer checks on metered networks

### User consent model
- **Notify only**: inform the user, let them decide when to download and install
- **Download automatically, install on next launch**: good default for most apps
- **Forced update**: reserved for critical security patches; explain why it cannot be deferred
- Always provide a Preferences setting for update behavior (auto/manual/off)
- Show release notes before asking the user to install -- informed consent

### Differential updates
- Compute binary diffs between versions; download only the delta, not the full binary
- Fall back to full download if the delta fails or the base version is too old
- Validate the patched binary checksum before replacing the running binary
- Delta updates reduce bandwidth by 60-90% for typical releases

### Download and install flow
1. Download update to a temporary staging directory
2. Verify checksum (SHA-256) and signature (code signing certificate or Sigstore)
3. Prompt the user to restart (or schedule install for next launch)
4. On restart, replace the old binary/app bundle with the staged update
5. Launch the new version; run any database migrations or config upgrades
6. Clean up the staging directory

### Rollback
- Keep the previous version alongside the new version until the new version runs successfully
- Provide a "Revert to previous version" option in Preferences or the tray menu
- Auto-rollback if the new version crashes on startup (detect via crash-on-launch counter)
- Store rollback state in a metadata file alongside the application

### Platform specifics
- **macOS**: Sparkle framework is the de facto standard; uses appcast XML feed, supports DSA/EdDSA signatures
- **Windows**: custom updater or frameworks like Squirrel.Windows; must handle UAC elevation for Program Files installs
- **Linux**: prefer system package managers (apt, flatpak) over custom auto-update
- **Flatpak/Snap**: updates are managed by the platform; do not implement custom auto-update

### Security
- All update traffic must use HTTPS with certificate pinning or at minimum standard TLS verification
- Sign update payloads with a key separate from the TLS certificate
- Verify signatures before applying any update -- compromised CDN should not compromise users
- Pin the update server hostname in the application binary

## Gotchas / Anti-patterns
- Checking for updates synchronously on startup -- delays launch, annoys users
- Forcing updates without explanation -- erodes trust
- Not signing update payloads -- man-in-the-middle can distribute malware
- Downloading updates over plain HTTP -- trivially interceptable
- No rollback mechanism -- a broken update bricks the application until manual reinstall
- Implementing custom auto-update on Linux when Flatpak/Snap/apt handle it natively
- Restarting the application without saving user work first

## References
- Sparkle (macOS): https://sparkle-project.org/
- Squirrel.Windows: https://github.com/Squirrel/Squirrel.Windows
- Tauri Updater: https://v2.tauri.app/plugin/updater/
- The Update Framework (TUF): https://theupdateframework.io/
- Sigstore: https://docs.sigstore.dev/
