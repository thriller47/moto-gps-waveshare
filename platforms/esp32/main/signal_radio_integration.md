# SIGNAL Internet Radio — experimental integration (`signal` branch)

Reference: https://github.com/zhitianfu/signal-internet-radio

This branch is based on `NoOta`. It MUST NOT replace the working GPS
BLE/display stack or flash a standalone Arduino sketch onto the device.

## First integration milestone

- [x] Add a dependency-free mode arbitration policy
  (`signal_mode_controller.h`): navigation is the default; an active
  route preempts radio; requests to start radio while navigating are rejected.
- [ ] Bind actual phone navigation start/stop events to the policy, via the
  existing PhoneNavBridge.
- [ ] Add an explicit radio mode entry and back-to-GPS control in LVGL.
- [ ] Implement Wi-Fi startup/shutdown on radio entry/exit using ESP-IDF.
- [x] Add the ESP-IDF I2S TX output transport with stream-rate clock switching
  (22.05/32/44.1/48 kHz, MCLK 256×fs) and explicit stop/cleanup.
  This component compiles into the experimental firmware but is **not started**
  from app_main yet.
- [x] Add ES8311 I2C register initialization, 16-bit slave I2S
  setup, codec mute/volume, and verified 256fs rate coefficients.
  Uses `bsp_i2c_get_handle()` after `board_port_init()`, never creates
  another I2C bus. Source based on Espressif's Apache-2.0 ES8311 driver
  distributed in SIGNAL.
- [x] Add `SignalRadioAudio` coordinator: start I2S then codec,
  rate-switch with PA muted/off, and fail-closed shutdown. Compile these
  components on `signal`, **but do not invoke them from app_main yet**.
- [ ] Confirm actual GPIO46 PA control behavior and ES8311 playback on the
  user's Waveshare board before allowing radio mode to unmute.
- [ ] Integrate a compatible HTTPS/MP3/AAC stream decoder and verify audio
  output with **one station** before the station browser/UI.
- [ ] Add resource lifecycle tests: stop stream, I2S, Wi-Fi, and release audio
  buffers before returning to GPS; test BLE re-advertising/reconnection.
- [ ] Port SIGNAL screens into existing LVGL 9 renderer (do not run
  Arduino_GFX and LVGL against the CO5300 simultaneously).
- [ ] Measure internal RAM, PSRAM, Flash and display DMA under radio load.

## Hardware notes

Both applications target the Waveshare ESP32-S3-Touch-AMOLED-1.75C:
466x466 CO5300 QSPI panel, CST9217 touch (0x2D), ES8311 audio codec and
AXP2101 power management. Reference SIGNAL audio pins:
I2S BCLK=GPIO9, WS=GPIO45, data out=GPIO8, MCLK=GPIO42 (Waveshare BSP);
PA control=GPIO46 (hardware test pending); I2C SDA=GPIO15, SCL=GPIO14.

SIGNAL is an Arduino firmware using ESP32-audioI2S and Arduino_GFX.
Moto GPS is an ESP-IDF firmware using LVGL. The source is not a drop-in
library. The original SIGNAL README describes ~640 KiB audio input buffer,
a dedicated audio task, and per-stream ES8311 re-clocking. Check library
licenses and build compatibility before porting any source files.

## Safety contract

1. GPS navigation always wins.
2. Radio mode and navigation mode do not stream/display concurrently.
3. A failure of Wi-Fi or radio audio cannot block returning to navigation.
4. Keep the current `NoOta` build untouched until ESP-IDF build and
   on-device audio tests pass on the experimental branch.

## Current development state

The ES8311 codec and I2S audio transport now exist as **dormant ESP-IDF
components**. The amplifier remains off unless a future radio worker
explicitly starts the coordinator and unmutes the codec. No Wi-Fi connection,
decoder, station manager, user UI or audio task is wired into app_main yet.
Do not flash this as a radio test. ESP-IDF compile and hardware testing
remain pending.

**Important board wiring discrepancy:** the external Arduino SIGNAL sketch
uses GPIO16 for MCLK, but Waveshare's maintained BSP/schematic identifies
GPIO42 as the hardware I2S_MCLK connection. This integration uses GPIO42.
Board reference:
https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75/blob/main/HARDWARE_REFERENCE.md

Compile check (GitHub Actions): run Project Build on branch `signal` with
ESP32 and Native enabled. Codec tests validate only the clock policy, not
physical ES8311, I2C or speaker audio. Only run an actual audio test on
the device once a deliberately opt-in radio test entry point is added.
