package com.craftleste.client.browser;

import com.craftleste.client.CraftlesteSession;
import com.craftleste.client.PageInspector;
import com.craftleste.CraftlesteMod;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import de.keksuccino.rinku.listeners.RinkuInitListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * <p>The screen owns no browser. {@link CraftlesteBrowser} does, and it deliberately outlives this
 * screen: closing here only parks the page, so reopening is instant instead of paying for the
 * port's whole boot again (dotnet startup, OPFS mount, the MonoMod patch, the game's own load).
 * The screen's job is to attach that browser to the viewport at the right size and feed it input.
 *
 * <p>The browser is created here only once Rinku has initialized CEF. Rinku initializes on the
 * first GUI render (MixinGui), which is normally before this screen exists, but a screen opened
 * during that first render still finds an uninitialized Rinku; {@link #createBrowserIfReady}
 * then defers through {@link Rinku#scheduleForInit} and retries in {@link #tick} rather than
 * throwing from {@link Rinku#assertInitialized}.
 */
public class BrowserScreen extends Screen {

	public static final Component TITLE = Component.literal("Celeste");
	private static final int BACKDROP_COLOR = 0xFF0E1116;
	private static final int LOADING_BAR_HEIGHT = 2;
	private static final int LOADING_BAR_TRACK_COLOR = 0x55000000;
	private static final int LOADING_BAR_FILL_COLOR = 0xFF3BA8FF;

	private final String url;

	private boolean initScheduled;
	private boolean browserButtonDown;

	public BrowserScreen(String url) {
		super(TITLE);
		this.url = url;
	}

	@Override
	protected void init() {
		super.init();
		createBrowserIfReady();
	}

	/**
	 * Attaches the shared browser to {@link #url}, deferring when Rinku has not initialized CEF yet.
	 * The deferred listener re-runs on the render thread and only while this screen is still the one
	 * showing, so a closed screen never creates a browser nobody will park.
	 */
	private void createBrowserIfReady() {
		if (CraftlesteBrowser.isAvailable()) {
			syncViewport();
			return;
		}

		if (Rinku.isInitialized()) {
			attachBrowser();
		} else if (Rinku.isInitializationAllowed() && !initScheduled) {
			initScheduled = true;
			Rinku.scheduleForInit(new RinkuInitListener() {
				@Override
				public void onInit(boolean successful) {
					if (successful) {
						minecraft.execute(() -> {
							if (minecraft.gui.screen() == BrowserScreen.this) {
								attachBrowser();
							}
						});
					}
				}
			});
		}
	}

	private void attachBrowser() {
		CraftlesteBrowser.acquire(url);
		browserButtonDown = false;
		CraftlesteBrowser.showViewport(browserPixelWidth(), browserPixelHeight());
	}

	/** The live browser, or null when Chromium is not up yet. */
	private RinkuBrowser browser() {
		return CraftlesteBrowser.getBrowser();
	}

	private int guiScale() {
		return minecraft.getWindow().getGuiScale();
	}

	/**
	 * The screen's logical size, read straight from the window rather than from {@code width} and
	 * {@code height}: those Screen fields only refresh when the screen itself is rebuilt, so a
	 * window resized while the screen is open - or a screen built against a stale window - leaves
	 * them describing a size the window no longer has. craftlatro documents the same trap from its
	 * own history of a surface stuck at whatever size it started at.
	 */
	private int screenWidth() {
		return minecraft.getWindow().getGuiScaledWidth();
	}

	private int screenHeight() {
		return minecraft.getWindow().getGuiScaledHeight();
	}

	/** The browser pixel size for the current GUI scale; the surface covers the whole viewport. */
	private int browserPixelWidth() {
		return Math.max(1, screenWidth() * guiScale());
	}

	private int browserPixelHeight() {
		return Math.max(1, screenHeight() * guiScale());
	}

	/**
	 * Keeps the browser's surface matched to the screen, on the same frame the size is known, and
	 * re-checks the paint rate: Chromium painting faster than Minecraft composites is waste, since
	 * Rinku keeps only the newest frame.
	 */
	private void syncViewport() {
		CraftlesteBrowser.observeFrameRate(minecraft.getFps());
		CraftlesteBrowser.showViewport(browserPixelWidth(), browserPixelHeight());
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		syncViewport();
	}

	private int toBrowserX(double guiX) {
		return (int) Math.round(guiX * guiScale());
	}

	private int toBrowserY(double guiY) {
		return (int) Math.round(guiY * guiScale());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partial) {
		super.extractRenderState(guiGraphics, mouseX, mouseY, partial);
		syncViewport();
		renderLoadingIndicator(guiGraphics);

		RinkuBrowser browser = browser();
		// The window's own logical size, for the same reason the viewport uses it: the Screen's
		// width and height describe the size at rebuild time, not the size now.
		int viewportWidth = screenWidth();
		int viewportHeight = screenHeight();

		// Chromium renders the viewport opaque, so the backdrop is covered as soon as the texture
		// is ready; until then it hides the vanilla screen background.
		guiGraphics.fill(0, 0, viewportWidth, viewportHeight, BACKDROP_COLOR);

		if (browser != null && browser.isTextureReady()) {
			Identifier texture = browser.getTextureIdentifier();
			if (texture != null) {
				// The source rectangle is the WHOLE texture, which has to be spelled out: the
				// ten-argument blit overload forces srcWidth/srcHeight to the destination size, and
				// the UV range it then builds is (u + srcWidth) / textureWidth - so declaring a
				// browser surface larger than the GUI viewport sampled only a fraction of the page
				// and stretched that crop over the screen. The chromium surface is deliberately
				// framebuffer-sized (screenWidth * guiScale) while the GUI quad is gui-scaled, so
				// the two are never the same number whenever the GUI scale is not 1.
				int surfaceWidth = Math.max(1, CraftlesteBrowser.textureWidth());
				int surfaceHeight = Math.max(1, CraftlesteBrowser.textureHeight());
				guiGraphics.blit(
						RenderPipelines.GUI_TEXTURED,
						texture,
						0, 0,
						0f, 0f,
						viewportWidth, viewportHeight,
						surfaceWidth, surfaceHeight,
						surfaceWidth, surfaceHeight
				);
			}
		}
	}

	private void renderLoadingIndicator(GuiGraphicsExtractor guiGraphics) {
		RinkuBrowser browser = browser();
		if (browser == null || !browser.isLoading()) {
			return;
		}

		int barWidth = width;
		guiGraphics.fill(0, 0, barWidth, LOADING_BAR_HEIGHT, LOADING_BAR_TRACK_COLOR);

		int segmentWidth = Math.max(20, barWidth / 4);
		int travelRange = barWidth + segmentWidth;
		int animatedOffset = (int) ((Util.getMillis() / 6L) % travelRange) - segmentWidth;
		int segmentStart = Math.max(0, animatedOffset);
		int segmentEnd = Math.min(barWidth, animatedOffset + segmentWidth);
		if (segmentEnd > segmentStart) {
			guiGraphics.fill(segmentStart, 0, segmentEnd, LOADING_BAR_HEIGHT, LOADING_BAR_FILL_COLOR);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
		boolean handled = super.mouseClicked(event, isDoubleClick);
		if (handled) {
			return true;
		}
		RinkuBrowser browser = browser();
		if (browser == null) {
			return false;
		}

		browser.sendMousePress(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
		browser.setFocus(true);
		browserButtonDown = true;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = super.mouseReleased(event);
		if (handled) {
			return true;
		}
		// Only the button the page actually saw pressed is released here, so a press that started
		// outside the surface cannot leave the page holding a stuck button.
		RinkuBrowser browser = browser();
		if (browser != null && browserButtonDown) {
			browser.sendMouseRelease(toBrowserX(event.x()), toBrowserY(event.y()), event.button());
			browserButtonDown = false;
		}
		return false;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		RinkuBrowser browser = browser();
		if (browser != null) {
			browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
		}
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		boolean handled = super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (handled) {
			return true;
		}
		RinkuBrowser browser = browser();
		if (browser == null) {
			return false;
		}

		browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY), scrollY, 0);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_F8) {
			// The screen owns every key while it is open, so the page diagnostics have a key of
			// their own: this inspects the open page and answers in chat. The port never sees F8.
			inspectOpenPage();
			return true;
		}
		if (super.keyPressed(event)) {
			return true;
		}
		RinkuBrowser browser = browser();
		if (browser == null) {
			return false;
		}

		browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
		browser.setFocus(true);
		return true;
	}

	private void inspectOpenPage() {
		try {
			RinkuBrowser browser = browser();
			PageInspector.evaluateAsync(browser, PageInspector.LAYOUT_INSPECTION, result ->
					reportInspection("layout", result));
			PageInspector.evaluateAsync(browser, PageInspector.OPFS_INSPECTION, result ->
					reportInspection("storage", result));
			PageInspector.evaluateAsync(browser, PageInspector.GAMEPAD_INSPECTION, result ->
					reportInspection("gamepad", result));
		} catch (RuntimeException failure) {
			// The inspector is the only channel while the screen owns every key, so a silent failure
			// sends the next debugging round in circles: report it where the player can read it.
			CraftlesteMod.LOGGER.warn("Page inspection could not start", failure);
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.literal("page inspection failed: " + failure));
			}
		}
	}

	private void reportInspection(String label, String result) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		minecraft.player.sendSystemMessage(Component.literal(
				"[" + label + "] " + (result.length() > 220 ? result.substring(0, 220) + " ..." : result)));
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (super.keyReleased(event)) {
			return true;
		}
		RinkuBrowser browser = browser();
		if (browser == null) {
			return false;
		}

		browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (super.charTyped(event)) {
			return true;
		}
		RinkuBrowser browser = browser();
		if (browser == null) {
			return false;
		}

		browser.sendKeyTyped((char) event.codepoint(), 0);
		browser.setFocus(true);
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		createBrowserIfReady();
	}

	@Override
	public void onClose() {
		// Park, do not close: the page keeps running so reopening the game skips the port's whole
		// boot - the dotnet startup, the OPFS mount and the MonoMod patch of Celeste. A real close
		// is CraftlesteBrowser.release(), for world unload and client shutdown.
		CraftlesteBrowser.park();
		browserButtonDown = false;
		super.onClose();
	}
}
