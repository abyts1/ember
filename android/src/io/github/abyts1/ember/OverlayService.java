package io.github.abyts1.ember;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Fallback host: a foreground service with an app overlay. Used only while the
 * accessibility service (which can also cover system UI) is not enabled.
 */
public class OverlayService extends Service implements Filter.Listener {
    static final String ACTION_TOGGLE_PAUSE = "io.github.abyts1.ember.TOGGLE_PAUSE";
    static final String ACTION_STOP = "io.github.abyts1.ember.STOP";
    static final String ACTION_REFRESH = "io.github.abyts1.ember.REFRESH";
    private static final String CHANNEL = "ember";
    private static final int NOTIF_ID = 1;

    private Filter filter;
    private int curK;
    private boolean paused;

    /** Starts whichever host applies; the accessibility service picks up changes by itself. */
    static void start(Context c) {
        if (EmberAccessibilityService.isRunning()) return;
        c.startForegroundService(new Intent(c, OverlayService.class).setAction(ACTION_REFRESH));
    }

    static void toggle(Context c, boolean pauseOnly) {
        Model m = Model.load(c);
        if (pauseOnly) m.paused = !m.paused;
        else m.enabled = false;
        m.save(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Filter status", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) toggle(this, false);
        else if (ACTION_TOGGLE_PAUSE.equals(action)) toggle(this, true);

        Model m = Model.load(this);
        paused = m.paused;
        if (!m.enabled || !m.hasLocation() || !Settings.canDrawOverlays(this)
                || EmberAccessibilityService.isRunning()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        if (filter == null) filter = new Filter(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this);
        filter.start();
        return START_STICKY;
    }

    @Override
    public void onTemperature(int kelvin, boolean paused) {
        curK = kelvin;
        this.paused = paused;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification());
    }

    private Notification notification() {
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE_PAUSE), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), piFlags);
        String text = paused ? "Paused" : (curK > 0 ? curK + "K" : "Starting…");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ember)
                .setContentTitle("Ember")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause", pause).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        if (filter != null) filter.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
