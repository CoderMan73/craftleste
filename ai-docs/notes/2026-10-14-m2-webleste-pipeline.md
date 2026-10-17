# M2: Webleste runtime acquisition, local serving, asset intake

## Runtime pin (record 2026-10-14)

| Fact | Value |
|---|---|
| Origin repo | https://github.com/MercuryWorkshop/celeste-wasm (branch `threads-v2`) |
| Official build | GitHub release tag `latest`, asset `webleste-loader.tar.zst` (zstd tar of `frontend/dist`) |
| Release published | 2026-08-26T06:15:01Z |
| Pinned commit | `b4b19eb24a8499e4de96aee79f1272ffdb57281f` |
| Asset size | 42,845,972 bytes; sha256 `c0b7bc350efc0162b6542a18e04930bf31d28b0de966ebb5fba19ea3a86aa679` |

CI fact worth keeping: the release is a continuous build of the branch tip (`.github/workflows/main.yml`
rebuilds and republishes it on every push), so the pin is a snapshot of a moving branch. The mod
fetches the release metadata (which carries the commit hash) and records the pin in three places:
the extraction directory name (`<game>/runtimes/<commit>`), the `<game>/current` pointer file the
server reads, and the config. If the GitHub API rate-limits an unauthenticated client, the fetch
falls back to the stable asset URL with pin `unknown`; a later successful metadata fetch records
the real hash.

## Problem

M2 needs three things the mod did not have: the Webleste runtime itself, a server that can feed it
to the embedded browser under the isolation it requires, and a path from the player's own Celeste
install into the layout the port expects.

## Contracts confirmed from the port's source (branch `threads-v2`)

- **OPFS contract** (`frontend/src/fs.tsx`): the OPFS root is the game root. `hasContent()` requires
  `Content/{Dialog,Effects,FMOD,Graphics,Maps,Monocle,Overworld,Tutorials}` plus a `.ContentExists`
  marker file; the loader then mounts that root at `/libsdl` and loads `/libsdl/Celeste.exe` (how.md).
  `skipOobe()` writes an empty version of exactly this layout, which is what the bootstrap page
  now writes for real.
