# Moto GPS — Project State

> **Updated: 2026-10-30** (Europe/Rome). Living project handoff; checked against the `valhalla` source, Git history, and GitHub Actions on this date. **Code/CI success is not proof of on-road hardware stability.**

## Sources of truth and working rules

1. The currently checked-out branch code, build configuration and tests.
2. Git commits, PRs, GitHub Actions results and hardware logs.
3. This document: a handoff, **not** proof that a fix works on hardware.
4. Conversation history as context until corroborated by source, CI or a repeatable physical test.

Read root `AGENTS.md` before changes. Keep each session's decisions, changed paths, verification status, regressions and next step in this file. Do not silently overwrite historical decisions; explicitly record reversals.

## Project / active branches

- Repository: `https://github.com/thriller47/moto-gps-waveshare` (upstream reference: `mx3353672833-debug/moto-gps-waveshare`).
- **Active development baseline: `valhalla`**. Latest verified commit at this documentation update: `4c363b8fcd70636bddbe09fd3afddf3b41628ab2`.
- Other remote branches currently present: `signal` (separate radio/OSRM experimentation) and `test/ble-scene-ack` (BLE/scene ACK experiments). Do not merge their experimental changes into `valhalla` without review/explicit approval.
- `main` and `NoOta` are obsolete; do not recreate/rebase from them. Upstream is for comparison only; never push to upstream.
- Prefer one consolidated commit and **one CI run** per change; avoid unrelated modifications or repeated workflow triggers.
- Hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75C, 466×466 CO5300 AMOLED, CST9217 touch, QMI8658 gyro, 8 MB PSRAM and 32 MB flash. Firmware ESP-IDF + NimBLE + LVGL; Android companion app communicates via BLE.
- Windows ESP-IDF / esptool and Android Studio are used for local flashing; a Linux machine is also available.

## Current architecture (verified in source)

| Component | Current implementation | Important limitation |
| --- | --- | --- |
| Routing | Android uses offline **Valhalla** when its graph is installed; an online Valhalla path also exists. Offline graph import accepts `*.tar.zst`. | Do not conflate routing graphs with gray road context. |
| Place search | Online **Photon** search. | A graph/MotoMap import does **not** make destination search offline. |
| Gray OSM roads | `HybridMapRepository` loads imported local **MotoMap ZIP** data; if coverage is missing, `GatewayRoadTiles` requests public **Overpass** data and caches available tiles. Android sends `MapScene` over BLE. | **Render is not used for gray-road retrieval.** Public Overpass is unreliable; requests can fail or return no usable context. |
| Green route | Android's selected route geometry is windowed and sent via BLE; `NavPresenter` projects it for the ESP32 display. | Green routing/geometry can remain active even when gray-road `MapScene` is absent. |
| Heading | Android GPS bearing while moving, position displacement fallback; ESP32 `MotionHeadingFusion` uses phone travel course as absolute reference and QMI8658 yaw for interim motion. | Phone magnetic azimuth must not rotate the bike map just because the handset moves on a seat. |
| Diagnostics | Android persistent rotating files and **Export diagnostics** share action. ESP32 256 KiB `diag` partition remains allocated, but runtime flash logging is **disabled by default** in the latest firmware. | ESP32 flash logger caused a *suspected* stability regression; not yet established as causal. |

### Map imports and user-confirmed behavior

- Italy OSM PBF from Geofabrik (`italy-261026.osm.pbf`) was converted to `italy-261026.motomap.zip`; Italy graph import used `Italy.tar.zst` from Routing Heart. The user confirmed that **with MotoMap loaded, gray roads appear** and route navigation generally works.
- Remove/keep removed obsolete 8-km local-map action, region list and downloads, online/offline toggles and corridor workflow. Retain direct MotoMap ZIP and Valhalla graph import controls.
- Route preference UI includes motorcycle touring/panoramic requirements; intermediate waypoints and arrival finish signaling remain project features. Verify exact implementations against current code before changing them.
- **Without MotoMap**, road tests on 2026-10-30 repeatedly showed **no gray context roads**. Android diagnostics reported zero usable segments across repeated lookups and no successful context scene; Overpass errors included timeouts and HTTP 502. The code now prefers `overpass.private.coffee` with `overpass-api.de` fallback, but the new endpoint has **not yet been shown to solve this on the road**.
- Do not claim that gray roads can reliably be retrieved offline without imported map data. Do not infer availability of local Photon search.

