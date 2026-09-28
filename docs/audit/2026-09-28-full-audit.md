# Full audit: tv-morning-alarm-app (2026-09-28)

Audited commit: `7c43813` (`main`, "Fix the two compile errors the updater merged with").
Scope: every Kotlin source file (16 files, about 2,750 lines), the manifest, resources, Gradle files, the CI workflow, the README, and the GitHub release and Actions state.

## Summary and verdict

The app is an Android app for a phone or an Android TV box. On a schedule it sends Wake-on-LAN to an LG webOS TV, pairs with the TV over SSAP (WebSocket on ports 3000 and 3001), sets the volume and launches a Spotify playlist on the TV. It has three more features: a settings backup (a daily copy on the device, plus export and import of a file), an in-app updater that reads GitHub `releases/latest` from this public repo, and a persistent debug log.

**Verdict: the alarm core works for its main use but has gaps. The updater's device-side code is careful. The release pipeline feeding it is the biggest risk.**

The alarm path is careful about the failures it has already hit (wake and Wi-Fi locks, unique work, pinning traffic to Wi-Fi, early-fire guard). It does not handle time-zone or clock changes, and the restore flow leaves the UI and the armed alarm out of step with the stored settings.

On the device, the updater correctly refuses a wrong package or a wrong signer before installing. But the workflow publishes a normal, "latest" release from any branch on `workflow_dispatch`, and it falls back to a debug build when the signing secrets are missing. Either one reaches every installed device through the updater.

The TV pairing key is written to the shareable debug log and is covered by Android Auto Backup. That contradicts the README's claim that the key is never in a backup.

There are no tests and no lint. CI has failed on its last two runs, and build-121 was built and published by hand.

Counts: **Critical 0 · High 1 · Medium 8 · Low 14**. Of these, 2 are marked Plausible.

## Findings

