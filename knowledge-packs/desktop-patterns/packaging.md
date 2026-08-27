# Packaging

## When to use
- Desktop application must be distributed to end users on macOS, Windows, and Linux
- Application requires installation (not just a portable binary)
- Code signing and notarization are required for distribution outside app stores
- Users expect native install/uninstall experiences on their platform

## Pattern

### macOS: DMG and app bundle
- Package as a `.app` bundle inside a `.dmg` disk image
- DMG should contain the app icon and a symlink to `/Applications` for drag-to-install
- Sign the app bundle with a Developer ID certificate using `codesign`
- Notarize with Apple's notary service (`notarytool`) -- unsigned apps are blocked by Gatekeeper
- Include an `Info.plist` with `CFBundleIdentifier`, `CFBundleVersion`, file associations, and URL schemes
- Hardened runtime must be enabled for notarization
- Alternative: `.pkg` installer for apps that need to install helpers, daemons, or system extensions

### Windows: MSI and MSIX
- **MSI**: traditional installer; use WiX Toolset to author; supports silent install (`msiexec /quiet`)
- **MSIX**: modern packaging format; supports auto-update, sandboxing, and Store distribution
- Sign the installer with an Authenticode certificate (EV cert avoids SmartScreen warnings)
- Register file associations, URL schemes, and Start Menu entries during installation
- Include an uninstaller that cleanly removes all files, registry entries, and shortcuts
- For portable distribution, also offer a `.zip` with a standalone `.exe`

### Linux: DEB, RPM, AppImage, Flatpak
- **DEB** (Debian/Ubuntu): define control file with dependencies, maintainer scripts, man pages
- **RPM** (Fedora/RHEL): author a `.spec` file; build with `rpmbuild` or `mock`
- **AppImage**: single file, no installation, runs on most distros; bundle all dependencies
- **Flatpak**: sandboxed, uses portals for system access; publish to Flathub for discoverability
- **Snap**: Canonical's sandboxed format; auto-updates; define a `snapcraft.yaml`
- Prefer Flatpak or DEB/RPM over AppImage for apps that need system integration (D-Bus, file associations)
- Always provide a `.desktop` file and an icon at multiple sizes (48x48, 128x128, 256x256, scalable SVG)

### Code signing
- **macOS**: Developer ID certificate from Apple; `codesign --deep --options runtime`
- **Windows**: Authenticode certificate; EV certificates reduce SmartScreen friction
- **Linux**: GPG-sign packages; DEB uses `dpkg-sig`, RPM uses `rpmsign`
- Automate signing in CI/CD; store signing keys in a hardware security module (HSM) or secret manager
- Never distribute unsigned binaries for production use

### Notarization (macOS)
- Submit the signed `.app` or `.dmg` to Apple's notary service via `xcrun notarytool submit`
- Wait for the notarization ticket (usually 1-5 minutes)
- Staple the ticket to the artifact: `xcrun stapler staple MyApp.dmg`
- CI/CD should fail the pipeline if notarization is rejected
- Common rejection causes: unsigned frameworks, missing hardened runtime, embedded forbidden entitlements

### Build automation
- Use a single CI/CD pipeline that builds, signs, notarizes, and publishes for all platforms
- Pin toolchain versions (Xcode, WiX, rpmbuild) for reproducible builds
- Produce checksums (SHA-256) for all artifacts alongside the binaries
- Publish artifacts to GitHub Releases, a CDN, or a package repository
- Tag releases with semantic versions; maintain a changelog

### Uninstallation
- Remove all application files, caches, and configuration on uninstall
- On macOS, provide an uninstall script or document manual removal (no native uninstaller)
- On Windows, register with Add/Remove Programs; uninstaller removes registry entries
- On Linux, package managers handle uninstall; clean up `/var`, `/etc`, and `~/.config` entries

## Gotchas / Anti-patterns
- Distributing unsigned macOS apps -- Gatekeeper blocks them; users must override in System Settings
- Using EV cert for macOS (not applicable) or non-EV for Windows (triggers SmartScreen warnings)
- AppImage without bundled dependencies -- breaks on distros with different library versions
- Not stapling the notarization ticket -- offline users cannot verify notarization
- Leaving orphan files after uninstall (caches, logs, config) -- annoys power users
- Building Linux packages only for one distro family -- misses half the audience

## References
- Apple Notarization: https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
- WiX Toolset (Windows): https://wixtoolset.org/
- Flatpak Documentation: https://docs.flatpak.org/
- AppImage Documentation: https://docs.appimage.org/
- Electron Forge (cross-platform): https://www.electronforge.io/
- Tauri (cross-platform): https://v2.tauri.app/distribute/