## Agreed ESP32 navigation UI — current, not historical

- **Green** selected-route line; gray OSM roads behind it and road labels above the line where present. Route line should cover the useful circular viewport and follow motion smoothly.
- The **normal turn-arrow glyphs were removed** in commit `61410c27`; keep only the arrival **checkered flag**, along with the regular small bike-position marker. A user nevertheless reported seeing occasional **left-turn and roundabout arrows specifically without MotoMap**. This is an **unresolved observed discrepancy**, not proof that the old arrow renderer is still in the source. Do not dismiss it as a mistaken flash (user confirmed both latest APK and ESP32 firmware were installed).
- Distance to the next turn: **28 px at roughly 8 o'clock**, visible within **200 m** (changed from earlier proposed center-under-road-name and 100–150 m thresholds). Keep destination distance in its existing central position. The former curved/scrolling street-name arc and verbose turn instructions were removed/hidden by later UI changes.
- **3.4× progressive zoom near turns**, returning to normal after the maneuver. Commit `9caccf03` removed an obsolete dependency on gray road data: zoom must still work with **green route only**. Never re-enable turn arrows as the fallback for missing context.
- Remove speed, compass, music and OTA pages/functions; retain the speed-limit badge. **Important caveat:** the separate `signal` radio experiment and some radio-mode code remain in the repository; do not confuse those with requested navigation pages.
- Distance-to-turn still sometimes disappears **up to ~20–30 m before the physical turn** despite progress stabilization. Distinguish a geometrically reached Valhalla maneuver from the actual passed maneuver; do not assume displaying `0 m` means the turn was completed.
- Roundabouts and back-to-back turns must preserve the current maneuver until actually passed and must not reverse driving direction. Historic wrong/inverted roundabout arrow behavior preceded removal of glyphs.

## Recent changes — chronology and reasons (2026-10-28 to 2026-10-30)

| Commit / date | Change and scope | Verification / caveat |
| --- | --- | --- |
| `9f29b5cf` · Oct 28 | Established root `PROJECT_STATE.md` / `AGENTS.md` continuity notes. | Document-only baseline, now updated. |
| `9dabc718` · Oct 28 | Smoother near-turn zoom with shared road context and faster GPS/BLE cadence. | Road tests still showed heading/turn issues. |
| `70286995`, `c1681ed8` · Oct 29 | Raised zoom, simplified guidance layout / hid verbose road arc, turn distance moved to ~8 o'clock. | Later threshold superseded. |
| `61410c27` · Oct 29 | Removed ordinary turn arrows; retained arrival checkered flag; set counter to 28 px, near turn from 200 m. | Arrow-like symbols were still reported only when no MotoMap is loaded; origin not confirmed. |
| `887339c2` · Oct 30 | Added `RouteDistanceGuard`, faster route/map checks, more responsive ESP32 heading fusion, more eager reroute (45 m, 3 fixes, 4 s, 10 s cooldown), faster Overpass timeouts / larger bounding area. | Unit/smoke checks and CI do not resolve the premature turn-counter disappearance on road. |
| `9caccf03` · Oct 30 | Allowed 3.4× turn zoom without gray-road `MapScene`, added native no-context zoom regression test. | Fixes an obsolete code dependency, **not confirmed** to explain unexpected arrow symbols. |
| `91db9e3d` · Oct 30 | Added ESP32 flash ring logger (`diag`, 256 KiB at `0xC20000`), Android rotating file logger and shareable ZIP, diagnostics decoder/script. | Android export still active. Flash logger later became the main suspected cause of LVGL stalls. |
| `ed805f5c` · Oct 30 | Added `NavigationHeadingPolicy`: after first valid fix, ignore handset compass azimuth; use moving GPS course or displacement fallback. Updated Overpass endpoint and endpoint-error diagnostics. | Android-only; physical improvement should be retested. |
| `9f8dcc6f` · Oct 30 | Capped external LVGL mutex wait at 250 ms and added `RENDER_PHASE` / `DISPLAY_LOCK_TIMEOUT` traces. | Exposed a stalled LVGL/display worker, but timeout **did not cure** the freezes. |
| **`4c363b8f` · Oct 30** | **Disabled ESP32 flash diagnostics by default** via `MOTO_ESP_DIAG_FLASH_ENABLED=OFF`, stubbing logger calls and excluding its implementation at build time. Preserves `diag` partition and old records. | **A/B road test with this firmware pending.** Do not pronounce the freeze fixed yet. |

