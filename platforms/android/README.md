# Glimpse Android Port

This is the community Android companion port for the MOTO GPS Waveshare Edition project. It establishes BLE communication and maps JNI boundaries to use the shared C++ core protocol logic.

## Development Status

### Implemented / Automated Tests Passed
* **JNI Protocol Adapter**: Kotlin JNI integration mapping C++ `moto::ble` serialization features.
* **BLE Architecture State Machine**: Implemented initial robust Gatt callback handlers utilizing sequence queued chunks.
* **Unit Tests**: Parsed payload definitions successfully compiled and execution verification validated locally.
* **Build Configuration**: Gradle wrapper locked and GitHub CI (`.github/workflows/android-build.yml` and `checks.yml`) built utilizing stable JDK 17 environments. Fixed `.gitignore` handling.

### Physical Device Tests Unverified (Missing Live Tests)
* **Screen Lock Resiliency**: Verification pending real-device validation.
* **Bluetooth Bonding & Disconnection Recovery**: Unverified via pure unit tests due to live GATT requirement.
* **Location/GPS Live Matching**: Live navigation simulation via pure Android location managers pending.

## Build Instructions (Debug APK)

To build the debug APK locally:

1. Clone the project and init the submodules:
   ```sh
   git clone --recurse-submodules https://github.com/YOUR_ACCOUNT/moto-gps-waveshare.git
   cd moto-gps-waveshare/platforms/android
   ```
2. Build via the local Gradle Wrapper:
   ```sh
   ./gradlew assembleDebug
   ```
3. Locate the output file at:
   `platforms/android/app/build/outputs/apk/debug/app-debug.apk`

*Note: You do not need to configure an `.env` or set any secrets locally simply to build the debug client structure.*

## License & Attribution

This derivative uses original project material under the **[PolyForm Noncommercial 1.0.0](../../LICENSE.md)** license. All upstream constraints and map data notices apply. See the main `README.en.md` for full references.
