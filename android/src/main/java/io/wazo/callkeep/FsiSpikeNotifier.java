package io.wazo.callkeep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/**
 * SPIKE ONLY — fallback full-screen-intent incoming-call notification used when the
 * Telecom calling account is unavailable (e.g. ColorOS), where displayIncomingCall
 * would otherwise silently no-op. Posts a CallStyle full-screen-intent notification
 * targeting the app's IncomingCallActivity (by class name, since it lives in the app
 * module). Mirrors the app-module FsiSpike: same channel id + notification id so the
 * app's FsiActionReceiver can cancel it. Remove together with the rest of the FSI spike.
 */
class FsiSpikeNotifier {
    private static final String TAG = "FsiSpike";
    private static final String CHANNEL_ID = "fsi_spike_incoming";
    private static final int NOTIF_ID = 770077;
    // Safety net: auto-expire the FSI notification if no explicit cancel/answer/decline
    // arrives — e.g. the remote end_call push is dropped/delayed by Doze or aggressive
    // OEM background-kill (the exact ROM class this fallback targets). Without it, the
    // .setOngoing(true) notification (and the IncomingCallActivity it can launch) lingers
    // indefinitely and resurfaces on unlock. 60s comfortably exceeds a normal ring window
    // so it never cuts off a live ringing call; an answered call cancels the notification
    // explicitly well before this fires.
    private static final long FSI_TIMEOUT_MS = 60_000L;
    private static final String ACTIVITY = "com.hipcall.mobile.IncomingCallActivity";
    private static final String RECEIVER = "com.hipcall.mobile.FsiActionReceiver";

    private static NotificationManager nm(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = nm(context);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        boolean tr = java.util.Locale.getDefault().getLanguage().equals("tr");
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                tr ? "Gelen Çağrılar" : "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(tr ? "Tam ekran gelen çağrı bildirimleri" : "Full-screen incoming call alerts");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(true);
        Uri ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(ringtone, attrs);
        nm.createNotificationChannel(channel);
    }

    static void post(Context context, String uuid, String callerName, String handle) {
        try {
            ensureChannel(context);
            String pkg = context.getPackageName();
            String name = (callerName != null && !callerName.isEmpty())
                    ? callerName : (handle != null ? handle : "Incoming call");
            String number = handle != null ? handle : "";

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            String safeUuid = uuid != null ? uuid : "";

            Intent fsIntent = new Intent();
            fsIntent.setClassName(pkg, ACTIVITY);
            fsIntent.putExtra("caller_name", name);
            fsIntent.putExtra("caller_number", number);
            fsIntent.putExtra("call_uuid", safeUuid);
            fsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent fsPi = PendingIntent.getActivity(context, 1001, fsIntent, piFlags);

            // Answer must launch the Activity DIRECTLY. Routing through a
            // BroadcastReceiver that then calls startActivity() is a "notification
            // trampoline", banned since Android 12 — the activity launch gets
            // BAL_BLOCKED. A direct getActivity PendingIntent from a notification
            // action carries the notification-tap BAL grant and is allowed.
            Intent answerIntent = new Intent();
            answerIntent.setClassName(pkg, "com.hipcall.mobile.MainActivity");
            answerIntent.putExtra("fsi_answer_uuid", safeUuid);
            answerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent answerPi = PendingIntent.getActivity(context, 1002, answerIntent, piFlags);

            PendingIntent declinePi = PendingIntent.getBroadcast(
                    context, 1003, action(pkg, "decline", safeUuid), piFlags);

            int smallIcon = context.getApplicationInfo().icon;
            if (smallIcon == 0) {
                // Fallback so a quirky ROM can never post a blank/failed notification
                // (the exact missed-call failure mode this path exists to fix).
                smallIcon = android.R.drawable.sym_call_incoming;
            }

            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Person person = new Person.Builder().setName(name).build();
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(smallIcon)
                        .setStyle(Notification.CallStyle.forIncomingCall(person, declinePi, answerPi))
                        .setFullScreenIntent(fsPi, true)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setOngoing(true)
                        .setTimeoutAfter(FSI_TIMEOUT_MS)
                        .build();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(smallIcon)
                        .setContentTitle(name)
                        .setContentText(number)
                        .setFullScreenIntent(fsPi, true)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setOngoing(true)
                        .setTimeoutAfter(FSI_TIMEOUT_MS)
                        .addAction(0, "Decline", declinePi)
                        .addAction(0, "Answer", answerPi)
                        .build();
            } else {
                notification = new Notification.Builder(context)
                        .setSmallIcon(smallIcon)
                        .setContentTitle(name)
                        .setContentText(number)
                        .setFullScreenIntent(fsPi, true)
                        .setPriority(Notification.PRIORITY_HIGH)
                        .build();
            }
            nm(context).notify(NOTIF_ID, notification);
            Log.d(TAG, "[fork] Posted fallback FSI incoming-call notification for " + name);
        } catch (Exception e) {
            Log.e(TAG, "[fork] Failed to post fallback FSI notification", e);
        }
    }

    private static Intent action(String pkg, String action, String uuid) {
        Intent intent = new Intent();
        intent.setClassName(pkg, RECEIVER);
        intent.setPackage(pkg);
        intent.putExtra("fsi_action", action);
        intent.putExtra("call_uuid", uuid);
        return intent;
    }

    static void cancel(Context context) {
        try {
            nm(context).cancel(NOTIF_ID);
            // Cancelling the notification does NOT finish the full-screen
            // IncomingCallActivity. On a remote cancel/end push, also broadcast the
            // dismiss action so any showing call screen is torn down (otherwise the
            // ring stops but the FSI screen lingers).
            context.sendBroadcast(
                    new Intent("com.hipcall.mobile.FSI_DISMISS")
                            .setPackage(context.getPackageName()));
            Log.d(TAG, "[fork] Cancelled fallback FSI notification + dismissed call screen");
        } catch (Exception e) {
            Log.e(TAG, "[fork] Failed to cancel FSI notification", e);
        }
    }
}
