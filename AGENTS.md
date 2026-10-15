# AGENTS.md - craftleste

## Agent Role

Specialist maintainer of **craftleste**. Prioritize correctness of the integration glue, keep
third-party/externally-supplied content out of the repo (see Boundaries > Never), and verify
each milestone against PLAN.md (tracked in the tracking workspace, not in this repo).

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Minecraft | 26.2 | Target game (stay version-locked with craftlatro; Rinku ships per-MC artifacts) |
| Fabric Loader + API | 0.19.5 / 0.161.0+26.2 | Mod loader and API |
| Java | 25 | Compile toolchain (mirrors craftlatro) |
| Gradle | Fabric Loom 1.18-SNAPSHOT | Build system |
| Rinku | 3.0.4-26.2 | Embedded Chromium (`de.keksuccino:rinku-fabric`, Maven: https://keksuccino.github.io/maven/); 3.0.5 exists in source for a later bump |
| Cloth Config | 26.2.155 (LGPLv3) | Settings screen; hard dependency |
| Mod Menu | 20.0.3 (compile only) | Settings entrypoint; not a runtime dependency |
| Webleste | commit-pinned `threads-v2` | Genuine Celeste wasm runtime (MercuryWorkshop/celeste-wasm); fetched at runtime, never bundled |
| Celeste (FNA) | 1.4.0.0 | Player-supplied game files (exe + Content); never bundled |

## Key Commands

```bash
./gradlew build         # Build the project
./gradlew runClient     # Run locally
./gradlew check         # Run tests
```

IDE config: Loom 1.18 dropped `genIntelliJ`; the equivalent is `ideaSyncTask` (auto-triggered on
IDE import). The rest of this file's task names are current.

## Dev environment (Windows)

The dev machine is Windows and the tooling is not. Each of these has cost a session at least once:

- **PowerShell 5.1 has no `&&`**: chain with `;` and test `$?`.
- **Existing files are CRLF**: anchor `edit` calls to short unique strings, not whole blocks.
- **Never replace an open file**: close every stream before `Files.move` or delete.
- **The AWT clipboard is unavailable in a dev launch** (headless JVM): use the GLFW clipboard.
- **CI is Linux**: `gradlew` needs its executable bit committed and `gradle/wrapper/gradle-wrapper.jar` must not be gitignored.
- **Local sources before web search**: `E:\Coding_Projects\MCSourceCode` (fabric-api/26.2, vanilla-minecraft/26.2, cloth-config, Rinku) for API and version questions.

## Architecture

Integration glue only — the mod does **not** reimplement Celeste and owns no game engine. Data flow at runtime:

1. Player supplies their own Celeste 1.4.0.0 (FNA build) as either the executable path or the Content directory; intake treats them as the same thing and resolves Content from the exe when only the exe is given.
2. `WeblesteDownloader` fetches the pinned `threads-v2` port build from its official origin into `<instance>/craftleste/game`, only when missing, recording the commit pin in `ai-docs/`.
3. A local HTTP server (`com.sun.net.httpserver`) serves the game directory with COOP/COEP/CORP on every response, `application/wasm` MIME handling, a directory-traversal guard, and support for the port's split-wasm chunked fetch.
4. A mod-owned bootstrap page fetches the player's assets from the local server and writes them into browser OPFS at the loader's mount paths (`/libsdl`), then navigates to the port's index — this is the no-file-dialog intake path.
5. Rinku renders the port's URL in an embedded Chromium on a flat, framed Fabric GUI screen (`BrowserScreen`: title bar, drag, resize, fullscreen), 16:9 game area inside the window.
6. Minecraft keyboard/mouse input (and gamepads only if Probe 1 confirms support) is mapped into the embedded browser view.

Key directories:
- `src/main/java` - Mod source: downloader, HTTP server, screen + input bridge, config, paths
- `src/main/resources` - `fabric.mod.json` (id `craftleste`, GPL-3.0, client-side), bootstrap page, assets
- `ai-docs/` - AI field notes, decisions, lessons learned across sessions
- `docs/` - Human-facing documentation only

Instance-local paths (config, saves, game directory) live behind a single paths class in the `CraftlatroPaths` mold; never hardcode them.

## Code Style and Conventions

- Java 25, standard Minecraft modding conventions (Fabric Loom). Write self-documenting code; prefer explanatory method names over inline comments. The exception is non-obvious behavior (COOP/COEP necessity, wasm chunked fetch, port quirks), which belongs in Gotchas.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `ci:`, ...), subject imperative, one change per commit.
- Version is one line in `gradle.properties` (`MAJOR.MINOR.PATCH+MCVER`, first release `0.1.0+26.2`); `CHANGELOG.md` follows Keep a Changelog.