- **Split wasm** (`frontend/src/game/dotnet.ts`): the custom resource loader requests
  `dotnet.native.<hash>.wasm0`, `.wasm1, ... with the index appended, and stops at the first
  non-200 or non-wasm content type. The Makefile produces those chunks with
  `split -b20M -d -a1`. Consequence for the server: a missing chunk must 404 as a plain error and
  never fall back to an HTML page, and the chunk MIME type must be wasm-compatible.
- **Relative boot paths**: the page imports `../_framework/dotnet.js` and derives its mount path from
  `location.pathname`, so the runtime root is served at `/` and the port is none the wiser.
- **COOP/COEP**: `frontend/public/_headers` matches the headers the local server sets on every
  response (same-origin / require-corp), which is what gates SharedArrayBuffer for the 16-thread
  pthread pool (`pthreadPoolInitialSize: 16`).

## Solution

- `CraftlestePaths`: instance-local layout - `<game>/runtimes/<commit>` per fetched pin (versions
  never overwrite each other), `<game>/current` pointer, `<game>/celeste` for the player's files.
  The resolvers take the game directory as an argument so the standalone probes test the real
  layout without a running game.
- `WeblesteDownloader`: release metadata parse (two anchored patterns, no JSON dependency),
  download to a `.part` file, extract to a staging directory that only replaces the published one
  when it contains an index.html, then the pin file plus config. A crashed attempt leaves the
  previously served runtime intact.
- `RuntimeExtractor`: zstd stream (zstd-jni, BSD-2-Clause) into tar (commons-compress, Apache-2.0),
  with a traversal guard. Both libraries are open-source codecs, JiJ-bundled in the jar like any
  Fabric library; the boundary that matters (nothing of Webleste, Celeste or Chromium is ever
  bundled) is untouched - the 42 MB runtime is fetched at runtime from the official release.
- `CelesteIntake`: accepts the exe or the Content folder, resolves the other side, validates the
  port's 8-folder contract, and copies the exe (stored as `Celeste.exe`) with its siblings plus the
  whole Content tree into `<game>/celeste`, replacing an old copy on re-intake.
- `CraftlesteHttpServer`: two roots under one origin (runtime at `/`, player files at the port-known
  names), COOP/COEP/CORP on every response including errors, `no-store` everywhere, wasm chunk MIME
  mapping, traversal guard that never falls back to HTML, plus three mod-owned contexts:
  `craftleste/bootstrap.html`, `craftleste/probe2.html` and `craftleste/manifest.json`.
- `BootstrapPages`: the no-file-dialog intake. The bootstrap page reads the manifest, streams every
  file from the local server into OPFS at the port's layout, writes the `.ContentExists` marker and
  redirects to the port's index. A `localStorage` stamp (file count + byte total) makes an
  interrupted copy redo itself: the port's own `hasContent()` checks only that the folders exist, so
  without the stamp an empty-folder copy would read as installed. The threaded-wasm probe page
  serves Probe 2: crossOriginIsolated + a 16-worker SharedArrayBuffer barrier, which are exactly
  the prerequisites the port's pthread pool gates on.
- Commands (`/craftleste fetch|load|open|probe|threads`) on a worker thread for the two long steps,
  since the Cloth settings screen is M4. The open key leads to the game when it is ready and to the
  probe pages when it is not.

## Decision: HTTP cache vs. the stale-runtime lesson

craftlatro's `440f734` lesson was love.js's IndexedDB package cache keyed by `?g=`. Webleste's
split-wasm chunks are fetched fresh per load, and the port's static resources are content-hashed,
so the mod serves everything `no-store` and pins versions in directory names. What is NOT settled
by evidence: whether the .NET runtime persists its assembly cache in IndexedDB across pin changes
(the same risk shape as love.js). The evidence that settles it is a dev run that fetches pin A,
plays, fetches pin B and plays again; until then a stale-assembly symptom would be fixed by adding a
pin query parameter to the entry navigation. Recorded here so the next session starts from the
symptom, not the guess.

## The port's own frontend boots

The third run (2026-10-14, second half) reached the port itself: after the bootstrap copy completed
(1218 files, 1111 MB, about 4.5 minutes at roughly 4 MB/s), the browser navigated to the port's page
and rendered it - the webleste splash background, its logo at v1.4.0.0, and its Intro step. That
confirms the local server, the COOP/COEP/CORP headers and the URL layout all feed the port correctly,
and it incidentally closes two probes: the port's own Intro reports the File System Access pickers as
unavailable in the embedded browser (its "Copy local Celeste assets" button is disabled), which is
RESEARCH.md unknown 1 answered - the picker path is dead here, so the bootstrap page is not a nice-to-have
but the only intake path.

What the port showed was its `Intro` step, which per `splash.tsx` only renders when
`initialHasContent` (fs.tsx: `Content/{8 folders}` plus `.ContentExists` at the OPFS root) is false.
So the port's view of OPFS does not contain a copy our own page reports as complete. The write side
is verified by the bootstrap's own checks before it redirects; the read side was unobservable until
the page inspector existed. Recorded as the M2 blocker, with the diagnostic below.

## Diagnostic channel (page inspector)

An agent cannot see the browser, and the port's page cannot be edited, so the mod got a window into
whatever page is open:

- `PageInspector` runs JS via JCEF's inherited `executeJavaScript`, with the expression's value
  returned through the page title and captured by a `CefDisplayHandler` relay. Warnings and errors
  the page logs are also relayed into the Minecraft log, so a port boot failure is visible.
- **F8** while the screen is open inspects the open page (the screen owns every key while it is open,
  which is also why chat commands do nothing there) and answers in chat.
- `/craftleste opfs` runs the same inspection; `/craftleste js <expression>` runs any expression.

The inspection reports the port's exact storage view: the OPFS root entries, the Content subfolders,
which required items are missing, and the origin's usage/quota. That is the measurement that settles
the Intro mystery - either the files are there (and the gate is subtler than it reads) or they are not
(and the write went somewhere the port does not read).

## Threaded wasm: the real wall, and the CEF switch

The fourth run got past the copy entirely: the port's page ran its own JS, and the console relay
caught the decisive error - `MONO_WASM: Assert failed: SharedArrayBuffer is not enabled on this
page`. The assert is literal, at module scope of the port's `_framework/dotnet.js`:

```js
void 0 === globalThis.SharedArrayBuffer && qe(!1, "SharedArrayBuffer is not enabled on this page...")
```

So `SharedArrayBuffer` is undefined in the port's page. Chromium gates SAB on cross-origin
isolation, and that gate has a second layer CEF inherits: without a *concrete* agent cluster for
the origin (site isolation), COOP/COEP yield only logical isolation - the restrictions apply, the
capability does not. CEF's default startup builds no isolated origin for ours, and Rinku owns its
switch list with no mod hook, but JCEF calls `onBeforeCommandLineProcessing` on every registered
app handler during startup, and Rinku starts CEF on the first GUI render, long after mod
initializers run. `CraftlesteCefOptions` therefore registers that handler from the main initializer
and adds `enable-features=SharedArrayBuffer` (the documented bypass for exactly this gate) plus
`isolate-origins=http://localhost:18081` (the route to real isolation for the served origin; the
command line is fixed before the port is chosen, so the feature flag carries the general case).