| ID | Sev | Area | Location | Finding | Suggested fix |
|---|---|---|---|---|---|
| F1 | High | CI / updater | `.github/workflows/build-apk.yml:3-6,82-85` | `workflow_dispatch` on any branch publishes a normal (not pre-release) `build-N` release, which becomes `releases/latest`. The updater then downloads it and installs it on every device. build-117 was published this way from branch `claude/disabled-enabled-toggle-jxerzc`. | Publish only when `github.ref == 'refs/heads/main'`. Otherwise use `prerelease: true` or upload an artifact only. |
| F2 | Medium | Security / docs | `WebOsClient.kt:338,460,674`; `MainActivity.kt:750-776` | Every raw SSAP message is logged in full, including the `registered` payload that carries `client-key` (the same field read at `WebOsClient.kt:342`). The log is persisted and has **Copy** and **Share** buttons, so the TV pairing credential leaves the device in every shared log. | Redact `client-key` before logging (in `DebugLog.log`, or by stripping it from the payload). |
| F3 | Medium | Security / docs | `AndroidManifest.xml:22` | `android:allowBackup="true"` with no backup rules. Android Auto Backup (and `adb backup` below API 31) copies `tvalarm_prefs` (which holds `client_key`) and the debug log. The README says "The TV pairing key is never in a backup." | Set `allowBackup="false"`, or add `dataExtractionRules`/`fullBackupContent` that exclude both preference files. |
| F4 | Medium | Correctness | `MainActivity.kt:83-93,112-118,276` | `dayChips` is `by lazy` and holds the chips of the **first** binding. After a file restore, `setupUi()` inflates a new layout, but the chip state is written to the old, detached chips. The visible chips show the XML default (all checked, `activity_main.xml:229-242`), and `currentDaysMask()` reads the invisible old chips. So after a restore the day selection shown is wrong, and taps on the day chips are ignored when saving. | Make `dayChips` a function or rebuild it in `setupUi()`, or do not re-inflate (re-bind the values only). |
| F5 | Medium | Correctness | `MainActivity.kt:112-124`; `Backup.kt:109-115` | A restore writes time, days and `alarm_enabled` but never re-arms or cancels the `AlarmManager` alarm. Example: the alarm is disabled (cancelled) and a file with `alarm_enabled=true` is restored. The status line shows "Scheduled at HH:MM" (`is_scheduled` is still true) but nothing is armed. A changed time keeps firing at the old time. | After `Backup.apply`, call `AlarmScheduler.scheduleNext` or `cancel` according to the restored `alarm_enabled`, and refresh `next_alarm_at`. |
| F6 | Medium | Alarm | `AndroidManifest.xml:53-61`; `AlarmScheduler.kt:21-42` | The alarm is armed as an absolute RTC instant computed from local wall time. There is no receiver for `TIMEZONE_CHANGED` or `TIME_SET`, so after a zone change or a manual clock change the next alarm fires at the old instant (the wrong local time). It corrects itself only after one wrong firing. | Handle `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED` in `BootReceiver` (same re-arm guard). |
| F7 | Medium (Plausible) | Alarm / Doze | `AlarmReceiver.kt:22-31`; `MainActivity.kt:730-733` | The run is expedited work with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`. When expedited quota is exhausted (low standby bucket, OEM restrictions), the run becomes ordinary deferrable work and can be delayed by Doze. This is the "music minutes late" symptom the wake lock was meant to fix. Not reproduced. | Start a short foreground service directly from the exact-alarm receiver (allowed on API 31+), or use `setAlarmClock`, which also exits Doze. |
| F8 | Medium | Build health | repo root; `README.md` "Building from source" | There is no Gradle wrapper (`gradlew`, `gradle/wrapper`), although the README says `./gradlew assembleDebug`. There are no unit tests and no lint step. CI runs 120 and 121 failed (Actions is billing-blocked, per the build-121 notes), and build-121 was built locally. | Commit the wrapper (8.7). Add `lint` and JVM unit tests for `Backup`, `Updater.parseRelease/runNumberOf/checkDue` and `AlarmScheduler`'s next-time computation (they are pure logic). |
| F9 | Medium | Release / updater | `build-apk.yml:39-58,64-72` | Without the signing secrets, CI silently publishes a **debug** build as the new `latest`. Every installed (release-signed) device then downloads it, refuses it (`WRONG_SIGNER`), and cannot see any update until a newer signed build is published. The same "silent fallback" is rejected in the `app/build.gradle` comment (lines 32-35). | Fail the job, or publish as pre-release, when `signed=no`. |
| F10 | Low | Docs | `README.md:6,95-101`; `app/build.gradle:24` | "Latest release: v1.0.0" is stale (latest is build-121; `v1.0.0` is an old tag the updater cannot parse). The README says CI publishes a debug APK to `build-N` on every push, which is no longer true (manual signed builds). The Gradle comment says `android/keystore.properties`, but the code reads the root `keystore.properties`. | Update the README and the comment. |
| F11 | Low | Docs / feature gap | `Backup.kt:217-224`; `README.md` Backup section | The daily on-device copies (three kept) and the guard copy taken before a restore cannot be restored from the UI: `restoreLatest`/`copies` have no caller. "Restoring the wrong file is itself reversible" is true only with adb. | Add a "Restore yesterday's copy" action, or reword the README. |
| F12 | Low | Validation | `Backup.kt:109-115`; `AlarmScheduler.kt:22-33` | Restored values are not range-checked. `alarm_days_mask=0` makes `scheduleNext` skip the day filter (fires every day while the UI says "no days selected"). An out-of-range hour or minute rolls over through the lenient `Calendar`. `wake_volume` is unbounded. | Clamp or reject on import (hour 0-23, minute 0-59, mask 1-127, volume 0-100). |
| F13 | Low (Plausible) | TV / leanback | `AndroidManifest.xml:31-38` | The README says the app was built for an Android TV box, but the manifest has no `LEANBACK_LAUNCHER` category, no `android:banner`, and no `uses-feature android.software.leanback`/`touchscreen required=false`. It will not appear on the home screen of a Google TV / Android TV launcher (it works on boxes that run a phone launcher). | Add the leanback intent category, a banner, and non-required touchscreen/leanback features. |
| F14 | Low | Updater UX | `MainActivity.kt:105,152-227`; `UpdateReceiver.kt:329-346` | The check on launch auto-downloads and starts installing with no user action. If the download finishes after the user leaves, `STATUS_PENDING_USER_ACTION` → `startActivity` is blocked by background-activity-launch rules (Android 14+) and only logged. The UI still says "Installing…". | Show "Update ready — tap to install" and start the session from the foreground. |
| F15 | Low | Updater | `Updater.kt:118-120` | `LAST_CHECK_KEY` is written **before** the network call, so a failed check (offline at launch) suppresses the automatic check for 24 h. | Write the timestamp only after a successful fetch. |
| F16 | Low | Updater | `app/build.gradle:9,21` | `versionCode` is `github.run_number`. Renaming or recreating the workflow resets the counter, and every later build then looks older, so the updater reports "up to date" forever. | Add an offset or a floor (for example `max(run_number, lastPublished)+…`), or derive the code from a committed version file. |
| F17 | Low | Security | `WebOsClient.kt:46-49,199-211` | Command registration tries `ws://:3000` first, so the pairing key crosses the LAN in cleartext whenever the TV accepts it. `wss://` uses a trust-all manager and a hostname verifier that accepts anything, with no certificate pinning. Accepted protocol constraints, but the order is a choice. | Try `wss://3001` first, and trust-on-first-use pin the TV certificate after pairing. |
| F18 | Low | Correctness | `MainActivity.kt:623-626`; `AlarmScheduler.kt:41-49` | The Boolean returned by `scheduleNext` is ignored. `markScheduled` runs and the UI shows "Scheduled" even when `setExactAndAllowWhileIdle` threw. Setting the switch programmatically (`:624`) re-enters its listener, so the alarm is armed twice and two toasts are shown. | Check the result, and guard the listener against programmatic changes. |
| F19 | Low | Alarm | `AndroidManifest.xml:10-11`; no receiver | There is no `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` handling, so on API 31-32, re-granting "Alarms & reminders" does not re-arm until the app is opened. `SCHEDULE_EXACT_ALARM` lacks `maxSdkVersion="32"` alongside `USE_EXACT_ALARM`. | Add the receiver and the `maxSdkVersion`. |
| F20 | Low | Robustness | `SsdpDiscovery.kt:25-62` | The `DatagramSocket` is closed only on the success path (it leaks if `send` throws). Unlike WoL and TCP, it is not bound to Wi-Fi. The `LOCATION` URL from any SSDP responder is fetched (a LAN-only request to an arbitrary URL). | Use `socket.use {}` and `TvNetwork.bind(socket)`, and fetch only same-host `LOCATION`s. |
| F21 | Low | Maintainability | `ArpUtil.kt` (whole file); `WebOsClient.kt:588-600` | `ArpUtil` is dead code that duplicates `getMacFromArpTable`. `DebugLog.log` re-reads, concatenates and rewrites up to 60 KB of SharedPreferences on **every** line (quadratic per run, main thread when called from the UI). | Delete `ArpUtil`. Move the log to an append-only file with rotation. |
| F22 | Low | Dependencies | `build.gradle:2-3`; `app/build.gradle:15-19,79-84` | AGP 8.5.0, Kotlin 1.9.24, compile and target SDK 34, work-runtime 2.9.1. Nothing is vulnerable, but everything is a year behind, and target 34 is below current Play requirements (irrelevant while sideloaded). CI pins Gradle 8.7. | Bump together once the wrapper and CI are healthy. |
| F23 | Low | Exported component | `AndroidManifest.xml:53-61` | `BootReceiver` is exported and also listens for the unprotected `QUICKBOOT_POWERON` actions, so any app can make it re-arm. It is harmless (idempotent and guarded by the enabled flag), but noted for completeness. | Optional: drop the vendor actions or add `android:permission` where possible. |

