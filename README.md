# Pcalc

A Pokémon damage calculator app for Android, made for the AYN Thor's second screen. It runs entirely offline, from inside the APK.

What you get:
* **The calculator**, with the Thor's small-screen layout, as on the website. More games are on the way: each one you pick has its own box, runs and settings.
* **Pictures:** all Pokémon sprites, trainer pictures and item icons come with the calculator itself, so they work offline too.
* **Emulator sync built in:** the calculator follows the battle in RetroArch on the other screen, and connects by itself as soon as RetroArch answers.
* **Backups:** your boxes, runs and settings are backed up to a folder on the device automatically.
* **Auto-import:** the game's save is imported by itself each time the game saves.
* **Made for the Thor's lower screen:** it opens there by itself, the tabs sit at the bottom, in reach of your thumbs, and there's a Black theme for its OLED screens.

The calculator itself is [rr-calc](https://github.com/kvarflow/rr-calc). Pcalc is the app around it, game-neutral by design: it pins an rr-calc commit as a git submodule (`rr-calc/`), builds it and packs the result into the APK.

## Install

On the Thor, open [Releases](https://github.com/kvarflow/Pcalc/releases), download `Pcalc-<version>.apk` from the newest release and open it. You'll need to allow installs from your browser or file manager.

Pcalc is in **beta** (`0.1.0-beta.N`), so its releases are marked *Pre-release*. Every push also builds an APK, which you can find under the run's *Artifacts* in [Actions](https://github.com/kvarflow/Pcalc/actions).

### Making a release

1. Set `versionName` in `app/build.gradle.kts` to the new version, and raise `versionCode` by 1.
2. Merge that change to `main`.
3. In [Actions](https://github.com/kvarflow/Pcalc/actions/workflows/build.yml), choose **Run workflow** on `main`, tick **release** and run it. Pushing a `v<versionName>` tag does the same.

The workflow builds the APK, creates the `v<versionName>` tag and publishes the release. Versions with a suffix, such as `-beta.2`, are marked pre-release. To rebuild a beta under the same version, tick **replace** as well: the old pre-release and its tag are deleted first. A full release can't be replaced.

## What the app adds around the calculator

* **Offline:** the pages are served from the APK at `https://appassets.androidplatform.net/calc/`. That is a secure origin, so your box, settings and runs persist between launches as they do in a browser.
* **Requests are limited:**
  * The calculator's own files come from the APK.
  * Emulator sync (`http://127.0.0.1:31125`) is answered by the app itself (see below). Other requests to `127.0.0.1`/`localhost` are blocked.
  * The calculator's images (Pokémon sprites, trainer pictures, item icons) are its own files, kept in rr-calc's `src/img/`, so they're in the APK with the rest of it. Nothing is fetched from other sites, at build time or when you use the app.
  * Everything else gets an empty answer, which includes analytics. Web and email links open in the browser or mail app; links of any other kind are ignored.
* **Files:** *Import .sav* opens Android's file picker. Files the calculator saves, such as the frag sheet CSV or a backup, ask where to save.
* **Thor-friendly:**
  * The app is full screen.
  * It opens on the smaller screen (the Thor's bottom one), even when started from the top screen's launcher. On a device with one screen, or with a bigger monitor plugged in, it stays where it opens.
  * Its opening screen shows the icon's three HP bars filling in, on navy, until the calculator has loaded, then fades to it.
  * Moving it between the two screens or rotating doesn't reload it.
  * Back, including the B button, goes back a page but never closes the calculator.
* **Identification:** the user agent ends in `Pcalc/<version>`, so the calculator can tell it's in the app.

## Backups

Everything the calculator keeps is saved inside the app and survives updates: every mode's box, runs, personal bests, settings and fight notes. Backups protect it from the rest, such as uninstalling, clearing storage, a new device or a factory reset.

* **Automatic:** the app backs up whenever you leave it, and every 10 minutes while it's open, if anything changed. The latest 10 automatic backups are kept.
* **Where:** by default, the app's own folder on the device (`Android/data/io.github.kvarflow.pcalc/files/backups`). That survives updates but goes if the app is uninstalled.
* **Your own folder:** in the calculator, open **More › Backup › Choose folder** and pick any folder, such as Documents or the SD card. Backups go there from then on, and they survive uninstalling.
* **If that folder can't be written** (moved, card removed, permission revoked), backups fall back to the app's folder rather than being lost.
* **Back up now / Restore:** in the same panel. Restoring replaces what's saved with the backup, after asking. The app first backs up what's there, so a restore can be undone.
* **Save backup file / Restore from file:** a single backup file you can keep anywhere. The same file restores on the website.

After reinstalling, choose the same folder again, then restore its newest backup.

## Auto-import of the game's save

Pcalc can import your save by itself each time the game saves, so your box keeps up as you play.

1. In RetroArch, turn on **Settings › Saving › SaveRAM Autosave Interval** (say 10 seconds), so the game's save file is written as you play and not only when you quit.
2. In Pcalc, import your save once with **Import .sav**, as before.
3. Under **More › Import / Export › Auto-import**, choose **Choose save file** and pick the game's save. RetroArch keeps it as `<game>.srm` in its `saves` folder.

While the calculator is open, the app looks at that file every few seconds and hands it over when it changes; the app only reads it. The import is quiet when it's the same run in the same mode. A save of the other mode, or of a new game (which would start a new run), is left for **Import .sav**, which asks first; the status line says which. **Stop** stops watching.

## Emulator sync with RetroArch, built in

The calculator can follow a battle as you play, in games whose calculator supports it. It opens the trainer, loads the Pokémon each side has out, and sets their HP, stat changes and status as the game has them. On a computer this needs rr-calc's sync script running beside the emulator. In Pcalc it's built in, so there's nothing else to install.

1. In RetroArch, play the game with the **mGBA** core.
2. Turn on **Settings › Network › Network Commands**, port 55355, and save the configuration.
3. In Pcalc, import your `.sav` (so your Pokémon are in your box), then turn on **Emulator sync**, next to *Import .sav*.

RetroArch can stay on the top screen and Pcalc on the bottom one.

How it works: the calculator asks `http://127.0.0.1:31125/state` once a second. The app answers that request itself: `RetroArchSync.kt` reads the game's memory from RetroArch with `READ_CORE_MEMORY` over UDP and returns exactly what rr-calc's `emulator/retroarch_sync.py` returns. It only reads memory and changes nothing in the game.

`RetroArchSyncTest` checks it against a fake RetroArch. Its output was also compared with the Python script's for the same memory: they're identical.

## Build

```sh
git clone --recurse-submodules https://github.com/kvarflow/Pcalc && cd Pcalc   # with rr-calc

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

**Releases** are signed with the release key, which is kept only in the repository's secrets and used only by the release job. They're signed through Android's APK key rotation:
* The old sideload key, `keystore/pcalc-sideload.jks`, is public. It vouches for the release key and gives up the right to replace the app (`--set-rollback false`).
* A phone with an earlier build therefore updates in place and keeps its data.
* From then on, nothing signed with the public sideload key can replace Pcalc.

This needs Android 9 or later, which is why `minSdk` is 28.

Betas may still be published with the sideload key while the release key isn't set up. A full release without the release key in the secrets fails rather than fall back to the public sideload key (tick **allow_sideload** to publish with it anyway). Builds that aren't releases, such as branches, pull requests and local builds, always use the sideload key. Once releases use the release key, those builds won't install over a release.

#### Setting up the release key (once)

1. On a computer with Java (`keytool` comes with it), make the key. Choose a strong password and keep both the file and the password safe, for example in a password manager. If they're lost, future updates can't install over the app.

   ```sh
   keytool -genkeypair -keystore pcalc-release.jks -storetype PKCS12 -alias pcalc-release \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Pcalc release"
   ```

2. Turn the file into text: `base64 -w0 pcalc-release.jks` on Linux, or `base64 -i pcalc-release.jks` on macOS.
3. On GitHub, go to **Settings › Secrets and variables › Actions › New repository secret** and add:
   * `PCALC_RELEASE_KEYSTORE`: the base64 text.
   * `PCALC_RELEASE_KEYSTORE_PASSWORD`: the password.
4. Make the next release. Its notes say "Signed with the release key". Install it over the previous one: it should update, not reinstall, and keep your data.

### Security

* **Network:** none needed. The network security config allows unencrypted traffic only to the device itself, and trusts no user-added certificate authorities.
* **Bridge:** only the calculator's own origin can message the app (`WebMessageListener`, main frame only). It can hand over a file to save, a backup, or ask for one of the app's backups by its exact name. Message sizes are capped.
* **Files:** the WebView can't read files (`allowFileAccess` and `allowContentAccess` are off). The `.sav` import only gets the file you pick.
* **Emulator sync:** it only reads RetroArch's memory, never writes. A reply that isn't RetroArch's is reported, never crashes the app.
* **Build:**
  * The actions are pinned to exact commits.
  * The Gradle wrapper is validated.
  * npm packages install from lockfiles with their scripts off.
  * The build downloads nothing but the toolchain and packages: the calculator, its images included, comes from the pinned rr-calc commit.
* **Releases:** the release job is the only one with write access or the signing key. It checks the signature before publishing, and publishes the APK's sha256 alongside it.

## Licence

MIT (see `LICENSE`). rr-calc is MIT too, a fork of [smogon/damage-calc](https://github.com/smogon/damage-calc).

The Pokémon sprites, trainer pictures and item icons are Nintendo / Game Freak / The Pokémon Company artwork; rr-calc's README says where each set comes from.