## Testing

- **Framework**: none configured yet; standalone probes compiled against the real classes are the default verification move (see the verification ladder).
- **Run all**: `./gradlew check`
- **Coverage target**: none yet; there are no test sources.

### The verification ladder

Use the highest rung that actually runs, and name the rung in every claim:

1. `./gradlew build` (or `compileClientJava`) — compiles only.
2. A standalone probe compiled against the real classes — for logic that cannot run inside Minecraft (e.g. the OPFS-write harness, save-store smoke tests).
3. The `/craftleste stats` diagnostics report, pasted by the user — the measurement. Ask for the report, not an impression.
4. A user-driven `./gradlew runClient` — the only channel that can verify anything in the browser. **An agent cannot open the game screen** (it needs a real input event), so every browser change is handed back for the user to run.

A claim of "verified" that rests on reading source is not on this ladder. At most one browser-affecting change per user verification run, with a statement of exactly what to check.

## Commits, versions and releases

The short version (full runbook lands at `docs/commits-and-releases.md`, copied from craftlatro):

- Conventional Commits with one change per commit; bodies that record a dead end stay in history.
- Version bumped only in `gradle.properties`; the tag and GitHub release are `vMAJOR.MINOR.PATCH`.
- Release-only CI attaches the jar (`.github/workflows/release.yml`, copied from craftlatro); **Ask First** on every tag and release.

## Boundaries

### Always
- Keep player-supplied and third-party content (Celeste code/assets, FMOD binaries, Webleste build files, the Chromium runtime) out of the repo and out of the mod jar; every runtime component is fetched at runtime from its official origin.
- Record decisions with the evidence that would settle them, and any non-obvious findings, in `ai-docs/decisions/` or `ai-docs/notes/` before code.

### Ask First
- Creating a remote repository or pushing tags/releases.
- Changing the project license or adding dependencies with non-open runtimes.

### Never
- Bundle third-party game code/assets or redistributable runtimes in the jar or repo.
- Hardcode paths or credentials; store user file-chooser state under the config directory.

## Context Loading

| Task | Read |
|------|------|
| Start work | PLAN.md (tracking workspace: `E:\Coding_Projects\future-projects\public\active\craftleste\PLAN.md`), AGENTS.md, this file |
| Runtime docs | `docs/` |
| Field notes | `ai-docs/` |

## Gotchas

- **Webleste licensing is undefined** (no license file) and its audio layer embeds proprietary FMOD object code: runtime download from the official origin only, nothing hosted, nothing bundled; an upstream license inquiry is recommended before 1.0.
- The wasm port is cross-origin-isolated: it needs COOP/COEP/CORP on every served response or it will not boot.
- The served game directory is version-stamped on purpose — a runtime change must never be served from a stale browser cache (the craftlatro `440f734` lesson).
- Memory: 600 MB to 1.6 GB for the port alone, plus Minecraft, plus the roughly 150 MB Chromium runtime. Measure with the stats report (M3/M4) before promising anything; render scale is the lever.
- The port is self-described as mostly/near complete: first-run warning plus honest measurement replace any "1-to-1" promise, and Everest is not part of the promise.
- No upstream tags exist for the port: pin by commit and record the pin in `ai-docs/`; bump deliberately.
- Browser storage can evict save data: export/import is a first-class feature, not an afterthought.
- The three embedded-browser unknowns (file pickers in OSR, threaded wasm under OSR, gamepad support) each have a named probe in M1/M2 and a fallback (bootstrap page without pickers; keyboard-first input).

## Local Skills

This project may define repo-local skills in `.agents/skills/`. When starting a task, scan
this folder for skills whose descriptions match the current work.

- **Discovery**: Check `.agents/skills/` at task start.
- **When to use**: Any skill whose description matches the task at hand.
- **When to suggest**: Recurring, repeatable processes worth standardizing.
- **When NOT to suggest**: One-off decisions, single-use observations (use ai-docs/).

## ai-docs

This project uses an `ai-docs/` subdirectory for field notes, architectural decisions, and
lessons learned. See `ai-docs/README.md` for guidelines on recording knowledge between
agent sessions.

> Guidance from `references/agents-guide.md`.
