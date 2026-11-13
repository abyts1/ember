package io.github.abyts1.ember;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/** Foreground service that keeps a non-touchable tinted overlay over the screen. */
public class OverlayService extends Service {
    static final String ACTION_TOGGLE_PAUSE = "io.github.abyts1.ember.TOGGLE_PAUSE";
    static final String ACTION_STOP = "io.github.abyts1.ember.STOP";
    static final String ACTION_REFRESH = "io.github.abyts1.ember.REFRESH";
    private static final String CHANNEL = "ember";
    private static final int NOTIF_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    private Model model;
    private int curK;
    private ValueAnimator anim;

    private final Runnable tick = this::update;

    // No point recomputing while the screen is off; catch up instantly when it turns on.
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(tick);
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) update();
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, OverlayService.class).setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Filter status", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        model = Model.load(this);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            model.enabled = false;
            model.save(this);
        } else if (ACTION_TOGGLE_PAUSE.equals(action)) {
            model.paused = !model.paused;
            model.save(this);
        }
        if (!model.enabled || !model.hasLocation() || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg(notification());
        ensureOverlay();
        update();
        return START_STICKY;
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    private void ensureOverlay() {
        if (overlay != null) return;
        overlay = new View(this);
        overlay.setBackgroundColor(0);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.alpha = 0.8f; // max obscuring opacity that still lets touches through on Android 12+
        wm.addView(overlay, lp);
        curK = 0;
    }

    private void update() {
        handler.removeCallbacks(tick);
        if (overlay == null) return;
        long now = System.currentTimeMillis();
        int want = model.paused ? model.dayK : model.targetK(now);
        int color = model.paused ? 0 : model.overlayColor(want);
        if (want != curK) {
            boolean big = curK != 0 && Math.abs(want - curK) > 300;
            setColor(color, big);
            curK = want;
            getSystemService(NotificationManager.class).notify(NOTIF_ID, notification());
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (!model.paused && pm.isInteractive()) handler.postDelayed(tick, model.nextDelay(now, curK));
    }

    private void setColor(int color, boolean fade) {
        if (anim != null) anim.cancel();
        // A fully transparent overlay still costs a compositor layer; hide it instead.
        if (color >>> 24 != 0) overlay.setVisibility(View.VISIBLE);
        if (!fade) {
            overlay.setBackgroundColor(color);
            if (color >>> 24 == 0) overlay.setVisibility(View.GONE);
            return;
        }
        int from = ((android.graphics.drawable.ColorDrawable) overlay.getBackground()).getColor();
        anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        anim.setDuration(1500);
        anim.addUpdateListener(a -> overlay.setBackgroundColor((int) a.getAnimatedValue()));
        if (color >>> 24 == 0) anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                overlay.setVisibility(View.GONE);
            }
        });
        anim.start();
    }

    private Notification notification() {
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE_PAUSE), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), piFlags);
        String text = model.paused ? "Paused" : (curK > 0 ? curK + "K" : "Starting…");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ember)
                .setContentTitle("Ember")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, model.paused ? "Resume" : "Pause", pause).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (anim != null) anim.cancel();
        unregisterReceiver(screen);
        if (overlay != null) wm.removeView(overlay);
        overlay = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
