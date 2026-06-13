# Day/Night Wallpaper — Projectivy Launcher plugin

An Android TV wallpaper-provider plugin for [Projectivy Launcher](https://projectivylauncher.com/).
It picks wallpapers by **time of day** (fixed times or computed sunrise/sunset) and, optionally, the
current **weather** (clear / rain / snow).

## How it works (and why it uses almost no power)

This is **not** an always-running app. It's a Projectivy *wallpaper provider plugin*: a `Service`
that Projectivy binds to and asks "what should the wallpaper be?" on a timer. Our service checks the
time of day (and weather) and returns **only** the images for the current bucket. Projectivy caches
the answer for 5 minutes (`items_cache_duration_millis`) and then asks again — so changes take effect
within a few minutes, with no background service, alarms, or wake-locks.

Images live in the app's own private storage and are handed to Projectivy through a `FileProvider`
read grant, so **no storage permissions** are required.

- `/sample` — the plugin (service, settings screen, sun/weather logic). Kotlin. *(module folder is
  still named `sample`; the app package is `…daynight`.)*
- `/api` — the AIDL contract Projectivy uses. **Do not modify.**

Built by forking the official template: https://github.com/spocky/projectivy-plugin-wallpaper-provider

### Day/night decision
- **Fixed times** (default): day starts 07:00, night starts 19:00 — both editable in settings.
- **Sunrise/sunset**: computed locally (NOAA sunrise equation, no API key) from the coordinates of
  your ZIP code. Falls back to fixed times until the ZIP has been geocoded.

### Weather (optional)
- ZIP → coordinates via [api.zippopotam.us](https://api.zippopotam.us) (US, key-less), cached until
  the ZIP changes.
- Current weather via [Open-Meteo](https://open-meteo.com) (key-less), fetched on a background thread
  and cached **3 hours** (`WEATHER_MAX_AGE_MS`). WMO weather code → `clear` / `rain` / `snow`.

## Images

The app ships with **built-in placeholder defaults** (labeled gradient images in
`res/drawable-nodpi/def_*.png`), so it shows *something* for every bucket immediately on install —
no setup required. Any bucket you supply your own images for overrides its default.

Three ways to supply your own images, easiest first:
1. **Import from a folder** (in-app, no PC): settings ▸ *Import images from a folder…* opens the
   system folder picker. Point it at a folder (e.g. a USB drive) that contains subfolders named like
   the buckets below; their images are copied in.
2. **File manager** on the TV — drop files into the folders below.
3. **adb push** from a PC (below).

The app creates these under `…/Android/data/tv.projectivy.plugin.wallpaperprovider.daynight/files/`:

```
day/         night/
day_rain/    night_rain/
day_snow/    night_snow/
```

Selection = `<day|night>` + weather. A weather folder that's empty **falls back** to the plain
`day`/`night` folder, so you only need to fill in the conditions you care about. Put one image per
folder for a fixed look, or several — Projectivy rotates within the active set. Supported: jpg, jpeg,
png, webp, gif, bmp.

```
adb push day.jpg        /sdcard/Android/data/tv.projectivy.plugin.wallpaperprovider.daynight/files/day/
adb push night.png      /sdcard/Android/data/tv.projectivy.plugin.wallpaperprovider.daynight/files/night/
adb push day-rain.jpg   /sdcard/Android/data/tv.projectivy.plugin.wallpaperprovider.daynight/files/day_rain/
# ...etc
```

> Note: create the folders via the app first (open its Settings, or let Projectivy query it) so they
> are **app-owned** — folders created with `adb shell mkdir` are owned by `shell` and the app can't
> read them.

## Build & install

Requires Projectivy **Premium** (custom wallpapers are a premium feature).

1. Open this folder in **Android Studio** and let it sync. Accept any prompt to install missing SDK
   components.
2. Build the APK: **Build ▸ Build APK(s)**, or from a terminal:
   ```
   gradlew :sample:assembleDebug
   ```
   The APK lands in `sample/build/outputs/apk/debug/`.
3. Install to the TV (connected via adb):
   ```
   adb install -r sample\build\outputs\apk\debug\sample-debug.apk
   ```

## Turn it on / configure

On the TV: **Projectivy Settings ▸ Appearance ▸ Wallpaper ▸** select **Day/Night Wallpaper**. Then
open its **Settings** (or open the "Day/Night Wallpaper" app directly) to:

- toggle **Day/night source** (fixed times ↔ sunrise/sunset — fixed-time fields grey out in sunrise mode),
- enter your **ZIP code**,
- toggle **Use weather** and **Check weather now** (shows the current result inline),
- adjust the fixed-mode day/night times,
- **Import images from a folder** (USB / Downloads).

After changing settings, **re-select** the provider in Projectivy so it re-reads the plugin (it
caches plugin metadata and only re-queries on cache expiry or re-selection).

## Notes

- The build pins `compileSdk`/`targetSdk` to 35 and a few AndroidX libs to stable versions so it
  compiles against a commonly-installed SDK. Bump them back up if you want the latest.
- ZIP geocoding currently assumes **US** ZIP codes (`/us/` Zippopotam endpoint).
