package io.github.abyts1.ember;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Hosts the filter as an accessibility overlay, which is drawn above the status bar,
 * navigation bar, notification shade and quick settings. It subscribes to no
 * accessibility events and reads no screen content; the service exists only for
 * the window type.
 */
public class EmberAccessibilityService extends AccessibilityService {
    private static volatile boolean running;
    private Filter filter;

    static boolean isRunning() {
        return running;
    }

    // Turning ember on/off from the app shows/hides the overlay without toggling the service.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefs = (p, k) -> sync();

    @Override
    protected void onServiceConnected() {
        running = true;
        // Hand over from the app-overlay fallback so there's only one tint.
        stopService(new Intent(this, OverlayService.class));
        filter = new Filter(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        getSharedPreferences(Model.PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefs);
        sync();
    }

    private void sync() {
        Model m = Model.load(this);
        boolean want = m.enabled && m.hasLocation();
        if (want && !filter.running()) filter.start();
        else if (!want && filter.running()) filter.stop();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        // Accessibility turned off: fall back to the app overlay if the user still wants the filter.
        OverlayService.start(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (!running) return;
        running = false;
        getSharedPreferences(Model.PREFS, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefs);
        if (filter != null) filter.stop();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