Relevant code: `platforms/android/app/src/main/java/com/motogps/android/viewmodel/NavigationViewModel.kt`, `NavigationHeadingPolicy.kt`, `RouteDistanceGuard.kt`, `map/GatewayRoadTiles.kt`, `diagnostics/DiagnosticLog.kt`; `platforms/esp32/main/phone_nav_bridge.cpp`, `motion_heading_fusion.hpp`, `moto_diagnostics.cpp`, `board_port_waveshare_1_75c.cpp`; `shared/nav_presenter/src/moto_nav_presenter.cpp`, `shared/nav_ui/src/moto_nav_ui.cpp`. See also `docs/diagnostics/ROAD_TEST_LOGGING.md`.

## Latest road tests and failure analysis (Oct 30)

**Test A: earlier firmware, without MotoMap**
- Slow/absent gray-road context, occasional unexpected left/roundabout symbols, heading alignment delayed. With MotoMap installed, gray roads rendered correctly but turn distance still vanished too early.
- User confirmed **both Android and ESP32** had been flashed with the current `valhalla` build; do not attribute anomalies to an old image without evidence.

**Test B: flash logging enabled; Android ZIP + ESP32 CSV**
- Moving the phone on a car seat could cause the magnetic azimuth to compete with actual travel course. The GPS-heading policy above addresses the code path; do not attribute display freezes to phone orientation.
- Manual ESP32 restarts were logged with reset code `POWERON`. **The user reset the device deliberately after the map stopped updating**: these are **not** evidence of spontaneous panic, reset or battery failure.
- Analysis of the first logs found BLE `NAV_SNAPSHOT` events continued for roughly 35–104 seconds after the last successful `ESP_RENDER`. The display side stopped updating while BLE reception remained active.
- A subsequent **10-minute road test without MotoMap had four display freezes**, requiring manual restarts. ESP32 diagnostic output contained **206 `DISPLAY_LOCK_TIMEOUT` events**; all four sessions showed successful rendering followed by failed attempts to obtain the LVGL display mutex while navigation snapshots continued.
- That proves a **display-side stall / lock contention**, but does **not** yet prove why the LVGL worker retained the lock. The specific suspected regression is the new flash logger writing ~64-byte records repeatedly from `moto_demo` at up to one flush attempt per 25 ms, with sector erases; flash/cache/PSRAM contention is a credible mechanism, not a proven root cause.
- User explicitly noted the freezes **began after enabling ESP32 diagnostics**. Thus the A/B firmware **with logger entirely disabled** is the priority before touching TE synchronization, LVGL internals or gyro logic.

