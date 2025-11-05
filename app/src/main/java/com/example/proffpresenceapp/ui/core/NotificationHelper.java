package com.example.proffpresenceapp.ui.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.example.proffpresenceapp.R;
import com.example.proffpresenceapp.ui.FaceRecognitionActivity;

/**
 * Centralized notifications utilities.
 */
public final class NotificationHelper {

    public static final String CHANNEL_ID = "pp_general";
    private static final String CHANNEL_NAME = "ProfessorPresence";

    private NotificationHelper() { }

    /** Safe to call repeatedly; creates channel on Android 8.0+. */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch =
                new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
        ch.enableLights(true);
        ch.setLightColor(Color.MAGENTA);
        ch.enableVibration(true);
        ch.setDescription("General app notifications");
        nm.createNotificationChannel(ch);
    }

    /** Simple “toast-style” notification. */
    public static void showSimple(Context ctx, String title, String message, int notifyId) {
        ensureChannel(ctx);
        int icon = getSmallIcon(ctx);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifyId, n);
    }

    /**
     * Actionable notification that opens FaceRecognitionActivity when tapped.
     * Use from GeofenceReceiver on ENTER/DWELL.
     */
    public static void notifyVerifyFace(Context ctx, String subtitle, int notifyId) {
        ensureChannel(ctx);
        int icon = getSmallIcon(ctx);

        Intent intent = new Intent(ctx, FaceRecognitionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        // If you need mutable on Android 12+, swap to FLAG_MUTABLE.

        PendingIntent pi = PendingIntent.getActivity(ctx, 2001, intent, flags);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Verify your presence")
                .setContentText(subtitle == null ? "Tap to open camera" : subtitle)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(subtitle == null ? "Tap to open camera" : subtitle))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifyId, n);
    }

    /* ---------- Backward-compatibility wrapper ---------- */

    /** Legacy name used elsewhere; forwards to notifyVerifyFace(...). */
    @Deprecated
    public static void notifyFaceVerification(Context ctx) {
        notifyVerifyFace(ctx, "Tap to verify your presence", 2001);
    }

    /* ---------- helpers ---------- */

    /** Falls back to app icon if you don’t have a dedicated status icon. */
    private static int getSmallIcon(Context ctx) {
        // If you have a monochrome status icon, replace with your own, e.g. R.drawable.ic_stat_name
        int appIcon = ctx.getApplicationInfo().icon;
        return appIcon != 0 ? appIcon : android.R.drawable.stat_sys_warning;
    }
}
