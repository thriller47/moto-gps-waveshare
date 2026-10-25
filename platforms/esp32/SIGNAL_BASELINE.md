# SIGNAL — GPS + internet radio working baseline

Hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75C. Tested by the device owner on ESP-IDF 6.1 (October 25, 2026).

**Verified on hardware:** navigation works, radio streams and plays MP3 audio, and changing radio stations no longer reboots the board. GPS and radio run in separate modes, with a controlled software restart on mode switch. Do not assume they operate simultaneously.

## Preserve these settings

Both `sdkconfig` (committed working configuration) and `sdkconfig.defaults` should agree:

```ini
CONFIG_SPIRAM_USE_MALLOC=y
CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=512
CONFIG_ESP_MAIN_TASK_STACK_SIZE=24576
CONFIG_FREERTOS_TASK_CREATE_ALLOW_EXT_MEM=y
CONFIG_AUDIO_DECODER_MP3_SUPPORT=y
```

`CONFIG_SPIRAM_USE_CAPS_ALLOC` must **not** be selected. Under PSRAM malloc mode, normal allocations can use PSRAM; this keeps more internal/DMA-capable memory for the I2S speaker driver and MP3 processing. Changing the allocation policy has previously caused either I2S DMA out-of-memory or MP3 decoder initialization failures.

ESP-IDF `sdkconfig.defaults` does **not** overwrite an existing local `sdkconfig`. If settings drift, use `idf.py menuconfig` to restore them and save before rebuilding.

## Read-only baseline check

From the repository root:

```cmd
python platforms\esp32\tools\check_signal_baseline.py
```

The command checks both configuration files without changing them.

## Rebuild and test

Enter the ESP-IDF-enabled shell, then:

```cmd
cd /d C:\moto-gps-waveshare
git switch signal
git pull --ff-only origin signal
cd platforms\esp32
idf.py build flash monitor
```

Test cold boot navigation, switch to radio, connect Wi-Fi, play MP3 and switch stations several times, then switch back to navigation. Verify there is no `ESP_ERR_NO_MEM`, `MP3 decoder ret 10`, or assert/reset. A successful build is **not** a substitute for these hardware tests.

Do not accidentally commit a stray `platforms/esp32/idf.py`: use ESP-IDF's installed `idf.py`. `dependencies.lock` is a generated dependency resolution file; review its contents and portability before deciding whether to version it.

Work only on branch `signal`; do not modify `NoOta`.
