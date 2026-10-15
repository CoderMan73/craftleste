package com.craftleste.client.browser;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefPaintEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Owns the single Chromium browser the game is shown in.
 *
 * <p>The browser deliberately outlives {@link BrowserScreen}. Closing the screen only detaches it:
 * the page keeps running, so reopening the game is instant instead of paying for the port's whole
 * boot again - the dotnet runtime's startup, the OPFS mount, the MonoMod patch of Celeste, and the
 * game's own load. That patch alone takes a minute, and re-running it every time the player closes
 * the window is the difference between a game and a slideshow.
 *
 * <p>While no screen is showing, the surface is parked: audio is muted, the paint rate drops to a
 * trickle and the surface shrinks, because Rinku pumps pending paint frames for every live browser
 * on the Minecraft render thread, and an unshown full-rate surface costs frames and makes noise
 * while playing. Note that a parked page still ticks - slowly - so a game left open in the
 * background does advance; a real pause is a later concern (the port exposes no pause).
 *
 * <p>Release (a genuine close) is for world unload and client shutdown only, where keeping a page
 * alive would leak it for the rest of the session.
 */
public final class CraftlesteBrowser {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final int PARKED_FRAME_RATE = 5;

	/** Chromium is allowed to paint at least this fast even when Minecraft reports fewer frames. */
	private static final int MIN_RUNTIME_FRAME_RATE = 60;
	/** The paint rate the browser gets while nothing has told it otherwise. */
	private static final int DEFAULT_FRAME_RATE = 60;
	private static final int MINECRAFT_FPS_SAMPLES = 3;
	private static final long FRAME_RATE_SYNC_INTERVAL_MILLIS = 1000L;

	private static final AtomicInteger paintsInWindow = new AtomicInteger();
	private static final int[] minecraftFpsSamples = new int[MINECRAFT_FPS_SAMPLES];

	private static int minecraftFpsSampleCount;
	private static int minecraftFpsCursor;
	private static long lastFrameRateSyncMillis;
	private static long windowStartMillis;
	private static int lastPaintRate;

	private static final Consumer<CefPaintEvent> PAINT_COUNTER = event -> {
		if (event == null || event.getPopup()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
		}
		paintsInWindow.incrementAndGet();
	};

	private static RinkuBrowser browser;
	private static String activeUrl;
	private static boolean parked;
	private static int appliedFrameRate;
	private static int appliedWidth;
	private static int appliedHeight;
	private static boolean audioMuted;

	private CraftlesteBrowser() {
	}

	/**
	 * Returns the live browser for {@code url}, creating it when there is none.
	 *
	 * <p>The two-argument {@link Rinku#createBrowser} overload is used on purpose: the sizing
	 * overload resizes before the caller can attach anything, and craftlatro hit a native crash in
	 * Rinku's async paint path doing that. The caller sizes the browser through
	 * {@link #showViewport} on the same frame instead, which is what pins the page's resolution
	 * before it creates its canvas.
	 *
	 * @return the browser, or {@code null} when Chromium is not ready yet
	 */
	public static synchronized RinkuBrowser acquire(String url) {
		if (browser != null && !browser.isValid()) {
			releaseBrowser();
		}
		if (browser == null) {
			if (!Rinku.isInitialized()) {
				return null;
			}
			LOGGER.info("Creating the craftleste browser for {}", url);
			RinkuBrowser created = Rinku.createBrowser(url, false);
			created.useBrowserControls(false);
			browser = created;
			activeUrl = url;
			parked = false;
			appliedFrameRate = 0;
			audioMuted = false;
			paintsInWindow.set(0);
			windowStartMillis = 0;
			addPaintCounter(browser);
			return browser;
		}
		if (!url.equals(activeUrl)) {
			LOGGER.info("craftleste browser target changed; loading {}", url);
			browser.loadURL(url);
			activeUrl = url;
		}
		return browser;
	}

