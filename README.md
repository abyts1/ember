# ember

An f.lux-style screen colour temperature daemon. It follows the real position of the sun at your location, warming the screen through dusk and cooling it again at dawn. On Linux it is a single 30 KB C binary for Hyprland. There is also an [Android app](android/).

ember is an independent project and is not affiliated with f.lux.

## Features

- **Sun-tracked transitions.** It computes solar elevation with the NOAA approximation (checked against `astral` to within 0.01°) and blends from day to night as the sun goes from +3° to −6° (civil twilight).
- **Even-looking blends.** It interpolates in mireds, not kelvin, so each step of a transition looks about the same size.
- **Earliest wake time.** It holds night colours until a set time even if the sun is already up, then eases into daylight over 30 minutes.
- **Late-night mode.** An optional, warmer temperature between two clock times, with a 45-minute fade in and out.
- **Low resource use.** One timer wakeup every 5 minutes when the target isn't changing, and every 30–60 seconds during transitions. It writes to hyprsunset's socket directly (no `hyprctl` fork) and only when the value moves by at least 25 K. Memory use is about 3 MB RSS.
- **Suspend-aware.** It uses a `CLOCK_BOOTTIME` timer, so it corrects the temperature as soon as the machine resumes.
- **Fades.** Large jumps (startup, resume, late mode) fade over 1.5 s.
- **Signals.** `SIGHUP` reloads the config. `SIGUSR1` toggles pause. `SIGTERM` restores normal colours.

## Install (Arch / Hyprland)

```sh
git clone https://github.com/abyts1/ember && cd ember
makepkg -si            # builds ember-git from the PKGBUILD
# or: make && sudo make PREFIX=/usr install
```

Requires `hyprsunset` (`pacman -S hyprsunset`), which applies the colour matrix in Hyprland.

```sh
mkdir -p ~/.config/ember
cp /usr/share/doc/ember/config.example ~/.config/ember/config   # set latitude/longitude
systemctl --user enable --now ember.service                      # also pulls in hyprsunset.service
```

If your hyprsunset config defines `profile { }` blocks, remove them. Otherwise hyprsunset switches temperature on its own schedule and fights ember.

## Usage

```
ember                 run the daemon
ember -p              print sun elevation and current target
ember -s              print the next 24 h, hourly
ember -o              apply the current target once and exit
ember --lat 1.35 --lon 103.82 -s
pkill -USR1 ember     pause / resume
```

## Configuration

`~/.config/ember/config`; every option is listed in [contrib/config.example](contrib/config.example).

| key | default | meaning |
|---|---|---|
| `latitude`, `longitude` | — | required, decimal degrees (N/E positive) |
| `day_temp` / `night_temp` | 6500 / 3400 | K |
| `wake_time` | off | earliest wake time, `HH:MM` |
| `late_temp`, `late_start`, `late_end` | 2300, off, off | late-night mode |
| `elevation_day` / `elevation_night` | 3 / −6 | transition bounds in degrees |
| `step` | 25 | minimum K change to push |
| `fade_ms` | 1500 | fade length for big jumps, 0 = snap |

## Android

See [android/README.md](android/README.md).

## License

MIT
