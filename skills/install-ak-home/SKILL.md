---
name: install-ak-home
description: Install AK Home (a fast glass launcher for Android TV and TV boxes) on a TV over Wi-Fi with ADB, make it the home screen, or put the TV's original home screen back. Use when the user says "install AK Home on my TV", "make my TV look better", "replace my TV launcher", "my Android box home screen is slow", or "put my old TV home screen back".
---

# Install AK Home on an Android TV

AK Home replaces the TV's home screen. You do everything over ADB from the computer; the TV and the computer must
be on the same Wi-Fi. Nothing on the TV is deleted: the stock launcher is only switched off (`pm disable-user`) and
written down on the TV, so it can always come back.

Tell the user what you are about to do before each step that touches the TV, in their language.

## 1. Tools

- `adb` must work (`adb version`). If it is missing, tell the user to install Google's Platform Tools
  (https://developer.android.com/tools/releases/platform-tools) and add it to PATH; do not download it yourself.

## 2. Connect to the TV

1. Ask the user for the TV's IP address (TV: Settings → Network → the Wi-Fi network → IP address).
2. Network debugging must be on: Settings → About → press **Build** 7 times → back → Developer options →
   **USB debugging** / **Network debugging** on.
3. Connect:
   - Android 10 or older, or boxes with "Network debugging": `adb connect <ip>:5555`
   - Android 11+ with "Wireless debugging": ask for the pairing port and 6-digit code shown on the TV,
     `adb pair <ip>:<pair-port> <code>`, then `adb connect <ip>:<port shown on the TV>`.
4. A dialog appears on the TV: the user must choose **Always allow** and **OK**. Then `adb devices` shows `device`.
   Use `adb -s <ip>:<port>` for every command below when more than one device is listed.

## 3. Which edition

- `adb shell getprop ro.hdmi.device_type` contains `0` → a TV set → **Screens** edition (has an Inputs button for HDMI).
- contains `4` → a TV box → **TV Box** edition.
- otherwise: `adb shell "dumpsys tv_input | grep -ci hdmi"` > 0 → Screens; else ask the user: "TV set or box?"

Download the APK from the latest release (same signature as every other AK Home, so it updates any earlier one):

- TV Box: `https://github.com/AK-Agency01/ak-home/releases/latest/download/AK-Home-TVBox.apk`
- Screens: `https://github.com/AK-Agency01/ak-home/releases/latest/download/AK-Home-Screens.apk`

## 4. Install

```
adb install -r AK-Home-<edition>.apk
```

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → an AK Home with another signature is there: `adb uninstall com.akhome.launcher`, install again.
- `INSTALL_FAILED_VERSION_DOWNGRADE` → `adb install -r -d …`.
- `INSUFFICIENT_STORAGE` → `adb shell pm trim-caches 999G`, install again.

Let AK Home install its own updates later: `adb shell appops set com.akhome.launcher REQUEST_INSTALL_PACKAGES allow`

## 5. Make it the home screen

1. List the launchers that answer the Home key (Android 7+):
   `adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
2. Keep only **real launchers**: system apps (`adb shell pm list packages -s`) or known ones such as
   `com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx`, `com.google.android.leanbacklauncher`,
   `com.droidlogic.launcher.provider`, `com.android.launcher3`. Never switch off `com.akhome.launcher`, `android`,
   or anything whose name contains settings, setup, provision, fallback or wizard. Never touch ordinary apps the user
   installed, even if they declare a home screen.
3. Write them down on the TV first, one per line (this is what "put it back" reads):
   `adb shell "printf '%s\n' <pkg1> <pkg2> > /data/local/tmp/akhome-restore.txt"`
4. Switch each one off: `adb shell pm disable-user --user 0 <pkg>`
5. Pick AK Home: Android 10+ `adb shell cmd role add-role-holder android.app.role.HOME com.akhome.launcher 0`;
   Android 7–9 `adb shell cmd package set-home-activity com.akhome.launcher/.MainActivity`.
6. Check: `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
   must show `com.akhome.launcher/…`. If another launcher still answers, repeat 2–5 for it.
7. `adb shell input keyevent 3` (Home), wait 3 s, `adb shell pidof com.akhome.launcher` must print a number.

Tell the user it is done and that AK Home updates itself.

## Put the original home screen back

```
adb shell cat /data/local/tmp/akhome-restore.txt
adb shell pm enable <each package listed>
adb shell cmd role add-role-holder android.app.role.HOME <first package listed> 0   # Android 10+
adb uninstall com.akhome.launcher          # only if the user wants AK Home removed
adb shell rm -f /data/local/tmp/akhome-restore.txt
adb shell input keyevent 3
```
