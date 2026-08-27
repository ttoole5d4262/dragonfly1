# WP500 Thermal + Sensor Recorder v0.3

This is the actual WP500 recorder, replacing the original Camera2 probe with the phone's real Tiny2C / Infisense AC020 thermal path recovered from the installed WP500 software.

## Direct thermal backend
- Dynamically loads the official `com.energy.tc2c` SDK already installed on the WP500. Vendor APKs/libraries are not redistributed in this project.
- Opens the internal Tiny2C thermal USB path with the vendor `USBMonitor`.
- Builds `IrcamEngine` with the WP500's own settings: `256 x 386`, `25 fps`, `USB_DUAL_NATIVE_CAM`.
- Decodes the visible thermal image area as `256 x 192`.
- Extracts the radiometric plane from the vendor frame and feeds it to the stock `LibIRTemp` engine.
- Displays center, minimum, maximum, and average temperature.
- Manual FFC/shutter correction.
- Iron/gray display palettes.

## Synchronized measurement
The app displays/logs:
- thermal frame timing and temperature statistics
- accelerometer
- gyroscope
- magnetic field
- gravity / linear acceleration
- rotation vectors
- compass azimuth, pitch, roll
- light
- proximity
- pressure / ambient temperature / humidity if present

All streams use Android's monotonic clock so sensor and thermal events can be aligned later.

When recording, the app creates:
1. `wp500_session_YYYYMMDD_HHMMSS.csv` — synchronized thermal/sensor measurements.
2. `wp500_radiometric_YYYYMMDD_HHMMSS.bin` — every fifth radiometric plane (~5 fps) with timestamp + frame index + raw 256x192 payload.

The binary file starts with Java `DataOutputStream.writeUTF("WP500_RAD_V1")`, followed by width, height, divisor as 32-bit big-endian integers. Each record is `elapsed_ns` (int64), `frame_index` (int64), `payload_length` (int32), then payload bytes.

## First WP500 test
1. Keep the manufacturer's Thermal Cam (`com.energy.tc2c`) installed.
2. Install/launch this app and allow Camera/notification permissions.
3. Tap **CONNECT INTERNAL THERMAL**.
4. Accept USB permission if Android displays it.
5. If the phone says Tiny2C is not exposed, tap **PREPARE THERMAL HARDWARE**, allow the calibrated factory Thermal Imaging screen to initialize the module, then return and press CONNECT again.
6. Once `THERMAL LIVE` appears, tap **START SYNCHRONIZED RECORDING**.

**Do not select Factory Reset in the manufacturer's FactoryTest app.**

## Building
Open the project in Android Studio with Android SDK 34 and build the `app` module. The project intentionally has no Maven dependencies; the WP500 vendor SDK is loaded from the already-installed stock Thermal Cam at runtime.

## Automatic APK build
The project includes `.github/workflows/build-apk.yml`. If the project is placed in a GitHub repository, **Actions → Build WP500 Thermal Recorder APK → Run workflow** builds an installable debug APK and uploads it as the `WP500ThermalRecorder-debug-apk` artifact.