The evidence that settles the outcome is the browser's own answer: Probe 2 now reports
`crossOriginIsolated`, `typeof SharedArrayBuffer` and this document's own response headers the
moment it opens. If the switch lands, SAB exists and dotnet boots regardless of which isolation
mode CEF granted; if not, the header line says whether the server side is even right.

Also from that run: `open` really does open the screen (the log shows it, and the browser loads) -
the "nothing happens" reading was the page still being blank while the port's bundle booted, which
the SAB failure explains.

## The port boots to its Play button (fifth run, 2026-10-14 late)

The switch landed. The fifth run's log opens with `Registered craftleste Chromium switches
(SharedArrayBuffer, isolated origin)`, and the rest of the run is the port actually running: the
dotnet runtime booted, the MonoMod patcher ran all its passes (MonoModRelinker, AutoPatch,
PostProcessor, [FMODPatcher] FMOD Version, forced GC after the patch), the port's main UI rendered
with its own top bar (Mods, Achievements, Files, Settings, Fullscreen) and the Play button, and
`hasContent()` passed on the way - the port showed its **Patch Celeste** step, which only appears
when the OPFS copy is found. That retires the earlier "Intro shows no content" dead end: the copy
is visible to the port, and the Intro in the run before was the SAB crash aborting the splash flow,
not a storage mismatch.

`Play` then hit the last wall: `CelesteLoader.Init()` threw
`System.IO.FileNotFoundException: Could not load file or assembly 'Microsoft.Xna.Framework.Game'`.
The player's install is the **XNA** build (the Windows Steam default - its root holds
CSteamworks.dll, steam_api.dll, fmod.dll/fmodstudio.dll and no FNA.dll). The port's loader ships
its own web FNA, and the game's `Microsoft.Xna.Framework.*` references resolve against it only for
an exe built against FNA; the XNA build's references point at the real XNA redistributables, which
live in the machine's GAC and are nowhere in the install folder, so nothing can load them. Every
FNA build - Linux Steam, the Windows `opengl` beta, itch's FNA build, the port's own
r58playz-hosted 64-bit Windows FNA build - has `FNA.dll` next to the exe and no XNA build does,
which makes it a clean marker.

Intake now checks for it and refuses with the explanation instead of failing a screen later in C#:
`CelesteIntake.validateInstall` requires `FNA.dll` in the install root (or in `lib64/`), and the
load command says how to get the FNA build when it is missing.

## Discovery, and why acquisition stays manual

The question "can we automate this for the user" splits in two, and the split matters:

- **Acquisition: no, and deliberately not.** Steam switches beta branches only through its own UI
  (no protocol URL, no public API), and downloading a hosted copy of a commercial game is exactly
  the redistribution this project refuses to touch - the runtime is fetched from its official
  origin, the game is player-supplied, and neither is ever hosted or bundled. The port's own intro
  states the same rule. So the answer to "how do I get the FNA build" stays text: Steam's Linux
  depot, the Windows `opengl` beta, itch, or the port author's own build.
- **Discovery: yes, nearly completely.** `InstallDiscovery` finds a complete FNA build on the
  machine without asking: every Steam library through each Steam install's `libraryfolders.vdf`
  (default roots on Windows, plus the Linux and macOS Steam folders), then the itch and GOG default
  layouts. The first candidate that is a complete FNA build is copied in and its path is recorded
  like any other. The stored path is still tried first, so a remembered install is never
  re-discovered; when it stops working, discovery runs instead.

The refusal path matters as much as the find path: when the only candidate is an XNA build (the
Steam default shape), discovery reports that build by name with the "switch the beta" explanation
rather than a bare "not found". A buggy first build of the loop scanned the real machine instead of
the injected candidates and promptly refused the player's own XNA Steam install - the machine's
`libraryfolders.vdf` parsing worked, which is the part that needed proving.

## The stale-copy trap (sixth run, 2026-10-14 late)

After switching Steam to the `opengl` beta, the run still failed with the same XNA assembly error -
and with no chat message at all. The cause was the readiness check, not the validation: the game
directory held the copy made from the default XNA build, and `isReady()` judged only Celeste.exe and
Content, so it called that copy complete and handed it to the port. The validation had been right all
along; it was just never consulted for the copy.

The install and the copy are different things, and they have to be judged by the same contract. The
fix makes `isReady()` require the FNA marker in the game directory as well, so a beta switch makes
the old copy read as incomplete, the stored path (same Steam folder, now the FNA build) is reloaded,
and the game opens. Confirmed on disk: the Steam folder after the switch holds
`FNA.dll, FNA3D.dll, SDL2.dll, Celeste.Content.dll, gamecontrollerdb.txt`, the game directory still
held the XNA set without them.

The same guard went into the bootstrap page's verification and its `alreadyInstalled()` check, so an
old OPFS copy from before the FNA check reads as incomplete and is recopied rather than booted into
the C# error. The failure the player saw was never a mystery error - it was "wrong build" wearing a
disguise - and the message now says so where they are looking.

## The merged copy (seventh run, 2026-10-14 late)

After the beta switch, the load ran correctly from the stored path (the log: `loading your Celeste
files from E:\SteamLibrary\...\Celeste.exe` → `copied 11 install files and 1216 Content files`, up
from 7/1211), the bootstrap copied to OPFS, the port patched Celeste - and Init still failed on
`Microsoft.Xna.Framework.Game`, the same as with the XNA build.

What the disk showed: the game directory held **12** files while the Steam install held **11** -
`Celeste.exe.config`, a leftover from the XNA build, had survived the recopy. The intake copy only
deleted the old `Content` tree and merged root files over what was there, so a beta switch produced
a directory that was part XNA and part FNA. The port patches whatever `Celeste.exe` it finds and the
game loads whatever sits beside it; a merged directory is exactly the shape that yields an XNA
assembly error from an FNA install. The copy now mirrors the install root exactly - it replaces the
directory rather than merging into it - and a new test pins the file set to the install's.

Verified along the way, all from local files rather than assumption: the exe references
`FNA v21.3.5.0` (no XNA at all - the earlier string grep conflated XNA *namespace* names with
assembly references); the loader and the patcher reference `FNA v25.11.0.0`; the runtime ships that
same FNA under `virtualPath: FNA.dll`; and **no** assembly in `_framework` (204 of them) or in the
game folder references anything Xna-named. What remains open is the exact reason the patched
assembly asks for XNA, and the inspector now answers it: it dumps `/libsdl` (the OPFS mount) and
`/bin` (the loader's assembly symlinks, built from the boot config by `MountDlls`) from the .NET
runtime's own filesystem, which is the difference between "the files are in storage" and "the
runtime can see them".

## Derived state must be invalidated with its input (eighth run, 2026-10-14 night)

The XNA error survived a clean FNA copy in the game directory *and* a clean FNA copy into OPFS. The
cause was a third copy nobody had thought about: `CustomCeleste.dll`, the patched assembly, which the
bootstrap writes nothing over because it is not in the manifest.

The port decides whether to patch by checking whether that file exists - `splash.tsx`'s
`initialIsPatched`, gated into `Intro` as "content present and already patched, so skip to done". So
after the first run (which patched the *XNA* Celeste.exe and produced exactly the XNA-referencing
assembly the error names, public key token and all), every later run found content plus a patched
assembly, skipped patching, and booted the patch made from the previous build. That is why the
failure was identical before and after the beta switch, and why it went straight to the red screen
with no Patch step in between.

The bootstrap now removes the patcher's outputs (`CustomCeleste.dll`, `Celeste.Mod.mm.dll`,
`MMHOOK_Celeste.dll`, `everest.zip`) before copying new files in, which forces the port to patch
against what was just installed. `Celeste/` - the port's save directory - is deliberately left
alone: it is player state, not derived state.

The first version of that fix was incomplete and would not have changed the outcome: it removed the
patch only on the full-copy path, and the failing run took the *skip* path (the files were already
current, so nothing was copied). Invalidation has to cover both paths, and it has to stay cheap for
repeat opens, so the rule is now: a patch is current only if `CustomCeleste.dll`'s modification time
is newer than the copy that produced the files it was patched from, recorded in `localStorage` when
the copy finishes. A missing record counts as stale - the safe default, since one extra patch costs
a minute while booting the wrong build costs the session.

This is the third time the same shape bit this pipeline, and it deserves to be named as one lesson
rather than three bugs: **a cache is only correct if everything that invalidates it is listed.** The
first was the browser's HTTP cache (the craftlatro `440f734` lesson, answered with `no-store`); the
second was the game-directory snapshot passed off as ready (answered by judging the copy by the
install's contract); the third is the patched assembly (answered by invalidating derived state on
copy). Each one hid behind a check that was true for the wrong reason.

Also from this run: the shutdown watchdog fired a thread dump and the client exited with -8 when the
game was closed while the port's runtime was still live. That is real but it is M3's lifecycle work
(the browser must be parked and CEF unwound before the JVM exits), not an M2 correctness issue.

The page inspector was also failing silently - F8 produced nothing readable. It now reports its own
exceptions into chat and the log, because a diagnostic channel that fails quietly costs a whole
debugging round.

## M2 is done: the game is playable (2026-10-15 final run)

With the blit crop fixed, the final run went all the way: the port's splash booted
(`CelesteWasm on .NET 10.0.3-dev`, `CELESTE : 1.4.0.0`, `FNA3D Driver: OpenGL`), loaded
(`BEGIN LOAD - GFX LOAD: 19230ms`), reached Celeste's save-file select screen, and the player
confirmed it works: arrow keys, Z, X and C all function; a save file was created and loaded; audio
works. The patch step costs about 25 seconds on first open and is skipped on every later open.

PLAN.md M2's exit criterion is met, and M1's with it. The whole chain works unattended after the
one-time setup: stored path -> intake -> local server -> bootstrap OPFS copy -> MonoMod patch ->
Celeste. No command, no file dialog, no re-patch.

What the final screenshots also settle about M3: the game renders correctly through the screen at
full size, and the remaining known gaps are (a) the ~25s patch on first open, (b) frame rate, which
is the paint-rate cap's business and is already in place but untunable, and (c) the framed window
with its 16:9 game area, which the port's own UI (top bar, Mods/Achievements/Files/Settings
buttons, fullscreen) still argues for - the "Game not running" area and the port's chrome currently
span the whole screen, and M3's viewport sizing is what puts the game itself in a 16:9 box.

## M2's exit criterion is met (2026-10-14 night)

The run reached the game. After the open key, the bootstrap, the port's Splash, the Patch step and
`play()`, the screen showed Celeste's own title screen - the CLIMB logo, Options, Credits, Exit, and
the port's "Confirm C / Back X" input prompt. That is PLAN.md M2's exit criterion ("the port's
frontend boots to its Play button with the player's assets present in OPFS, verified by a
user-driven runClient") met, and M1's exit with it: a screen rendering a URL through Rinku in the
real client, carrying a full game.

The MP3 of it works end to end: Stored path -> intake -> local server -> bootstrap OPFS copy ->
port patch -> game. Nothing in that chain needs a command or a file dialog any more.

## Gamepad input does not work — root cause found (resolved, 2026-10-16)

Reported 2026-10-15 from a playable session: with a controller plugged in, Celeste does not respond
to it at all, while the keyboard (arrows, Z, X, C) works. So the failure is isolated to gamepad
input and not to input forwarding in general — the mapping from Minecraft's screen into the browser
is proven by the keyboard working.

### Root cause (confirmed at runtime via F8 gamepad inspection)

F8 gamepad inspection (`/craftleste inspect`) reports:

```
[navigator][gamepad] getGamepads=true | pads=1 [0:8BitDo Ultimate 2C Wired Controller (Vendor: 2dc8 Product: 301d)|buttons=15|axes=10|mapping=]
```

The controller is detected with correct button count (15) and axes (10), but `mapping=""` (empty).

The port's emscripten SDL2 runtime (`dotnet.native.sadgvw1639.js`) copies `e.mapping` from the
Gamepad object via `stringToUTF8(e.mapping, eventStruct + 1368, 64)` in `fillGamepadEventData`.
However, SDL2's `SDL_GameControllerOpen()` does NOT use this `mapping` field for its database lookup.
Instead, it generates a GUID from the vendor/product IDs (`03000000c82d00001d30000000000000`)
and looks it up in the SDL2 controller database compiled into the wasm binary.

Searching the wasm binary (`dotnet.native.vwx8rc7ccu.wasm3`) confirms:
- The 8BitDo GUID `03000000c82d00001d30000000000000` is NOT present in the compiled-in database
- The controller name `8BitDo Ultimate 2C` is NOT present
- The `gamecontrollerdb.txt` file (SDL 2.0.6 format, 419 lines) exists at `/libsdl/` but has
  zero 8BitDo entries and is never loaded (no call to `SDL_GameControllerAddMappingsFromFile`)
- The upstream database at `mdqinc/SDL_GameControllerDB` has the mapping, but the port's
  emscripten SDL2 build predates it

The first attempt — patching `navigator.getGamepads` to return `mapping: "standard"` — was
**incorrect** because SDL2's emscripten backend uses GUID-based database lookup, not the Gamepad
API `mapping` property, for the GameController API.

### Fix applied

The SDL2 `SDL_GAMECONTROLLERCONFIG` environment variable is the correct mechanism: SDL2 checks it
during `SDL_Init(SDL_INIT_GAMECONTROLLER)` and loads any mappings it contains. The 8BitDo mapping
(`03000000c82d00001d30000000000000,8BitDo Ultimate 2C,a:b0,...`) is injected into the emscripten
runtime's `ENV` object by replacing `var ENV = {};` with
`var ENV = {"SDL_GAMECONTROLLERCONFIG":"<mapping>"};` when the server serves
`_framework/dotnet.native.*.js`.

This runs before any C code executes, so `getEnvStrings()` includes the env var in its cached
output, and SDL2 picks it up at init time.

A secondary `navigator.getGamepads` wrapper (injecting `mapping: "standard"`) is also applied via
the port's `index.html` for robustness, in case any code path does read the Gamepad API mapping.

### Files changed

- `BootstrapPages.java`: `SDL_GAMECONTROLLER_CONFIG` constant with the 8BitDo mapping string;
  `GAMEPAD_PATCH_JS` with the `navigator.getGamepads` wrapper
- `CraftlesteHttpServer.java`: `injectEnvVar()` method replaces `var ENV = {};` in the served
  emscripten runtime JS with a pre-populated `ENV` containing `SDL_GAMECONTROLLERCONFIG`;
  `injectGamepadPatch()` injects `GAMEPAD_PATCH_JS` into `index.html`'s `<head>`

## The inspector's silent syntax error

F8 kept reporting `(no result title within 10s)` while the browser logged
`Uncaught SyntaxError: Unexpected token 'const' (http://localhost:18081/index.html:1)` once per
retry. The cause was the probe's own wrapper: the expression is a statement block
(`const out = []; ... return ...`), and it was interpolated into `await ( <expression> )`, so the
browser was asked to parse `await (const out = []; ...)`. Wrapping it in its own async function
body - `await (async () => { <expression> })()` - fixes it. The lesson is that a diagnostic channel
which cannot fail loudly costs two debugging rounds, which is why it now logs its heartbeat and
reports exceptions.

The three probes worth taking (PLAN.md M1's Probe 1 and M2's Probe 2; RESEARCH.md unknowns 1-3):
`/craftleste probe` reports whether the embedded Chromium can open a directory picker and read
gamepads, and `/craftleste threads` reports cross-origin isolation and a 16-worker
SharedArrayBuffer barrier. They answer the intake and input questions for M3/M4, and they are pages
the player opens themselves - the agent cannot see a browser.

## The browser outlives the screen (M3 lifecycle, started 2026-10-15)

The report from the first playable session was that the game takes a long time to patch and play,
and that every close forces a re-patch. Both halves of that are fixed, and the second one was a bug
in the first one.

The re-patch loop was mine: `patchIsCurrent` compared the patch against a *recorded* copy time, and
that record was only written on the full-copy path. A run that took the skip path - which is what a
repeat open takes - never wrote one, and a missing record read as "stale", so the patch was removed
and redone on every single open, forever. The comparison now uses the input file's own timestamp
(`CustomCeleste.dll` newer than the `Celeste.exe` it was patched from), which needs no bookkeeping
on our side, is correct on the first run, and survives `localStorage` being cleared.

The keep-alive is craftlatro's pattern, read from its source rather than re-derived (the user pointed
out the repo is local): `BalatroBrowser` owns a single `RinkuBrowser` that outlives
`BrowserScreen`. Closing the screen calls `park()`, not `close()` - audio muted, paint rate cut to
5fps, surface shrunk to 320x180 - and reopening calls `showViewport()` on the same live browser, so
the page and everything it loaded survive. `release()` is the genuine close, for world unload and
client shutdown. craftleste now has `CraftlesteBrowser` with the same shape, plus the paint-rate cap
against Minecraft's observed frame rate (Chromium painting faster than Minecraft composites is
waste; Rinku keeps only the newest frame).

