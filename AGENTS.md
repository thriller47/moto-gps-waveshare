# Development instructions — Moto GPS

These instructions apply to the repository unless a more specific AGENTS.md exists deeper in the tree.

## Start of every coding session
1. **Mandatory: always read the root `PROJECT_STATE.md` before analyzing, planning, editing, committing, or resuming work on this project**, even in a new chat or when the task appears small. Review prior decisions, implemented fixes, failed attempts, pending tests, and known regressions so old problems are not reintroduced. Also read applicable nested `AGENTS.md` files.
2. Use `valhalla` as the active development baseline; `main` is obsolete. Identify active branch, recent commits, current code, relevant tests and CI state. Do not use `main` as the development baseline.
3. Treat PROJECT_STATE.md as a handoff, **not** proof of implementation; resolve discrepancies by inspecting source.
4. State scope and potential cross-component impact before changing protocol, routing or navigation behavior.

## Engineering rules
- Prefer small, reviewable changes on an appropriate feature/test branch. Do not push to upstream or merge into main without explicit instruction.
- Check Android ↔ BLE ↔ ESP32 compatibility before changing payloads or navigation semantics.
- Keep offline Valhalla/OSM assets and Photon online-search behavior clearly distinguished.
- For turn indicators, inspect maneuver type, exit geometry, route progress and transition timing; test roundabouts and successive turns.
- Preserve existing arrival distance, gray context roads and agreed display layout unless explicitly requested otherwise.
- Never claim a build, test, device flash or on-road validation passed unless actually executed.

## End of every coding session
Update `PROJECT_STATE.md` in the same change whenever implementation state, decisions, bugs or next steps change. Record:
- Date, branch, commit/PR reference.
- What changed and key files.
- Tests run with results; tests not run and why.
- Open bugs, risks, hardware verification pending.
- One concrete next action.
Do not overwrite older decisions silently; document reversals and reasons.
