# M1: Scaffolding and dependency integration landed

## Problem

craftleste had no build system, source files, or dependencies: the repo was skeleton-only
(README/AGENTS/ROADMAP/LICENSE plus empty dirs). M1 also carries two probes: dependency
resolution and the Probe 1 capability report.

## Solution

Gradle build seeded from craftlatro's proven shape (same versions, same dependency pins), with
craftleste's own IDs:

- `settings.gradle`, `build.gradle`, `gradle.properties` — Loom `1.18-SNAPSHOT`, MC `26.2`,
  loader `0.19.5`, fabric-api `0.161.0+26.2`, Java 25 toolchain, `splitEnvironmentSourceSets()`,
  Rinku `3.0.4-26.2`, Cloth Config `26.2.155`, Mod Menu `20.0.3` compile-only + localRuntime,
  `version=0.1.0+26.2` (first release per AGENTS.md).
- `gradle/wrapper/` + `gradlew`/`gradlew.bat` copied from craftlatro (Gradle 9.7.1). The wrapper
  jar is committed on purpose (CI needs it); this was the state of the old `.gitignore`, which
  ignored `gradle/wrapper/gradle-wrapper.jar` — fixed, and `run/` + `.kilo/` added. `.gitattributes`
  (`* text=auto`, `*.bat text eol=crlf`) mirrors craftlatro so CI's Linux checkout stays sane.
- `fabric.mod.json`: id `craftleste`, GPL-3.0, `environment: client`, entrypoints main
  (`com.craftleste.CraftlesteMod`) and client (`com.craftleste.client.CraftlesteClient`). No
  `contact` block (no remote repo exists yet; creating one is Ask First) and no icon (craftleste
  has none; nothing borrowed from craftlatro's). The Mod Menu entrypoint is deliberately absent
  until M4's Cloth settings screen exists — an entrypoint without a screen factory would crash
  Mod Menu.
- `client.browser.BrowserScreen`: full-viewport Rinku screen, the M1 exit ("a screen that renders
  any URL"). Creation goes through `Rinku.isInitialized()` / `scheduleForInit` because Rinku
  initializes on the first GUI render and `createBrowser` asserts initialized; the M3 framed
  window, parking and paint-rate cap are NOT here (M3 scope).
- `client.probe.ProbePages`: Probe 1 as a percent-encoded `data:text/html` URL, so it renders
  with no local server (M2's job). It reports, with buttons and on the console:
  `showDirectoryPicker` presence and a live pick attempt, `navigator.storage.getDirectory`
  presence and a full OPFS write/read-back/remove round-trip, `navigator.getGamepads` presence
  and connected pads, plus `isSecureContext` and `crossOriginIsolated` as context.
- `CraftlesteClient`: one keybind (default C, own controls category) opening the probe screen,
  consuming the click once per press.
- `docs/rinku-api-notes.md`: seeded from craftlatro's source-verified notes per PLAN.md M1.

## Why it matters

- The data URL choice is itself a finding waiting to happen: if CEF refuses top-level data:
  navigation, the dev run shows a blank page and the fallback is already the M2 local server
  (recording the decision, not the outcome — the run has not happened yet).
- The mod's `main` source set stays empty of client code: everything browser-side lives in the
  split `client` source set, exactly like craftlatro, so the two projects stay reviewable against
  each other (ROADMAP goal 3).

## Verification

- Rung 1 (`./gradlew build`) — green (2026-10-14, after fixing a `RenderPipelines` import path and a
  static-field forward reference).
- M1 exit needs rung 4 (user-driven `./gradlew runClient`): open the screen (default C), press the
  three probe buttons, and paste back the report lines. The agent cannot open a browser screen.

## Commit note

`gradlew` is a copied shell script, and Windows checkouts do not carry the executable bit, so the
first commit of this scaffold must record it explicitly:

```
git update-index --chmod=+x gradlew
```

CI is Linux (AGENTS.md Gotchas) and the wrapper jar is committed, so this is the one bit a clone
gets wrong without it.
