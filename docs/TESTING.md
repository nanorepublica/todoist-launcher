# Testing the launcher

Three layers: unit tests that run on every build, a debug-only time machine
so a day's worth of stage changes fits in ten minutes, and manual walkthroughs
with the result you should see at each step.

## 1. Automated

```sh
cd core && ../gradlew test            # engine rules, 58 tests, no SDK needed
./gradlew :app:testDebugUnitTest      # Robolectric: boots every screen, swipes from a row
```

## 2. Time travel (debug build only)

The debug build runs on a shifted clock. Stages, timed sessions, task due
dates and the review period all follow it; the real clock is untouched and
release builds have none of this. The shift survives a restart.

On the phone: long-press the home, Settings > "Debug: time travel and
scenarios". Set time, set date, +15 min, +1 hour, +1 day, Real time. The home
clock shows the shifted time.

From a computer with the phone on adb:

```sh
D="adb shell am broadcast -a uk.co.softwarecrafts.contextlauncher.DEBUG -n uk.co.softwarecrafts.contextlauncher.debug/uk.co.softwarecrafts.contextlauncher.debug.DebugReceiver"
$D --es time 19:05            # today at 19:05
$D --es date 2026-10-03       # another day, same time of day
$D --es advance 15m           # 15m, 2h, 1d
$D --ez reset true            # back to real time
$D --es scenario review_due   # run a scenario by id (table below)
$D --ez cleanup true          # delete the test tasks, real time
adb logcat -s ContextLauncherDebug   # results of scenario and cleanup broadcasts
```

## 3. Scenarios

Each scenario sets the clock to a moment in the default schedule (weekday
unless noted) and creates the Todoist tasks it needs, every one ending in
"(launcher test)". Run them from the Debug screen (the dialog repeats the
steps below) or by id over adb. "Clean up" deletes the tasks and resets the
clock. Needs: setup finished, the stage calendar with the default schedule,
a Todoist token.

| id | Moment | What it proves |
|----|--------|----------------|
| `stage_change` | 16:58 | A stage ends and the next starts on the clock |
| `morning_done` | 06:40 | Completing the labelled task ends the stage early and unlocks the perk |
| `bypass` | 10:00 | Friction screen, time limits, 0/10/15/30 s escalation, time's up |
| `capped` | 17:30 | Capped allowed app opens with a timer, no reason, not a bypass |
| `label_group` | 10:00 | A task's label unlocks its app group in any stage |
| `kids_down` | 19:10 | Kids' bedtime done, After bedtime until 21:00, then Wind-down |
| `review_due` | 10:00 | Review task due forces the review; Finish completes it |
| `wind_down` | 21:30 | Wind-down hides Claude from speak |
| `weekend` | Sat/Sun 07:15 | Weekend schedule and weekend default |

### stage_change
1. Run. Home: "Work PM · until 17:00", Work apps listed.
2. Wait up to two minutes. At 17:00 the line becomes "Family · until 19:00"
   and the apps become Claude, Spotify, YouTube · 15 min. No tap needed.

### morning_done
1. Run. Home: "Morning routine · until 07:30". Under Today: "● Exercise".
2. Tap the task, confirm. Toast "Completed". The stage line changes to
   "Default" (the weekday gap stage) and the Games group is listed for 10
   minutes (the perk). The task is gone from Today.
3. Debug > +15 min: the Games group disappears.

### bypass
1. Run. Home: "Work AM · until 12:30".
2. Swipe up. YouTube is greyed. Tap it: friction screen, no countdown the
   first time. Type a reason, tap "1 min". YouTube opens.
3. Back on the home the session line reads "YouTube · 0:5x left". Open the
   drawer and tap YouTube again: countdown 10 s before the pills enable.
   Third time 15 s, fourth 30 s.
4. Let a minute pass (or adb `advance 1m`). With the accessibility service
   on the launcher takes over the screen; otherwise a "time's up"
   notification appears.
5. Debug > Show last 20 events: BYPASS rows with your reasons and limits,
   TIMES_UP rows.

### capped
1. Run. Home: "Family · until 19:00" with "YouTube · 15 min".
2. Tap YouTube on the home. It opens at once, toast "YouTube for 15 min".
   Home shows the countdown.
3. adb `advance 15m`, or Debug > +15 min and reopen YouTube: time's up.
4. Events show CAPPED_OPEN then TIMES_UP, no BYPASS.

### label_group
1. Run. Home: Work AM, plus "○ Pay the gas bill" under Today, and the
   Banking group's apps under Apps.
2. Complete the task. The Banking apps disappear.

### kids_down
1. Run. Home: "Kids' bedtime · until 20:00", "● Kids down".
2. Complete it. Line becomes "After bedtime · until 21:00", Evening apps
   (Claude, Spotify) listed.
3. Debug > +1 hour, then +1 hour: "Wind-down · until 06:00".

### review_due
1. Run. The review screen opens by itself (stage line reads "Weekly review").
2. Back. Tap the stage line: it reopens. Scroll: bypasses from the other
   scenarios, app time (if usage access is on), suggestions once an app has
   three bypasses in one stage.
3. "Finish and complete task". Toast "Review finished, task completed". The
   stage falls back to Work AM.

### wind_down
1. Run. "Wind-down · until 06:00". Tap speak: "to task" and "copy" only.

### weekend
1. Run. "Morning routine · until 08:00". Debug > +1 hour: "Weekend", no
   end time.

## 4. Without the time machine

Things the shifted clock cannot stand in for:

- **Calendar edits**: move a block in the calendar app; the home follows
  within a few seconds (content observer).
- **Todoist round trip**: add a task in Todoist with @phone/morning due
  today; it appears on the home within 15 minutes, or at once after
  Settings > Sync Todoist now.
- **Accessibility enforcement**: with the service on, "time's up" lands you
  on the home even when another app is in front.
- **Speech**: speak, say a sentence, send to task; check it in Todoist.
