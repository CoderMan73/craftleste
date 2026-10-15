# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial scaffolding and foundation files (README.md, LICENSE, AGENTS.md, .gitignore, ROADMAP.md, CHANGELOG.md).
- `ai-docs/` for AI field notes, decisions, and lessons learned across agent sessions.
- `.agents/skills/` for repo-local skills.
- `docs/` reserved for human-facing documentation.
- M1 scaffolding: Gradle build (Loom 1.18-SNAPSHOT, MC 26.2, loader 0.19.5, fabric-api 0.161.0+26.2, Java 25) with the Rinku 3.0.4-26.2, Cloth Config 26.2.155 and Mod Menu 20.0.3 dependencies; committed Gradle wrapper (9.7.1).
- `fabric.mod.json` (id `craftleste`, GPL-3.0, client-side) and the main/client entry points.
- `BrowserScreen`: a flat GUI screen that renders any URL through Rinku, with deferred init through `scheduleForInit` and full mouse/keyboard bridging. M1's exit criterion (a screen rendering any URL in the real client) and M2's (the port's frontend booting to its Play button with the player's assets in OPFS) were both verified by user-driven `runClient` runs; Celeste itself launches and is playable with the keyboard, with saves and audio working.
- Probe 1 capability page (directory picker, OPFS round-trip, gamepads) bundled as a data URL, opened with the craftleste keybind (default C).
- `docs/rinku-api-notes.md` seeded from craftlatro's source-verified notes.
- M2 runtime pipeline: `WeblesteDownloader` fetches the official Webleste build (GitHub release `latest` of MercuryWorkshop/celeste-wasm, branch `threads-v2`, pin recorded in `ai-docs/notes/2026-10-14-m2-webleste-pipeline.md`) into a per-commit runtime directory; `RuntimeExtractor` unpacks the zstd tar; the runtime is never bundled.
- M2 local serving: a `com.sun.net.httpserver` on loopback serves the runtime root plus the player's Celeste files with COOP/COEP/CORP on every response, `no-store` caching, `application/wasm` for the port's split-wasm chunks, and a directory-traversal guard.
- M2 intake: `CelesteIntake` accepts the Celeste.exe or its Content folder, validates the port's Content contract, and copies the files into the instance's game directory.
- M2 bootstrap page: the mod-owned, no-file-dialog intake path - it streams the player's files from the local server into OPFS at the layout the Webleste loader expects (`Celeste.exe`, `Content/...`, `.ContentExists`) and hands off to the port's index; an install stamp makes an interrupted copy redo itself.
- Probe 2 page (threaded-wasm prerequisites: cross-origin isolation plus a 16-worker SharedArrayBuffer barrier) served through the local server.
- Commands `/craftleste fetch`, `/craftleste load <path>`, `/craftleste open`, `/craftleste probe`, `/craftleste threads` (Cloth Config settings arrive in M4).
- `PageInspector`: runs JS in the open page through JCEF's `executeJavaScript`, with results relayed through a display handler and page warnings/errors logged; F8 inspects the open page, `/craftleste opfs` and `/craftleste js <expression>` run the same from chat.
- The bootstrap copy runs eight files in parallel and the server streams responses instead of buffering each whole file, for a much larger share of the link's speed.
- `/craftleste load` with no path reuses the stored one; all commands now report what they did.
- Auto-provisioning on open: the keybind and `/craftleste open` fetch the Webleste runtime when it is missing and reload the player's Celeste files from the remembered path when those are missing, then open the screen; only a first run without a remembered path asks for anything.
- Chromium switches registered before CEF startup (`enable-features=SharedArrayBuffer`, `isolate-origins`) so the port's threaded wasm can start - CEF grants no concrete agent cluster by default, which otherwise leaves `SharedArrayBuffer` undefined despite the COOP/COEP headers.
- Intake refuses an XNA build of Celeste with a message saying how to get the FNA build (Steam's Linux build, the Windows `opengl` beta, itch, or the port's own download page): the port's loader only provides the XNA-named assemblies for FNA-built exes, and the XNA build's own assemblies live in the GAC, so `CelesteLoader.Init()` cannot load them.
- Automatic install discovery: the open key searches the machine for a complete FNA build - every Steam library (via `libraryfolders.vdf`), then the itch and GOG default layouts - copies the first one it finds, and records the path; a previously stored path is still tried first, and an XNA-only machine gets its Steam install named with the "use the opengl beta" explanation. `/craftleste find` runs the same search on its own. Acquisition is not automated by design: the game is player-supplied.
- Readiness now judges the copied files by the same contract as the install, FNA marker included. A player who switches the Steam beta from the default XNA build to `opengl` previously kept being handed the stale XNA copy - which passed the old exe-plus-Content check - and the port died inside its own boot on `Microsoft.Xna.Framework.Game` with no message from the mod. The copy now reads as incomplete after a beta switch and is reloaded from the same stored path automatically; the bootstrap page's verification and its "already installed" shortcut carry the same check.
- The intake copy now mirrors the install root instead of merging into the existing directory: a beta switch left the XNA build's `Celeste.exe.config` beside the FNA build's files, and the port would boot against a mixture of two releases. A test pins the copied file set to the install's file set.
- The bootstrap now removes the patcher's output (`CustomCeleste.dll` and the Everest artifacts) when the installed files change. The port decides whether to patch by whether that file exists, so a stale patch from a previous build was being booted instead of the files just installed - which is what kept producing the `Microsoft.Xna.Framework.Game` error after the beta switch. A patch counts as current only if it is newer than the copy that produced the files it was patched from, so repeat opens keep it instead of re-patching every time. The port's save directory (`Celeste/`) is left untouched: it is player state, not derived state.
- F8 now reports its own failures into chat and the log instead of failing silently, since the screen owns every key while it is open and a quiet failure costs a debugging round.
- `/craftleste opfs` (and F8) also dumps the .NET runtime's own view of the filesystem - `/libsdl` (the OPFS mount) and `/bin` (the loader's assembly symlinks) - so a mismatch between what is in storage and what the runtime can see is visible without guessing.
- The browser now outlives the screen (`CraftlesteBrowser`, the pattern craftlatro uses for its
  love.js page): closing the screen only parks the page - audio muted, paint rate cut to a trickle -
  so reopening the game skips the port's whole boot instead of re-patching Celeste every time. A
  genuine close happens on world unload and client shutdown.
