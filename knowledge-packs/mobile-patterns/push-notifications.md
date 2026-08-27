# Push Notifications

## When to use
- App must deliver time-sensitive information when the user is not actively using it
- Real-time updates (messages, alerts, status changes) improve the user experience
- Silent data sync must be triggered by the server without user-visible notification
- Notification behavior must be categorized and user-controllable

## Pattern

### Platform services
- **Android**: Firebase Cloud Messaging (FCM); sends through Google Play Services
- **iOS**: Apple Push Notification service (APNs); requires an APNs certificate or key
- **Cross-platform**: use FCM as a unified gateway; FCM forwards to APNs for iOS
- Register for push on app startup; obtain a device token and send it to your server
- Re-register on every app launch -- tokens can change (OS update, reinstall, token refresh)

### Permission handling
- **iOS**: must explicitly request notification permission (`UNUserNotificationCenter.requestAuthorization`)
- **Android 13+**: must request `POST_NOTIFICATIONS` runtime permission
- **Android 12 and below**: notifications are allowed by default
- Ask for permission at a contextually meaningful moment, not on first launch
- Show a pre-permission prompt explaining the value before triggering the OS dialog
- Gracefully degrade if permission is denied: offer in-app notification alternatives

### Notification channels (Android)
- Create channels for distinct notification categories: messages, updates, alerts, promotions
- Each channel has a name, description, importance level, sound, and vibration pattern
- Users can independently mute or configure each channel in system settings
- Do not create excessive channels (10+); group related notifications
- Channels cannot be modified after creation on the user's device -- plan names carefully

### Notification content
- Keep the title short (under 50 characters); put detail in the body
- Use the `data` payload for structured information; use the `notification` payload for display
- Include a `click_action` or `category` that routes to the correct screen on tap
- On iOS, set `badge` count to reflect unread items; clear it when the user opens the app
- On Android, use `BigTextStyle`, `InboxStyle`, or `MessagingStyle` for rich notification layouts

### Silent push notifications
- Use data-only payloads (no `notification` key) to trigger background work without showing a notification
- On iOS, set `content-available: 1` in the APNs payload for background fetch
- On Android, FCM data messages are handled in `onMessageReceived` even if the app is backgrounded
- Keep background processing under 30 seconds (iOS) or 10 seconds (Android) to avoid being killed
- Use silent push to trigger a sync, then show a local notification with fresh data if needed

### Grouping and summarization
- Group related notifications (e.g., multiple messages from the same conversation)
- On Android, use `setGroup()` and provide a summary notification for the group
- On iOS, use `threadIdentifier` to group notifications in the notification center
- When many notifications arrive, show a summary: "5 new messages from 2 conversations"

### Delivery reliability
- Push delivery is best-effort; do not rely on it for critical workflows
- Implement a fallback: poll the server on app foreground to catch missed notifications
- Use `collapse_key` (FCM) or `apns-collapse-id` (APNs) to replace outdated notifications
- Set TTL (time to live) on messages: ephemeral updates (0-60s), important alerts (hours-days)
- Monitor delivery rates and token churn in server-side analytics

### User control
- Provide in-app notification preferences that map to notification channels/categories
- Allow users to choose: all notifications, important only, or none
- Sync preferences to the server to avoid sending unwanted notifications
- Include an "unsubscribe" deep link in non-critical notification categories

## Gotchas / Anti-patterns
- Requesting notification permission on first launch with no context -- high denial rate
- Sending promotional notifications too frequently -- users disable or uninstall
- Using notification payload instead of data payload for background processing -- silent push fails
- Not handling token refresh -- server sends to stale tokens, notifications stop arriving
- Ignoring notification channels on Android -- all notifications land in a single bucket
- Not clearing the iOS badge count -- stale badge misleads users

## References
- Firebase Cloud Messaging: https://firebase.google.com/docs/cloud-messaging
- APNs Overview: https://developer.apple.com/documentation/usernotifications
- Android Notification Channels: https://developer.android.com/develop/ui/views/notifications/channels
- iOS Notification Best Practices: https://developer.apple.com/documentation/usernotifications/handling-notifications-and-notification-related-actions
