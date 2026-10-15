# BT Volume

Per-device volume attenuation for Bluetooth audio on Android. Go quieter than the lowest system volume step and fine-tune the volume in 0.5 dB steps.

On many phones the lowest volume step is still too loud for some headphones, and the steps in between are coarse. BT Volume remembers an extra attenuation for each Bluetooth device and applies it automatically whenever that device connects.

## Features

- Attenuation from 0 dB (no change) down to −40 dB, saved separately for each Bluetooth device
- 0.5 dB steps in the app, 1 dB steps from the notification
- Works on top of the normal volume buttons
- No internet permission, no ads, no tracking
- English and Czech

## Requirements

- Android 12 or newer
- A one-time setup from a computer to grant the DUMP permission (see [Setup](#setup))

Tested on a Pixel 10 Pro with Android 17. It should work on other phones too, but has not been tested there yet. If it does or does not work on your phone, please [open an issue](../../issues) with the phone model and Android version.

## Install

1. On your phone, download the APK from [Releases](../../releases).
2. Open it, allow installing apps from this source and tap **Install**. If Google Play Protect warns about an unknown app, choose **More details → Install anyway**.
3. Open BT Volume and allow notifications.

## Setup

Android has no public API that tells an app which other apps are playing audio. BT Volume reads this from the system audio dump, which needs the **DUMP** permission. Android only lets you grant it from a computer, once:

1. On the phone, enable Developer options: **Settings → About phone →** tap **Build number** 7 times.
2. Turn on **Settings → System → Developer options → USB debugging**.
3. On the computer, install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (it contains `adb`).
4. Connect the phone with a USB cable and confirm **Allow USB debugging** on the phone.
5. Run:

   ```
   adb shell pm grant eu.aibusinesspro.btvolume android.permission.DUMP
   ```

6. Open BT Volume. The setup box disappears once the permission is granted.

The permission survives reboots and app updates. Uninstalling the app removes it. You can turn USB debugging off afterwards.

## Usage

1. Connect your Bluetooth headphones, speaker or car.
2. Open BT Volume and move the slider. Right = louder, 0 dB = no change.
3. The value is saved for that device and applied every time it connects.
4. Use **− Quieter** and **+ Louder** in the notification to adjust by 1 dB without opening the app.

## How it works

- BT Volume attaches Android's `DynamicsProcessing` audio effect with a negative input gain to the audio session of every app that plays to the Bluetooth output.
- It finds those sessions by reading `dumpsys media.audio_flinger`, which is why it needs the DUMP permission.
- It does not use a single global effect on the output mix (audio session 0). On Pixel phones with Android 17, audio that is not spatialized bypasses that global effect. That is also why the "legacy mode" of some equalizer apps has no effect there.
- Android keeps an audio effect running with its last settings even after the app that created it lets go of it. BT Volume therefore always resets an effect to 0 dB before releasing it, including after the app was stopped by the system.

## Limitations

- Phone calls are not affected.
- It can only make audio quieter, not louder.
- When an app starts playing, the first fraction of a second can be at full level until the effect is attached.
- The format of the system audio dump can differ between phone makers and Android versions. If attenuation does not work on your phone, please open an issue.

## Privacy

BT Volume has no internet permission. Its settings stay on the phone. The DUMP permission lets it read system diagnostic output; it uses only the list of audio sessions that play to Bluetooth.

## Support

BT Volume is free. If it helps you, you can support its development:

<!-- Buy Me a Coffee link goes here -->

## Building

Requirements: JDK 17 or newer and the Android SDK with platform 36.

```
./gradlew assembleDebug
```

Release builds are signed with a key that is not part of this repository.

## License

[GNU General Public License v3.0](LICENSE)
