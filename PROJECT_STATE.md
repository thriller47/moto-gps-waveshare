# Moto GPS — Project State

> Updated: 2026-10-28. Living project handoff document. Always verify against current code and GitHub Actions before treating an item as implemented.

## Sources of truth
1. Current branch code, configuration and tests.
2. Git history, pull requests and CI logs.
3. This document as a maintained summary.
4. Conversation notes as *unverified context* until corroborated.

## Project overview
- Repository: `thriller47/moto-gps-waveshare`.
- Hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75C, 466 × 466 circular display.
- Companion Android application, BLE communication, ESP-IDF firmware, navigation and offline maps.
- The root README still describes much of the original iPhone-oriented upstream project; do not infer the current Android implementation from it.

## Current architecture — conversation-reported, verify in source
- Valhalla is the routing engine for the Android navigation workflow.
- Italy-wide Geofabrik OpenStreetMap data has been imported into Moto GPS; Valhalla routing graph tiles were obtained from Routing Heart.
- Offline navigation/routing is intended to be available; place search still uses online Photon.
- ESP32 shows a white route, gray contextual roads, maneuver indications and distances.
- Android calculates navigation state and transfers relevant information to ESP32 over BLE.
- Confirm exact file paths, tile packaging, runtime loading and fallback behavior before editing.

## Navigation UI requirements
- Keep remaining distance to destination in its existing position.
- Center distance to next maneuver below the road name, with approximately 28 px font (verify actual typography/layout).
- Normal maneuver indicator should appear within 100 m of the turn; allow continuity for closely spaced maneuvers.
- Do not advance to the following maneuver until the current one has actually been passed.
- Roundabout arrows must represent actual exit and driving direction; generic 'straight' for a roundabout exit is not adequate.
- Gray context roads already exist; consider turn-proximity zoom using route and surrounding-road geometry.
- Review GPS location refresh cadence and responsiveness without causing jitter or unnecessary battery use.

## Open issues / hypotheses — not confirmed fixes
| Area | Symptom / request | Next verification |
| --- | --- | --- |
| Roundabouts | Wrong / inverted arrows, different exits appear identical | Trace Valhalla maneuver types, bearings, roundabout exit handling and BLE encoding |
| Maneuver timing | Next turn shown before previous is complete; some prompts too early | Inspect route progress thresholds, pass detection and UI state transitions |
| Turn zoom | Need better junction context | Inspect ESP32 rendering transforms and Android map geometry data |
| Position refresh | Position update rate considered insufficient | Inspect Android location request interval, BLE rate and ESP32 interpolation |
| Offline search | Photon still online | Determine whether local geocoding is in scope; do not claim offline search |
| Internet radio | Earlier MP3 decoder initialization failures | Recheck current firmware and build; historical log is not proof of present failure |

## Decisions and guardrails
- Preserve existing gray road context and offline-first routing architecture.
- Prefer precise route geometry / maneuver semantics over guessing arrow direction from screen coordinates.
- Do not change BLE packet layouts without auditing Android and ESP32 producers/consumers and protocol tests.
- Keep hardware test results distinct from native/CI tests.
- Avoid unrelated feature changes when addressing navigation behavior.

## Session handoff procedure
At the start: work from `valhalla` (not obsolete `main`); inspect branch, recent commits, AGENTS.md, this file, relevant source, tests and CI.
At the end: update verified implementation status, changed files, tests run (pass/fail/not run), known regressions, open questions, and exact next action. Include commit SHA/PR when available.

## Latest documentation session — 2026-10-28
- Established this continuity record and root agent instructions on the active `valhalla` development branch.
- No navigation code was changed and no hardware/build tests were run in this documentation-only session.
- Next: audit current Valhalla maneuver → Android navigation → BLE → ESP32 display pipeline, then implement and test roundabout/timing fixes.
