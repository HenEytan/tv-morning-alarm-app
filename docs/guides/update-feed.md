# Update feed

Installed apps learn about new builds from the `tv-morning-alarm-updates` Cloudflare Worker, not from GitHub directly. The Worker reads this repo's GitHub releases with a server-side token and answers in GitHub's own JSON shape, so `Updater.parseRelease` reads both the same way. The APK holds no credential, and the repository can become private without stranding devices that already run a build with the Worker URL.

- Worker: <https://tv-morning-alarm-updates.peakbeat-cloud-library.workers.dev> (a download page for the latest APK)
- Code: [services/update-feed/](../../services/update-feed/)
- App side: `Updater.FEED_ORIGIN` and `Updater.latestReleaseUrls()` in [Updater.kt](../../app/src/main/java/com/henos/tvalarm/Updater.kt). The app asks the Worker first and falls back to `api.github.com`, which answers only while the repository is public.

## Routes

| Route | Answer |
| ----- | ------ |
| `GET /repos/HenEytan/tv-morning-alarm-app/releases/latest` | GitHub's latest release, with each asset's `url` and `browser_download_url` rewritten to the asset route below |
| `GET /repos/HenEytan/tv-morning-alarm-app/releases/assets/<tag>/<file>` | 302 to GitHub's short-lived download URL for that asset |
| `GET /` | Download page for the latest APK |

Any other owner/name or path is 404, any method other than GET or HEAD is 405, a missing token is 503, and GitHub failing or timing out is 502. Tag and file names must match `[A-Za-z0-9._-]+`.

## Secret

The Worker reads `TVALARM_GITHUB_TOKEN`, falling back to `GITHUB_TOKEN`. It is the shared fine-grained token with Contents: read on this repository, kept in the Mac login keychain as `peak-releases-github-token`. To set or rotate it without echoing it:

```
cd services/update-feed
security find-generic-password -s peak-releases-github-token -w | npx --yes wrangler@4 secret put TVALARM_GITHUB_TOKEN
```

## Deploy

The Worker runs on the Cloudflare free plan with no bindings (no R2, no KV).

```
cd services/update-feed
npm test
npm run deploy
```

## Publish a release

The feed has nothing to publish by itself: it serves whatever GitHub's `releases/latest` is. Build and release locally from `main`:

```
gh release view --json tagName          # the current build-N; the next one is N+1
BUILD_NUMBER=<N+1> ./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk /tmp/app-release-<N+1>.apk
gh release create build-<N+1> --title "TV Morning Alarm build <N+1>" --latest --notes "…" /tmp/app-release-<N+1>.apk
```

`assembleRelease` signs only when `keystore.properties` is in the repo root; an unsigned or debug-signed APK must never be published (see [Release and updates](../../AGENTS.md#release-and-updates)).

## Check

```
curl -s https://tv-morning-alarm-updates.peakbeat-cloud-library.workers.dev/repos/HenEytan/tv-morning-alarm-app/releases/latest | jq '.tag_name, .assets[].browser_download_url, .assets[].size'
curl -sL -o /tmp/check.apk <browser_download_url> && head -c2 /tmp/check.apk   # PK, and the byte count matches size
```

The tag should match `gh release view --json tagName`. Logs and reports should not include the redirect target: its query string is a signed URL.

## Before the repository goes private

Every installed device must first be running a build that asks the Worker (build 129 or later). An older build asks `api.github.com` without a token, which stops answering once the repository is private, and that device can then only be updated by installing an APK by hand.
