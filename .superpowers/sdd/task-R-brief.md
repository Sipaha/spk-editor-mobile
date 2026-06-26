# Task R brief — App-identity rebrand "SPK Remote" → "Sawe Mobile"

This is your requirements. Use the exact values below verbatim. This is a **mechanical refactor**, verified by build + existing tests — NOT test-first. Do it as ONE commit.

## Repo / branch
- Work in `/home/spk/.spk/sawe/ss/spk-solutions/spk-editor-mobile`, already on branch `phase6-mobile-delta-sync`.
- Commit ONE commit at the end. Do NOT push. Do NOT add a `Co-Authored-By` trailer (project rule).

## LOCKED targets (do not deviate)
1. `app/src/main/res/values/strings.xml`: `app_name` → `Sawe Mobile` (WITH the space — display only).
2. `settings.gradle.kts`: `rootProject.name` → `sawe-mobile` (kebab). **The on-disk repo directory stays `spk-editor-mobile` — do NOT rename it.**
3. Kotlin package rename `ru.sipaha.spkremote.{core,app,cli}` → `ru.sipaha.sawe.{core,app,cli}` across ALL `.kt`/`.kts`:
   - every `package`/`import` statement and every fully-qualified reference (~395 refs);
   - move the source dirs: `core/src/main/kotlin/ru/sipaha/spkremote/core` → `core/src/main/kotlin/ru/sipaha/sawe/core` (and the corresponding `core/src/test/...`, `app/src/main/...`, `app/src/test/...`, `app/src/androidTest/...` if present, `cli/src/...`).
   - Suggested approach: `git grep -l 'ru.sipaha.spkremote' | xargs sed -i 's/ru\.sipaha\.spkremote/ru.sipaha.sawe/g'`, then `git mv` each `.../ru/sipaha/spkremote` dir to `.../ru/sipaha/sawe`, then a `grep -rn 'spkremote' .` sanity pass (expect ZERO source hits).
4. `app/build.gradle.kts`: `applicationId` AND `namespace` → `ru.sipaha.sawe.app`. Also check `cli/build.gradle.kts` and `core/build.gradle.kts` for a `namespace`/`group` that references `spkremote` and update to `ru.sipaha.sawe(.core|.cli)` as appropriate.
5. Misc references: any remaining `"SPK Remote"`, `spkremote`, or `spk-editor-mobile` in gradle files, `README*`, `res/` strings, or other source/build config that the BUILD or the UI reads. Leave historical/changelog prose and `docs/` history untouched.

## Pairing protocol — ORTHOGONAL, do NOT touch
- KEEP `PairingUrl.SCHEME = "sawe-remote"` exactly as-is.
- KEEP the HMAC path and the `HmacChallengeAuthTest.kt` reference vectors UNTOUCHED.
- There is NO AndroidManifest deep-link `<intent-filter>` scheme, so the applicationId/package rename has no intent-filter coupling. Confirm by grepping the manifest; do not add one.

## выпилить the dead legacy scheme alias
The desktop now emits only `sawe-remote://`, so the parse-side legacy alias is dead. In `core/src/main/kotlin/ru/sipaha/sawe/core/PairingUrl.kt` (its new path after the move):
- Remove `const val LEGACY_SCHEME = "spk-editor-remote"`.
- Collapse `val ACCEPTED_SCHEMES = setOf(SCHEME, LEGACY_SCHEME)` to just `setOf(SCHEME)` — or inline the check as `require(parsed.scheme == SCHEME) { "expected scheme '$SCHEME', was '${parsed.scheme}'" }` and delete `ACCEPTED_SCHEMES` entirely if nothing else references it.
- Drop the KDoc lines describing the legacy scheme acceptance.
- In `PairingUrlTest.kt`, DELETE the two legacy test cases: the one asserting it "still accepts the legacy spk-editor-remote scheme" and the legacy-host variant. Keep every `sawe-remote` test.

## Done when (verify and report the evidence)
- `./gradlew :core:test :app:assembleDebug` is GREEN under the new package (run from the repo root; paste the tail of the output). If `:cli` builds in this project, include `:cli:assemble` or its test task too.
- `grep -rn 'spkremote\|spk-editor-mobile\|SPK Remote'` over `*.kt`, `*.kts`, `settings.gradle*`, `res/`, gradle config returns only intentional hits (history/docs prose) — list any remaining hits and justify each.
- `PairingUrlTest` no longer has the legacy cases; `SCHEME`/HMAC tests still pass.
- Report: status, the single commit hash, the test command + its result tail, and any judgment calls (e.g. a misc ref you chose to leave).
