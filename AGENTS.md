# Development instructions — Moto GPS

These instructions apply to the repository unless a more specific AGENTS.md exists deeper in the tree.

## Start of every coding session
1. **Mandatory: always read the root `PROJECT_STATE.md` before analyzing, planning, editing, committing, or resuming work on this project**, even in a new chat or when the task appears small. Review prior decisions, implemented fixes, failed attempts, pending tests, and known regressions so old problems are not reintroduced. Also read applicable nested `AGENTS.md` files.
2. **`valhalla` is the primary/default repository branch and the only integration baseline. `main` has been deleted**: do not use, recreate, or target it. `signal` is preserved only as the historical online-OSM/OSRM implementation; `test/*` branches are experimental. Check actual branch, code, recent commits, tests and CI before editing.
3. Treat PROJECT_STATE.md as a handoff, **not** proof of implementation; resolve discrepancies by inspecting source.
4. State scope and potential cross-component impact before changing protocol, routing or navigation behavior.

## Engineering rules
- Prefer small, reviewable changes on an appropriate feature/test branch. Merge into `valhalla` only with explicit approval; never push upstream or target the deleted `main` branch.
- Check Android ↔ BLE ↔ ESP32 compatibility before changing payloads or navigation semantics.
- Keep offline Valhalla/OSM assets and Photon online-search behavior clearly distinguished.
- **Turn guidance is the green route with progressive 3.4× zoom, not ordinary turn arrows.** Do not reintroduce turn/roundabout arrows, including when MotoMap gray roads are missing. Retain the numeric turn distance, distance to destination, and checkered arrival flag. Examine route progress only for new reproducible defects.
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
