# TV Morning Alarm

Wakes an LG webOS TV and starts a Spotify playlist on a schedule — a
DIY morning alarm that plays through your TV's speakers instead of a
phone.

**Latest release:** the newest `build-N` on the
[Releases](https://github.com/HenEytan/tv-morning-alarm-app/releases) page
(`v1.0.0` is an old tag the in-app updater does not read).

## What it does

1. Connects to your LG webOS TV over the local network (LG's SSAP remote
   protocol) and pairs with it.
2. On a schedule you set, wakes the TV (Wake-on-LAN, if a MAC address is
   available) and waits for it to come online.
3. Launches the Spotify app on the TV and starts your chosen playlist,
   at a wake-up volume you set.

## Backup and updates

**Your settings are backed up.** The TV address, MAC, playlist, time, days and
volume were all typed in by hand and lived only on this device. The app now
keeps a copy of them on the device once a day (three kept), and **Backup &
updates → Save settings to a file** writes one you can keep somewhere else —
that is the copy that survives a reinstall or a new box. Every restore first
takes a copy of what is there now, and refuses to go ahead if it cannot.
**Restore the on-device copy** offers the newest copy that differs from the
settings on screen — usually the one from before your last change, or, right
after a restore, the settings the restore replaced — so restoring the wrong
file is itself reversible. A restore re-arms (or cancels) the alarm from what it
restored; restored numbers are range-checked.

The TV **pairing key is never in a backup**. It is a credential for your
television and a backup file travels; you pair again after restoring, which is
one prompt on the TV screen.

**The app updates itself.** It asks GitHub once a day whether a newer build
exists and downloads it; the install waits for a tap on **Install build N** and
goes over the running app — no uninstall, settings kept. Android asks once for
permission to let the app install its own updates.

There is one condition, and the app is explicit about it rather than failing
late: **Android replaces an install only when the new APK carries the same
signing certificate.** A debug build is signed with a key the runner generates
for itself, so no two of them match and the updater refuses one by name
("signed with a different key — save your settings to a file, then install by
hand"). CI therefore **publishes only release-signed builds, and only from
`main`**: a run without the signing secrets fails instead of publishing, and a
run from any other branch (or a pull request) keeps its APK as a workflow
artifact and publishes nothing. The secrets CI signs with:

| Secret | What it is |
|---|---|
| `ANDROID_KEYSTORE_B64` | the keystore, base64-encoded (`base64 -w0 release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | its store password |
| `ANDROID_KEY_ALIAS` | the key alias inside it |
| `ANDROID_KEY_PASSWORD` | that key's password |

Keep the keystore. Losing it means no future build can update an existing
install — everyone has to uninstall first.

## Requirements

- An LG TV running webOS, on the same Wi-Fi network as the device
  running this app.
- An Android device or box (this was built for and tested on an
  Android TV box) to run the app and act as the alarm clock — it needs
  to stay powered on and reachable on the network for the schedule to
  fire.
- The Spotify app already installed and signed in on the TV itself.

## Getting started

1. **Install the APK.** Grab the latest APK from
   [Releases](https://github.com/HenEytan/tv-morning-alarm-app/releases),
   or build it yourself (see below). Sideload it onto the Android
   device/box that will run the alarm.
2. **Open the app and tap "Connect to TV."** It scans the network,
   finds your TV, and pairs automatically. Accept the pairing prompt
   that appears on the TV screen. If it finds your MAC address
   automatically, Wake-on-LAN is ready to go; if not, the app tells you
   and you can add it manually under **Advanced** (find it in the TV's
   own Settings → Network menu).
3. **Paste your Spotify playlist.** Either a playlist share link
   (`https://open.spotify.com/playlist/...`) or a `spotify:playlist:...`
   URI both work — the app converts it automatically.
4. **Set the time, days, and wake-up volume**, then tap
   **Save + Schedule Alarm**.
5. **Tap "Run Now"** anytime to test the whole flow immediately without
   waiting for the schedule.

Nothing is saved until you tap **Save + Schedule Alarm** (or **Run
Now**, which saves as a side effect) — editing a field alone doesn't
persist it.

## Troubleshooting

- **"Can't reach the TV"** — make sure the TV is powered on (or already
  awake) and on the same network.
- **No MAC found automatically** — some webOS firmware doesn't expose
  the MAC over the remote protocol, and some Android devices block
  reading it from the local network cache as a fallback. In that case,
  enter it manually under Advanced; find it on the TV itself via
  Settings → Network.
- **Alarm didn't fire** — check the in-app **Debug Log** (bottom of the
  screen) for a full timestamped trace of the last run, including SSAP
  requests/responses (the pairing key is redacted from it). Long-press the
  log button to clear it. A red "Saved … but NOT armed" line on the
  schedule means Android refused the exact alarm — grant "Alarms &
  reminders" and tap Save again.
- Make sure battery optimization is disabled for the app (it prompts
  for this after your first successful save) so Android doesn't kill
  it in the background before the scheduled time.

## Building from source

This repo builds via GitHub Actions (`.github/workflows/build-apk.yml`)
on every push to `main` and on pull requests: unit tests and lint (both
gate), then the APK. A push to `main` with the signing secrets set publishes
the release-signed APK to a `build-N` release, which is what installed apps
update from; a push to `main` without them fails. Anything else builds a
debug APK and keeps it as a workflow artifact only — it is never signed with
the release key. The `versionCode` is the run number `N`.

To build locally (the Gradle wrapper is committed; the Android SDK is
needed):

```
./gradlew testDebugUnitTest   # the JVM unit tests
./gradlew assembleDebug
```

The output APK will be under `app/build/outputs/apk/debug/`. A local
build is versioned `1.0 (dev)` with versionCode 1, so it sees every
published build as newer — and then refuses to install it ("signed with a
different key"), because a debug build carries this machine's debug key, not
the release key. Install a published build over a local one by uninstalling
first (save your settings to a file before). For a release-signed local build put a
`keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) in the repo root and run `./gradlew assembleRelease`.

## Tech notes

- Talks to the TV over LG's SSAP protocol via WebSocket (`wss://` port
  3001 first, then `ws://` port 3000), the same protocol LG's own mobile
  remote app uses.
- TV discovery uses SSDP (UPnP) multicast to find LG-classified devices
  on the network.
- Scheduling uses Android's exact alarms (`AlarmManager`) with
  `WorkManager` handling the actual wake/launch sequence in the
  background.
