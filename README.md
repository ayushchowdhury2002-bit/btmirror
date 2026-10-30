# BT Mirror

Android 10+ tablet app that captures the audio other apps are playing and
re-plays it to several connected Bluetooth devices at once.

## Build
1. Open the `BtMirror` folder in Android Studio (Ladybug or newer). Accept the Gradle sync / wrapper prompt.
2. Run on the tablet (USB debugging or wireless debugging).

## Use
1. Pair and connect your Bluetooth headphones/speakers in system settings.
2. Open BT Mirror, tick the devices, press **Start mirroring**, and allow the capture prompt.
3. Play audio in any app. If one device is behind another, raise "Extra delay" on the *faster* device
   and restart.

## Limits (Android, not the app)
- Apps that opt out of capture (some streaming/DRM apps, e.g. many video/music apps) are silent in the mirror.
- The original audio still plays on the tablet's active output; Android does not let a normal app mute it.
  Mirror to every Bluetooth device and keep the active route on one of them only if you accept an echo
  there, or turn that output's volume down.
- Bluetooth classic adds ~100-250 ms by itself and each codec differs, so "zero latency" is not possible.
  The app keeps its own buffering to ~10-40 ms and lets you align devices with the delay sliders.
- Whether two devices can receive audio at the same moment depends on the tablet's Bluetooth stack
  (Samsung Dual Audio, LE Audio on newer tablets). Plain A2DP on many tablets supports only one device.
