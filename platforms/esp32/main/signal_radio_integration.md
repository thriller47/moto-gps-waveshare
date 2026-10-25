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

## First physical speaker test (experimental branch only)

The `signal` branch now compiles a **one-shot, three-note (C5-E5-G5) melody, about 1.3 seconds at moderate-low volume
at boot** by default. It runs only after `board_port_init()` so the existing
Waveshare I2C controller already exists. The audio path starts muted with the
amplifier off; the test sets ES8311 volume to 22%, uses PCM amplitude below 20% of full scale and 35 ms fades, unmutes for the melody,
then powers everything down and leaves the GPS UI running. No Wi-Fi is started.

1. Run GitHub Actions **Project Build**, branch `signal`, ESP32 job.
2. Only if the build passes: retain a copy of the proven `NoOta` firmware
   for restoration, connect the board speaker, flash the experimental build.
3. Open serial monitor (115200) and look for `signal_test` messages.
   Expected: `Starting three-note ES8311 speaker melody` and
   `Melody completed; amplifier and I2S stopped`. Report audible tone or
   silence together with any codec/I2S errors.
4. To disable boot test for future builds, pass
   `-DMOTO_SIGNAL_AUDIO_BOOT_TEST=OFF` to ESP-IDF CMake configuration.

**Unverified on physical hardware:** I2C chip recognition, codec startup,
GPIO46 amplifier behavior and acoustic output. The successful build on
commit c53a320 predates this new test; a fresh build is required.

## Second hardware trial

The first user hardware test emitted a very quiet sound. For the next trial,
the experimental boot test now plays three soft notes (523, 659, 784 Hz),
420 ms each. It uses a 22% codec level and 35 ms per-note fades, without
Wi-Fi. Build branch `signal` again before flashing. Stop the experiment if
any crackle, strong click, clipping, or reboot occurs. `NoOta` is unchanged.

## Third audio trial: audible click, no distinct notes

User reports that the second melody build boots and produces only a very quiet
click. This does **not** establish correct PCM playback. Suspected mismatch:
ESP-IDF Philips stereo 16-bit sends 32 BCLK ticks per LRCK frame but original
SIGNAL ES8311 coefficient expects 64 BCLK ticks. Experimental codec register
0x06 is now set for BCLK /8 (256fs/8=32fs) while MCLK remains 256fs.
Per-note PCM write counts are logged to separate I2S delivery from physical
speaker/codec issues. **Rebuild signal** and inspect serial lines:
`signal_es8311`, `signal_audio`, `signal_radio_audio`, `signal_test`.
If three notes remain inaudible despite completed writes, investigate BSP
PA_CTRL handling and ES8311 register configuration rather than increasing
volume. Waveshare's hardware reference recommends PA control via its BSP,
not assuming GPIO46 electrical output behavior.

## Fourth trial: official Waveshare/Espressif audio stack

The user reproduced only a tiny startup click, despite ES8311 ID 0x83 and
18522 PCM frames per note being written without errors. The test now uses
`bsp_audio_init()`, `bsp_audio_codec_speaker_init()` and the
`esp_codec_dev_open/write/close/delete` API in place of the hand-written
`SignalRadioAudio` path. The I2C bus was already created by the Waveshare
board initialization; call the speaker init only *after* explicit BSP audio
init, so it does not try to start a second I2C bus. The same C5/E5/G5 PCM
test and low volume remain. The manual ES8311/I2S code remains compiled but
is NOT called in this trial; no competing codec or PA initialization.

**Caveat:** the official BSP does not offer a full audio/I2S deinit API. The
codec device is closed/deleted after the melody, but the BSP-owned I2S
peripheral remains initialized. This is acceptable for one boot-time hardware
test; full radio-mode resource cleanup must be designed before enabling
navigation/radio switching. A new ESP-IDF build and physical speaker test
are required. Watch `signal_test: Official Waveshare ES8311 speaker path`
for confirmation that the replacement code actually ran.

## First station and Wi-Fi foundation (October 2026)

The first radio chosen is **Radio Capital Classic Rock**:
https://www.capital.it/webradio/02/

