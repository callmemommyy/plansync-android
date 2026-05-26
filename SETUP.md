# PlanSync Android — Setup

## Prerequisites
- Android Studio Hedgehog or newer
- Firebase project (same one used by the web app)

---

## 1. Add google-services.json  ← REQUIRED before building

1. Open [Firebase Console](https://console.firebase.google.com) → your project
2. Project Settings → Your apps → Add app (Android)
   - Package name: `com.plansync.app`
   - Download **google-services.json**
3. Place the file at:  `app/google-services.json`

Without this file, the build will fail with:
`File google-services.json is missing from module root folder`

---

## 2. Enable Cloud Messaging in Firebase Console

1. Firebase Console → Project Settings → Cloud Messaging
2. Make sure Firebase Cloud Messaging API (V1) is **enabled**
3. Copy the **Server key** — you'll need it for the Cloud Functions

---

## 3. Build

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (needs KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD env vars)
./gradlew assembleRelease
```

---

## 4. How notifications work

```
Cloud Function (Firestore trigger)
  → Firebase Cloud Messaging
      → App in background: PlanSyncFirebaseService shows a system notification
        with a deep link tap target → opens the event page directly
      → App in foreground: FCM calls onMessageReceived() → shows notification
        AND posts JS event to WebView so the in-app toast fires too
      → App killed: FCM SDK shows notification automatically via notification payload
```

### Token flow
1. App starts → `FirebaseMessaging.getToken()` → cached in `MainActivity.latestFcmToken`
2. `NotificationBridge.getFcmToken()` exposes it to JS
3. Web shim (`notification-webview-shim.ts`) picks it up and saves to Firestore
4. Token rotates → `PlanSyncFirebaseService.onNewToken()` → calls `MainActivity.postTokenRefresh()`
   → injects `window.onFcmTokenRefreshed(token)` into WebView → web app re-saves to Firestore

---

## Files changed from original

| File | What changed |
|---|---|
| `app/build.gradle` | Added `firebase-bom`, `firebase-messaging`, `firebase-analytics`, `google-services` plugin |
| `build.gradle` | Added `google-services` classpath |
| `AndroidManifest.xml` | Added `POST_NOTIFICATIONS` permission, FCM service, channel + icon metadata |
| `MainActivity.java` | Added `NotificationBridge`, permission request + result handler, deep link navigation, token fetch, `postTokenRefresh()` static helper |
| `NotificationBridge.java` | **New** — JS interface for `window.NotificationBridge` |
| `PlanSyncFirebaseService.java` | **New** — FCM token refresh + foreground notification builder + channel setup |