## Details

**F1 (High). Branch builds reach every device.** The trigger is `on: push: [main]` **and** `workflow_dispatch` with no branch filter. The publish step always creates a normal release. GitHub's `releases/latest` (read at `Updater.kt:120`) returns the newest non-draft, non-pre-release release, whatever branch it came from. The release list confirms build-117 was a `workflow_dispatch` run on a `claude/` branch.

When the signing secrets are present, such a build is release-signed, passes `installability()` (same package, same signer, higher code), and is installed on every device on its next daily check. That puts unreviewed branch code on the alarm device with no review.

**F2 and F3. The pairing key leaves the device.** `pairOverEndpoint` logs `received: $text` and then reads `payload.client-key` from the same text. Each alarm run logs the `registered` frame again during command registration. `DebugLog` persists 60 KB in `tvalarm_prefs`' sibling file `tvalarm_debug_log`, and the log dialog offers Share. With `allowBackup=true` both files go to Google Drive Auto Backup. The key lets anyone on the LAN control the TV (power, input, launching apps, volume). `Backup.kt` rightly keeps it out of the app's own file, but these two paths bypass that.

**F4. Stale chip references after a restore.** In `onCreate`, `setupUi()` runs and `dayChips` is resolved against `binding#1`. `restoreFrom` then calls `setupUi()`, which sets `binding = inflate()` (#2) and `setContentView`. `dayChips.forEach { chip.isChecked = … }` updates #1's chips, which are no longer on screen. The user sees #2's chips, all checked (XML default). `doSaveAndSchedule → currentDaysMask()` reads #1. Reproduction by reading the code: restore a file with weekdays only, then untick Monday on screen and tap Save. The saved mask is still Monday-Friday, and the screen showed all seven days ticked before the tap.

**F5. A restore does not touch the scheduler.** `restoreFrom` only calls `Backup.apply` and `setupUi`. `refreshAllStatuses` derives "Scheduled at" from prefs (`alarm_enabled`, `is_scheduled`, hour and minute), not from the `AlarmManager` state, and the countdown uses the old `next_alarm_at`.

**F6. Time-zone and clock changes.** `setExactAndAllowWhileIdle(RTC_WAKEUP, next.timeInMillis)` fixes a UTC instant. Daylight-saving changes are handled, because each re-arm computes the next local occurrence with the current zone rules. A zone change between arming and firing is not handled, and neither is a manual clock change. A non-existent local time on the spring-forward day (for example 02:30) is shifted forward by the lenient `Calendar`, which is acceptable.

**F7. Expedited quota (Plausible).** WorkManager docs say out-of-quota expedited work runs as regular work, which is subject to Doze and app-standby deferral. The wake lock is taken only inside `doWork`, so it cannot help if the job never starts. Not reproduced on a device.

**F9. Debug fallback.** The step summary says so, but the release is still published as normal. Build-121's notes confirm the installed base is now release-signed (certificate SHA-256 `c88b17a7…dec1dc`), so any debug build published as latest blocks updates for everyone.

**The updater itself** (`Updater.kt`, `UpdateReceiver.kt`) was checked for these and is fine:
- only https downloads, into the app's private cache;
- size cap enforced, with a `.part` file renamed only after the byte count matches;
- package name and signer compared before install, failing closed on an empty signer;
- downgrade refused;
- `UpdateReceiver` not exported, and the PendingIntent is explicit, with `FLAG_MUTABLE` as PackageInstaller requires.

Using the last entry of `signingCertificateHistory` matches the documented ordering (current certificate last). Whether `getPackageArchiveInfo(..., GET_SIGNATURES)` returns signatures on API 26-27 was not verified. It fails closed (refusal) if not.

## What was verified and how

- Every source file, the manifest, resources, the Gradle files and the workflow were read in full. Each finding's file:line was checked against the code at `7c43813`.
- GitHub state was checked through the API:
  - the repo is **public** (so the updater's unauthenticated `releases/latest` works);
  - `releases/latest` = `build-121` (asset `app-release-121.apk`, notes say it was built locally and release-signed);
  - `v1.0.0` exists only as an old tag;
  - build-117 came from a `workflow_dispatch` run on a non-main branch;
  - workflow runs 120 and 121 concluded `failure`.
- `runNumberOf("v1.0.0")` returns null, confirmed by reading the regex (`^build-(\d+)$`).
- F4 was confirmed by tracing the `by lazy` initialisation against the two `setupUi()` calls.

## What was NOT verified

- **Nothing was compiled or run.** Java and Gradle 8.14 are present, but there is no Android SDK, and `dl.google.com` is blocked by the environment's egress proxy (CONNECT 403), so neither `assembleDebug` nor `lint` could run. There are no unit tests in the repo to run. Build success at `7c43813` rests on the build-121 release notes only.
- No device or emulator run. The Doze and expedited-work behaviour (F7), the Android TV launcher visibility (F13), the background install prompt (F14), and whether the signature check works on API 26-27 are reasoned from platform documentation, not observed.
- Whether the CI signing secrets are currently set (F9 applies only if they are not) is unknown; repository secrets are not readable.
- webOS protocol behaviour (which frames carry `client-key` beyond `registered`, and TV certificate stability for pinning) was not tested against a TV.

## Fix status (2026-09-28)

Branch `claude/repo-pr-review-audit-yjympg`. Nothing here was compiled or run: the environment has no Android SDK and Google's Maven is blocked. Every Kotlin file was parsed with ktlint 1.5.0, every XML file with a parser, the workflow with a YAML parser, and the redaction regex was exercised with `java.util.regex` directly. The JVM unit tests added under `app/src/test` run in CI (`./gradlew testDebugUnitTest`), not here.

| ID | Status | Where |
|---|---|---|
| F1 | **Fixed** — the release step runs only for a non-PR run on `main`; every other run uploads a workflow artifact and publishes nothing | `4b4e6ec` |
| F2 | **Fixed** — `DebugLog.redact()` blanks every `client-key` value before a line is written; `clear()` also wipes the old unredacted SharedPreferences copy; `DebugLogTest` | `b188635` |
| F3 | **Fixed** — `allowBackup="false"` | `b188635` |
| F4 | **Fixed** — `dayChips()` is a function over the current binding | `943e041` |
| F5 | **Fixed** — `restoreFrom` calls `AlarmScheduler.rearm`, which arms or cancels from the restored settings and the status line says which | `943e041` |
| F6 | **Fixed** — `BootReceiver` handles `TIMEZONE_CHANGED` and `TIME_SET` through the same `rearm` path | `943e041` |
| F7 | **Not fixed** — Plausible, not reproduced. Moving the run to a foreground service started from the receiver, or to `setAlarmClock`, changes the app's Doze behaviour in ways only a device can confirm; left for a session with one | — |
| F8 | **Fixed** — Gradle wrapper 8.7 committed (generated offline from a Gradle 8.14.3 distribution; the distribution SHA-256 is not pinned because services.gradle.org is unreachable here to read it); JVM unit tests for `AlarmScheduler.nextTrigger`, `Prefs.isDaySelected`, `Backup` (decode, sanitize, due, evict, names), `Updater` (runNumberOf, parseRelease, checkDue), `DebugLog.redact`; CI runs the tests as a gate and `lintDebug` as an advisory step (no baseline could be established here) | `4b4e6ec`, `943e041` |
| F9 | **Fixed** — a publishing run with no signing secrets fails; a non-publishing run builds debug as an artifact only | `4b4e6ec` |
| F10 | **Fixed** — README release line, CI description, build steps; build.gradle comment names the root `keystore.properties` | `4b4e6ec`, this commit |
| F11 | **Fixed** — "Restore the on-device copy" button, confirmed with the copy's date, through `Backup.restoreLatest` + `rearm` | `4627e87` |
| F12 | **Fixed** — `Backup.sanitizeInt` clamps hour, minute, mask (never 0), volume; `BackupTest` | `943e041` |
| F13 | **Fixed** — `LEANBACK_LAUNCHER`, `android:banner` (a layer-list drawable, not a 320×180 raster), non-required touchscreen/leanback features. Whether the launcher accepts a vector banner is unverified | `4627e87` |
| F14 | **Fixed** — the download stays automatic, the install waits for a tap on "Install build N" and starts from the foreground | `4627e87` |
| F15 | **Fixed** — `LAST_CHECK_KEY` written only after GitHub answered | `4627e87` |
| F16 | **Fixed (guarded)** — a publishing run fails when its number is not above the latest `build-N` release, with `VERSION_CODE_OFFSET` as the documented recovery. The scheme itself (run number = versionCode) is kept, because the updater compares the tag number with the versionCode | `4b4e6ec` |
| F17 | **Partly fixed** — `wss://3001` is tried first. Trust-on-first-use pinning is not added: it needs a TV to observe certificate stability against, and a wrong guess would lock every install out of its TV | `4627e87` |
| F18 | **Fixed** — `scheduleNext`'s result is checked in Save, the switch and `rearm`; a refused arm clears `next_alarm_at` and the schedule line says "NOT armed"; the switch is moved through `setSwitchSilently` so its listener does not re-enter | `943e041` |
| F19 | **Fixed** — `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` re-arms; `SCHEDULE_EXACT_ALARM` has `maxSdkVersion="32"` | `943e041` |
| F20 | **Fixed** — `DatagramSocket().use`, `TvNetwork.bind`, LOCATION fetched only from the responder's own host | `4627e87` |
| F21 | **Fixed** — `ArpUtil` deleted; the log is an append-only file cut back to its last 60 KB past 128 KB | `b188635` |
| F22 | **Not fixed** — AGP / Kotlin / SDK / WorkManager bumps cannot be verified without a build; do them in a session that can run `assembleRelease` | — |
| F23 | **Not fixed (accepted)** — the vendor `QUICKBOOT_POWERON` actions stay; the receiver is idempotent and gated on the enabled flag, and the receiver now also legitimately needs to be exported for the clock broadcasts | — |

Fixed 19 of 23 (F16 guarded, F17 partly); not fixed 4 (F7, F22, F23, and the pinning half of F17).