**Test C: pending**
- Flash latest commit `4c363b8f` (only app binary at `0x20000` if the updated partition table is already installed), run the **same no-MotoMap trip** for at least 20–30 minutes, and report whether freezes recur. Android logging/ZIP continues working.
- With ESP32 flash diagnostics disabled, **no new ESP32 diagnostic records** will be written. Existing `diag` flash data can still be read; they are historical and must not be mistaken for current-run events.
- Do not start additional GitHub Actions merely for documentation or exploratory checks.

## Diagnostics status and proposed v2

### Present implementation
- Android: `DiagnosticLog.kt` writes `current.log` / `previous.log` (each ~384 KiB), shares `motogps-android-diagnostics.zip` from the **Share/Export diagnostics** icon via Android `FileProvider`. Includes navigation, heading, BLE, Overpass, GPS and reroute metadata. No ADB or laptop required on the road.
- ESP32: partition table allocates `diag` `0xC20000`, size `0x40000` (256 KiB), outside app and existing `storage`; decoder `scripts/diagnostics/decode_esp32_trace.py` converts a raw dump to CSV. **Logging is compiled out by default** in commit `4c363b8f`. Do not enable for road use until an alternative is tested.
- Windows sample read (replace COM port): `esptool.exe --chip esp32s3 -p COM5 -b 460800 read-flash 0xC20000 0x40000 esp-diag.bin`; then `python scripts\\diagnostics\\decode_esp32_trace.py esp-diag.bin`. Do **not** run `erase-flash`.
- First installation of the diagnostic partition required a new `partition-table.bin` at `0x8000`; subsequent app-only flashes use `0x20000`. Keep the original app offset; do not use `0x10000`.

### Future architecture (design only; **not implemented**)
- Investigate a **preallocated circular PSRAM ring** (initial proposal 128 KiB, subject to memory measurements) with compact numeric records; no blocking/formatting/allocation/flash I/O in BLE, QMI8658 or LVGL callbacks. Drop low-priority records on overflow.
- Optional, versioned **BLE diagnostic retrieval**, negotiated by capabilities and lower priority than navigation/geometry/scene frames. Prefer small periodic critical-event summaries to Android; bulk transfer on demand **after stopping navigation**, including when screen is frozen but BLE is still alive.
- Android should persist fetched ESP32 events immediately and add them to the existing diagnostic ZIP, correlating ESP uptime/connection sessions with Android wall time.
- **PSRAM is volatile:** a physical power reset loses unsent events. Periodic small summaries persisted by Android mitigate but cannot eliminate this; do not claim guaranteed retrieval after power loss.
- Retain flash partition for future optional bench/offline dump **only when navigating/display DMA is stopped**, after validation. Do not reintroduce high-frequency flash writes.
- Develop v2 on an **isolated test branch**; host ring-buffer and decoder tests, BLE fragmentation/ACK/reconnection/backpressure tests, simulated frozen UI retrieval, then 30–60 minutes hardware stress with and without MotoMap before considering merge to `valhalla`.
- The proposed design was discussed on Oct 30. A prior attempt to create a GitHub Issue was rejected because Issues are disabled; the design is captured here so it is not lost.

## Verification and CI status at this update