	/**
	 * Applies the on-screen resolution, the audio state and the effective paint rate.
	 *
	 * <p>The browser is only resized when the size actually changes. Resizing is not free - it
	 * forces a full re-layout and repaint of the page - and this is called on every rendered frame
	 * by the screen, so telling Chromium to re-layout sixty times a second for a size that has not
	 * moved is the kind of thing that makes a page look wrong.
	 */
	public static synchronized void showViewport(int width, int height) {
		if (width <= 0 || height <= 0) {
			return;
		}
		parked = false;
		if (browser == null || !browser.isValid()) {
			return;
		}
		applyFrameRateNow();
		if (audioMuted) {
			browser.setAudioMuted(false);
			audioMuted = false;
		}
		if (width == appliedWidth && height == appliedHeight) {
			return;
		}
		appliedWidth = width;
		appliedHeight = height;
		browser.resize(width, height);
		nudgeRepaint();
	}

	/**
	 * The size the renderer's texture actually holds, which is what a blit must declare.
	 *
	 * <p>These are the dimensions of the last paint, not of the viewport: after a resize the
	 * renderer keeps the old texture until a paint at the new size arrives, and declaring the
	 * viewport size for a smaller texture stretches it - which is exactly the "why is it blurry
	 * after reopening" symptom.
	 */
	public static synchronized int textureWidth() {
		return browser != null && browser.getRenderer() != null ? browser.getRenderer().getTextureWidth() : 0;
	}

	public static synchronized int textureHeight() {
		return browser != null && browser.getRenderer() != null ? browser.getRenderer().getTextureHeight() : 0;
	}

	/**
	 * Provokes one paint at the current view size.
	 *
	 * <p>A resize does not reliably make Chromium repaint a page that has nothing to redraw, and
	 * until it does the renderer still holds the parked 320x180 surface. Toggling a transform on
	 * the document dirties it, which costs one frame and guarantees the paint that reallocates the
	 * texture at the real size.
	 */
	private static void nudgeRepaint() {
		try {
			browser.executeJavaScript(
					"try{document.body.style.transform='translateZ(0)';"
							+ "requestAnimationFrame(function(){document.body.style.transform='';});}catch(e){}",
					"craftleste", 1);
		} catch (RuntimeException failure) {
			// A page that is still navigating has no document to nudge; the next paint sorts it out.
			LOGGER.debug("Could not nudge a repaint after resizing.", failure);
		}
	}

	/**
	 * Keeps the page session alive but silent and cheap while no screen is rendering it.
	 *
	 * <p>The surface is deliberately <em>not</em> shrunk. Two things in this pipeline are pinned to
	 * the browser's size at the moment they are created and do not follow a later resize: the
	 * port's game canvas takes its backing-store size when the game starts, and Rinku's renderer
	 * reallocates its texture only when a paint at the new size arrives - which a page that has
	 * nothing to redraw never sends. Shrinking to 320x180 therefore left both of them stuck small,
	 * and the next open stretched a 320x180 render across the whole screen: everything huge and
	 * pixelated. The cost that mattered, frames spent on an unseen page, is the paint rate's job,
	 * and it is cut to 5. A full-size texture is 8 MB of VRAM, which is not a cost worth that.
	 */
	public static synchronized void park() {
		if (browser == null || !browser.isValid() || parked) {
			return;
		}
		parked = true;
		try {
			browser.setAudioMuted(true);
			audioMuted = true;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not mute the game browser while parked.", failure);
		}
		try {
			browser.setWindowlessFrameRate(PARKED_FRAME_RATE);
			appliedFrameRate = PARKED_FRAME_RATE;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not slow the parked game browser.", failure);
		}
		LOGGER.info("Game browser parked at {}x{}; the page keeps running", appliedWidth, appliedHeight);
	}

	/**
	 * Re-checks the paint rate once a second against Minecraft's observed frame rate.
	 *
	 * <p>Chromium painting faster than Minecraft composites is waste: Rinku keeps only the newest
	 * frame, so anything painted between two Minecraft frames is discarded without ever being shown.
	 * Capping the rate to what Minecraft displays eases the CPU the browser takes from it. The cap
	 * uses the highest of the last few readings so a single hitch cannot stall the game inside.
	 */
	public static synchronized void observeFrameRate(int minecraftFps) {
		if (minecraftFps <= 0) {
			return;
		}
		minecraftFpsSamples[minecraftFpsCursor] = minecraftFps;
		minecraftFpsCursor = (minecraftFpsCursor + 1) % MINECRAFT_FPS_SAMPLES;
		minecraftFpsSampleCount = Math.min(minecraftFpsSampleCount + 1, MINECRAFT_FPS_SAMPLES);

		long now = System.currentTimeMillis();
		if (lastFrameRateSyncMillis != 0 && now - lastFrameRateSyncMillis < FRAME_RATE_SYNC_INTERVAL_MILLIS) {
			return;
		}
		lastFrameRateSyncMillis = now;
		applyFrameRateNow();
	}