- Parking no longer shrinks the browser's surface. Two things are pinned to the browser's size at
  creation and do not follow a later resize - the port's game canvas backing store, and Rinku's
  renderer texture, which is only reallocated when a paint at the *new* size arrives. Shrinking to
  320x180 while parked left both stuck small, so the next open stretched a 320x180 render across the
  whole screen: everything huge and pixelated. The frame rate cut (60 to 5) is what actually costs,
  and it is kept.
- The viewport is now only re-applied when the screen size actually changes, instead of being
  re-declared on every rendered frame.
- The browser's surface and the blit destination are sized from the window's own logical size
  (`getGuiScaledWidth`/`getGuiScaledHeight`) rather than from `Screen.width`/`height`. Those Screen
  fields only refresh when the screen is rebuilt, so the surface was created at whatever size the
  window had at that moment and stayed there while the render stretched it across the screen - the
  zoomed-in, button-less page. Reading the window also makes window resizes and GUI-scale changes
  land live.
- F8 now reports two things: `[layout]` (the page's own viewport size, device pixel ratio, button
  count, and the canvas's backing-store versus CSS size) and `[storage]` (the OPFS plus the .NET
  runtime's own `/libsdl` and `/bin` views). A screenshot cannot tell a stretched surface from a
  page that laid itself out narrow; those numbers can.
- Probe 2 (threaded wasm) reports its static facts - `crossOriginIsolated`, `SharedArrayBuffer`, and this document's own response headers - the moment it opens, with the 16-worker barrier behind a button.
- Standalone probes as JUnit tests (runtime extraction round-trip, local server end-to-end, Celeste intake, install discovery) run by `./gradlew check`.

### Fixed
- The bootstrap page's script had a syntax error left by an edit, so it never ran and the page sat on its opening text forever - the inspector kept working, which made it look like a hang rather than a parse failure. The page scripts are now validated by a test that tokenizes them the way a JavaScript parser would and requires every bracket to balance; the guard was proven by re-injecting the bug and watching the test fail.
- A parked browser left the renderer's texture at the parked 320x180 size, and the blit declared the full viewport as the texture size, so the leftover small surface was upscaled across the screen - the "blurry after reopening" symptom. The blit now declares the renderer's real texture size, and unparking nudges a repaint so a paint at the new size actually arrives.
- The browser surface was being drawn with `GuiGraphicsExtractor.blit`'s ten-argument overload, which hardcodes the source rectangle to the destination size. The chromium surface is framebuffer-sized (`screenWidth * guiScale`) while the GUI quad is gui-scaled, so the sampled UV range became `(0 + viewportWidth) / surfaceWidth` — with a 1920x1058 surface in a 960x529 quad that is `0 to 0.5` on each axis: only the top-left quarter of the page was drawn, stretched over the whole screen. That is the zoomed-in, button-less page. The source rectangle is now passed explicitly on the eleven-argument overload, so the whole texture maps to the whole viewport and the GPU downscales.
- The page inspector's injected script had a syntax error that made F8 fail every time while logging a browser `Uncaught SyntaxError: Unexpected token 'const'` once per retry: the probe expressions are statement blocks and were interpolated into `await ( <expression> )`, so the browser was asked to parse `await (const out = []; ...)`. They are now wrapped in their own async function body.
- The port was re-patching Celeste on every single open. The bootstrap decided whether the patch was still valid against a *recorded* copy time, and that record was only written on the full-copy path - a repeat open, which takes the skip path, never wrote one, and a missing record read as "stale", so the patch was deleted and redone forever. The check now compares the patch against the input file's own modification time.
- `.gitignore` no longer ignores `gradle/wrapper/gradle-wrapper.jar` (a clone cannot work without it, and CI needs it committed); `run/` and `.kilo/` are now ignored.
- Open-source codec libraries (`zstd-jni`, BSD-2-Clause; `commons-compress`, Apache-2.0) as JiJ-bundled dependencies, needed to unpack the official Webleste release archive; no game runtime, game asset or browser binary is bundled anywhere in the mod jar.

> Guidance from `references/changelog-guide.md` (Keep a Changelog). Replace scaffolding
> notes with per-milestone entries as work lands.
