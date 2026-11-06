# Pcalc

A Pokémon damage calculator app for Android, made for the AYN Thor's second screen. The calculator runs entirely offline, from inside the APK.

The calculator itself is [rr-calc](https://github.com/kvarflow/rr-calc). Pcalc is the app around it: it pins an rr-calc commit as a git submodule (`rr-calc/`), builds it and packs the result into the APK.

## Install

On the Thor, open [Releases](https://github.com/kvarflow/Pcalc/releases), download `Pcalc-<version>.apk` from the newest release and open it. You'll need to allow installs from your browser or file manager.

Pcalc is in **beta** (`0.1.0-beta.N`), so its releases are marked *Pre-release*. Every push also builds an APK, which you can find under the run's *Artifacts* in [Actions](https://github.com/kvarflow/Pcalc/actions).

### Making a release

1. Set `versionName` in `app/build.gradle.kts` to the new version, and raise `versionCode` by 1.
2. Merge that change to `main`.
3. In [Actions](https://github.com/kvarflow/Pcalc/actions/workflows/build.yml), choose **Run workflow** on `main`, tick **release** and run it. Pushing a `v<versionName>` tag does the same.

The workflow builds the APK, creates the `v<versionName>` tag and publishes the release. Versions with a suffix, such as `-beta.2`, are marked pre-release.

## What the app adds around the calculator

* **Offline:** the pages are served from the APK at `https://appassets.androidplatform.net/calc/`. That is a secure origin, so your box, settings and runs persist between launches as they do in a browser.
* **Requests are limited:**
  * The calculator's own files come from the APK.
  * Emulator sync (`http://127.0.0.1:31125`) is answered by the app itself (see below). Other requests to `127.0.0.1`/`localhost` go through.
  * Pokémon sprites, which the calculator asks GitHub for, are answered from the APK. They come from the [May8th1995/sprites](https://github.com/May8th1995/sprites) submodule (`sprites/`, about 1 MB), plus Professor Oak from [smogon/sprites](https://github.com/smogon/sprites).
  * Other images the calculator loads by a fixed address (trainer pictures, item icons, Oak) are answered from the APK too. rr-calc's build lists them in `offline-assets.json`, and `packRemoteAssets` downloads each one once, into `app/build/remote-cache`, and packs it under `assets/remote/<host>/<path>`.
  * Everything else gets an empty answer, which includes analytics. Links to other sites open in the browser.
* **Files:** *Import .sav* opens Android's file picker. Files the calculator saves, such as the frag sheet CSV, ask where to save.
* **Thor-friendly:**
  * The app is full screen.
  * Moving it between the two screens or rotating doesn't reload it.
  * Back, including the B button, goes back a page but never closes the calculator.
* **Identification:** the user agent ends in `Pcalc/<version>`, so the calculator can tell it's in the app.

## Emulator sync with RetroArch, built in

The calculator can follow a battle in Radical Red as you play. It opens the trainer, loads the Pokémon each side has out, and sets their HP, stat changes and status as the game has them. On a computer this needs rr-calc's sync script running beside the emulator. In Pcalc it's built in, so there's nothing else to install.

1. In RetroArch, play Radical Red with the **mGBA** core.
2. Turn on **Settings › Network › Network Commands**, port 55355, and save the configuration.
3. In Pcalc, import your `.sav` (so your Pokémon are in your box), then turn on **Emulator sync**, next to *Import .sav*.

RetroArch can stay on the top screen and Pcalc on the bottom one.

How it works: the calculator asks `http://127.0.0.1:31125/state` once a second. The app answers that request itself: `RetroArchSync.kt` reads the game's memory from RetroArch with `READ_CORE_MEMORY` over UDP and returns exactly what rr-calc's `emulator/retroarch_sync.py` returns. It only reads memory and changes nothing in the game.

`RetroArchSyncTest` checks it against a fake RetroArch. Its output was also compared with the Python script's for the same memory: they're identical.

## Build

```sh
git clone --recurse-submodules https://github.com/kvarflow/Pcalc && cd Pcalc   # rr-calc + sprites

# 1. The calculator (Node 18+)
cd rr-calc
npm ci --ignore-scripts
(cd calc && npm ci --ignore-scripts && npx tsc -p .)
node build
cd ..

# 2. The APK (JDK 17, Android SDK 35)
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
```

`-PcalcDist=<dir>` packs a calculator built somewhere else instead of `rr-calc/dist`.

The `--ignore-scripts` plus `tsc` dance in step 1 is there because rr-calc's install also runs calc's `bundle` step. The pages don't use it, and it fails on a fresh install. `.github/workflows/build.yml` does the same steps on GitHub.

### Updating the calculator

```sh
cd rr-calc && git fetch && git checkout <commit or origin/master> && cd ..
git commit -am "rr-calc: update to <commit>"
```

### Signing

Builds are signed with `keystore/pcalc-sideload.jks` (password `pcalc-sideload`), which is committed on purpose. That way every build, from CI or a laptop, installs over the previous one without losing the calculator's saved data.

It isn't a secret, so anyone could sign an APK that installs over Pcalc. Only install APKs from this repo's releases. Before publishing anywhere public, switch to a key kept in the repo's secrets.

## Licence

MIT (see `LICENSE`). rr-calc is MIT too, a fork of [smogon/damage-calc](https://github.com/smogon/damage-calc).

The Pokémon sprites are Nintendo / Game Freak artwork, collected by the projects above. The sprite repository has no licence of its own; it's the set rr-calc already shows.
