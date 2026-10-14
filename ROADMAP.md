# craftleste Roadmap

## Project Goals

- The genuine Celeste runs, loaded from the player's own copy; the mod is integration glue, not an engine.
- Nothing copyrighted is bundled or distributed; every runtime component (Webleste port, Chromium runtime) is fetched at runtime from its official origin.
- The surface is the flat GUI screen, structured exactly like craftlatro, so the two projects stay reviewable against each other.
- Ship the second independent app in the craftlatro family, proving that its browser-as-a-screen pattern generalizes — the precondition for any later shared-library extraction.

## Project Non-Goals

- Reimplementing Celeste in Java or via LuaJ.
- Bundling the Webleste port or its FMOD layer (license undefined), or hosting any runtime or game assets.
- Depending on CEF file dialogs for asset intake (the bootstrap page needs no dialog at all).
- Promising 1-to-1 fidelity; the port is self-described as mostly complete.
- Everest or third-party Celeste mod content support.

## Shipped

(none yet)

## Current Objectives

- [ ] Scaffolding and dependency integration
- [ ] Webleste runtime acquisition, local serving, asset intake
- [ ] In-game screen surface
- [ ] Controls, UX, config, and fidelity
- [ ] Saves, packaging, and guardrails

## Planned

- Shared-library extraction with craftlatro after v1 (craftlatro roadmap backlog, Packaging/reuse).
- Rinku upgrade from the pinned 3.0.4 to 3.0.5 once available for MC 26.2.
- Upstream Webleste license inquiry (recommended before 1.0).
- Broadening the version matrix (per-MC Rinku artifacts, NeoForge) only as a dependency-validation task after v1.

## Out of Scope

- Any reimplementation of the game or its engine.
- Hosting or redistributing Celeste, Webleste, FMOD, or Chromium binaries.
- Version-matrix broadening and the shared library before v1.

## Aspirational Goals

- A 0.x release with licensing settled and the memory budget measured, not promised.
- Gamepad support if Probe 1 (`navigator.getGamepads` inside Rinku's browser) answers yes; keyboard-first otherwise.
- A reusable browser-screen library extracted from the craftlatro/craftleste pair.

> Guidance from `references/roadmap-guide.md`. Populate from PLAN.md before first release.
