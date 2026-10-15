package com.craftleste.client.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

/**
 * The player's own Celeste files, validated and copied into the game directory.
 *
 * <p>Intake accepts either the executable or its Content directory and treats them as the same
 * thing (CONCEPT.md user preference): given the Content directory, the executable is looked for
 * next to it; given the executable, Content is its sibling. The contract validated here is the
 * port's own - {@code hasContent()} in the port's fs.tsx requires Content/{Dialog,Effects,FMOD,
 * Graphics,Maps,Monocle,Overworld,Tutorials} plus a {@code .ContentExists} marker - so a copy that
 * passes here is a copy the port accepts.
 *
 * <p>Nothing from the player's copy is read beyond this copy step, and nothing here is ever
 * packaged: the files land in the instance's game directory, which is git-ignored space, and only
 * the local server ever reads them back so the bootstrap page can write them into OPFS.
 */
public final class CelesteIntake {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	/** The Content subfolders the port's hasContent() requires, in fs.tsx's own order. */
	public static final List<String> REQUIRED_CONTENT_FOLDERS = List.of(
			"Dialog", "Effects", "FMOD", "Graphics", "Maps", "Monocle", "Overworld", "Tutorials"
	);

	/** The name the executable is stored under: the loader loads /libsdl/Celeste.exe by that name. */
	public static final String EXE_NAME = "Celeste.exe";

	/**
	 * The marker that separates the FNA build from the XNA build.
	 *
	 * <p>The port's loader ships its own web FNA assemblies and the game's references to
	 * Microsoft.Xna.Framework.* resolve against them - but only for an exe built against FNA. The
	 * XNA build's references are to the real XNA redistributables, which live in the machine's GAC
	 * and are nowhere in the install, so it dies at {@code CelesteLoader.Init()} asking for
	 * Microsoft.Xna.Framework.Game. Every FNA build, on every platform and storefront, has FNA.dll
	 * sitting next to the exe; no XNA build has one.
	 */
	public static final String FNA_MARKER = "FNA.dll";

	private CelesteIntake() {
	}

	/** A resolved intake: which executable to copy and which Content directory it belongs to. */
	public record Resolution(Path exeFile, Path contentDirectory) {
	}

