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

# App unit tests, including the Robolectric launch smoke test that boots
# MainActivity in the JVM (first run -> onboarding; after setup -> resolve)
./gradlew :app:testDebugUnitTest
```

If Robolectric fails to fetch `android-all-instrumented` with HTTP 429, the
session proxy capped its parallel downloads: fetch the jar, .pom and both
.sha512 files one at a time with curl into
`~/.m2/repository/org/robolectric/android-all-instrumented/<version>/` and
rerun.

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
    todoist/TodoistModels.kt    API v1 wire shapes (items, labels, commands)
    todoist/TodoistMapper.kt    due-date parsing in the user's zone -> TaskSnapshot
    todoist/TaskCache.kt        pure merge rules for full/incremental sync
    todoist/TodayTasks.kt       home-screen rows: gating first, then overdue
    gate/AllowedApps.kt         Gatekeeper: allowlist for a resolution + launch decision
    gate/Escalation.kt          0/10/15/30 s ladder, per-block BypassCounter, limit options
    gate/TimedSession.kt        one running timed session + SessionRules for enforcement
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
  src/test/java/uk/co/softwarecrafts/contextlauncher/
    LaunchSmokeTest.kt          Robolectric boot test; add a case per new screen
    ui/OnboardingFragment.kt    first-run setup, re-openable from Settings > Context
    ui/HomeFragment.kt          text-only home: stage, clock, session, tasks, allowed apps
    ui/AppListFragment.kt       searchable app list; off-list apps greyed, gated on tap
    ui/FrictionFragment.kt      bypass screen: reason, time-limit pills, countdown
    gate/GateController.kt      decisions, session + bypass counter (persisted), enforcement
    calendar/CalendarStore.kt   CalendarContract: list, create local, read, seed
    engine/StageEngine.kt       StateFlow<StageState>; re-resolves on resume,
                                calendar change and at nextChangeAt
    data/AppPrefs.kt            non-exportable flags (onboardingDone)
    data/todoist/TodoistApi.kt  OkHttp calls: sync, completed-by-date, labels, quick add
    data/todoist/TodoistRepository.kt  cache in Room, complete/quick-add, is the engine's TaskSource
    data/todoist/TokenStore.kt  EncryptedSharedPreferences (todoist_secure, backup-excluded)
    data/todoist/TodoistSyncWorker.kt  15-minute WorkManager refresh
  src/main/java/app/olauncher/  borrowed Olauncher code, package kept as-is
    MainActivity.kt             single activity, nav host, back handling
    MainViewModel.kt            app list, launching, home apps, screen time
    ui/SettingsFragment.kt      settings screen
    data/Prefs.kt               SharedPreferences wrapper
    helper/Utils.kt             app list loading, launcher-default checks
    helper/MyAccessibilityService.kt  double-tap lock + window tracking for "time's up"
    listener/OnSwipeTouchListener.kt  home gestures
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
- Removed in phase 4 with the new home and app list: pinned home-apps grid,
  hidden apps, Private Space UI, screen-time line, pinned shortcuts. Some
  unused MainViewModel/Prefs code for them remains; prune when touched.
- Gating fails open: until setup is complete (no stage resolved) every app
  launches without friction.
- The accessibility service runs in the main process (Olauncher had it in
  ":serviceProcess") so it shares the GateController singleton.
- Upstream already declares INTERNET and PACKAGE_USAGE_STATS; nothing to add.
- Gestures: swipe up or left opens the app list; swipe right is reserved for
  the filtered notification list (v2, so a no-op in v1). This retires
  Olauncher's "swipe left/right app" setting.
- App classes beyond the stage allowlists: `hiddenApps` (background-only,
  never listed), groups of kind `unrestricted` (allowed in every stage,
  shown on the home screen under the group name) and kind `occasional`
  (listed only while a search is typed, gated normally). Long-press in the
  app list toggles them; Settings > Context > Hidden apps unhides.

## Phase plan

0. Fork baseline (done): rename, strip upstream extras, core module, this file.
1. Data layer (done): Room entities (stages, allowlists with optional caps,
   label to app-group map, always-allowed apps, event log), JSON
   import/export from Settings > Context.
2. Stage engine (done): CalendarContract reader + core `StageResolver` with
   fake-clock tests; stage line on the home screen; onboarding (default
   launcher, calendar permission, create or pick the stage calendar, seed
   the default schedule).
3. Todoist (done): unified API v1, incremental `/sync` with a stored sync
   token, offline cache in Room (`tasks`, `sync_state`), complete from the
   home screen via `item_close`, quick add, 15-minute WorkManager refresh
   plus a sync on every launcher resume (60 s throttle), token step in Setup.
4. Home and gating (done): text-only home (stage, clock, session line,
   tasks, allowed apps), app list with greyed off-list apps, friction screen
   with reason + capped time-limit pills + 0/10/15/30 s countdown per stage
   block, capped allowed apps open with a timer, "time's up" via the
   accessibility service (main process, window-state events,
   GLOBAL_ACTION_HOME) with a notification fallback and a re-check on
   resume. Olauncher's home grid, drawer, hidden apps, Private Space UI and
   swipe-app settings are gone.
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
- Stage labels are `phone/morning`, `phone/kidsdown`, `phone/review` (the
  spec's `ph_` prefix was replaced at the user's request). Labels the config
  refers to are created in Todoist on the first full sync if missing.
- A full sync omits completed items, so the repository also fetches today's
  completions from `/tasks/completed/by_completion_date`; incremental syncs
  carry completions as `checked` items. Completing from the launcher is
  optimistic and reverted on failure.
- A task stage with no tasks carrying its label today simply runs its
  calendar block; "done" needs at least one such task, all complete.
- The stage calendar is identified by display name (portable in the config
  export). Onboarding can create a device-local calendar ("Phone stages",
  ACCOUNT_TYPE_LOCAL: on this phone only, never synced) or use any synced
  calendar the user picks. Event titles match stage names, case-insensitive.
  Verified on the Fairphone 5: the Google Calendar app lists the local
  calendar under a non-Google account, and Fossify Calendar shows it once
  "CalDAV sync" is on in its settings. The local calendar is therefore the
  recommended route; a Google-account calendar (created on the web via a
  button that opens the create-calendar page) is the option for web editing.
- Default schedule (weekdays): Morning routine 06:30-07:30, Work AM
  09:00-12:30, Lunch 12:30-13:30, Work PM 13:30-17:00, Family 17:00-19:00,
  Kids' bedtime 19:00-20:00. Weekends: Morning routine 07:00-08:00, Kids'
  bedtime 19:00-20:00. Written as weekly recurring events; edit in a
  calendar app.