One consequence worth naming: a parked page still ticks, slowly, so a game left open in the
background does advance. The port exposes no pause, so that trade is accepted for now - it is the
same one craftlatro makes.

## The page that never ran (2026-10-15 night)

The first report after parking landed: the screen opened but sat on the bootstrap page showing its
opening text, the picture was blurry, and F8 returned a full OPFS dump while still reporting
`href=.../bootstrap.html`. The inspector working while the page itself was inert was the clue: the
page's own script had never run.

It had a syntax error. My edit that added the patch-currency check left a stray `}` after the
function, so the whole `<script>` failed to parse - the static HTML rendered, `run()` was never
called, and the page sat at "checking storage..." forever. The F8 probe still worked because
`executeJavaScript` evaluates a separate script that does not care about the page's own parse
state, which is exactly why the failure looked like a hang rather than a syntax error.

The blur had the same root plus a second one. `park()` shrinks the browser to 320x180, and
Rinku's renderer only reallocates its texture when a paint arrives *at the new size* - so after a
resize the renderer still held the small parked surface. The blit declared the viewport size as the
texture size, so that leftover small surface was upscaled across the screen: blur. Two fixes: the
blit now declares the renderer's real texture size (`RinkuBrowser.getRenderer().getTextureWidth()`),
and unparking nudges a repaint (toggling a transform on the document) so a paint at the new size
actually arrives instead of waiting for a page that may have nothing to redraw.

