# Rinku API Notes

Seeded from craftlatro's `docs/rinku-api-notes.md`, which was source-verified from
`E:\Coding_Projects\MCSourceCode\Rinku` (branch `26.2.0`, commit-pinned) and is version-matched to
the artifact this project pins (3.0.4-26.2). PLAN.md M1 says to seed rather than re-derive; this
file records what that means for craftleste and where the two projects' usage diverges.

## Dependency

```
Maven: https://keksuccino.github.io/maven/
Coordinate: de.keksuccino:rinku-fabric:3.0.4-26.2
```

Note: The source branch `26.2.0` reports `Rinku.VERSION = "3.0.5"` internally. The published
Maven artifact is `3.0.4-26.2`. The version difference is minor and does not affect the API
surface documented below.

## Initialization Lifecycle

`MixinGui` triggers `Rinku.initialize()` on first client tick (confirmed in source: `Rinku.initialize()`
is annotated "This gets called by MixinGui. This should not be called by anything else.")

| Method | Signature | Notes |
|--------|-----------|-------|
| `initialize` | `boolean initialize()` | Called by MixinGui on first render tick. Returns success. |
| `isInitialized` | `boolean isInitialized()` | Check if CEF is initialized via `RinkuInitializationController`. |
| `isInitializationAllowed` | `boolean isInitializationAllowed()` | Check if a new init attempt may be scheduled. |
| `assertInitialized` | `void assertInitialized()` | Throws RuntimeException if not initialized. |
| `scheduleForInit` | `void scheduleForInit(RinkuInitListener task)` | Queue a task to run when init completes. `RinkuInitListener.onInit(boolean successful)` callback. |

**Pattern**: When opening a BrowserScreen, check `Rinku.isInitialized()` first. If false and
`Rinku.isInitializationAllowed()`, use `scheduleForInit()` with a listener that creates the
browser on the render thread via `minecraft.execute()`. `craftleste.client.browser.BrowserScreen`
implements this pattern.

## Rinku (de.keksuccino.rinku.Rinku)

Static facade for browser creation and lifecycle.

| Method | Signature | Notes |
|--------|-----------|-------|
| `createBrowser` | `RinkuBrowser createBrowser(String url, boolean transparent)` | Asserts initialized. Delegates to render thread if called from non-render thread. Acquires from preloaded pool or creates new. |
| `createBrowser` | `RinkuBrowser createBrowser(String url, boolean transparent, int width, int height)` | Same as above, also resizes immediately. |
| `getClient` | `RinkuClient getClient()` | Access the `CefClient` wrapper for event handler registration. |
| `getSettings` | `RinkuSettings getSettings()` | Access persisted settings (loads from disk). |
| `shutdown` | `void shutdown()` | Permanently shuts down CEF for this process. |

## RinkuBrowser (de.keksuccino.rinku.RinkuBrowser)

Extends `CefBrowserOsr`. Off-screen rendered Chromium browser.

| Method | Signature | Notes |
|--------|-----------|-------|
| `getTextureIdentifier` | `Identifier getTextureIdentifier()` | Returns GL texture Identifier for rendering with `GuiGraphicsExtractor.blit()`. Returns null if renderer not ready. Delegates to `renderer.isTextureReady()`. |
| `isTextureReady` | `boolean isTextureReady()` | Check if the browser's texture is initialized and ready to render. |
| `isValid` | `boolean isValid()` | Inherited from CefBrowser. Native browser readiness gate (more authoritative than texture readiness for `loadURL()`). |
| `resize` | `void resize(int width, int height)` | Resize browser surface (in browser pixel space). |
| `loadURL` | `void loadURL(String url)` | Navigate to a URL (inherited from CefBrowser). |
| `reload` | `void reload()` | Reload current page (inherited). |
| `goBack` | `void goBack()` | Navigate back (inherited). |
| `goForward` | `void goForward()` | Navigate forward (inherited). |
| `canGoBack` | `boolean canGoBack()` | Can navigate back (inherited). |
| `canGoForward` | `boolean canGoForward()` | Can navigate forward (inherited). |
| `getURL` | `String getURL()` | Current URL (inherited). |
| `isLoading` | `boolean isLoading()` | Current loading state (inherited). |
| `setFocus` | `void setFocus(boolean focus)` | Set keyboard focus (inherited). |
| `close` | `void close()` | Close browser and free resources. Also `close(boolean force)`. |
| `useBrowserControls` | `RinkuBrowser useBrowserControls(boolean)` | Enable built-in browser shortcuts. Returns `this`. |

**Coordinate note**: Mouse coordinates passed to `sendMousePress`, `sendMouseMove`, etc. are in
browser pixel space. Must be scaled by `minecraft.getWindow().getGuiScale()` and offset by the
browser's screen position.

