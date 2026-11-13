package io.github.abyts1.ember;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;

/**
 * The tinted overlay plus its schedule. Hosted either by OverlayService (app overlay,
 * sits below the status bar and shade) or EmberAccessibilityService (accessibility
 * overlay, covers system UI too).
 */
final class Filter {
    interface Listener {
        void onTemperature(int kelvin, boolean paused);
    }

    private final Context ctx;
    private final int windowType;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final Runnable tick = this::update;
    private View overlay;
    private Model model;
    private int curK;
    private ValueAnimator anim;

    // No point recomputing while the screen is off; catch up instantly when it turns on.
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(tick);
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) update();
        }
    };

    // Settings saved from the app take effect immediately (same process).
    private final SharedPreferences.OnSharedPreferenceChangeListener prefs = (p, k) -> reload();

    Filter(Context ctx, int windowType, Listener listener) {
        this.ctx = ctx;
        this.windowType = windowType;
        this.listener = listener;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void start() {
        if (overlay != null) return;
        model = Model.load(ctx);
        overlay = new View(ctx);
        overlay.setBackgroundColor(0);
        overlay.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Max obscuring opacity that still passes touches through on Android 12+ (app overlays).
        lp.alpha = 0.8f;
        wm.addView(overlay, lp);
        curK = 0;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        ctx.registerReceiver(screen, f);
        ctx.getSharedPreferences(Model.PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefs);
        update();
    }

    void stop() {
        if (overlay == null) return;
        handler.removeCallbacks(tick);
        if (anim != null) anim.cancel();
        ctx.unregisterReceiver(screen);
        ctx.getSharedPreferences(Model.PREFS, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefs);
        wm.removeView(overlay);
        overlay = null;
    }

    boolean running() {
        return overlay != null;
    }

    private void reload() {
        if (overlay == null) return;
        model = Model.load(ctx);
        curK = -1; // force a repaint with the new settings
        update();
    }

    private void update() {
        handler.removeCallbacks(tick);
        if (overlay == null) return;
        long now = System.currentTimeMillis();
        int want = model.paused ? model.dayK : model.targetK(now);
        int color = model.paused ? 0 : model.overlayColor(want);
        if (want != curK) {
            boolean big = curK > 0 && Math.abs(want - curK) > 300;
            setColor(color, big);
            curK = want;
            if (listener != null) listener.onTemperature(want, model.paused);
        }
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (!model.paused && pm.isInteractive()) handler.postDelayed(tick, model.nextDelay(now, curK));
    }

    private void setColor(int color, boolean fade) {
        if (anim != null) anim.cancel();
        boolean clear = color >>> 24 == 0;
        // A fully transparent overlay still costs a compositor layer; hide it instead.
        if (!clear) overlay.setVisibility(View.VISIBLE);
        if (!fade) {
            overlay.setBackgroundColor(color);
            if (clear) overlay.setVisibility(View.GONE);
            return;
        }
        int from = ((ColorDrawable) overlay.getBackground()).getColor();
        anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, color);
        anim.setDuration(1500);
        anim.addUpdateListener(a -> overlay.setBackgroundColor((int) a.getAnimatedValue()));
        if (clear) anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (overlay != null) overlay.setVisibility(View.GONE);
            }
        });
        anim.start();
    }
}
