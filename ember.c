// ember - sun-aware screen colour temperature daemon for Hyprland (via hyprsunset IPC)
// SPDX-License-Identifier: MIT
#define _GNU_SOURCE
#include <errno.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define VERSION "1.0.0"
#define DEG (M_PI / 180.0)

typedef struct {
	double lat, lon;
	int have_loc;
	int day_k, night_k, late_k;
	int late_start, late_end; // minutes after midnight, -1 = disabled
	int wake;                 // earliest wake time: stay in night mode until then, -1 = off
	double elev_day, elev_night; // sun elevation bounds of the transition (deg)
	int step_k;                  // only push changes >= this many kelvin
	int fade_ms;                 // duration of fades for large jumps
} config;

static config cfg;

static char cfg_path[512];
static double cli_lat = NAN, cli_lon = NAN;
static char sock_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
static int verbose;

static void logmsg(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	fputs("ember: ", stderr);
	vfprintf(stderr, fmt, ap);
	fputc('\n', stderr);
	va_end(ap);
}

/* ---------- solar position (NOAA approximation, ~0.1 deg accuracy) ---------- */

static double sun_elevation(time_t t, double lat, double lon)
{
	double jd = t / 86400.0 + 2440587.5;
	double n = jd - 2451545.0;                       // days since J2000
	double L = fmod(280.460 + 0.9856474 * n, 360.0); // mean longitude
	double g = fmod(357.528 + 0.9856003 * n, 360.0) * DEG;
	double lambda = (L + 1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG;
	double eps = (23.439 - 0.0000004 * n) * DEG;
	double ra = atan2(cos(eps) * sin(lambda), cos(lambda));
	double dec = asin(sin(eps) * sin(lambda));
	double gmst = fmod(280.46061837 + 360.98564736629 * n, 360.0);
	double ha = (gmst + lon) * DEG - ra;              // local hour angle
	double la = lat * DEG;
	return asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)) / DEG;
}

/* ---------- temperature model ---------- */

// Interpolate in mireds (1e6/K): perceptually even, unlike linear kelvin.
static double mix_k(double a_k, double b_k, double f)
{
	if (f <= 0) return a_k;
	if (f >= 1) return b_k;
	double a = 1e6 / a_k, b = 1e6 / b_k;
	return 1e6 / (a + (b - a) * f);
}

static double smoothstep(double x)
{
	if (x <= 0) return 0;
	if (x >= 1) return 1;
	return x * x * (3 - 2 * x);
}

// Fraction (0..1) of the way into late-night mode, with a 45 min ramp in and out.
static double late_factor(time_t t)
{
	if (cfg.late_start < 0 || cfg.late_end < 0) return 0;
	struct tm tm;
	localtime_r(&t, &tm);
	double m = tm.tm_hour * 60 + tm.tm_min + tm.tm_sec / 60.0;
	const double ramp = 45.0;
	double since = fmod(m - cfg.late_start + 1440 + ramp, 1440); // minutes since ramp-in began
	double len = fmod(cfg.late_end - cfg.late_start + 1440, 1440);
	if (len == 0) len = 1440;
	if (since < ramp) return smoothstep(since / ramp);
	if (since < ramp + len) return 1;
	if (since < 2 * ramp + len) return 1 - smoothstep((since - ramp - len) / ramp);
	return 0;
}

// 1 between midnight and the wake time, then a 30 min ramp back to the sun schedule.
static double wake_hold(time_t t)
{
	if (cfg.wake < 0) return 0;
	struct tm tm;
	localtime_r(&t, &tm);
	double m = tm.tm_hour * 60 + tm.tm_min + tm.tm_sec / 60.0;
	if (m < cfg.wake) return 1;
	return 1 - smoothstep((m - cfg.wake) / 30.0);
}

static double target_k(time_t t, double *elev_out)
{
	double e = sun_elevation(t, cfg.lat, cfg.lon);
	if (elev_out) *elev_out = e;
	double f = (cfg.elev_day - e) / (cfg.elev_day - cfg.elev_night); // 0 = day, 1 = night
	double night = smoothstep(f), hold = wake_hold(t);
	double k = mix_k(cfg.day_k, cfg.night_k, hold > night ? hold : night);
	double lf = late_factor(t);
	if (lf > 0 && cfg.late_k < k) k = mix_k(k, cfg.late_k, lf);
	return k;
}

/* ---------- hyprsunset IPC ---------- */

