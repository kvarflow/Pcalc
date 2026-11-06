# Pcalc

A Pokémon damage calculator app for Android, made for the AYN Thor's second screen. The calculator runs entirely offline, from inside the APK.

The calculator itself is [rr-calc](https://github.com/kvarflow/rr-calc). Pcalc is the app around it: it pins an rr-calc commit as a git submodule (`rr-calc/`), builds it and packs the result into the APK.

## Install

On the Thor, open [Releases](https://github.com/kvarflow/Pcalc/releases), download `Pcalc-<version>.apk` from the newest release and open it. You'll need to allow installs from your browser or file manager.

Pcalc is in **beta** (`0.1.0-beta.N`), so its releases are marked *Pre-release*. Every push also builds an APK, which you can find under the run's *Artifacts* in [Actions](https://github.com/kvarflow/Pcalc/actions).

### Making a release

1. Set `versionName` in `app/build.gradle.kts` to the new version, and raise `versionCode` by 1.
2. Merge that change to `main`.
3. Tag it and push the tag: `git tag v<versionName> && git push origin v<versionName>`.

The workflow builds the APK and publishes the release. Versions with a suffix, such as `-beta.2`, are marked pre-release.

## What the app adds around the calculator

* **Offline:** the pages are served from the APK at `https://appassets.androidplatform.net/calc/`. That is a secure origin, so your box, settings and runs persist between launches as they do in a browser.
* **Requests are limited:**
  * The calculator's own files come from the APK.
  * `127.0.0.1`/`localhost` is allowed, for emulator sync.
  * `raw.githubusercontent.com` is allowed for Pokémon sprites, until they're bundled.
  * Everything else gets an empty answer, which includes analytics. Links to other sites open in the browser.
* **Files:** *Import .sav* opens Android's file picker. Files the calculator saves, such as the frag sheet CSV, ask where to save.
* **Thor-friendly:**
  * The app is full screen.
  * Moving it between the two screens or rotating doesn't reload it.
  * Back, including the B button, goes back a page but never closes the calculator.
* **Identification:** the user agent ends in `Pcalc/<version>`, so the calculator can tell it's in the app.

## Build

```sh
git clone --recurse-submodules https://github.com/kvarflow/Pcalc && cd Pcalc

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
