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
python3 -I build.py          # dist/: index.html, js/main.js, release.json (digests and source pins)
npm run build:android        # arm64 debug APK; JDK 21, SDK 37, NDK 27.0.12077973
npm run build:android:x86    # x86_64 debug APK
node verify-device.mjs <adb-serial> [out.json]
```

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
