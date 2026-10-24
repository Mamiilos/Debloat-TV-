# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
This copy is for Google TV only: the LG and Samsung web app and the freemytv.io website were removed.
Before you push, run `tools/check.sh`: the Google TV build and `translate.py --check`. Every pull request runs it too
(`.github/workflows/check.yml`).

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. If it hangs, Google Play Protect is asking on the TV (Android 14:
   *Risky app blocked*, "built for an older version of Android", since targetSdk is 33): they choose *More details* →
   *Install anyway* (updates don't ask again). On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   Chromecast with Google TV (Android 14) refuses port 5555 even with USB debugging on: have them turn on *Wireless
   debugging*, pair with `adb pair <address:port> <code>` (the code is on the TV), then run `adb tcpip 5555` over that
   connection. It closes again at every restart (Wireless debugging turns itself off; `persist.adb.tcp.port` is
   refused), and until `adb tcpip 5555` runs again *Original*, the watchdog and the boot check can't reach ADB. Tell
   them before the switch (#195). Google TV's voice results for films and shows come from its home app (launcherx),
   which Free My TV mode turns off; ADB can't turn off only its home activities, so tell them before they switch.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off. On Xiaomi TVs whose remote has no
   Home button, the same service makes its Mi button open Free My TV instead of PatchWall.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   Before they switch, run `adb disconnect <IP>:5555` (connect again for step 9): on Android 8 to 10 the TV can hand
   its answer to a computer that's still connected (#221).
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it. `mayTurnOff` also refuses
  Android's own pieces (`CORE`: with the permission controller or package installer off, a TV may not start again) and
  factory menus (`FACTORY`), and `neededByTv` keeps on, on the TV itself, anything that asks for `TV_INPUT_HARDWARE`
  (the layer under HDMI and the tuner: off, inputs show "No signal") or receives the remote's Input key
  (`fusion.android.tv.demo` does on several MediaTek boards).
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on HDMI (Chromecast with Google TV,
Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.
The onn 4K Streaming Box (Amlogic S905Y4, Google TV 14 tested) ships no promo apps of its own, so `DEBLOAT` has no onn
entries; its system pieces are in `PROTECTED`. USB debugging opens ADB on 5555 there, as the app needs.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`. On an HY300 with JmLauncher, also
`adb shell setprop persist.sys.home.selectpkg ''`.

## The code

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step. `ScreensaverAlone` reads `Prefs` from another process than the
  settings screen's, so it calls `Prefs.reload()` when it starts (which also re-applies a new language; `HomeData`
  fetches the forecast again when the city changed).
  To check for newer APIs, compile against `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node` and `Ui.Weighted` (the Android 9 font weight) should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German and Russian are hand translations: the reference, edited by hand,
  never replaced with machine ones. Dates and numbers use `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons, Sleep Timer) wins over those first values.
- Settings rows that need something this TV lacks stay, dimmed as *Unavailable*: `SettingsActivity.need` names what a
  row needs, `Capabilities.missing` says why it's missing (once per screen), and OK on the row shows it in a toast.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar), Sony and Xiaomi TVs and Formovie projectors, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
  Some Xiaomi TVs (MiTV-MSSP3, MediaTek, Android TV 9 tested) have no Home button on the remote (`RemoteKeys.XIAOMI`
  matches only the TVs, `MiTV-M…` models, not the Mi Box or Mi TV Stick): the Mi button sends
  `KEYCODE_BUTTON_4`, a global key that `com.xiaomi.android.tvsetup.partnercustomizer`'s `GlobalKeyReceiver` turns into
  PatchWall (`com.mitv.tvhome.atv`), never the home activity, so without the service there is no way back to Free My
  TV, and PatchWall's All Apps opens the disabled `tvlauncher`. `Prefs.KEY_MI` opens our home screen by default
  ("Xiaomi default", empty, leaves it to PatchWall). Leave PatchWall on: it also shows the TV's input picker.
  Xiaomi TVs sold in Russia (MiTV-MOOQ1, MediaTek, Android TV 11 tested) send `KEYCODE_BUTTON_13` for Okko, `_1` for
  VK, `_16` for Rutube, `_11` for KION, `_10` for Wink and `_12` for IVI, global keys for Xiaomi's hot-key receiver
  (`com.xiaomi.android.tvsetup.partnercustomizer`, its log: "dealWithHotkey keycode:199"). "Xiaomi default" (empty)
  leaves the key to the TV.
  `adb shell input keyevent` doesn't test any of these: injected keys skip the accessibility key filter.
- HY300 projectors (Hotack, Allwinner H713, Android 11 tested; the build claims to be an `askey`/`adt3`): the stock
  home screen and screensaver are `com.htc.htclauncherhighenglishd08`, which also carries an ad SDK (`com.zeasn.ad`).
  Keystone, zoom and projection mode live in `com.htc.htcsettings` (`action.htcsettings.prjsettings`), and the Miracast
  receiver is `com.softwinner.miracastReceiver/.Miracast`: `HomeApps.drawerShortcuts` adds both (kind `DEVICE`) where
  they resolve. Opening Miracast moves the Wi-Fi to Wi-Fi Direct: adb drops and asks to be allowed again. Nothing opens
  a passthrough input there; Allwinner's `com.softwinner.awlivetv` does, but always on HDMI 1 (the one real port of
  the three the board lists), so `inputs` keeps only HDMI 1.
  Other HY300s (Amlogic txhd2, Android 13 tested; the build claims to be a Google `redfin`, model `t950s_be311`) have
  the same HTC home screen, but the Home button goes to JmLauncher (`com.jm.jmlauncher`), which ignores the default
  home: it opens the package in `persist.sys.home.selectpkg`, else the HTC one, and with that off and the property
  empty, a black screen. Their adb shell runs as root, so the switch sets the property (`HomeMode.JM_HOME`) and only
  then turns the HTC home off; Original and the watchdog put both back. Wireless debugging's port changes and turns
  itself off, but adb also listens on 5555 there.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
  The Kalley 43G200 (`R3_GTV`) turns it back on at every start and boot-looped without its home screen: the switch
  refuses there. On Amazon Fire TV (Fire OS: `HomeMode.AMAZON`), Amazon's launcher keeps the Home button (`priority=950`
  on `CATEGORY_HOME`), so the switch refuses Free My TV mode while *Original* and the watchdog still run, and no
  `com.amazon.*` package is ever turned off (`mayTurnOff`).
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot
  check leaves the setup app and the stock home screen for later.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
