package io.github.abyts1.ember;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Single settings screen, built in code to keep the APK dependency-free. */
public class MainActivity extends Activity {
    private EditText lat, lon, day, night, late, lateStart, lateEnd, wake, intensity;
    private TextView status;
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Ember");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        col.addView(title);
        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(12));
        col.addView(status);

        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        int num = InputType.TYPE_CLASS_NUMBER;
        int time = InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
        lat = field("Latitude (north +)", dec);
        lon = field("Longitude (east +)", dec);
        day = field("Daytime temperature (K)", num);
        night = field("Night temperature (K)", num);
        wake = field("Earliest wake time (HH:MM, blank = off)", time);
        late = field("Late-night temperature (K)", num);
        lateStart = field("Late-night start (HH:MM, blank = off)", time);
        lateEnd = field("Late-night end (HH:MM)", time);
        intensity = field("Filter intensity (%)", num);

        Button on = new Button(this);
        on.setText("Save & turn on");
        on.setOnClickListener(v -> saveAndStart());
        Button off = new Button(this);
        off.setText("Turn off");
        off.setOnClickListener(v -> {
            Model m = Model.load(this);
            m.enabled = false;
            m.save(this);
            stopService(new Intent(this, OverlayService.class));
            refresh();
        });
        Button sys = new Button(this);
        sys.setText("Cover status bar & notifications…");
        sys.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(on);
        col.addView(off);
        col.addView(sys);
        TextView hint = new TextView(this);
        hint.setText("Turn on \"Ember (cover system UI)\" under Accessibility → Downloaded apps. "
                + "If it's greyed out: App info → ⋮ → Allow restricted settings.");
        hint.setPadding(0, dp(4), 0, 0);
        col.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(col);
        setContentView(sv);
        fill(Model.load(this));

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Model m = Model.load(this);
        // Returning from the overlay-permission screen: start if the user enabled it there.
        if (m.enabled && m.hasLocation() && Settings.canDrawOverlays(this)) OverlayService.start(this);
        refresh();
    }

    private EditText field(String label, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(8), 0, 0);
        col.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        col.addView(e);
        return e;
    }

    private void fill(Model m) {
        lat.setText(Double.isNaN(m.lat) ? "" : String.valueOf(m.lat));
        lon.setText(Double.isNaN(m.lon) ? "" : String.valueOf(m.lon));
        day.setText(String.valueOf(m.dayK));
        night.setText(String.valueOf(m.nightK));
        late.setText(String.valueOf(m.lateK));
        lateStart.setText(Model.hhmm(m.lateStart));
        lateEnd.setText(Model.hhmm(m.lateEnd));
        wake.setText(Model.hhmm(m.wake));
        intensity.setText(String.valueOf(m.intensity));
    }

    private void saveAndStart() {
        Model m = Model.load(this);
        try {
            m.lat = Double.parseDouble(lat.getText().toString().trim());
            m.lon = Double.parseDouble(lon.getText().toString().trim());
            if (Math.abs(m.lat) > 90 || Math.abs(m.lon) > 180) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            status.setText("Enter a valid latitude and longitude.");
            return;
        }
        m.dayK = kelvin(day, m.dayK);
        m.nightK = kelvin(night, m.nightK);
        m.lateK = kelvin(late, m.lateK);
        int ls = Model.parseHhmm(lateStart.getText().toString());
        int le = Model.parseHhmm(lateEnd.getText().toString());
        int wk = Model.parseHhmm(wake.getText().toString());
        if (ls == -2 || le == -2 || wk == -2) {
            status.setText("Times must be HH:MM (24-hour).");
            return;
        }
        m.lateStart = ls;
        m.lateEnd = le;
        m.wake = wk;
        try {
            m.intensity = Math.max(0, Math.min(100, Integer.parseInt(intensity.getText().toString().trim())));
        } catch (NumberFormatException ignored) {
        }
        m.enabled = true;
        m.paused = false;
        m.save(this);
        fill(m);

        if (!EmberAccessibilityService.isRunning() && !Settings.canDrawOverlays(this)) {
            status.setText("Allow \"Display over other apps\" for Ember, then come back.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        // Restart so the service picks up new settings.
        stopService(new Intent(this, OverlayService.class));
        OverlayService.start(this);
        refresh();
    }

    private static int kelvin(EditText e, int fallback) {
        try {
            return Math.max(1000, Math.min(20000, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void refresh() {
        Model m = Model.load(this);
        if (!m.hasLocation()) {
            status.setText("Set your location to begin.");
            return;
        }
        long now = System.currentTimeMillis();
        String mode = EmberAccessibilityService.isRunning() ? "covers system UI" : "apps only";
        status.setText(String.format(Locale.US, "%s (%s) · sun %.1f° · target %dK",
                m.enabled ? (m.paused ? "Paused" : "On") : "Off", mode, m.sunAt(now), m.targetK(now)));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