static int resolve_socket(void)
{
	const char *rt = getenv("XDG_RUNTIME_DIR"), *his = getenv("HYPRLAND_INSTANCE_SIGNATURE");
	if (!rt || !his) {
		logmsg("XDG_RUNTIME_DIR / HYPRLAND_INSTANCE_SIGNATURE not set (not in a Hyprland session?)");
		return -1;
	}
	int n = snprintf(sock_path, sizeof sock_path, "%s/hypr/%s/.hyprsunset.sock", rt, his);
	return (n > 0 && (size_t)n < sizeof sock_path) ? 0 : -1;
}

// Returns 0 on "ok". One short-lived connection per request, matching hyprctl.
static int hs_send(const char *cmd)
{
	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) return -1;
	struct sockaddr_un sa = { .sun_family = AF_UNIX };
	memcpy(sa.sun_path, sock_path, sizeof sa.sun_path);
	struct timeval tv = { .tv_sec = 1 };
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
	setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
	char buf[64];
	int rc = -1;
	size_t len = strlen(cmd);
	if (connect(fd, (struct sockaddr *)&sa, sizeof sa) == 0 && write(fd, cmd, len) == (ssize_t)len) {
		ssize_t r = read(fd, buf, sizeof buf - 1);
		if (r > 0) {
			buf[r] = 0;
			rc = strncmp(buf, "ok", 2) == 0 ? 0 : -1;
			if (rc) logmsg("hyprsunset replied: %s", buf);
		}
	}
	close(fd);
	return rc;
}

static int hs_temperature(int k)
{
	char cmd[32];
	snprintf(cmd, sizeof cmd, "temperature %d", k);
	return hs_send(cmd);
}

/* ---------- config ---------- */

static int parse_hhmm(const char *s)
{
	int h, m;
	if (sscanf(s, "%d:%d", &h, &m) != 2 || h < 0 || h > 23 || m < 0 || m > 59) return -2;
	return h * 60 + m;
}

static int clamp_k(long k) { return k < 1000 ? 1000 : k > 20000 ? 20000 : (int)k; }

static int set_opt(const char *key, const char *val)
{
	char *end;
	double d = strtod(val, &end);
	int num_ok = end != val;
	if (!strcmp(key, "latitude") && num_ok) cfg.lat = d, cfg.have_loc |= 1;
	else if (!strcmp(key, "longitude") && num_ok) cfg.lon = d, cfg.have_loc |= 2;
	else if (!strcmp(key, "day_temp") && num_ok) cfg.day_k = clamp_k((long)d);
	else if (!strcmp(key, "night_temp") && num_ok) cfg.night_k = clamp_k((long)d);
	else if (!strcmp(key, "late_temp") && num_ok) cfg.late_k = clamp_k((long)d);
	else if (!strcmp(key, "elevation_day") && num_ok) cfg.elev_day = d;
	else if (!strcmp(key, "elevation_night") && num_ok) cfg.elev_night = d;
	else if (!strcmp(key, "step") && num_ok && d >= 1) cfg.step_k = (int)d;
	else if (!strcmp(key, "fade_ms") && num_ok && d >= 0) cfg.fade_ms = (int)d;
	else if (!strcmp(key, "late_start")) cfg.late_start = !strcmp(val, "off") ? -1 : parse_hhmm(val);
	else if (!strcmp(key, "wake_time")) cfg.wake = !strcmp(val, "off") ? -1 : parse_hhmm(val);
	else if (!strcmp(key, "late_end")) cfg.late_end = !strcmp(val, "off") ? -1 : parse_hhmm(val);
	else return -1;
	if (cfg.late_start == -2 || cfg.late_end == -2 || cfg.wake == -2) return -1;
	return 0;
}

static int load_config(const char *path, int required)
{
	FILE *f = fopen(path, "re");
	if (!f) {
		if (required || errno != ENOENT) logmsg("cannot open %s: %s", path, strerror(errno));
		return required ? -1 : 0;
	}
	char line[256];
	int ln = 0, bad = 0;
	while (fgets(line, sizeof line, f)) {
		ln++;
		char *p = line;
		p[strcspn(p, "#\n")] = 0;
		char *eq = strchr(p, '=');
		if (!eq) {
			while (*p == ' ' || *p == '\t') p++;
			if (*p) logmsg("%s:%d: expected key = value", path, ln), bad = 1;
			continue;
		}
		*eq = 0;
		char *key = p, *val = eq + 1, *e;
		while (*key == ' ' || *key == '\t') key++;
		for (e = eq - 1; e >= key && (*e == ' ' || *e == '\t'); e--) *e = 0;
		while (*val == ' ' || *val == '\t') val++;
		for (e = val + strlen(val) - 1; e >= val && (*e == ' ' || *e == '\t' || *e == '\r'); e--) *e = 0;
		if (set_opt(key, val)) logmsg("%s:%d: bad option '%s'", path, ln, key), bad = 1;
	}
	fclose(f);
	return bad ? -1 : 0;
}

