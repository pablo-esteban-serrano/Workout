# Workout

A small, distraction-free workout tracker for minimalist Android phones — built for the [Light Phone III](https://www.thelightphone.com/) and [Mudita Kompakt](https://mudita.com/), and connects to a [Polar Verity Sense](https://www.polar.com/en/verity-sense) (or any standard Bluetooth LE heart rate strap) for live heart rate.

No accounts, no cloud sync, no ads, no accent colors — just start a workout, see your heart rate and elapsed time, and end up with a local history you can look back on.
![Screenshot](https://github.com/pablo-esteban-serrano/Workout/blob/master/appscreens.png)

![MKScreenshot](https://github.com/pablo-esteban-serrano/Workout/blob/master/mkscreenshots.png)
## Features

- **7 workout types**: Walk, Run, Bike, Stationary Bike, Core, Weightlifting, Punching Bag
- **Live heart rate** over standard Bluetooth LE (Heart Rate Service `0x180D`) — works with the Polar Verity Sense and any other strap that implements the standard BLE Heart Rate Profile
- **Auto-reconnect**: if the strap drops out of range mid-workout, the app backs off and retries (2s → 30s) automatically, no need to reconnect by hand
- **HR zones**: a simple 5-zone model based on estimated max heart rate (`220 − age`)
- **Calorie estimate**: MET-based estimate from workout type, your weight, and elapsed time
- **Workout history**: every finished workout is saved locally (duration, calories, avg/peak HR) with two-tap delete
- **Runs through backgrounding**: workout timing is computed from wall-clock timestamps, not a running counter, so it stays accurate if the screen turns off or the app is backgrounded
- **Two display palettes**: a black-background palette for OLED-style screens, and an inverted white-background palette for e-ink displays (Settings → *Invert colors*) — e-ink panels render a black background as a dim, ghosty grey rather than true black, so the inverted palette is much easier to read on those displays
- **No accounts, no network calls, no ads** — everything lives in a local Room database and DataStore file on the device

## Supported devices

Built and tested on:

- **Light Phone III** (LightOS)
- **Mudita Kompakt** (Kompakt OS 1.6)

It's a plain sideloaded Android app — no OEM-specific SDK is used for the core app, so it should run on most Android devices with Bluetooth LE, though the visual design (large text, monochrome, no navigation chrome) is deliberately tuned for minimalist/e-ink hardware.

> **Why not the official Light SDK?** Light's official `light-sdk` Tool framework doesn't support Bluetooth yet, which this app needs for the heart rate strap — so it's built and sideloaded as a standalone Android app instead.

## Hardware you'll need

- An Android phone with Bluetooth LE (Light Phone III, Mudita Kompakt, or any other Android device)
- A Bluetooth LE heart rate strap that implements the standard [Bluetooth Heart Rate Profile](https://www.bluetooth.com/specifications/specs/heart-rate-profile-1-0/) (the Polar Verity Sense is what this app was built and tested against, but any compliant strap should work)

## Building it yourself

This is a standard Android Studio project.

1. Clone the repo
2. Open it in Android Studio
3. Enable Developer Options and USB debugging on your device
4. Run/install via Android Studio, or build an APK and sideload it with `adb install`

Requires Android with `targetSdk 36`. No special build flags or secrets are needed — there's no backend, no API keys, nothing to configure.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+), or `ACCESS_FINE_LOCATION` (below Android 12) | Required by Android to scan for and connect to BLE devices |
| `POST_NOTIFICATIONS` (Android 13+) | The workout-tracking foreground service needs a notification to stay alive while backgrounded — on LightOS this notification is never actually shown to you, since LightOS has no notification shade |

All permissions are requested at the point they're needed (starting a workout), never up front.

## Architecture, briefly

- `WorkoutConnectionService` — a bound + foreground `Service` that owns the BLE connection and all workout timing/state, so a workout survives the screen turning off or the Activity being recreated
- `MainActivity` — Compose UI, binds to the service, holds a `UserProfileRepository` (DataStore, for age/weight/display preference) and a Room `WorkoutDatabase`
- `Screens.kt` — all screens: Home, Active Workout, Settings, History
- `WorkoutType.kt` — the 7 activity types, MET values, and the HR-zone/calorie math
- `LightTheme.kt` — the monochrome theme, including the two color palettes (normal / inverted for e-ink)

## Disclaimer

Calorie and max-heart-rate estimates use standard population-level formulas (MET × weight × time, and `220 − age`), not anything measured for you personally. Treat them as rough estimates, not medical-grade readings. This app isn't a medical device and isn't a substitute for guidance from a doctor or trainer. 

## License

MIT — see [LICENSE](LICENSE). Feel free to fork, modify, and adapt this for your own minimalist phone.

## Contributing

This started as a personal project for my own phones, so it's fairly opinionated about scope and style (plain text buttons, no accent colors, no settings sprawl). Issues and PRs are welcome, especially for support on other minimalist/e-ink Android devices — just note the design philosophy above before proposing something that pulls in default Material styling or adds an account/cloud layer.
