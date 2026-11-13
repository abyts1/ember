package io.github.abyts1.ember;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Settings plus the same sun/temperature model as the Linux daemon (ember.c). */
final class Model {
    static final String PREFS = "ember";

    double lat = Double.NaN, lon = Double.NaN;
    int dayK = 6500, nightK = 3400, lateK = 2300;
    int lateStart = -1, lateEnd = -1, wake = 6 * 60; // minutes after midnight, -1 = off
    double elevDay = 3, elevNight = -6;
    int intensity = 70; // percent
    boolean enabled, paused;

    static Model load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Model m = new Model();
        m.lat = Double.longBitsToDouble(p.getLong("lat", Double.doubleToLongBits(Double.NaN)));
        m.lon = Double.longBitsToDouble(p.getLong("lon", Double.doubleToLongBits(Double.NaN)));
        m.dayK = p.getInt("day", m.dayK);
        m.nightK = p.getInt("night", m.nightK);
        m.lateK = p.getInt("late", m.lateK);
        m.lateStart = p.getInt("lateStart", m.lateStart);
        m.lateEnd = p.getInt("lateEnd", m.lateEnd);
        m.wake = p.getInt("wake", m.wake);
        m.intensity = p.getInt("intensity", m.intensity);
        m.enabled = p.getBoolean("enabled", false);
        m.paused = p.getBoolean("paused", false);
        return m;
    }

    void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon))
                .putInt("day", dayK).putInt("night", nightK).putInt("late", lateK)
                .putInt("lateStart", lateStart).putInt("lateEnd", lateEnd).putInt("wake", wake)
                .putInt("intensity", intensity)
                .putBoolean("enabled", enabled).putBoolean("paused", paused)
                .apply();
    }

    boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    /* ---------- solar position (NOAA approximation) ---------- */

    static double sunElevation(long millis, double lat, double lon) {
        double n = millis / 86400000.0 + 2440587.5 - 2451545.0;
        double L = (280.460 + 0.9856474 * n) % 360.0;
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
        double lambda = Math.toRadians(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double gmst = (280.46061837 + 360.98564736629 * n) % 360.0;
        double ha = Math.toRadians(gmst + lon) - ra;
        double la = Math.toRadians(lat);
        return Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec)
                + Math.cos(la) * Math.cos(dec) * Math.cos(ha)));
    }

    /* ---------- temperature model ---------- */

    static double mixK(double a, double b, double f) {
        if (f <= 0) return a;
        if (f >= 1) return b;
        double ma = 1e6 / a, mb = 1e6 / b;
        return 1e6 / (ma + (mb - ma) * f);
    }

    static double smooth(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * (3 - 2 * x);
    }

    private static double minutesOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
    }

    double lateFactor(long t) {
        if (lateStart < 0 || lateEnd < 0) return 0;
        double m = minutesOfDay(t), ramp = 45;
        double since = ((m - lateStart + 1440 + ramp) % 1440 + 1440) % 1440;
        double len = ((lateEnd - lateStart) % 1440 + 1440) % 1440;
        if (len == 0) len = 1440;
        if (since < ramp) return smooth(since / ramp);
        if (since < ramp + len) return 1;
        if (since < 2 * ramp + len) return 1 - smooth((since - ramp - len) / ramp);
        return 0;
    }

    double wakeHold(long t) {
        if (wake < 0) return 0;
        double m = minutesOfDay(t);
        if (m < wake) return 1;
        return 1 - smooth((m - wake) / 30.0);
    }

    double sunAt(long t) {
        return sunElevation(t, lat, lon);
    }

    int targetK(long t) {
        double e = sunAt(t);
        double night = smooth((elevDay - e) / (elevDay - elevNight));
        double k = mixK(dayK, nightK, Math.max(night, wakeHold(t)));
        double lf = lateFactor(t);
        if (lf > 0 && lateK < k) k = mixK(k, lateK, lf);
        return (int) (Math.round(k / 25.0) * 25);
    }

    /** ms until the target may next change: short in transitions, long when flat. */
    long nextDelay(long now, int cur) {
        final int[] probes = {30, 60, 120, 300};
        for (int i = 0; i < probes.length; i++)
            if (targetK(now + probes[i] * 1000L) != cur) return (i == 0 ? 30 : probes[i - 1]) * 1000L;
        return 300_000L;
    }

    /* ---------- kelvin -> overlay colour ---------- */

    /** ARGB overlay colour for a temperature; transparent at >= 6500K. */
    int overlayColor(int k) {
        double t = k / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661
                : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        double blueLoss = 1 - clamp(b) / 255.0;
        // Android 12+ blocks touches through overlays above 0.8 opacity; stay well below.
        double a = Math.min(0.75, blueLoss * intensity / 100.0);
        return ((int) Math.round(a * 255) << 24) | ((int) clamp(r) << 16) | ((int) clamp(g) << 8) | (int) clamp(b);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    static String hhmm(int m) {
        return m < 0 ? "" : String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** Parses "HH:MM"; empty or "off" = -1; malformed = -2. */
    static int parseHhmm(String s) {
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("off")) return -1;
        String[] p = s.split(":");
        try {
            int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (p.length > 2 || h < 0 || h > 23 || m < 0 || m > 59) return -2;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -2;
        }
    }
}
