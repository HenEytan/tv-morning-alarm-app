# TV Morning Alarm

Wakes an LG webOS TV and starts a Spotify playlist on a schedule \u2014 a
DIY morning alarm that plays through your TV's speakers instead of a
phone.

**Latest release:** [v1.0.0](https://github.com/HenEytan/tv-morning-alarm-app/releases/tag/v1.0.0)

## What it does

1. Connects to your LG webOS TV over the local network (LG's SSAP remote
   protocol) and pairs with it.
2. On a schedule you set, wakes the TV (Wake-on-LAN, if a MAC address is
   available) and waits for it to come online.
3. Launches the Spotify app on the TV and starts your chosen playlist,
   at a wake-up volume you set.

## Requirements

- An LG TV running webOS, on the same Wi-Fi network as the device
  running this app.
- An Android device or box (this was built for and tested on an
  Android TV box) to run the app and act as the alarm clock \u2014 it needs
  to stay powered on and reachable on the network for the schedule to
  fire.
- The Spotify app already installed and signed in on the TV itself.

## Getting started

1. **Install the APK.** Grab the latest `app-debug.apk` from
   [Releases](https://github.com/HenEytan/tv-morning-alarm-app/releases),
   or build it yourself (see below). Sideload it onto the Android
   device/box that will run the alarm.
2. **Open the app and tap "Connect to TV."** It scans the network,
   finds your TV, and pairs automatically. Accept the pairing prompt
   that appears on the TV screen. If it finds your MAC address
   automatically, Wake-on-LAN is ready to go; if not, the app tells you
   and you can add it manually under **Advanced** (find it in the TV's
   own Settings \u2192 Network menu).
3. **Paste your Spotify playlist.** Either a playlist share link
   (`https://open.spotify.com/playlist/...`) or a `spotify:playlist:...`
   URI both work \u2014 the app converts it automatically.
4. **Set the time, days, and wake-up volume**, then tap
   **Save + Schedule Alarm**.
5. **Tap "Run Now"** anytime to test the whole flow immediately without
   waiting for the schedule.

Nothing is saved until you tap **Save + Schedule Alarm** (or **Run
Now**, which saves as a side effect) \u2014 editing a field alone doesn't
persist it.

## Troubleshooting

- **"Can't reach the TV"** \u2014 make sure the TV is powered on (or already
  awake) and on the same network.
- **No MAC found automatically** \u2014 some webOS firmware doesn't expose
  the MAC over the remote protocol, and some Android devices block
  reading it from the local network cache as a fallback. In that case,
  enter it manually under Advanced; find it on the TV itself via
  Settings \u2192 Network.
- **Alarm didn't fire** \u2014 check the in-app **Debug Log** (bottom of the
  screen) for a full timestamped trace of the last run, including SSAP
  requests/responses. Long-press the log button to clear it.
- Make sure battery optimization is disabled for the app (it prompts
  for this after your first successful save) so Android doesn't kill
  it in the background before the scheduled time.

## Building from source

This repo builds via GitHub Actions (`.github/workflows/build-apk.yml`)
on every push to `main`, publishing a debug APK to a `build-N` tag. To
build locally instead:

```
./gradlew assembleDebug
```

The output APK will be under `app/build/outputs/apk/debug/`.

## Tech notes

- Talks to the TV over LG's SSAP protocol via WebSocket (`ws://` port
  3000, `wss://` port 3001), the same protocol LG's own mobile remote
  app uses.
- TV discovery uses SSDP (UPnP) multicast to find LG-classified devices
  on the network.
- Scheduling uses Android's exact alarms (`AlarmManager`) with
  `WorkManager` handling the actual wake/launch sequence in the
  background.