The lesson worth keeping is about the guard, not the bug: a syntax error inside a Java string is
invisible to review, and this is now the second time one has cost a debugging round. The page
scripts are therefore validated by a test that tokenizes them the way a JavaScript parser would
(tracking strings, template literals and comments so bracket counting is not fooled) and requires
balance. The guard was proven by re-injecting the stray brace and watching the test fail.

## Park must not resize the browser (2026-10-15, after the parked reopen)

The parked reopen produced a page that was "massive and way too zoomed in". Both halves of the
parked state were at fault, and craftlatro's shape had hidden it:

- `park()` shrank the browser to 320x180, which made the port's page re-lay-out at that size. Two
  things in this pipeline are pinned to the browser's size at creation and do not follow a later
  resize: the port's game canvas takes its backing-store size when the game starts
  (`.game { aspect-ratio: 16/9; flex: 0 1 min(calc(9*100vw/16), 100vh) }` is pure `vw`/`vh` CSS, but
  the canvas element's `width`/`height` attributes are set once in C#), and Rinku's renderer
  reallocates its texture only when a paint at the *new* size arrives - which a page with nothing to
  redraw never sends.
- `showViewport()` was called on every rendered frame and resized unconditionally, so the browser was
  told to re-layout sixty times a second even when the size had not moved.

The fix is that parking only mutes and slows the paint rate: the cost that mattered, frames spent on
an unseen page, is the frame rate's job. A full-size texture is 8 MB of VRAM, which is not worth
corrupting the layout over. `showViewport()` now resizes only on a real size change, and
`appliedWidth/Height` are reset on release so the next session re-applies.

Two symptoms, one cause: the earlier "blurry after reopening" was the same 320x180 texture being
stretched, before the blit declared the renderer's real texture size. Both are gone with the resize,
because the size is never wrong now.

## The stale Screen size (2026-10-15 evening)

The zoomed-in page with no buttons was not the park path at all. The browser was being sized from
`Screen.width`/`height`, and those fields only refresh when the screen is rebuilt - craftlatro's
`BrowserScreen` documents the same trap from its own history ("the Balatro surface used to stay
stuck at whatever size it started at") and reads `minecraft.getWindow().getGuiScaledWidth()`
instead. So the surface was created at whatever size the window had when the screen was first built,
laid out its page there, and stayed small while the blit stretched it across the whole screen.

Both symptoms from this session were the browser being the wrong size for what was drawing it: the
zoom was a wrong view rect, and the earlier blur was a stale small texture. The viewport and the
blit destination now both read the window's own logical size, which is also what makes a window
resize or GUI-scale change land live.

The workaround for the parked size is gone too, for the same reason: parking no longer shrinks the
browser, so nothing is left to go stale.

## The viewport is right; the canvas waits for the game (2026-10-15 late)

The layout probe (F8) reported from the live page:

```
inner=1920x1058 | docClient=1920x1058 | dpr=1 | scroll=1920x1058 | buttons=72
canvas backing=300x150 css=1737x977 at 92,81 | .game css=1737x977 at 92,81 | bodyChildren=2
```

So the browser is full-size, the port's `.game` block is 1737x977 at the correct offset (a 16:9
area centred in 1920x1058), and all 72 of the port's buttons are in the DOM. The viewport is fixed.

The one number that still looks wrong, `canvas backing=300x150`, is the HTML default for a canvas
with no `width`/`height` attributes - and the port sets none anywhere in its sources (checked
dotnet.ts, main.tsx, game/index.tsx, game/loading.tsx, loader/*.cs). The canvas is sized when the
game starts, by the runtime's first GL viewport call, so a freshly-opened page that has not been
played yet legitimately shows a 300x150 buffer stretched over the 16:9 area. The dark screen the
player sees is the port's idle "Game not running" state, not a rendering fault.

That distinguishes the last two symptoms cleanly: the zoom was a wrong view rect (fixed), and what
remains is only the game not being started yet.

## The blit overload that crops (2026-10-15, final fix of the zoom)

The zoomed page was never a layout problem. The page was laying itself out correctly the whole time
(`inner=1920x1058`, `.game css=1737x977 at 92,81`, 72 buttons) - what was on screen was a *crop* of
it, so the topbar filled the window and the buttons sat off the edge.

`GuiGraphicsExtractor.blit`'s ten-argument overload hardcodes the source rectangle to the
destination size:

```java
this.blit(pipeline, texture, x, y, u, v, width, height, width, height, textureWidth, textureHeight);
```

and the UV range the full overload builds is `(u + srcWidth) / textureWidth`. The chromium surface is
deliberately framebuffer-sized (`screenWidth * guiScale`) while the GUI quad is gui-scaled, so those
two numbers differ by exactly the GUI scale factor. With a 1920x1058 surface drawn into a 960x529
quad, `srcWidth` was 960, `textureWidth` was 1920, and the sampled range was `0 to 0.5` in each
axis - the top-left quarter of the page, stretched over the whole screen. It looked like an extreme
zoom-in because it is one.

The fix is to pass the source rectangle explicitly, using the eleven-argument overload with
`srcWidth = srcHeight = the renderer's texture size`, so the whole texture maps to the whole viewport
and the GPU does the downscale.

The tell was that the page's own measurements and the picture disagreed, and the picture was not a
zoom of the measurements - it was a *subset* of them. When a rendering pipeline has both a source
rectangle and a texture size, a crop and a zoom look the same from the outside, and only the numbers
tell them apart.

## Verification

- Rung 1: `./gradlew build` - green.
- Rung 2: three standalone probes (`src/test/java`) - 25 tests, green: extraction round-trip built
  with the same libraries the downloader reads with, server end-to-end over real HTTP (headers,
  two-root split, wasm MIME, plain 404 chunks, traversal refusal, mod-owned endpoints), and the
  intake copy/validation against synthetic installs.
- Rung 4 (first user run, 2026-10-14): `fetch` downloaded the exact 42,845,972-byte asset, extracted
  247 entries, published the runtime and recorded the pin; `/craftleste load` copied 7 install files
  and 1211 Content files; the local server started on 18081 serving both roots; the screen rendered
  the bootstrap page through Rinku, which also closes M1's exit (a screen rendering any URL in the
  real client). The second run closed the pipeTo bug and reached the real blocker: a 404 on
  `Celeste.exe.config` from the name-list root split. Not yet reached: the port's own page booting,
  and the Probe 1/2 report lines.
  Bugs surfaced and fixed: the bootstrap's `writable.close()` after `pipeTo`, the greedy path
  argument capturing chat quotes, and the root split itself. See lessons below.

## Lessons from the first real run

- **`pipeTo` closes the writable; never close again.** `response.body.pipeTo(writable)` finishes by
  closing the OPFS writable, which is what commits the file. An extra `await writable.close()`
  throws `TypeError: Cannot close a CLOSED writable stream` and aborted the whole copy on the first
  file. The port's own copy helpers pipe without closing for exactly this reason; the pattern is
  now copied in the bootstrap's writeFile.
- **Chat command arguments arrive quoted.** A pasted `"E:\SteamLibrary\...\Celeste.exe"` reaches the
  greedy string argument with the quotes included, and `Path.of` rejects an illegal leading `"`.
  `CelesteIntake.parseUserPath` strips them; both quoted and unquoted paths work now.
- **Serve the player's folder by precedence, not by name.** The first server split the roots by a
  name list (`Celeste.exe`, `.ContentExists`, `Content/*`), but the intake copies every sibling of
  the exe - the second run died on `Celeste.exe.config` with a 404 because that name was on neither
  list. The resolver now tries the player's folder first and the runtime build as the fallback,
  which is also the safer rule: the port's dist contains none of the install-root names, so nothing
  is shadowed, and both candidates are still checked against their own root.
- **The bootstrap screen captures the keyboard.** While it is open, chat (and therefore the
  `/craftleste` commands) cannot be typed; Escape out of the screen first. The page inspector's F8
  key works inside the screen for exactly this reason.
- **A sequential copy is the bottleneck.** 1218 files one at a time ran about 4 MB/s. The copy now
  runs eight lanes over the same files, and the server streams each response instead of buffering the
  whole file per request. The eight-lane figure was chosen because the server's thread pool is eight.
- **`/craftleste load` remembers the path.** A greedy argument keeps the quotes a pasted path arrives
  with (fixed earlier), and the stored path is reused by a bare `/craftleste load` until it stops
  resolving; every command now says what it did, so a silent no-op cannot masquerade as a failure.
- **Open brings the mod up to date by itself.** The keybind and `/craftleste open` fetch the runtime
  when it is missing and reload the player's files from the remembered path when those are missing,
  then open the screen - the same "cache the good stuff" shape the sibling mod uses. Only the very
  first run, with no remembered path, asks for anything. The auto-fetch is a default, not a policy:
  M4's settings screen is where it becomes a toggle.
- **CEF needs a command-line switch for SharedArrayBuffer.** The gate is not the headers - both are
  on every response - but CEF grants no concrete agent cluster by default, and dotnet's threaded
  wasm refuses to boot without the buffer. See the section above; the switch is registered at mod
  load, before Rinku's startup.
