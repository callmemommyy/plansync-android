package com.plansync.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles two FCM jobs:
 *
 *  1. onNewToken()       — called when FCM rotates the device token.
 *                          Sends the new token to the web app via JS so it
 *                          can re-save it to Firestore.
 *
 *  2. onMessageReceived() — called when a push arrives while the app is in
 *                           the FOREGROUND. (Background/killed messages are
 *                           shown automatically by the FCM SDK using the
 *                           notification payload + channel metadata.)
 *                           We build a proper notification with a deep link
 *                           so tapping it opens the right event page.
 */
public class PlanSyncFirebaseService extends FirebaseMessagingService {

    private static final String TAG          = "PlanSyncFCM";
    static final String CHANNEL_ID           = "plansync_events";
    private static final String APP_URL      = "https://plansyncapk.vercel.app";
    // Shared counter so multiple notifications don't overwrite each other
    static final AtomicInteger NOTIF_ID      = new AtomicInteger(1000);

    // ── Token refresh ─────────────────────────────────────────────────────────
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token.substring(0, 8) + "…");

        // Post a message to the WebView so the web app re-registers the token
        // in Firestore. MainActivity listens for this and calls the JS bridge.
        MainActivity.postTokenRefresh(token);
    }

    // ── Foreground message ────────────────────────────────────────────────────
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        RemoteMessage.Notification notif = message.getNotification();
        Map<String, String> data         = message.getData();

        String title   = notif != null ? notif.getTitle() : data.getOrDefault("title",   "PlanSync");
        String body    = notif != null ? notif.getBody()  : data.getOrDefault("body",    "");
        String eventId = data.getOrDefault("eventId", null);
        String type    = data.getOrDefault("type",    "");

        showNotification(title, body, eventId, type);
    }

    // ── Build + show a notification ───────────────────────────────────────────
    void showNotification(String title, String body, String eventId, String type) {
        ensureChannel();

        // Deep-link intent: tapping opens the event page in the WebView
        String deepUrl = (eventId != null && !eventId.isEmpty())
                ? APP_URL + "/events/" + eventId
                : APP_URL;

        Intent tapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(deepUrl));
        tapIntent.setClass(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, NOTIF_ID.get(), tapIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setColor(0xFF6366F1)       // indigo — matches the app brand
                .setVibrate(new long[]{0, 200, 100, 200});

        // Group same-event notifications so they collapse in the shade
        if (eventId != null && !eventId.isEmpty()) {
            builder.setGroup("event_" + eventId);
        }

        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        try {
            nm.notify(NOTIF_ID.getAndIncrement(), builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS was denied — nothing to do
            Log.w(TAG, "POST_NOTIFICATIONS permission denied");
        }
    }

    // ── Create notification channel (Android 8+ requirement) ─────────────────
    static void ensureChannel(android.content.Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Event updates",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Plan changes, votes, and confirmed dates");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 200, 100, 200});
        channel.setShowBadge(true);

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void ensureChannel() { ensureChannel(this); }
}
