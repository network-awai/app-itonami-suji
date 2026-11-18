# Suji on Awai Android (Tauri v3)

This folder packages the suji page as an Awai Android app (`network.awai.suji`).
Tracking: [AWAI-3488](https://linear.app/kotobalabs/issue/AWAI-3488) (parent
[AWAI-3356](https://linear.app/kotobalabs/issue/AWAI-3356)). The physics, the
scene and the UI run in the WebView, exactly as in the browser. The Tauri host
exposes only its version. It has no network, filesystem or shell access, and the
app makes no external requests.

Upstream: the page source is `kotoba-lang/kami-engine` `packages/kami-app-suji`
(ADR-0049). This repository is one of its copies. The physics comes from
`cloud-itonami/suji` at the commit pinned in `deps.edn`.

## Build

The canonical build is `amu compile --target wasm32-browser`. `amu` ships only as
a Linux x86-64 binary, so this app uses the ClojureScript build instead (allowed
for Awai Otehon apps). Two problems needed a workaround, and neither changes the
app source:

- **Dependencies.** `deps.edn` mixes two kotoba monorepo commits and repositories
  that no longer exist, and tools.deps cannot resolve it. `kotoba-overrides.edn`
  reads 17 Kotoba libraries from one monorepo checkout at one commit.
- **`.cljk` files.** The ClojureScript compiler does not read `.cljk`. `stage.py`
  copies the classpath and gives each `.cljk` file the extension it had before
  the rename (from `cljk-origin.edn`, `.cljc` by default), following the
  workspace oracle rule.

```sh
npm ci                       # in android/
python3 -I build.py          # dist/ (pages, js, release.json) and .build/host/host.js; runs the cljs tests
node --test test/host.test.mjs
npm run build:android        # arm64 debug APK; JDK 21, SDK 37, NDK 27.0.12077973
npm run build:android:x86    # x86_64 debug APK
node verify-device.mjs <adb-serial> [out.json]
node verify-results.mjs <adb-serial> <candidate.json> [out.json]
```

## Bot results (suji-posture)

[AWAI-3512](https://linear.app/kotobalabs/issue/AWAI-3512). The bot and the app
share one file, `src/awai/suji/candidate.cljc`. It validates a posture task,
solves it through the app's own `kami.app-suji.core/solved` (cloud-itonami/suji),
and checks a candidate.

- **Task** (`awai.suji.task.v1`): the body, every posture angle the app has a
  slider for, and the session length, within the slider ranges. A missing angle
  or an unknown field is refused, never defaulted. Examples are in
  `profiles/tasks/`.
- **Host command** (`.build/host/host.js`, Node): `run <task.json> <out-dir>`
  writes `candidate.json` (`awai.suji.candidate.v1`, status
  `candidate-not-reviewed`) with the full loads, tensions and session strain.
  A refused task writes `refusal.json` and exits 2. `example` prints the
  simulator's opening posture as a task. It makes no network request.
- **Itonami profile** (`profiles/suji-posture/`): `cloud.itonami.profile.v2` on
  the Muse rail. `read_source`, `write_workspace` and `inference_muse` are
  autonomous, `publish_external` needs approval, and `git_push` is blocked. It is
  repo-local: it is not installed in `~/.itonami/profiles`, and no scheduler runs
  it until an owner does. After changing SOUL.md, config.yaml, profile.yaml or
  cron/jobs.json, recompute the bundle CID with Otehon's
  `itonami.otehon.bundle/bundle-cid`.
- **App** (`results.html`, linked from the simulator as 「bot の結果を開く」): opens a
  local candidate.json, solves its task on the device, and shows it only when
  every result agrees. Nothing is sent or stored.

Agreement is not exact equality. Measured on 2026-11-18, Node 26 (V8 14.6) and
Chrome 156 differed in 135 of 869 numbers, by at most 5.2e-13 absolute and
1.9e-13 relative. `Math.pow`, `Math.exp` and similar functions may round
differently between engines, and suji's iterative solvers carry that difference
forward. Each number must therefore agree within `tolerance` (1e-11, relative
to the larger magnitude, with an absolute floor below 1). Keys, strings,
booleans and list lengths must be equal. The page states the largest
difference it found.

Debug APKs are test artifacts. They are not signed or OTA releases.

## Device check

`verify-device.mjs` connects to the WebView's DevTools socket (debug builds only)
and checks five things:

1. The page states the WebGPU backend. A WebGL 2.0 fallback fails this check.
2. The viewport published bone meshes.
3. Moving the head-flexion slider changes the computed load.
4. The same move changes the head bone's transform in the draw list.
5. The page fits the screen width.

The screen must be on and unlocked. The UI re-renders on animation frames, and a
locked phone sends none, so the page stops updating while it is locked.

Results for 2026-11-18 are in `evidence/20261118/`. A moto g05 (Android 17, Mali,
WebView 156) passes all five checks. The Awai Android x86_64 PC was unreachable
over adb during this run. Its WebView has no WebGPU (AWAI-3487), so it is
expected to fail check 1 with a stated `webgl2` backend.
