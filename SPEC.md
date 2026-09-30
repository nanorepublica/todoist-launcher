# Context launcher — v1 spec

Sep 30, 2026 · @Andrew Miller

## Overview

A text-only Android launcher that narrows the phone to what the current part of the day needs, and opens up once that stage's tasks are done. It replaces Before Launcher on a Fairphone 5.

- **Goal:** fewer distractions when opening the phone; finish a set of tasks before moving to the next part of the day.
- **Principles:** text only, no icons; data-driven (every bypass and minute is logged); friction over hard blocks.
- **Build:** fork upstream [Olauncher](https://github.com/tanujnotes/Olauncher) (Kotlin, GPLv3), borrowing ideas from [barakh's blocking fork](https://github.com/barakh/Olauncher). Personal use first; open source, may publish.
- **Core model:** calendar sets the stage and its time window; Todoist labels define what "done" means for that stage.

## Stages

The day runs as named stages from a dedicated Google calendar. Task stages unlock early when their tasks are done; time stages simply hold a state.

| Stage | Type | Starts | "Done" / ends | Phone state |
| --- | --- | --- | --- | --- |
| Morning routine | Task | Calendar block | Routine tasks ticked (incl. exercise) | Narrow; daily games unlock for 10 min once done |
| Work AM | Time | Calendar block | Block ends | Work apps + task-linked admin apps |
| Lunch | Time | Calendar block | Block ends | Same as work; no special unlock |
| Work PM | Time | Calendar block | Block ends | As Work AM |
| Family (pickup, dinner) | Time | Calendar block | Block ends | Locked down; Claude, music, YouTube (15 min fixed cap) |
| Kids' bedtime | Time | Calendar block | "Kids down" task ticked | Locked down |
| After bedtime | Task-triggered | "Kids down" ticked | 21:00 | Small set open; unlocks capped at 5 min |
| Wind-down | Time | 21:00 fixed | Next morning | Locked down |
| Weekly review | Task-triggered | `@ph_review` task due | Review completed | Launcher forces the review |

- **Weekends:** same calendar, weekend-named stages; the morning routine still applies.
- **Gaps with no block:** weekdays fall to a default stage; weekends default to locked-down family time.
- **Overlaps:** the most restrictive stage wins.

## Gating and friction

Apps outside the current stage stay reachable through search, greyed out, behind a friction screen. No hard blocking in v1.

- **Friction screen:** optional typed reason plus a required time limit. The stage sets the maximum limit (e.g. none at work, 5 min after bedtime).
- **Time's up:** the launcher returns you to the home screen.
- **Countdown escalation** per stage block: 1st bypass no wait, 2nd 10 s, 3rd 15 s, 4th+ 30 s. Resets when the stage changes.
- **Always allowed:** Phone and Camera sit outside the cap; up to 4 more apps, configurable.
- **Known limit:** a launcher only controls launches from its own screen; notifications and recents bypass it.

## Tasks and Todoist

Stage labels on tasks decide what "done" means; existing Todoist projects stay as they are.

- **Stage labels:** e.g. `@ph_morning`, `@ph_review`. Tasks carrying the current stage's label gate its early unlock.
- **Other tasks due today:** shown on the home screen, but they gate nothing.
- **Task-linked apps:** a Todoist label maps to an app group in launcher settings (e.g. `@banking` → banking apps). While such a task is open, those apps are allowed in any stage.
- **Ticking off:** tasks can be completed from the launcher without opening Todoist.

## Home screen and voice

The home screen shows the current stage, today's tasks, the allowed apps and two plain-text actions. There is no live bypass count.

| Action | Tap | Long-press | Available |
| --- | --- | --- | --- |
| speak | Record, transcribe on-device, then choose: task, Claude, or copy | — | Every stage; Claude destination hidden in wind-down |
| add task | Todoist Ramble in listening mode (app shortcut) | Typed Quick Add | Every stage |

- **Transcription:** Android's built-in on-device recognizer, always. Audio is not kept.
- **Routing:** tasks go in through the Todoist API; Claude receives text through Android's share handoff; copy puts it on the clipboard.

## Data, sync and config

Everything runs on the phone except Todoist calls; if Todoist can't be reached, the launcher keeps its last known state.

- **Calendar:** the dedicated Google calendar, synced to the Fairphone and read on-device. No API key; works offline.
- **Todoist:** [API v1](https://developer.todoist.com/api/v1/) with a personal token pasted in settings. Fetched when the launcher opens plus a background refresh every 15 min, using incremental `/sync`. OAuth only if published.
- **Network:** the fork adds the internet permission that Olauncher leaves out.
- **Storage:** a local SQLite database (Room) holds config and the usage log.
- **Config:** edited through in-app settings screens. Import and export as a JSON file for backup and sharing.

## Usage data and weekly review

The launcher logs every gate event and minute of app use, and a forced weekly review turns them into one-tap changes.

- **Logged:** each off-list open (app, stage, optional reason, chosen limit, bypass number), stage changes, task ticks, plus time in each app via Android's usage-access permission.
- **Trigger:** a recurring Todoist task labelled `@ph_review`. When it falls due, the launcher enters the review stage; finishing the review ticks the task. Moving the task in Todoist moves the review.
- **Suggestions:** rule-based on-device checks in v1, e.g. "Instagram bypassed 6× during work, averaging 12 min against a 5-min limit: lower the cap?"
- **Apply:** every suggestion has a one-tap Apply that changes the setting.
- **Export:** JSON for v1.

## Roadmap and open checks

v1 is everything above; v2 adds AI and stronger enforcement once the rules have proved themselves.

**v2 candidates**

- [Jev](https://typesafe.ai/) from TypeSafe AI classifies weekly patterns into typed suggestions, shown only at high confidence. Cloud API in early access, so the summary leaves the phone.
- Jev pre-selects the likely destination for a "speak" transcript.
- Per-stage gating strength, e.g. a hard block during deep work.
- Filtered notifications, like Before Launcher's, following the current stage — closes the notification gap noted under gating.
- OAuth for Todoist if published.

**Checks on the device**

- [ ] Confirm Todoist exposes Ramble as an app shortcut the launcher can start
- [ ] Confirm the Claude app accepts shared text into a new chat
- [ ] Confirm on-device transcription works offline on the Fairphone 5
- [ ] Name the weekday default stage for gaps with no calendar block