// Start from built-in defaults so keys removed from the file actually revert.
static int load_all(int required)
{
	static const config defaults = {
		.day_k = 6500, .night_k = 3400, .late_k = 2300,
		.late_start = -1, .late_end = -1, .wake = -1,
		.elev_day = 3.0, .elev_night = -6.0,
		.step_k = 25, .fade_ms = 1500,
	};
	cfg = defaults;
	if (cfg_path[0] && load_config(cfg_path, required)) return -1;
	if (!isnan(cli_lat)) cfg.lat = cli_lat, cfg.have_loc |= 1;
	if (!isnan(cli_lon)) cfg.lon = cli_lon, cfg.have_loc |= 2;
	return 0;
}

static int validate(void)
{
	if (cfg.have_loc != 3) {
		logmsg("latitude and longitude are required (config file or --lat/--lon)");
		return -1;
	}
	if (cfg.lat < -90 || cfg.lat > 90 || cfg.lon < -180 || cfg.lon > 180) {
		logmsg("latitude/longitude out of range");
		return -1;
	}
	if (cfg.elev_day <= cfg.elev_night) {
		logmsg("elevation_day must be greater than elevation_night");
		return -1;
	}
	return 0;
}

/* ---------- main loop ---------- */

static int quantize(double k) { return clamp_k(lround(k / cfg.step_k) * cfg.step_k); }

// Fade from `from` to `to` in ~60 Hz-free coarse steps; cheap and avoids a visible snap.
static int apply(int from, int to, int fade)
{
	if (fade && from > 0 && cfg.fade_ms > 0 && abs(to - from) > 300) {
		const int frames = 15;
		struct timespec ts = { 0, (long)cfg.fade_ms * 1000000L / frames };
		for (int i = 1; i < frames; i++) {
			int k = (int)lround(mix_k(from, to, smoothstep((double)i / frames)));
			if (hs_temperature(k)) return -1;
			nanosleep(&ts, NULL);
		}
	}
	return hs_temperature(to);
}

// Seconds until the target could next move by a step: short during transitions, long when flat.
static int next_wakeup(time_t now, int cur)
{
	static const int probes[] = { 30, 60, 120, 300 };
	for (size_t i = 0; i < sizeof probes / sizeof *probes; i++)
		if (quantize(target_k(now + probes[i], NULL)) != cur) return probes[i] > 30 ? probes[i - 1] : 30;
	return 300;
}

static void arm(int tfd, int secs)
{
	struct itimerspec its = { .it_value = { .tv_sec = secs } };
	timerfd_settime(tfd, 0, &its, NULL);
}

static int run_daemon(void)
{
	sigset_t mask;
	sigemptyset(&mask);
	sigaddset(&mask, SIGTERM);
	sigaddset(&mask, SIGINT);
	sigaddset(&mask, SIGHUP);
	sigaddset(&mask, SIGUSR1);
	sigprocmask(SIG_BLOCK, &mask, NULL);
	int sfd = signalfd(-1, &mask, SFD_CLOEXEC);
	// CLOCK_BOOTTIME keeps counting across suspend, so we re-evaluate right after resume.
	int tfd = timerfd_create(CLOCK_BOOTTIME, TFD_CLOEXEC);
	if (sfd < 0 || tfd < 0) {
		logmsg("signalfd/timerfd: %s", strerror(errno));
		return 1;
	}

	int cur = 0;            // last kelvin hyprsunset acknowledged, 0 = unknown
	time_t last_push = 0;   // resend periodically in case something else changed it
	int paused = 0, fails = 0;
	struct pollfd pfd[2] = { { .fd = sfd, .events = POLLIN }, { .fd = tfd, .events = POLLIN } };
	arm(tfd, 1);

	for (;;) {
		if (poll(pfd, 2, -1) < 0) {
			if (errno == EINTR) continue;
			logmsg("poll: %s", strerror(errno));
			return 1;
		}
		if (pfd[0].revents & POLLIN) {
			struct signalfd_siginfo si;
			if (read(sfd, &si, sizeof si) != sizeof si) continue;
			if (si.ssi_signo == SIGHUP) {
				config old = cfg;
				if (load_all(0) || validate()) {
					logmsg("reload failed, keeping previous config");
					cfg = old;
				} else logmsg("config reloaded");
			} else if (si.ssi_signo == SIGUSR1) {
				paused = !paused;
				logmsg(paused ? "paused" : "resumed");
				if (paused) apply(cur, cfg.day_k, 1), hs_send("identity");
			} else {
				if (cur) hs_send("identity");
				return 0;
			}
			cur = paused ? 0 : cur;
			last_push = 0;
			arm(tfd, 1);
			continue;
		}
		if (!(pfd[1].revents & POLLIN)) continue;
		uint64_t exp;
		if (read(tfd, &exp, sizeof exp) < 0) continue;
		if (paused) continue;

		time_t now = time(NULL);
		double elev;
		int want = quantize(target_k(now, &elev));
		if (want != cur || now - last_push >= 600) {
			if (apply(cur, want, cur != want) == 0) {
				if (verbose || want != cur) logmsg("%dK (sun %.1f°)", want, elev);
				cur = want, last_push = now, fails = 0;
			} else {
				if (fails++ == 0) logmsg("cannot reach hyprsunset at %s (is it running?)", sock_path);
				cur = 0;
				arm(tfd, fails < 10 ? 2 : 15);
				continue;
			}
		}
		arm(tfd, next_wakeup(now, cur));
	}
}

