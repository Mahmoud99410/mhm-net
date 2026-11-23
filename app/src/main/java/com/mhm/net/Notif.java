package com.mhm.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class Notif {
    private Notif() {}

    public static final String CH_MONITOR = "monitor";
    public static final String CH_FIREWALL = "firewall";
    public static final String CH_ALERT = "alert";

    public static final int ID_MONITOR = 1, ID_FIREWALL = 2, ID_ALERT = 3;

    public static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_MONITOR, "مراقبة الشبكة والسرعة", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_FIREWALL, "حجب التطبيقات", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "تنبيهات الشبكة", NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static PendingIntent openApp(Context c, int tab) {
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra("tab", tab);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, tab, i, flags);
    }

    @SuppressWarnings("deprecation")
    public static Notification build(Context c, String channel, String title, String text, PendingIntent pi, boolean ongoing) {
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, channel)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(!ongoing)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(ongoing ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
        }
        if (!ongoing) b.setAutoCancel(true);
        return b.build();
    }

    public static void alert(Context c, String title, String text, PendingIntent pi) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID_ALERT, build(c, CH_ALERT, title, text, pi, false));
    }
}
