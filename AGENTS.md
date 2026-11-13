# Development instructions — Moto GPS

These instructions apply to the repository unless a more specific AGENTS.md exists deeper in the tree.

## Start of every coding session
1. **Mandatory: always read the root `PROJECT_STATE.md` before analyzing, planning, editing, committing, or resuming work on this project**, even in a new chat or when the task appears small. Review prior decisions, implemented fixes, failed attempts, pending tests, and known regressions so old problems are not reintroduced. Also read applicable nested `AGENTS.md` files.
2. **`valhalla` is the primary/default repository branch and the only integration baseline. `main` has been deleted**: do not use, recreate, or target it. `signal` is obsolete and should be deleted after any needed archival check; `test/*` branches are experimental. Check actual branch, code, recent commits, tests and CI before editing.
3. Treat PROJECT_STATE.md as a handoff, **not** proof of implementation; resolve discrepancies by inspecting source.
4. State scope and potential cross-component impact before changing protocol, routing or navigation behavior.

## Current-state handoff at the top of PROJECT_STATE.md
- **Mandatory: maintain a single section titled `## STATO ATTUALE — RIPARTIRE DA QUI` immediately below the main `# Moto GPS — Project State` heading**. It must be the first section read when opening a new chat or resuming work, followed by the relevant detailed history and actual code/CI verification.
- Update this section **at the end of every work session whenever state or next steps change**, in the same documentation change as the detailed historical entry. Keep it short, factual and current; do not append a second current-state section or silently rewrite historical entries.
- Include: **date of last update; integration branch (`valhalla`) and active experimental branch (if any); latest implemented change; latest verified build/CI status with explicit distinction between user-reported and independently verified results; hardware/on-road testing status; known blockers/risks; and one specific next action**.
- Distinguish work **implemented** from work **planned**, and successful compilation from on-device validation. If branch-specific state differs, say so explicitly. When closing or merging a test branch, update the branch reference and next step.
- If the section is absent, create it before concluding the next work session. If the document starts with older dated notes before the main heading, normalize the heading/section order without deleting the historical content.

## Branch lifecycle and cleanup
- Keep **exactly one integration branch**, `valhalla` (also the repository default), and **normally only one active experimental `test/*` branch** at a time. Do not recreate `main` or maintain `signal`.
- Start experimental work from the latest `valhalla`; integrate it into `valhalla` only after explicit user approval and relevant validation.
- After a successful merge and verified target branch state, delete the merged remote experimental branch and prune stale local tracking refs. Do not keep completed or obsolete test branches indefinitely.
- Before deleting **any** remote branch, inspect its ahead/behind commits against `valhalla`, unmerged work, open PRs, and whether it is the current active experiment. If commits would otherwise be lost, create and verify an archival tag or obtain explicit approval to discard them first. Never force-delete or silently lose unique work.
- Do not delete the current active test branch or change the default branch as part of routine cleanup; explain exceptions if a temporary second test branch is genuinely needed.

## GitHub Actions / CI execution policy
- **Never trigger a GitHub Actions build for each intermediate commit.** Finish all planned source, test, and documentation changes first, then trigger **one consolidated CI build** for the final revision only.
- Prefer preparing related edits as a single final commit/push. If intermediate pushes are unavoidable, explicitly prevent their CI runs (for example with `[skip ci]` where supported), and run CI only on the final commit. Do not manually dispatch workflows during intermediate work.
- Check the workflow triggers before pushing: instructions in this file alone do not disable automatic GitHub Actions `push` triggers. Do not claim CI ran only once unless verified.
- If the final build fails, investigate and batch necessary fixes before requesting another run; avoid repeated speculative runs.

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

## Operational manual maintenance
- At the end of each coding session, **check whether `docs/MANUALE_OPERATIVO.md` needs an update**. When procedures or user-facing commands change, update the manual **in the same consolidated change** as the implementation and `PROJECT_STATE.md`; do not rely on the project-state handoff as a substitute for runnable instructions.
- Update the manual for changes to prerequisites and tool versions, repository/branch workflows, build and CI instructions, ESP32 flash offsets or port defaults, Android installation, MotoMap/Valhalla import and conversion, diagnostic export, or other operational steps users must perform.
- Keep Windows PowerShell vs CMD commands clearly labeled, paths and filenames consistent with the current project, and distinguish **validated procedures** from experimental or untested ones. Preserve historical instructions only when explicitly labeled as historical.
- If an implementation change does not affect a user procedure, **do not make cosmetic manual edits** solely to satisfy this check. Note any relevant documentation caveat in `PROJECT_STATE.md` when it matters for handoff.