| Commit / workflow | GitHub Actions result | What it establishes |
| --- | --- | --- |
| [`91db9e3d` — run 41051151667](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41051151667) | **Success** (Android, ESP32, backend; native skipped) | Builds of the initial diagnostic implementation, **not** runtime reliability. |
| [`ed805f5c` — run 41060444084](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41060444084) | **Success** (Android; other unnecessary jobs skipped) | Compiles changed Android heading / Overpass code. |
| [`9f8dcc6f` — run 41061406359](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41061406359) | **Success** (ESP32 and backend; Android/native skipped) | The instrumentation build compiled; later road tests **still froze**. |
| [`4c363b8f` — run 41094597313](https://github.com/thriller47/moto-gps-waveshare/actions/runs/41094597313) | **Success** (ESP32; remaining jobs skipped when irrelevant) | The flash-logging-disabled firmware builds; **on-road A/B result pending**. |

Earlier Android and native navigation tests covered `RouteDistanceGuard`, heading selection and no-map zoom. The user reported four green jobs for a separate upstream-comparison test effort; **do not claim those experimental changes were merged** into `valhalla`. No physical-device tests were performed in this documentation session.

## Open problems and priorities

| Priority | Issue | Known facts | Next action |
| --- | --- | --- | --- |
| **P0** | ESP32 screen/map freezes | BLE navigation updates still arrive; LVGL mutex timeouts; regression began after flash logger was introduced | Finish A/B road test with logger fully disabled. If it still freezes, instrument LVGL worker/TE/DMA without writing to flash in motion. |
| **P1** | No gray context without imported MotoMap | Local MotoMap works; public Overpass requests timed out/HTTP 502; changed endpoint needs verification | Review Android ZIP after A/B, verify endpoint responses/OSM cache; prefer offline import as reliable baseline. |
| **P1** | Turn counter disappears before physical maneuver | Reported up to 20–30 m early; `RouteDistanceGuard` did not fully resolve | Compare maneuver geometry, GPS route progress, release threshold, and zero-distance hiding using captured trace. |
| **P1** | Heading alignment / phone moving on seat | Magnetic heading was allowed to overwrite travel course; Android code now prioritizes moving GPS | On-road validation of `NavigationHeadingPolicy` + ESP32 QMI8658 fusion. |
| **P2** | Unexpected left/roundabout glyphs in no-map mode | Reported despite removal of arrow rendering | Safely reproduce without photographing while driving; inspect other draw paths using bench scene replay. |
| **P2** | ESP32 diagnostic recording for future bugs | Flash logger is unsafe until disproved; now off | Design/test PSRAM→low-priority BLE→Android v2 separately; leave `valhalla` unchanged meanwhile. |
| Later | Offline place search / Photon; optional radio work | Photon remains online; `signal` isolated | Do not mix these into stability fixes. |

## Decisions and exclusions to preserve

- Keep `valhalla` as the integration baseline; `signal` and `test/ble-scene-ack` isolated.
- Maintain the **existing destination-distance position**, current turn counter (8 o'clock, 28 px, <=200 m), current zoom 3.4×, green route, genuine gray MotoMap roads, checkered arrival flag and speed-limit badge.
- **No ordinary turn arrows**, no OTA, no speed/compass/music navigation pages. Any future reintroduction must be an explicit new decision rather than a side effect of fallback rendering.
- With missing MotoMap, do not make route visibility/zoom dependent on gray context.
- Preserve Android↔BLE↔ESP32 protocol compatibility; tests are required before packet/schema changes.
- Do not call a fix “resolved” simply because CI is green or because a reset reason was `POWERON`.
- Avoid generating duplicate workflow runs; consolidate commits and keep experimental work off the tested `valhalla` baseline.
- Do not delete user maps, NVS or diagnostic partition while flashing; preserve flash layout.

## Next concrete action / session handoff

1. **First:** after the successful CI build, test `4c363b8f` on the ESP32 **with the flash logger disabled**, on the same no-MotoMap scenario where four stalls occurred in ten minutes. Android APK can stay on the heading-policy/Overpass version; export the Android ZIP after the ride. Compare freeze frequency and GPS/BLE symptoms. Do **not** use old ESP32 CSV records as evidence from the new run.
2. **If stable:** document the A/B outcome, keep logger off, and develop the v2 RAM/BLE design **on a test branch**, with no writes to SPI flash during navigation.
3. **If still frozen:** instrument the *LVGL worker, QSPI DMA/flush progress and TE gating* with nonintrusive sampling, then isolate by controlled changes. Avoid layering several unrelated fixes into one road test.
4. **In every session:** inspect the current branch/SHA, applicable `AGENTS.md`, pertinent files and CI; update this document with **what changed, exact tests actually run, remaining hardware verification and next step**.
