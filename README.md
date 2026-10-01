# Context launcher

A minimal, text-only Android launcher that narrows the phone to what the
current part of the day needs, and opens up once that stage's tasks are done.

- The day runs as named stages from a dedicated Google calendar.
- Todoist labels decide what "done" means for a stage.
- Apps outside the current stage stay reachable through search, greyed out,
  behind a friction screen with a time limit. No hard blocking.
- Every bypass and minute is logged, and a weekly review turns the log into
  one-tap changes.

See [SPEC.md](SPEC.md) for the full v1 spec and [CLAUDE.md](CLAUDE.md) for
build commands, layout and design decisions.

## Building

Debug builds need the Android SDK (Android Studio is the easy way):

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The engine tests run on any JVM without the SDK:

```sh
cd core && ../gradlew test
```

## Status

Personal use first, built for a Fairphone 5. All seven v1 phases (fork, data layer,
stage engine, Todoist, home and gating, voice, weekly review, settings) are
done; what follows is iteration on the home screen. See the phase plan in CLAUDE.md.

## Credits and license

Forked from [Olauncher](https://github.com/tanujnotes/Olauncher) by Tanuj
Sharma, with ideas from [barakh's fork](https://github.com/barakh/Olauncher).
Licensed under the [GNU GPLv3](LICENSE).