The website is a player page, not a directly decodable audio endpoint.
A third-party directory currently reports this HLS playlist:
`https://streamcdng7-4c4b867c89244861ac216426883d1ad0.msvdn.net/webradio/capitalclassicrock/live.m3u8`
(https://radioitaliastreaming.it/). It may change and has **not** been
verified end-to-end by the ESP32. `signal_stations.h` records the station
and `requires_hls=true`, so a plain MP3 decoder is insufficient.

`SignalWifi` (signal_wifi.h/.cpp) is compiled as a **dormant**, radio-only
ESP-IDF STA manager: initialize Wi-Fi/netif/event loop, load SSID/password
from `signal_wifi` NVS namespace, wait for DHCP address, bounded
reconnection, disable modem sleep, and stop/destroy owned resources. It does
NOT connect on boot or affect `NoOta`. An owner task in radio mode will
later call `start_saved()` and `stop()` and may call
`save_credentials(ssid,password)` during a dedicated provisioning flow.
Do not commit secrets to source or CI. There is not yet an on-device
credential entry screen, nor an HLS demuxer/AAC decoder. The Wi-Fi function
therefore cannot be exercised by a normal user build yet, and the boot speaker
test remains as before.

**Next prerequisite:** add a secure on-device Wi-Fi provisioning UI and
navigation-mode arbitration; then verify a connection to a 2.4 GHz network
before implementing HTTP(S)/HLS decoding. The earlier successful physical
test on official Waveshare BSP confirms that the three speaker notes worked.

## First live station simplified to original SIGNAL preset

For the initial end-to-end radio playback use **Radio Paradise – Main Mix**,
one of the preloaded presets in zhitianfu/signal-internet-radio's
`station_manager.cpp`. Its official listening page lists a direct 128 kb/s
MP3 stream on a Europe server at
`http://stream-uk1.radioparadise.com/mp3-128`.
`signal_stations.h` now selects this as `kFirstStation` with
`StreamFormat::DirectMp3`. Radio Capital Classic Rock remains defined as
`kCapitalClassicRock` with `StreamFormat::Hls` for a later milestone.

This is a station-selection change only: **there is still no MP3 network
streamer or playback decoder connected to the audio BSP**, nor a provisioning
screen or start/stop mode UI. The dormant Wi-Fi manager remains unchanged.

## Experimental serial Wi-Fi provisioning test

A temporary **lab-only serial console** has been added on `signal`
(`signal_wifi_console.cpp`), built into the default experimental firmware
but it DOES NOT start Wi-Fi until an explicit serial command arrives.
Use a USB serial terminal capable of sending newline-terminated input;
some read-only monitors do not send keyboard input to the device.
At 115200 baud, commands:

- `wifi set MySSID MyPassword` — saves credentials in the ESP32 NVS namespace
  `signal_wifi`. Spaces in SSID are not supported by this temporary CLI.
- `wifi test` — disconnect any previous trial, connect to the saved
  2.4 GHz network, print the DHCP IP or a timeout/error.
- `wifi status` — print connection state.
- `wifi off` — disconnect and shut down the Wi-Fi driver.

**Security limits:** This development CLI has no password masking. Serial
terminal echo/history may reveal the password and default NVS is not
necessarily encrypted. Use a disposable test hotspot, not a valuable
production Wi-Fi password. Never post serial commands containing passwords
into issues, chats or CI logs. For the final product, replace this path with
a secure pairing/provisioning UI and encrypted credential storage.

The GPS still boots as before; **do not use the Wi-Fi test while riding or
navigating**. This temporary lab console does not yet enforce mode
arbitration; mode-controlled production Wi-Fi is a separate milestone.
The three-note audio test still runs at boot. Radio Paradise MP3 streaming
and decode are NOT implemented yet. Build and hardware tests are pending
for this commit. To omit the CLI pass `-DMOTO_SIGNAL_WIFI_CONSOLE=OFF`
to CMake; it is not part of `NoOta`.

## LVGL worker OOM: PSRAM stack trial (October 2026)

The experimental SIGNAL build with Wi-Fi compiled but failed on actual
hardware *before* GPS UI startup or Wi-Fi console initialization:

```text
board_waveshare: before LVGL worker: internal free=15244 largest=7680, PSRAM free=4416096
esp_lvgl:adapter: Failed to create LVGL task
board_waveshare: LVGL worker task could not start
moto_gps: board initialization stopped ... ESP_ERR_NO_MEM
```

The exact resolved `espressif/esp_lvgl_adapter` dependency is **0.6.4**,
whose documented default worker stack is **8 KiB** and whose config supports
`stack_in_psram = true`. A 7680-byte largest contiguous internal block cannot
fit the normal 8192-byte stack. The experimental board port now requests
that the LVGL worker stack be placed in PSRAM before calling
`esp_lv_adapter_init()`. The three-note audio test and Wi-Fi console are not
started until later; this is a boot-time allocation failure unrelated to
incorrect radio credentials.

After a fresh ESP32 GitHub Actions build, verify on hardware that the
`LVGL worker stack requested in PSRAM` line is followed by
`Navigation UI ready` and `signal_console: Experimental Wi-Fi console ready`.
If not, provide the complete boot errors and the `before LVGL worker`
measurement. The adapter might fall back to internal heap or another
internal-only allocation may be exhausted. **Do not conduct Wi-Fi tests until
the navigation display boots reliably**. `NoOta` is unchanged.

## Native USB Serial/JTAG CLI fix (October 2026)

The device now boots with the LVGL PSRAM stack but the user reports that
Arduino IDE Monitor `wifi` commands are ignored. This board's USB-C is native
ESP32-S3 USB D-/D+ on GPIO19/20; UART0 GPIO43/44 is routed to the expansion
header. Espressif documents that an interactive REPL over USB Serial/JTAG
must select `CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG=y` as the **primary** console,
not only as a secondary log output. The `signal` sdkconfig defaults now
explicitly select native USB as primary. The CLI disables stdio buffering,
acknowledges received command lengths (never echoing secrets), and logs
internal heap status before task creation so a failed 4 KiB task allocation
is distinguishable from a wrong serial port.

Build `signal` anew, flash it, open Arduino Monitor using the native USB
port at 115200 with **Newline**, and send `wifi status` first. Expect
`signal_console: Experimental Wi-Fi console ready`, followed by
`signal_console: Serial command received` and a status line.
If the console-ready log is absent, inspect the task-creation/error logs
and free heap diagnostic before entering passwords. Do not look for an
ESP32-created access point: `SignalWifi` is STA-only and joins an existing
2.4 GHz hotspot. On a Galaxy S24 FE, select the hotspot 2.4 GHz band and
look for a connected device in the hotspot client list, not available Wi-Fi
networks. Use throwaway hotspot credentials: CLI/NVS are not hardened.

No Wi-Fi connection, credentials, streaming, or reproduction over network
has yet been confirmed by the device.
