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

Cloud sessions start from a clean container with no Android SDK. Run
`scripts/install-android-sdk.sh` once per session (needs `dl.google.com` on
the environment's network allowlist; it is) before any `:app` task. The
`core` build only needs Maven Central, which is why it is a separate Gradle
build (see below): its tests run even without the SDK.

## Layout

```
SPEC.md                         v1 spec (source of truth)
core/                           standalone Gradle build, pure Kotlin/JVM
  src/main/kotlin/uk/co/softwarecrafts/contextlauncher/core/
    Clock.kt                    Clock interface + SystemClock
    config/Config.kt            LauncherConfig, Stage, StageTrigger, AppGroup...
    config/ConfigJson.kt        the one JSON codec (export file + Room columns)
    config/ConfigValidator.kt   cross-field checks; import is rejected on problems
    config/SeedConfig.kt        SPEC.md stage table as the first-run config
    log/LogEvent.kt             usage-log record + EventType
    stage/StageResolver.kt      which stage is active now (stateless, tested)
    stage/StageTracker.kt       block changes for per-block counters and logging
    stage/Inputs.kt             CalendarEvent, TaskSnapshot
    stage/Resolution.kt         Activation, ActivePerk, Resolution
    stage/DefaultSchedule.kt    weekly blocks onboarding writes to the calendar
  src/test/kotlin/.../core/
    FakeClock.kt                settable clock for tests
app/                            Android app (Olauncher fork)
  schemas/                      Room schema history (commit every version)
  src/main/java/uk/co/softwarecrafts/contextlauncher/   new code
    Graph.kt                    lazy singletons: database, repositories, clock
    data/db/                    Room entities, DAOs, AppDatabase, Mappers
    data/ConfigRepository.kt    load/save/seed/export/import of LauncherConfig
    data/EventLogRepository.kt  append-only usage log
    ui/ConfigTransfer.kt        JSON export/import via the system file picker
    ui/OnboardingFragment.kt    first-run setup, re-openable from Settings > Context
    calendar/CalendarStore.kt   CalendarContract: list, create local, read, seed
    engine/StageEngine.kt       StateFlow<StageState>; re-resolves on resume,
                                calendar change and at nextChangeAt
    data/AppPrefs.kt            non-exportable flags (onboardingDone)
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
scripts/install-android-sdk.sh  SDK bootstrap for cloud sessions
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
  SQL. Lists inside a row (allowed apps, group ids) and the stage trigger are
  JSON text columns encoded with the core codec, not join tables. Schema
  changes: bump AppDatabase.VERSION, add a Migration, commit app/schemas.
- Dependencies come from `Graph` (a small service locator), not a DI
  framework.
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
- Gestures (for phase 4): swipe right opens the app list; swipe left is
  reserved for the filtered notification list (v2, so a no-op in v1). This
  retires Olauncher's "swipe left/right app" setting.

## Phase plan

0. Fork baseline (done): rename, strip upstream extras, core module, this file.
1. Data layer (done): Room entities (stages, allowlists with optional caps,
   label to app-group map, always-allowed apps, event log), JSON
   import/export from Settings > Context.
2. Stage engine (done): CalendarContract reader + core `StageResolver` with
   fake-clock tests; stage line on the home screen; onboarding (default
   launcher, calendar permission, create or pick the stage calendar, seed
   the default schedule).
3. Todoist: unified API v1, incremental `/sync`, offline cache, complete from
   launcher, 15-minute WorkManager refresh.
4. Home and gating: text-only home, greyed off-list apps in search, friction
   screen, countdown escalation, accessibility-driven return to home.
5. Voice actions: speak (on-device SpeechRecognizer) and add task (Ramble
   shortcut, long-press Quick Add).
6. Usage logging and weekly review with one-tap Apply suggestions.
7. Settings screens.

## Spec defaults in use (agreed, override in settings later)

- Weekday default stage for calendar gaps: "Default", Work allowlist, no cap.
- "Most restrictive" on overlap: an explicit restrictiveness rank on each
  stage, editable in settings. Weekly review ranks highest, Default lowest.
- After a task stage unlocks early: the stage ends and the phone falls to the
  next calendar block or the gap default; post-completion perks (10 min of
  daily games after the morning routine) run alongside.
- Capped allowed apps (YouTube in Family): launch directly with the timer
  running, no reason prompt, not counted as a bypass.
- Task-linked app groups: only tasks due today or overdue unlock their group.
- Weekly review "falls due": due date is today or earlier; if the task has a
  time, that time has passed.
- A task stage with no tasks carrying its label today simply runs its
  calendar block; "done" needs at least one such task, all complete.
- The stage calendar is identified by display name (portable in the config
  export). Onboarding can create a device-local calendar ("Phone stages",
  ACCOUNT_TYPE_LOCAL: on this phone only, never synced) or use any synced
  calendar the user picks. Event titles match stage names, case-insensitive.
- Default schedule (weekdays): Morning routine 06:30-07:30, Work AM
  09:00-12:30, Lunch 12:30-13:30, Work PM 13:30-17:00, Family 17:00-19:00,
  Kids' bedtime 19:00-20:00. Weekends: Morning routine 07:00-08:00, Kids'
  bedtime 19:00-20:00. Written as weekly recurring events; edit in a
  calendar app.