	private static int effectiveFrameRate() {
		int minecraftCeiling = highestRecentMinecraftFps();
		if (minecraftCeiling <= 0) {
			return DEFAULT_FRAME_RATE;
		}
		return Math.max(MIN_RUNTIME_FRAME_RATE, Math.min(DEFAULT_FRAME_RATE, minecraftCeiling));
	}

	private static int highestRecentMinecraftFps() {
		if (minecraftFpsSampleCount == 0) {
			return 0;
		}
		int highest = 0;
		for (int index = 0; index < minecraftFpsSampleCount; index++) {
			highest = Math.max(highest, minecraftFpsSamples[index]);
		}
		return highest;
	}

	private static void applyFrameRateNow() {
		if (browser == null || !browser.isValid()) {
			return;
		}
		int frameRate = effectiveFrameRate();
		if (frameRate == appliedFrameRate) {
			return;
		}
		try {
			browser.setWindowlessFrameRate(frameRate);
			appliedFrameRate = frameRate;
			LOGGER.debug("Game browser paint rate set to {} fps", frameRate);
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not set the game browser paint rate.", failure);
		}
	}

	/** Drops the current session. The next {@link #acquire(String)} starts a fresh page. */
	public static synchronized void release() {
		releaseBrowser();
	}

	/**
	 * Runs a script in the live page, for the few things the page has to be asked to do - the
	 * runtime's filesystem and the game's own store are two of them, and Chromium will not hand
	 * them to the JVM any other way.
	 */
	public static synchronized void executeScript(String script) {
		if (browser == null || !browser.isValid()) {
			return;
		}
		try {
			browser.executeJavaScript(script, "craftleste", 1);
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not run a script in the page.", failure);
		}
	}

	/** True when a session exists and has not been closed. */
	public static synchronized boolean isAvailable() {
		return browser != null && browser.isValid();
	}

	/** The live browser, or null when there is none. Callers must handle a null Chromium. */
	public static synchronized RinkuBrowser getBrowser() {
		return isAvailable() ? browser : null;
	}

	public static synchronized boolean isParked() {
		return parked;
	}

	/** Repaints observed over the last sampling window, for the diagnostics report. */
	public static synchronized int samplePaintRate() {
		long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			return 0;
		}
		long elapsed = Math.max(1L, now - windowStartMillis);
		int paints = paintsInWindow.getAndSet(0);
		lastPaintRate = (int) Math.round(paints * 1000.0 / elapsed);
		windowStartMillis = now;
		return lastPaintRate;
	}

	private static void addPaintCounter(RinkuBrowser target) {
		try {
			if (target instanceof CefBrowserOsr osrBrowser) {
				osrBrowser.addOnPaintListener(PAINT_COUNTER);
			}
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not observe browser repaints; the paint rate will read 0.", failure);
		}
	}

	private static void releaseBrowser() {
		if (browser != null) {
			if (browser.isValid() && Rinku.isInitialized()) {
				try {
					if (browser instanceof CefBrowserOsr osrBrowser) {
						osrBrowser.removeOnPaintListener(PAINT_COUNTER);
					}
				} catch (RuntimeException ignored) {
					// The listener is a no-op counter; a failed removal is harmless.
				}
				try {
					browser.close();
				} catch (RuntimeException failure) {
					LOGGER.warn("Failed to close the game browser cleanly.", failure);
				}
			}
			browser = null;
		}
		activeUrl = null;
		appliedWidth = 0;
		appliedHeight = 0;
		parked = false;
		appliedFrameRate = 0;
		audioMuted = false;
		paintsInWindow.set(0);
		lastPaintRate = 0;
		windowStartMillis = 0;
		minecraftFpsSampleCount = 0;
		minecraftFpsCursor = 0;
		lastFrameRateSyncMillis = 0;
	}
}
