package com.craftleste.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Every file path the mod owns, in one place.
 *
 * <p>Minecraft's convention is that a mod keeps its settings under the instance's config directory
 * and its generated data under the instance root, never under the player's home directory: players
 * move instances between launchers, back them up, and delete them, and a mod that writes to
 * {@code ~} survives none of that. Cloth Config reads and writes {@code config/} for exactly this
 * reason.
 *
 * <p>The Webleste runtime lives in {@code <instance>/craftleste/game/runtimes/<commit>}, one
 * directory per fetched commit, so a runtime change never overwrites the version that is currently
 * served, and the player's own Celeste files live in {@code <instance>/craftleste/game/celeste} so
 * they survive a runtime update without being copied again.
 */
public final class CraftlestePaths {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final String CONFIG_FILE_NAME = "craftleste.properties";
	private static final String MOD_DIRECTORY_NAME = "craftleste";
	private static final String SAVES_DIRECTORY_NAME = "saves";
	private static final String GAME_DIRECTORY_NAME = "game";
	private static final String RUNTIMES_DIRECTORY_NAME = "runtimes";
	private static final String CELESTE_DIRECTORY_NAME = "celeste";

	private CraftlestePaths() {
	}

	/** {@code config/craftleste.properties}, in the instance's config directory like Cloth Config uses. */
	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
	}

	/** {@code config/craftleste/saves}, where save exports, import staging and backups live. */
	public static Path savesDirectory() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(MOD_DIRECTORY_NAME)
				.resolve(SAVES_DIRECTORY_NAME);
	}

	/** {@code <instance>/craftleste/game}: the Webleste runtime, the player's Celeste files, downloads. */
	public static Path gameDirectory() {
		return FabricLoader.getInstance().getGameDir()
				.resolve(MOD_DIRECTORY_NAME)
				.resolve(GAME_DIRECTORY_NAME);
	}

	/** {@code <game>/runtimes/<commit>}: one extracted official build, named after its commit pin. */
	public static Path runtimeDirectory(String commit) {
		return runtimeDirectory(gameDirectory(), commit);
	}

	/**
	 * {@code <game>/runtimes/<commit>}, resolved from a given game directory.
	 *
	 * <p>The resolver is separate from {@link #gameDirectory()} because everything on disk should
	 * be testable without a running game: the standalone probes exercise the real path layout
	 * against a temporary directory instead of the instance.
	 */
	public static Path runtimeDirectory(Path gameDirectory, String commit) {
		return gameDirectory.resolve(RUNTIMES_DIRECTORY_NAME).resolve(commit);
	}

	/**
	 * {@code <game>/current}: the commit pin of the runtime that is currently served.
	 *
	 * <p>A one-line file rather than a config entry on purpose: the downloader updates it as its
	 * last, atomic step, and a runtime change must switch the served directory in the same write
	 * that lands it on disk.
	 */
	public static Path currentPinFile() {
		return currentPinFile(gameDirectory());
	}

	/** The same pointer file, resolved from a given game directory. */
	public static Path currentPinFile(Path gameDirectory) {
		return gameDirectory.resolve("current");
	}

	/** {@code <game>/celeste}: the player's own Celeste.exe and Content folder, never packaged. */
	public static Path celesteDirectory() {
		return gameDirectory().resolve(CELESTE_DIRECTORY_NAME);
	}
}