	/**
	 * Turns a path as a player types it in chat into a path the filesystem accepts. The greedy
	 * command argument keeps the surrounding quotes verbatim, so a pasted
	 * {@code "E:\SteamLibrary\...\Celeste.exe"} arrives quoted and {@code Path.of} rejects it;
	 * the quotes are the chat habit, not part of the path.
	 */
	public static Path parseUserPath(String rawPath) {
		String trimmed = rawPath.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}
		return Path.of(trimmed);
	}

	/**
	 * Resolves an exe-or-Content path into an executable and its Content directory, or returns
	 * null with the reason logged.
	 */
	public static Resolution resolve(Path exeOrContent) {
		Path given = exeOrContent.toAbsolutePath().normalize();
		if (!Files.exists(given)) {
			LOGGER.warn("Intake path does not exist: {}", given);
			return null;
		}

		if (Files.isDirectory(given)) {
			if (!given.getFileName().toString().equalsIgnoreCase("Content")) {
				LOGGER.warn("Intake directory is not a Content folder: {}", given);
				return null;
			}
			Path content = given;
			Path exe = content.getParent().resolve(EXE_NAME);
			if (!Files.isRegularFile(exe)) {
				LOGGER.warn("No {} next to the Content folder at {}", EXE_NAME, content.getParent());
				return null;
			}
			return new Resolution(exe, content);
		}

		Path content = given.getParent().resolve("Content");
		if (!Files.isDirectory(content)) {
			LOGGER.warn("No Content directory next to {}", given);
			return null;
		}
		return new Resolution(given, content);
	}

	/** Whether a Content directory satisfies the port's contract. */
	public static boolean isValidContent(Path contentDirectory) {
		if (!Files.isDirectory(contentDirectory)) {
			return false;
		}
		for (String folder : REQUIRED_CONTENT_FOLDERS) {
			if (!Files.isDirectory(contentDirectory.resolve(folder))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether the install is the FNA build the port runs, with the reason it is not when it is the
	 * XNA build. Returns null when the install is acceptable.
	 */
	public static String validateInstall(Path exeFile) {
		Path installRoot = exeFile.getParent();
		if (Files.isRegularFile(installRoot.resolve(FNA_MARKER))
				|| Files.isRegularFile(installRoot.resolve("lib64").resolve(FNA_MARKER))) {
			return null;
		}
		return "this is the XNA build of Celeste, and the port only runs the FNA build (no "
				+ FNA_MARKER + " in the game folder). On Steam the Linux build is FNA-based, and the"
				+ " Windows FNA build is the 'opengl' beta of celeste; the port's own download page"
				+ " (r58playz.dev) also offers a 64-bit Windows FNA build.";
	}

	/**
	 * Whether the game directory already holds a complete set of the player's files.
	 *
	 * <p>The FNA marker is checked here too, not just at intake time. The copy is a snapshot: a
	 * player who switches the Steam beta from the default XNA build to `opengl` changes the source
	 * install but leaves the old copy in the game directory, and a check that only looked for
	 * Celeste.exe and Content would call that XNA copy ready and hand it to the port, which then
	 * dies inside its own boot asking for Microsoft.Xna.Framework.Game. Judging the copy by the
	 * same contract as the install makes the re-copy happen by itself.
	 */
	public static boolean isReady(Path celesteDirectory) {
		return Files.isRegularFile(celesteDirectory.resolve(EXE_NAME))
				&& Files.isRegularFile(celesteDirectory.resolve(FNA_MARKER))
				&& isValidContent(celesteDirectory.resolve("Content"));
	}

	/** What one intake copy did, for the status message the player reads. */
	public record CopyReport(int rootFiles, int contentFiles) {
	}

	/**
	 * Copies the resolved files into {@code celesteDirectory} so the directory becomes an exact
	 * mirror of the install root: the executable's sibling files, the whole Content tree, and
	 * nothing else.
	 *
	 * <p>The directory is replaced rather than merged because a merge keeps the previous build's
	 * files. A player who switches the Steam beta from the default XNA build to the FNA build
	 * would otherwise keep Celeste.exe.config and any other file the FNA build does not ship, and
	 * the port would boot against a mixture of two releases - which is exactly the shape that
	 * produced an XNA assembly error from an FNA install.
	 */
	public static CopyReport copyIntoGameDirectory(Resolution resolution, Path celesteDirectory) {
		try {
			deleteRecursively(celesteDirectory);
			Files.createDirectories(celesteDirectory);

			int rootFiles = 0;
			try (Stream<Path> siblings = Files.list(resolution.exeFile().getParent())) {
				List<Path> files = siblings.filter(Files::isRegularFile).toList();
				for (Path sibling : files) {
					Files.copy(sibling, celesteDirectory.resolve(sibling.getFileName().toString()),
							StandardCopyOption.REPLACE_EXISTING);
					rootFiles++;
				}
			}
			if (!Files.isRegularFile(celesteDirectory.resolve(EXE_NAME))) {
				// The player's executable had another name; store it under the one the loader expects.
				Files.copy(resolution.exeFile(), celesteDirectory.resolve(EXE_NAME),
						StandardCopyOption.REPLACE_EXISTING);
				rootFiles++;
			}

			int contentFiles = copyTree(resolution.contentDirectory(), celesteDirectory.resolve("Content"));
			return new CopyReport(rootFiles, contentFiles);
		} catch (IOException failure) {
			throw new UncheckedIOException("Could not copy the Celeste files", failure);
		}
	}

	/** Recursively copies a directory tree, returning the number of files written. */
	public static int copyTree(Path source, Path target) throws IOException {
		if (!Files.isDirectory(source)) {
			return 0;
		}
		Files.createDirectories(target);
		int[] files = {0};
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
				Path relative = source.relativize(directory);
				Files.createDirectories(relative.toString().isEmpty() ? target : target.resolve(relative));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Path relative = source.relativize(file);
				Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
				files[0]++;
				return FileVisitResult.CONTINUE;
			}
		});
		return files[0];
	}

	private static void deleteRecursively(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
