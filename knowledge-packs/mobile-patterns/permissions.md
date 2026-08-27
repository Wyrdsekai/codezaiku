# Permissions

## When to use
- App accesses sensitive device capabilities (camera, location, contacts, microphone, storage)
- Runtime permission requests must be timed and explained to maximize grant rate
- App must function gracefully when permissions are denied
- Platform permission models differ between Android and iOS

## Pattern

### Request timing
- Request permissions at the moment of use, not at app launch
- Trigger the request from a user action that makes the need obvious (tap "Take Photo" then request camera)
- Never batch-request all permissions on first launch -- high denial rate, poor user experience
- If permission is essential for a feature, explain before requesting; if optional, handle denial silently

### Rationale dialogs (pre-permission prompts)
- Show a custom in-app dialog explaining why the permission is needed before the OS dialog
- Good: "We need camera access to scan barcodes. You can change this later in Settings."
- Bad: "Please allow all permissions for the best experience."
- On Android, check `shouldShowRequestPermissionRationale()` to detect if the user previously denied
- On iOS, you only get one OS prompt per permission per install -- make it count

### Permission request flow
1. Check if permission is already granted
2. If not, show rationale dialog (if appropriate)
3. Request via OS API (`requestPermissions` on Android, `requestAuthorization` on iOS)
4. Handle the result: granted, denied, or permanently denied (Android "Don't ask again")
5. If denied, gracefully degrade; if permanently denied, direct user to Settings

### Graceful degradation
- If camera is denied: offer file upload or gallery pick as an alternative
- If location is denied: let the user type an address manually
- If notifications are denied: show in-app notifications or a banner instead
- If storage is denied: explain what functionality is limited and why
- Never crash, show a blank screen, or display a raw error on permission denial

### Platform specifics: Android
- Declare permissions in `AndroidManifest.xml`; request dangerous permissions at runtime
- Permission groups: granting one permission in a group may auto-grant others (varies by OS version)
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` replaced `READ_EXTERNAL_STORAGE` on Android 13+
- Photo Picker (`ACTION_PICK_IMAGES`) requires no permission at all -- prefer it over storage access
- Background location (`ACCESS_BACKGROUND_LOCATION`) must be requested separately after foreground location is granted
- On Android 14+, partial photo/video access is available

### Platform specifics: iOS
- Permissions defined in `Info.plist` usage descriptions (mandatory; rejection if missing)
- Provide clear, honest `NSCameraUsageDescription`, `NSLocationWhenInUseUsageDescription`, etc.
- iOS distinguishes "When In Use" vs "Always" for location; request "When In Use" first
- Limited Photos access (iOS 14+): user can grant access to selected photos only
- Tracking permission (`ATTrackingManager`): required before accessing IDFA on iOS 14.5+

### Permission auditing
- Regularly review which permissions the app requests; remove any that are no longer used
- Minimizing permissions increases user trust and reduces Store review friction
- Document internally why each permission is needed and which feature depends on it
- On Android, use the `PermissionController` to audit granted permissions at runtime

### Edge cases
- Permission revoked while app is in background: recheck on `onResume` / `viewWillAppear`
- OS upgrade changes permission model: test the app on each new major OS release
- Enterprise/MDM: device administrator may pre-grant or permanently deny permissions
- Rooted/jailbroken devices: permissions may be bypassed; do not rely on permissions for security-critical checks

## Gotchas / Anti-patterns
- Requesting all permissions at launch -- users deny everything out of suspicion
- Not providing a rationale -- users deny because they do not understand the need
- Crashing or showing an empty screen when a permission is denied
- Requesting "Always" location when "When In Use" suffices -- App Store/Play Store rejection risk
- Not re-checking permissions on resume -- permission may have been revoked in Settings
- Using deprecated storage permissions on Android 13+ instead of the media-specific permissions

## References
- Android Permissions Guide: https://developer.android.com/guide/topics/permissions/overview
- iOS Privacy and Permissions: https://developer.apple.com/documentation/uikit/protecting-the-user-s-privacy
- Material Design Permission Patterns: https://m3.material.io/foundations/overview
- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photopicker