## RinkuClient (de.keksuccino.rinku.RinkuClient)

Wrapper around `org.cef.CefClient`.

| Method | Signature | Notes |
|--------|-----------|-------|
| `getHandle` | `CefClient getHandle()` | Raw CEF client. |
| `addLoadHandler` | `void addLoadHandler(CefLoadHandler)` | Register a load handler. |
| `addDisplayHandler` | `void addDisplayHandler(CefDisplayHandler)` | Register a display handler. |
| `removeDisplayHandler` | `void removeDisplayHandler(CefDisplayHandler)` | Remove a display handler. |
| `addContextMenuHandler` | `void addContextMenuHandler(CefContextMenuHandler)` | Register context menu handler. |
| `addAudioHandler` | `void addAudioHandler(CefAudioHandler)` | Register an audio handler. |
| `addDownloadHandler` | `void addDownloadHandler(CefDownloadHandler)` | Register a download handler. |

## Flat GUI Screen Pattern (ExampleScreen reference)

From `de.keksuccino.rinku.example.ExampleScreen`:

1. **Browser creation in `init()`**:
   ```java
   browser = Rinku.createBrowser(DEFAULT_URL, transparent);
   ```

2. **Rendering in `extractRenderState(GuiGraphicsExtractor, int mouseX, int mouseY, float partial)`**:
   ```java
   if (browser != null && browser.isTextureReady()) {
       Identifier texture = browser.getTextureIdentifier();
       if (texture != null) {
           guiGraphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, w, h);
       }
   }
   ```

3. **Mouse input mapping** (note Minecraft swaps middle/right buttons vs CEF):
   - `browser.sendMousePress(mouseX, mouseY, button)`
   - `browser.sendMouseRelease(mouseX, mouseY, button)`
   - `browser.sendMouseMove(mouseX, mouseY)`
   - `browser.sendMouseWheel(mouseX, mouseY, scrollY, 0)` — scroll amount is `scrollY` (double)

4. **Keyboard input mapping**:
   - `browser.sendKeyPress(key, scancode, modifiers)`
   - `browser.sendKeyRelease(key, scancode, modifiers)`
   - `browser.sendKeyTyped(codepoint, modifiers)`

5. **Cleanup in `onClose()`**:
   ```java
   if (Rinku.isInitialized()) {
       browser.close();
   }
   ```

## Input Event Signatures (MC 26.2)

From vanilla Minecraft 26.2 source and Rinku ExampleScreen:

```java
// Screen.java
public boolean keyPressed(KeyEvent event)
public boolean keyReleased(KeyEvent event)
public boolean charTyped(CharacterEvent event)

// ContainerEventHandler.java (default implementations)
public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
public boolean mouseReleased(MouseButtonEvent event)
public boolean mouseDragged(MouseButtonEvent event, double dx, double dy)
public boolean mouseScrolled(double x, double y, double scrollX, double scrollY)
default void mouseMoved(double mouseX, double mouseY)
```

**MC 26.2 API changes**:
- `LocalPlayer.displayClientMessage(Component, boolean)` → renamed to `sendSystemMessage(Component)`
  (boolean parameter for action bar removed; use `sendOverlayMessage` for action bar).
- `Screen.resize(int width, int height)` still exists.

## M1 Checklist

- [x] Fabric 26.2 mod project scaffolded with Loom 1.18
- [x] Rinku dependency resolves: `de.keksuccino:rinku-fabric:3.0.4-26.2` from `https://keksuccino.github.io/maven/`
- [x] Rinku public API confirmed from source — flat GUI browser screen driven programmatically:
  - `Rinku.createBrowser(url, false)` + `RinkuBrowser` render/input methods
  - `Rinku.scheduleForInit()` for deferred init when Rinku isn't ready yet
- [x] ExampleScreen reference inspected for input mapping patterns
- [x] MC 26.2 API changes recorded (sendSystemMessage, event signatures)
- [x] Probe 1 page bundled as a data URL (`client.probe.ProbePages`), pending the user-driven run

## Dev Run Notes

Rinku initialization is triggered by `MixinGui` when the game first renders. In a development
environment, Rinku downloads the JCEF runtime (~150 MB Chromium bundle) on first launch.
Cache locations:
- JCEF runtime: `<game instance>/rinku-libraries/jcef-v1/<platform>/<commit>/`
- Config: `<game instance>/config/rinku/rinku.properties`
- Browser cache: `%LOCALAPPDATA%\Rinku\cef-cache` (Windows)

The dev run directory is `runClient/` relative to the project root (Fabric Loom default).
