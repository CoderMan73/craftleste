package com.craftleste.client.discovery;

import com.craftleste.client.intake.CelesteIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a usable Celeste install on this machine without asking the player for a path.
 *
 * <p>Acquisition is deliberately not here and cannot be: Steam switches beta branches only through
 * its own UI, and nothing in this project downloads a hosted copy of a commercial game - the game
 * is player-supplied. What can be automated is <em>discovery</em>: once the player has the FNA
 * build installed anywhere - the Steam default library, any extra Steam library, the itch or GOG
 * default folders - a press of the open key finds it, loads it, and remembers the path.
 *
 * <p>Every Steam library is found through the Steam install's {@code libraryfolders.vdf}, which
 * lists them all; the default install roots cover the common Windows cases, and the Linux and
 * macOS Steam roots are checked too.
 */
public final class InstallDiscovery {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final Path[] DEFAULT_STEAM_ROOTS = {
			Path.of("C:\\Program Files (x86)\\Steam"),
			Path.of("C:\\Program Files\\Steam"),
			Path.of(System.getProperty("user.home", "."), ".steam", "steam"),
			Path.of(System.getProperty("user.home", "."), "Library", "Application Support", "Steam"),
	};

	/** Storefront default layouts, for installs that never went through Steam. */
	private static final Path[] MANUAL_STORE_ROOTS = {
			Path.of("C:\\Program Files (x86)\\GOG Galaxy\\Games"),
			Path.of(System.getProperty("user.home", "."), "AppData", "Roaming", "itch", "apps"),
	};

	/**
	 * {@code "path" "D:\\SteamLibrary"} entries, with the doubled backslashes the format escapes
	 * paths with collapsed back to single ones.
	 */
	private static final Pattern LIBRARY_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");

	private InstallDiscovery() {
	}

	/** Where a discovery attempt ended: an install to use, or the reason the best candidate was refused. */
	public record DiscoveryResult(Path installDirectory, String problem) {
		public boolean found() {
			return installDirectory != null;
		}
	}

	/**
	 * The first install that is a complete FNA build among this machine's candidates.
	 */
	public static DiscoveryResult findFnaInstall() {
		return findFnaInstall(candidateDirectories());
	}

	/**
	 * The first install that is a complete FNA build, or - when every candidate is refused - the
	 * problem with the one that looked most like an install. A Steam folder with the XNA build is
	 * the common shape of the second case, and naming it is the difference between "not found" and
	 * "found it, but switch the beta". The candidate list is a parameter so the standalone probe can
	 * run this against a fake library tree instead of the machine.
	 */
	public static DiscoveryResult findFnaInstall(List<Path> candidates) {
		String refusedProblem = null;
		for (Path directory : candidates) {
			if (!Files.isDirectory(directory)) {
				continue;
			}
			Path exe = findExecutable(directory);
			if (exe == null || !Files.isDirectory(exe.getParent().resolve("Content"))) {
				continue;
			}
			if (!CelesteIntake.isValidContent(exe.getParent().resolve("Content"))) {
				continue;
			}
			String problem = CelesteIntake.validateInstall(exe);
			if (problem == null) {
				return new DiscoveryResult(exe.getParent(), null);
			}
			if (refusedProblem == null) {
				refusedProblem = directory.getFileName() + ": " + problem;
			}
		}
		return new DiscoveryResult(null, refusedProblem);
	}

	private static Path findExecutable(Path directory) {
		Path expected = directory.resolve(CelesteIntake.EXE_NAME);
		if (Files.isRegularFile(expected)) {
			return expected;
		}
		// Store builds that renamed the executable still call it Celeste.exe, but a non-Steam
		// bundle might not; one exe in the root is the whole game either way.
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".exe"))
					.findFirst()
					.orElse(null);
		} catch (IOException failure) {
			return null;
		}
	}

	/** Steam's Celeste folder in every library, then the non-Steam store defaults. */
	public static List<Path> candidateDirectories() {
		List<Path> candidates = new ArrayList<>();
		for (Path library : steamLibraries()) {
			addCandidate(candidates, library.resolve("steamapps").resolve("common").resolve("Celeste"));
		}
		for (Path root : MANUAL_STORE_ROOTS) {
			addCandidate(candidates, root.resolve("Celeste"));
		}
		return candidates;
	}

	/** Every Steam install's own library plus each extra library its {@code libraryfolders.vdf} lists. */
	public static List<Path> steamLibraries() {
		List<Path> libraries = new ArrayList<>();
		for (Path root : DEFAULT_STEAM_ROOTS) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			addLibrary(libraries, root);

			Path listing = root.resolve("steamapps").resolve("libraryfolders.vdf");
			if (!Files.isRegularFile(listing)) {
				continue;
			}
			try {
				for (String path : parseLibraryPaths(Files.readString(listing))) {
					addLibrary(libraries, Path.of(path));
				}
			} catch (IOException failure) {
				LOGGER.warn("Could not read {}", listing, failure);
			}
		}
		return libraries;
	}

	static List<String> parseLibraryPaths(String vdf) {
		List<String> paths = new ArrayList<>();
		Matcher matcher = LIBRARY_PATH.matcher(vdf);
		while (matcher.find()) {
			paths.add(matcher.group(1).replace("\\\\", "\\"));
		}
		return paths;
	}

	private static void addLibrary(List<Path> libraries, Path library) {
		Path normalized = library.toAbsolutePath().normalize();
		if (Files.isDirectory(normalized) && !libraries.contains(normalized)) {
			libraries.add(normalized);
		}
	}

	private static void addCandidate(List<Path> candidates, Path directory) {
		if (!candidates.contains(directory)) {
			candidates.add(directory);
		}
	}
}
