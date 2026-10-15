package com.craftleste.client;

import com.craftleste.client.browser.BrowserScreen;
import com.craftleste.client.browser.CraftlesteBrowser;
import com.craftleste.client.config.CraftlesteConfig;
import com.craftleste.client.discovery.InstallDiscovery;
import com.craftleste.client.downloader.WeblesteDownloader;
import com.craftleste.client.intake.CelesteIntake;
import com.craftleste.client.probe.ProbePages;
import com.craftleste.client.server.CraftlesteHttpServer;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the mod has to have in place before it can show a game, and the server that shows it.
 *
 * <p>Two things must be true together: the Webleste runtime is fetched and the player's Celeste
 * files are copied in. Both are remembered - the runtime by its commit pin, the files by the path
 * they were taken from - so after the first time, opening the game is one action: whatever is
 * missing is done automatically, exactly as the sibling mod does with its cached runtime and files,
 * and only a first run ever has to ask for a path.
 */
public final class CraftlesteSession {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final String PROBE_2_PATH = "probe2.html";

	private static CraftlesteSession INSTANCE = null;

	private CraftlesteHttpServer server;

	private CraftlesteSession() {
	}

	public static synchronized CraftlesteSession getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new CraftlesteSession();
		}
		return INSTANCE;
	}

	/**
	 * The live browser, or null when none is open. The browser outlives the screen - it is parked,
	 * not closed - so this is non-null for as long as the game session exists.
	 */
	public RinkuBrowser getActiveBrowser() {
		return CraftlesteBrowser.getBrowser();
	}

	/** Whether the local server is up. */
	public boolean isRunning() {
		return server != null && server.isRunning();
	}

	/**
	 * Brings the game up to date and opens it, reporting each step through {@code reporter}. When
	 * everything is already in place this opens the screen immediately; otherwise it starts what is
	 * missing - a fetch, a copy from the remembered path, or a search of the machine for an FNA
	 * build - and opens as soon as the last step finishes. Only a first run with nothing findable
	 * ever has to ask for a path.
	 */
	public boolean openWhenReady(Minecraft client, Consumer<String> reporter) {
		runNextStep(client, reporter);
		return isReady();
	}

	private void runNextStep(Minecraft client, Consumer<String> reporter) {
		if (!isRuntimeComplete()) {
			reporter.accept("the Webleste runtime is missing - fetching it from its official origin...");
			runOffThread("fetch", () -> describeFetch(), message -> {
				reporter.accept(message);
				runNextStep(client, reporter);
			});
			return;
		}
		ensureCelesteFiles(client, reporter);
	}

	private void ensureCelesteFiles(Minecraft client, Consumer<String> reporter) {
		if (CelesteIntake.isReady(CraftlestePaths.celesteDirectory())) {
			if (!openGameScreen(client)) {
				reporter.accept("the files are ready but the local server would not start - check the log");
			}
			return;
		}

		String stored = CraftlesteConfig.getInstance().getCelesteSourcePath();
		if (stored != null && !stored.isBlank()) {
			reporter.accept("loading your Celeste files from " + stored + "...");
			runOffThread("load", () -> loadCelesteFrom(stored), message -> {
				reporter.accept(message);
				if (CelesteIntake.isReady(CraftlestePaths.celesteDirectory())) {
					// The copy landed: open the game now that the files are complete.
					openGameScreen(client);
				} else {
					// The stored path refused (the XNA build, or it is gone). Discovery does not
					// look at the stored path, so this cannot retry the same copy in a loop.
					discoverCelesteFiles(client, reporter);
				}
			});
			return;
		}
		discoverCelesteFiles(client, reporter);
	}

	/**
	 * Looks for a complete FNA build on this machine and copies it in. The player owns the game and
	 * already has it somewhere - a Steam library, an itch or GOG default folder - so the first run
	 * should not have to ask for a path either.
	 */
	private void discoverCelesteFiles(Minecraft client, Consumer<String> reporter) {
		if (CelesteIntake.isReady(CraftlestePaths.celesteDirectory())) {
			ensureCelesteFiles(client, reporter);
			return;
		}
		reporter.accept("looking for an FNA build of Celeste on this machine...");
		runOffThread("find", () -> describeDiscovery(), message -> {
			reporter.accept(message);
			if (CelesteIntake.isReady(CraftlestePaths.celesteDirectory())) {
				ensureCelesteFiles(client, reporter);
			} else {
				reporter.accept("craftleste needs your Celeste files once - /craftleste load <Celeste.exe or Content folder>");
				reporter.accept("if you own the Steam build: the Linux depot is FNA-based, and the Windows FNA build is the 'opengl' beta");
			}
		});
	}

	/** Drives a discovery from a command, without opening anything. */
	public void findCelesteFiles(Consumer<String> reporter) {
		reporter.accept("looking for an FNA build of Celeste on this machine...");
		runOffThread("find", () -> describeDiscovery(), reporter);
	}

	private static String describeDiscovery() {
		InstallDiscovery.DiscoveryResult found = InstallDiscovery.findFnaInstall();
		if (found.found()) {
			String loaded = loadCelesteFrom(
					found.installDirectory().resolve(CelesteIntake.EXE_NAME).toString());
			return "found " + found.installDirectory() + " - " + loaded;
		}
		if (found.problem() != null) {
			return "found a Celeste install but it is not usable: " + found.problem();
		}
		return "no Celeste install found on this machine";
	}

	private static String describeFetch() {
		WeblesteDownloader.Result result = WeblesteDownloader.install(CraftlestePaths.gameDirectory());
		return switch (result) {
			case FETCHED -> "runtime fetched";
			case ALREADY_PRESENT -> "runtime already on disk";
			case FAILED -> "runtime fetch failed - check the log";
		};
	}

	/** Whether the runtime and the player's files are both in place. */
	public boolean isReady() {
		return isRuntimeComplete() && CelesteIntake.isReady(CraftlestePaths.celesteDirectory());
	}

	/** Why the game cannot be opened right now, or null when it can. */
	public String missingRequirement() {
		if (!isRuntimeComplete()) {
			return "the Webleste runtime is not fetched yet";
		}
		if (!CelesteIntake.isReady(CraftlestePaths.celesteDirectory())) {
			String stored = CraftlesteConfig.getInstance().getCelesteSourcePath();
			if (stored != null && !stored.isBlank()) {
				// A remembered path: the player should not have to type it again.
				return "your Celeste files are not loaded - they load automatically from " + stored;
			}
			return "your Celeste files are not loaded - run /craftleste load <Celeste.exe or Content folder>";
		}
		return null;
	}

	/** Runs an intake for a stored or typed path, recording the path on success. */
	public static String loadCelesteFrom(String path) {
		Path given = CelesteIntake.parseUserPath(path).toAbsolutePath().normalize();
		CelesteIntake.Resolution resolution = CelesteIntake.resolve(given);
		if (resolution == null) {
			return "could not resolve that path (needs Celeste.exe next to a Content folder, or the Content folder itself)";
		}
		if (!CelesteIntake.isValidContent(resolution.contentDirectory())) {
			return "that Content folder is missing required subfolders (Dialog, Effects, FMOD, Graphics, Maps, Monocle, Overworld, Tutorials)";
		}
		String problem = CelesteIntake.validateInstall(resolution.exeFile());
		if (problem != null) {
			return problem;
		}
		CelesteIntake.CopyReport report = CelesteIntake.copyIntoGameDirectory(
				resolution, CraftlestePaths.celesteDirectory());
		CraftlesteConfig.getInstance().setCelesteSourcePath(given.toString());
		return "copied " + report.rootFiles() + " install files and " + report.contentFiles() + " Content files";
	}

	/** The same intake, reported through a consumer when the copy finishes. */
	public void loadCelesteInBackground(String path, Consumer<String> reporter) {
		runOffThread("load", () -> loadCelesteFrom(path), reporter);
	}

	/** The runtime fetch, reported through a consumer when it finishes. */
	public void fetchRuntimeInBackground(Consumer<String> reporter) {
		runOffThread("fetch", CraftlesteSession::describeFetch, reporter);
	}

	private boolean isRuntimeComplete() {
		String pin = WeblesteDownloader.currentPin(CraftlestePaths.gameDirectory());
		return pin != null && Files.isRegularFile(CraftlestePaths.runtimeDirectory(pin).resolve("index.html"));
	}

	private void runOffThread(String name, Supplier<String> step, Consumer<String> onDone) {
		Thread thread = new Thread(() -> {
			String result;
			try {
				result = step.get();
			} catch (RuntimeException failure) {
				result = "failed: " + failure.getMessage();
			}
			String message = result;
			Minecraft.getInstance().execute(() -> onDone.accept(message));
		}, "craftleste-" + name);
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Starts the local server for the current runtime pin, unless it is already up. Returns false
	 * when the game cannot be served yet.
	 */
	public boolean ensureServerRunning() {
		if (isRunning()) {
			return true;
		}
		if (!isReady()) {
			LOGGER.info("Not starting the game server: {}", missingRequirement());
			return false;
		}
		Path runtimeDirectory = CraftlestePaths.runtimeDirectory(
				WeblesteDownloader.currentPin(CraftlestePaths.gameDirectory()));
		server = new CraftlesteHttpServer(runtimeDirectory, CraftlestePaths.celesteDirectory());
		return server.start();
	}

	/** The URL the browser screen opens for the game: the bootstrap page, which fills OPFS first. */
	public String gameUrl() {
		return server.getBaseUrl() + "craftleste/bootstrap.html";
	}

	/** Opens the game if it can be served, and reports false otherwise. */
	public boolean openGameScreen(Minecraft client) {
		if (!ensureServerRunning()) {
			return false;
		}
		client.gui.setScreen(new BrowserScreen(gameUrl()));
		return true;
	}

	/**
	 * Opens Probe 2 (threaded wasm) through the local server. The point of the probe is to test the
	 * server's own isolation headers in the real browser, so it refuses to run without one: on a
	 * data URL it would only prove SharedArrayBuffer exists, not that the game's threads can start.
	 */
	public boolean openThreadedWasmProbeScreen(Minecraft client) {
		if (!ensureServerRunning()) {
			return false;
		}
		client.gui.setScreen(new BrowserScreen(server.getBaseUrl() + "craftleste/" + PROBE_2_PATH));
		return true;
	}

	/**
	 * Opens Probe 1 (directory picker, OPFS, gamepads). It is a self-contained data URL, so it
	 * opens even on a machine with no runtime and no server, which is what keeps the M1 capability
	 * report reachable from a fresh install.
	 */
	public void openCapabilityProbeScreen(Minecraft client) {
		client.gui.setScreen(new BrowserScreen(ProbePages.CAPABILITY_PROBE_URL));
	}

	public void stop() {
		if (server != null) {
			server.stop();
			server = null;
		}
		// A genuine close, for client shutdown and world unload: a parked page kept alive past the
		// session it belongs to would leak it.
		CraftlesteBrowser.release();
	}
}
