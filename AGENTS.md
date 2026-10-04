# AGENTS.md — coding-agent guide for tv-morning-alarm-app

Rules live here; every `CLAUDE.md` is a one-line `@AGENTS.md` import. Edit this file, never the
import. A rule that applies to one folder goes in a nested `AGENTS.md` there, with its own
one-line `CLAUDE.md` beside it.

## What this repo is

TV Morning Alarm: an Android app, sideloaded onto a phone or an Android TV box, that on a schedule wakes an LG webOS TV (Wake-on-LAN), pairs with it over LG's SSAP WebSocket protocol, sets a wake-up volume and starts a Spotify playlist on the TV. It is a personal DIY tool for whoever runs it on their own home network; installed copies update themselves from this repo's GitHub releases. The thing a newcomer gets wrong: **a merge to `main` publishes a release that every installed device downloads and installs** — see [Release and updates](#release-and-updates).

## Layout

| Package / folder                                                         | Role                                                                                                                                       | Depends on                                            |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------- |
| [app/src/main/java/com/henos/tvalarm/](app/src/main/java/com/henos/tvalarm/) | The whole app, one package: UI (`MainActivity`), scheduling (`AlarmScheduler`, receivers, `TvAlarmWorker`), TV protocol (`WebOsClient`, `SsdpDiscovery`, `TvNetwork`), settings (`Prefs`, `Backup`), self-update (`Updater`, `UpdateReceiver`), `DebugLog` | AndroidX core/appcompat, Material, WorkManager, OkHttp |
| [app/src/test/java/com/henos/tvalarm/](app/src/test/java/com/henos/tvalarm/) | Pure-JVM unit tests (JUnit 4; the real `org.json` on the test classpath, since `android.jar` only stubs it)                               | JUnit, org.json                                       |
| [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)       | CI: unit tests and lint gate, then builds the APK; on `main` it release-signs and publishes `build-N`                                      | —                                                     |
| [docs/audit/](docs/audit/)                                               | Dated audit and review reports                                                                                                             | —                                                     |

_[FILL IN: the boundary rule between them — what may import what, and where a cross-cutting helper goes.]_

## Public contracts

- **The release channel the in-app updater reads**: `Updater.kt` fetches GitHub `releases/latest` for `HenEytan/tv-morning-alarm-app` and reads the number from a tag matching `build-<N>`; that `N` is the CI run number (plus `VERSION_CODE_OFFSET` in the workflow), which `app/build.gradle` also stamps as `versionCode`. The tag format, the repo name and the `versionCode` scheme move together or devices stop updating.
- **The signing identity**: `applicationId` `com.henos.tvalarm` and the release keystore's certificate. Android replaces an install only for the same package and the same certificate; changing either strands every installed device.
- **The settings backup file format** (`Backup.kt`, `VERSION`, `APP` = `tv-morning-alarm`): files saved by older builds must still restore.
- **The stored preferences** (`Prefs.kt`, file `tvalarm_prefs`, its keys and defaults): live on installed devices across updates; renaming a key silently loses a setting.

Changing any of them is a behavior change, never a refactor. Before changing a shared module, grep its consumers across the repo; a signature change enumerates every call site.

## Docs

| Folder                       | What it holds                                                      |
| ---------------------------- | ------------------------------------------------------------------ |
| [docs/adr/](docs/adr/)       | Immutable architecture decision records.                           |
| [docs/design/](docs/design/) | Design specs for a feature or subsystem.                           |
| [docs/plans/](docs/plans/)   | Implementation plans, task by task.                                |
| [docs/guides/](docs/guides/) | Guides, indexed by [docs/guides/README.md](docs/guides/README.md). |

Docs mirror rules and code for humans. When a change makes a guide, design doc, or README wrong, update it in the same change. Don't load a doc to follow a rule.

## Common tasks

The Gradle wrapper is committed; a local build needs the Android SDK (CI uses JDK 17, `platforms;android-34`, `build-tools;34.0.0`).

| Command                                 | Effect                                                                                                                  |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `./gradlew testDebugUnitTest`           | The JVM unit tests (`app/src/test`). Gates CI.                                                                          |
| `./gradlew lintDebug`                   | Android lint; report at `app/build/reports/lint-results-debug.html`. Gates CI.                                          |
| `./gradlew assembleDebug`               | Debug APK under `app/build/outputs/apk/debug/`, versioned `1.0 (dev)`, versionCode 1, signed with this machine's debug key. |
| `./gradlew assembleRelease`             | Release APK; signed only when a `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) is in the repo root, otherwise unsigned on purpose. |
| `./gradlew testDebugUnitTest lintDebug` | The verify command — both CI gates in one run.                                                                          |

`./gradlew testDebugUnitTest lintDebug` is the aggregate check. It skips everything that needs a device: the TV protocol, Wake-on-LAN, `AlarmManager`/`WorkManager` scheduling, the install step of the updater, and the UI. _[FILL IN: how to check those by hand — which device or emulator, and against which TV.]_

### Release and updates

- A push to `main` (that is, a merged PR) publishes a release-signed `build-N` release, which becomes `releases/latest` and reaches every installed device through the updater. A pull request or a run from any other branch builds a debug APK as a workflow artifact only and publishes nothing; a run on `main` without the signing secrets fails rather than publish.
- Only release-signed builds may ever be published: a debug build is signed with a key the runner generates for itself, so installed devices download it, refuse it, and then see no further updates.
- CI signs with the secrets `ANDROID_KEYSTORE_B64` (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Keep the keystore: losing it means no future build can update an existing install — everyone has to uninstall first.
- Renaming or recreating the workflow resets the run counter, and every later build would look older than what is installed. The publishing run checks its number against the latest release and stops; recovery is setting `VERSION_CODE_OFFSET` in [build-apk.yml](.github/workflows/build-apk.yml) to the last published number.
- `v1.0.0` is an old tag the updater does not read; the newest `build-N` is the latest release.
- A local build is versioned `1.0 (dev)` with versionCode 1, so it sees every published build as newer and then refuses to install it (different key). To put a published build over a local one, uninstall first — save the settings to a file before.

---

## Git

- Never include the Claude Code session link (`Claude-Session:` trailer, `https://claude.ai/code/session_...`) in commit messages, PR bodies, or issue and review comments.
- The `Co-Authored-By` trailer names `Agent`, never the full model/email.
- A PR body ends with `Co-Authored-By: Agent`, never with a "Generated with Claude Code" line or any other tool attribution.

### Git Commit Conventions

Commits follow Conventional Commits

```
type(scope): short description
```

| Type       | When to use                               |
| ---------- | ----------------------------------------- |
| `feat`     | New feature or capability                 |
| `fix`      | Bug fix                                   |
| `refactor` | Code change with no functional difference |
| `test`     | Adding or fixing tests                    |
| `chore`    | Maintenance, dependency updates, tooling  |

Scope identifies the affected package(s) or area.
Scope is optional for changes that span the whole repo or don't map cleanly to a single package.

**Rules**

- Description is lowercase, no trailing period.
- Use imperative mood: "add", "fix", "remove" — not "added" or "fixes".
- If a commit spans more than 2 scopes, omit the scope, keep only the type, and use a multi-line commit for details.

**Multi-line commits**

Prefer multi-line format whenever a commit includes multiple distinct changes — not just for PR squash merges. Use a single-line message only when the commit does exactly one thing.

Add a body listing the individual changes as bullet points. Omit iterative `type(scope)` bullets that only refine or clean up work introduced earlier in the same PR — include only bullets that add distinct value.

**Bullet prefix rule:** if every bullet has the same `type(scope)` as the subject line, omit `type(scope):` from all bullets and write only the description. If any bullet differs, include `type(scope):` on all bullets.

### Git Branch Rules

- **Protected branches: `main` — commits land there only via PRs or automated tooling, never manually.** Always work on a feature branch and open a PR. Merging to `main` publishes a release to every installed device.
- Branch naming: `feat/<description>`, `fix/<issue-number>-<description>`, `refactor/<issue-number>-<description>`.

### Issues

Issues carry the label of their kind: `bug` for a defect or regression, `enhancement` for a feature or behavior change, _[FILL IN: the label for cleanup with no behavior change — the repo has no `refactor` label]_. Title: one specific line naming the area and the symptom, capability, or target.

### Pull Requests

- Label the PR to match the issue it closes, adding `security`, `breaking`, etc. when they apply.
- The PR body closes its issue (`Closes #<n>`).

---

## House rules

### Workflow rules

- **When the user asks a question, discuss first** — don't jump to implementation or edits as a response.
- **Be direct and concise** — no pleasantries, no preamble, no filler. Disclaimers and caveats stay short; the response goes to the main answer. Asked to explain something, give the high-level summary unless depth is asked for.
- **Link what you name.** A file or a doc section in a reply is a markdown link ([AGENTS.md](AGENTS.md), [Git](AGENTS.md#git)), never a bare path.
- **Report an edit, don't paste it.** What changed, where (linked), and why — the user reads the file.
- **Feedback in chunks.** Review findings or suggestions you volunteer in an interactive conversation come in chunks of up to five points, saying how many remain. A skill's prescribed report is presented as that skill says, and an unattended run sends the whole report in one message.
- **Ask before adding a dependency.** Prefer what the repo already has.
- **Ask before generating `.md` docs**, unless explicitly instructed otherwise.
- **A document is as long as its task needs.** Cover the substance; no filler sections, restated summaries, or boilerplate. A skill's or template's required sections are substance — the rule governs what fills them and what is added beyond them.
- **Search the web for current docs when researching a dependency, API, or tool** — training data is stale. Verify against the installed version before applying advice.
- **If a rule conflicts with a task, ask** — don't silently bypass.
- **TDD is mandatory for features, fixes, and behavior changes** — the `tdd` skill: a failing test first, then the minimum to pass.
- **`./gradlew testDebugUnitTest lintDebug` must pass before committing.** Before reporting a PR ready, run it again plus whichever manual checks Common tasks names.

### Technical rules

- **Design principles: DRY, KISS, YAGNI, SOLID — in that order of frequency.** Don't abstract until the second duplicate. Don't add config knobs, hooks, or generics for a use case that isn't in the diff. An established codebase pattern is not over-engineering: repeating it for new code is expected; flag as YAGNI only abstractions nothing in the codebase uses.
- **Tests live under `app/src/test/java/`, mirroring the source package, as `<ClassName>Test.kt` — pure-JVM JUnit 4 tests run by `testDebugUnitTest`.** Every new public function, type, or component ships with tests in the same commit; cover the happy path, the documented edge cases (empty, null, error), and failure paths. Tests exercise real logic.
- **Document non-obvious logic only.** A short comment explaining _why_ (invariant, workaround, protocol quirk, ADR reference) is welcome. Don't restate _what_ the code does.
- **Never count what the text lists.** "The three options", "both callbacks", "these five steps" — in a doc, a comment, a docstring, or a commit body — go stale the moment an item is added or removed. Let the list carry its length.
- **When a code question is really an architecture question, read the ADR before editing.** A boundary or a shape that looks wrong was decided, not overlooked.
- **Follow existing code patterns.** Different areas may differ in style — adapt. When existing code and these rules disagree, the rules win: legacy code may predate them.

### Checks and evidence

Each of these exists because its absence ships something wrong.

- **Every claim in a report is audited against a tool result from this session.** Report only work you can point to evidence for, and say explicitly what is not yet verified. Outcomes faithfully: a failing test with its output, a skipped step named, and what is done and verified stated plainly, without hedging.
- Every guard, gate, or check must be provably able to fail: break what it guards, watch it go red, revert. A check you cannot demonstrate red is not a check.
- Catches fail closed. A tool error, an empty result, or a skipped step never reads as "no findings".
- Numbers in commit messages and PR bodies are prose; evidence is the command that ran and its exit status.

## Security

When writing or reviewing code, check for the following. The categories follow the OWASP Top 10; look an item up there for depth. Severity: **HIGH** = blocker,
**MEDIUM** = should fix, **LOW** = consider fixing but always notify the team.

### HIGH — Blockers

- **Hardcoded credentials or API keys** (Security Misconfiguration) in source code or committed config files. Here that includes the release keystore (`*.jks`, `*.keystore`) and `keystore.properties`, which `.gitignore` keeps out: a leaked keystore lets anyone's build replace every install.
- **Sensitive data exposure** (Cryptographic Failures): secrets, tokens, or PII written to logs, included in error output, or returned beyond what the caller needs. Here that includes the TV pairing key (`client-key`): it is never in a settings backup (`Backup.kt` excludes it, and the manifest keeps `android:allowBackup="false"`) and `DebugLog.redact` blanks it from the shareable Debug Log.
- **Untrusted input reaching a shell, a query, a parser, or a filesystem path unvalidated** (Injection) — injection and path traversal. Parameterize queries; sanitize any path built from input.

### MEDIUM — Should fix

- **Cryptographic failures** (Cryptographic Failures): weak algorithms, hardcoded IVs, home-rolled crypto, insufficient key lengths; secrets encrypted at rest and in transit.
- **Unhandled errors** (Security Misconfiguration) — an uncaught rejection, panic, or exception that leaks a stack trace or internal state to a caller.

### LOW — Consider

- **Vulnerable or outdated components** (Vulnerable and Outdated Components): when adding or upgrading a dependency, verify it has no known CVEs and is actively maintained.

## Code conventions

### General

- No magic numbers or strings — named constants.
- No commented-out code. A `TODO` / `FIXME` references a ticket or states a clear action.
- No debug prints in shipped code — use the project's logger.
- Prefer early return over nested conditionals; split a function with many branches.
- When a function takes more than two parameters, two of the same type, or any boolean, take one named argument object (or struct) instead.
- Lint and typecheck every file you touch before finishing; lint errors are often real bugs — a missing await, an unhandled error, a wrong import.

### Kotlin

_[FILL IN: naming, typing, and idiom rules for Kotlin that a linter does not already enforce]_

## Permissions when running unattended

This section applies when you run as a subagent, in a background task, or in a non-interactive session — anywhere a permission prompt has no one to answer it. An interactive session simply asks; a denied call there means the user declined, so adjust the approach rather than retry it.

Unattended, a denied tool call is a silent failure mid-workflow. A call must pass both the tools the session has and the project's permission lists (`.claude/settings.json` for Claude Code — `permissions.allow` is auto-approved, `permissions.deny` is blocked). Read them at the start and plan around them.

- Each piped variant of a shell command needs its own allow entry: `Bash(git log*)` does not cover `git log | head`.
- Fetch only allowed domains; call only allowed MCP tools; check the deny list for path restrictions before editing.
- When a needed tool is missing, try a permitted alternative; if none exists, stop and report — do not retry the denied call.
- At the end of your work, list any tool you needed but could not use, so the user can extend the settings:

```
MISSING PERMISSIONS:
  - <Tool>(<pattern>): <why needed>
```
