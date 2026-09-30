# Context launcher

A text-only Android launcher that narrows the phone to what the current part of
the day needs, and opens up once that stage's tasks are done. Fork of
[Olauncher](https://github.com/tanujnotes/Olauncher) (GPLv3). Personal build for
a Fairphone 5, sideloaded over adb.

`SPEC.md` is the source of truth. Where a prompt and the spec disagree, the spec
wins and the disagreement gets reported.

## Build and test

```sh
# Core engine tests (pure JVM, no Android SDK needed, ~30 s)
cd core && ../gradlew test

# App debug build (needs the Android SDK; ANDROID_HOME or local.properties)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# App unit tests
./gradlew :app:testDebugUnitTest
```

The debug build installs as `uk.co.softwarecrafts.contextlauncher.debug`, so
it sits beside any other launcher. Set it as default from the phone's
Settings > Apps > Default apps > Home app, or from the launcher's own settings
(long-press the home screen).

Cloud sessions: the container's network policy blocks `dl.google.com`, which
serves both the Android SDK and the Android Gradle plugin. Only the core tests
run there. That is why `core` is a separate Gradle build with just Maven
Central (see below).

## Layout

```
SPEC.md                         v1 spec (source of truth)
core/                           standalone Gradle build, pure Kotlin/JVM
  src/main/kotlin/uk/co/softwarecrafts/contextlauncher/core/
    Clock.kt                    Clock interface + SystemClock
  src/test/kotlin/.../core/
    FakeClock.kt                settable clock for tests
app/                            Android app (Olauncher fork)
  src/main/java/app/olauncher/  borrowed Olauncher code, package kept as-is
    MainActivity.kt             single activity, nav host, back handling
    MainViewModel.kt            app list, launching, home apps, screen time
    ui/HomeFragment.kt          home screen (gestures, clock, home apps)
    ui/AppDrawerFragment.kt     searchable app list ("drawer")
    ui/SettingsFragment.kt      settings screen
    data/Prefs.kt               SharedPreferences wrapper
    helper/Utils.kt             app list loading, launcher-default checks
    helper/MyAccessibilityService.kt  double-tap lock (reused for "time's up")
    helper/usageStats/          UsageStatsManager event reader
gradle/libs.versions.toml       version catalog shared by both builds
```

New code goes under `uk.co.softwarecrafts.contextlauncher.*`. Borrowed
Olauncher code stays under `app.olauncher` so provenance is obvious and
upstream cherry-picks stay possible. Do not rename it.

Gradle: the root `settings.gradle` does `includeBuild 'core'` (a composite
build). The app depends on `uk.co.softwarecrafts:core`; Gradle substitutes the
included build. `core/settings.gradle` reuses the root version catalog.

## Architecture rules

- Domain logic (stage resolution, gating, countdown escalation, weekly-review
  rules, Todoist sync state) lives in `core` as plain Kotlin behind the `Clock`
  interface, with JUnit tests using `FakeClock`. The app only adapts Android
  inputs (calendar rows, Todoist JSON, usage events) into core types.
- Room holds config and the usage log. Repositories wrap DAOs; UI never runs
  SQL.
- The Todoist token is entered in-app and stored with
  `androidx.security:security-crypto` EncryptedSharedPreferences (deprecated
  upstream but functional). It is excluded from Android auto-backup. Nothing
  secret is committed.
- "Time's up" is enforced by the accessibility service (window-change events
  plus `GLOBAL_ACTION_HOME`), with a re-check whenever the launcher resumes.
  Usage-stats polling is the fallback when the service is off.
- Small, focused commits. One phase at a time; stop after each phase and give
  build and on-device test steps.

## Decisions

- applicationId `uk.co.softwarecrafts.contextlauncher` (hyphens are not legal
  in Android package names). Debug builds append `.debug`.
- minSdk 33: the Fairphone 5 runs Android 14+. Legacy `Build.VERSION` branches
  can be removed when touched.
- Kept from Olauncher: swipe up (drawer), swipe down (notifications), swipe
  left/right apps, double-tap lock, long-press settings, clock and date, home
  alignment, status bar toggle, theme, bold font, text size, auto keyboard,
  rename apps, app info, uninstall, set-as-default flow.
- Dropped in phase 0: daily wallpaper, rate/share/review/Pro dialogs and
  links, translations, Play Store metadata.
- Deferred to phase 4 (home and drawer are rebuilt there): pinned home-apps
  grid, hidden apps, Private Space, screen-time line, pinned shortcuts.
- Upstream already declares INTERNET and PACKAGE_USAGE_STATS; nothing to add.

## Phase plan

0. Fork baseline (done): rename, strip upstream extras, core module, this file.
1. Data layer: Room entities (stages, allowlists with optional caps, label to
   app-group map, always-allowed apps, event log), JSON import/export.
2. Stage engine: CalendarContract reader + core `StageResolver` with fake-clock
   tests; stage banner on the home screen.
3. Todoist: unified API v1, incremental `/sync`, offline cache, complete from
   launcher, 15-minute WorkManager refresh.
4. Home and gating: text-only home, greyed off-list apps in search, friction
   screen, countdown escalation, accessibility-driven return to home.
5. Voice actions: speak (on-device SpeechRecognizer) and add task (Ramble
   shortcut, long-press Quick Add).
6. Usage logging and weekly review with one-tap Apply suggestions.
7. Settings screens.

Open spec questions and the defaults in use are tracked in the session
conversation until settled; settled ones get recorded here.
