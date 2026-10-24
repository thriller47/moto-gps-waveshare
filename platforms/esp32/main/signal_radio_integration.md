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
- [ ] Port ES8311 I2C codec initialization and **synchronize codec clocking**
  with the I2S driver on every rate change; share I2C correctly with
  Waveshare BSP and CST9217.
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
I2S BCLK=GPIO9, WS=GPIO45, data out=GPIO8, MCLK=GPIO16;
PA enable=GPIO46; I2C SDA=GPIO15, SCL=GPIO14.

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

The first low-level audio transport exists, but **there is no radio playback
yet**. In particular, we deliberately do not enable GPIO46 amplifier power
without initializing ES8311. No Wi-Fi connection, decoder, station management
or audio task has been wired to the navigation firmware. Do not flash this as
a radio test. ESP-IDF compile and on-device verification are still required.