static void usage(FILE *o)
{
	fprintf(o,
		"usage: ember [options]\n"
		"  -c, --config FILE   config file (default: $XDG_CONFIG_HOME/ember/config)\n"
		"      --lat DEG       latitude  (north positive)\n"
		"      --lon DEG       longitude (east positive)\n"
		"  -p, --print         print sun elevation and target temperature, then exit\n"
		"  -o, --once          apply the current target once, then exit\n"
		"  -s, --schedule      print the next 24h of targets (hourly), then exit\n"
		"  -v, --verbose       log every push\n"
		"  -V, --version\n"
		"signals: SIGHUP reloads config, SIGUSR1 toggles pause, SIGTERM restores identity\n");
}

int main(int argc, char **argv)
{
	int mode = 0; // 0 daemon, 'p' print, 'o' once, 's' schedule
	const char *cpath = NULL;

	for (int i = 1; i < argc; i++) {
		const char *a = argv[i];
		const char *next = i + 1 < argc ? argv[i + 1] : NULL;
		if ((!strcmp(a, "-c") || !strcmp(a, "--config")) && next) cpath = argv[++i];
		else if (!strcmp(a, "--lat") && next) cli_lat = atof(argv[++i]);
		else if (!strcmp(a, "--lon") && next) cli_lon = atof(argv[++i]);
		else if (!strcmp(a, "-p") || !strcmp(a, "--print")) mode = 'p';
		else if (!strcmp(a, "-o") || !strcmp(a, "--once")) mode = 'o';
		else if (!strcmp(a, "-s") || !strcmp(a, "--schedule")) mode = 's';
		else if (!strcmp(a, "-v") || !strcmp(a, "--verbose")) verbose = 1;
		else if (!strcmp(a, "-V") || !strcmp(a, "--version")) return puts("ember " VERSION), 0;
		else if (!strcmp(a, "-h") || !strcmp(a, "--help")) return usage(stdout), 0;
		else return usage(stderr), 2;
	}

	if (cpath) snprintf(cfg_path, sizeof cfg_path, "%s", cpath);
	else {
		const char *x = getenv("XDG_CONFIG_HOME"), *h = getenv("HOME");
		if (x && *x) snprintf(cfg_path, sizeof cfg_path, "%s/ember/config", x);
		else if (h) snprintf(cfg_path, sizeof cfg_path, "%s/.config/ember/config", h);
	}
	if (load_all(cpath != NULL) || validate()) return 1;

	time_t now = time(NULL);
	if (mode == 'p') {
		double e;
		int k = quantize(target_k(now, &e));
		printf("sun %.2f°  target %dK\n", e, k);
		return 0;
	}
	if (mode == 's') {
		for (int h = 0; h <= 24; h++) {
			time_t t = now - now % 3600 + h * 3600;
			struct tm tm;
			double e;
			localtime_r(&t, &tm);
			int k = quantize(target_k(t, &e));
			printf("%02d:00  sun %6.1f°  %5dK\n", tm.tm_hour, e, k);
		}
		return 0;
	}
	if (resolve_socket()) return 1;
	if (mode == 'o') {
		int k = quantize(target_k(now, NULL));
		if (hs_temperature(k)) return logmsg("cannot reach hyprsunset at %s", sock_path), 1;
		printf("%dK\n", k);
		return 0;
	}
	return run_daemon();
}
