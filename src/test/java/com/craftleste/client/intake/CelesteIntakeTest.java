package com.craftleste.client.intake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone probe for the Celeste intake: resolution from either side, the port's content
 * contract, and the copy that feeds the local server (PLAN.md M2).
 */
class CelesteIntakeTest {

	private static Path createInstall(Path root, String exeName) throws IOException {
		return createInstall(root, exeName, true);
	}

	private static Path createInstall(Path root, String exeName, boolean fnaBuild) throws IOException {
		Path install = root.resolve("install");
		Files.createDirectories(install.resolve("Content"));
		for (String folder : CelesteIntake.REQUIRED_CONTENT_FOLDERS) {
			Files.createDirectories(install.resolve("Content").resolve(folder));
		}
		Files.writeString(install.resolve("Content/Dialog/prologue.txt"), "chapter", StandardCharsets.UTF_8);
		Files.writeString(install.resolve(exeName), "exe-bytes", StandardCharsets.UTF_8);
		Files.writeString(install.resolve("Celeste.pdb"), "symbols", StandardCharsets.UTF_8);
		if (fnaBuild) {
			// The marker that says the port can run this install at all.
			Files.writeString(install.resolve("FNA.dll"), "fna", StandardCharsets.UTF_8);
		}
		return install;
	}

	@Test
	void resolvesFromTheExe() throws IOException {
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe");

		CelesteIntake.Resolution resolution = CelesteIntake.resolve(install.resolve("Celeste.exe"));

		assertNotNull(resolution);
		assertEquals("Celeste.exe", resolution.exeFile().getFileName().toString());
		assertEquals(install.resolve("Content"), resolution.contentDirectory());
		assertTrue(CelesteIntake.isValidContent(resolution.contentDirectory()));
	}

	@Test
	void resolvesFromTheContentFolder() throws IOException {
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe");

		CelesteIntake.Resolution resolution = CelesteIntake.resolve(install.resolve("Content"));

		assertNotNull(resolution);
		assertEquals(install.resolve("Celeste.exe"), resolution.exeFile());
	}

	@Test
	void theFnaMarkerIsRequiredAndExplained() throws IOException {
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe");
		assertNull(CelesteIntake.validateInstall(install.resolve("Celeste.exe")));

		Path xnaInstall = createInstall(Files.createTempDirectory("intake"), "Celeste.exe", false);
		String problem = CelesteIntake.validateInstall(xnaInstall.resolve("Celeste.exe"));
		assertNotNull(problem);
		assertTrue(problem.contains("XNA"), problem);
		assertTrue(problem.contains(CelesteIntake.FNA_MARKER), problem);
	}

	@Test
	void aStaleXnaCopyInTheGameDirectoryIsNotReady() throws IOException {
		// The shape that cost a session: the player switched Steam to the opengl beta, but the
		// game directory still holds the copy made from the default XNA build. Readiness judged on
		// Celeste.exe and Content alone called that copy fine, and the port died inside its own
		// boot on Microsoft.Xna.Framework.Game.
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe", false);
		Path celesteDirectory = Files.createTempDirectory("game").resolve("celeste");
		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste.exe")), celesteDirectory);

		assertFalse(CelesteIntake.isReady(celesteDirectory));

		// After the beta switch the same load path produces a complete copy.
		Path fnaInstall = createInstall(Files.createTempDirectory("intake"), "Celeste.exe", true);
		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(fnaInstall.resolve("Celeste.exe")), celesteDirectory);

		assertTrue(CelesteIntake.isReady(celesteDirectory));
	}

	@Test
	void aTypedPathHasItsChatQuotesStripped() {
		assertEquals(Path.of("E:/SteamLibrary/Celeste.exe"),
				CelesteIntake.parseUserPath("\"E:/SteamLibrary/Celeste.exe\""));
		assertEquals(Path.of("E:/SteamLibrary/Celeste.exe"),
				CelesteIntake.parseUserPath("  \"E:/SteamLibrary/Celeste.exe\"  "));
		assertEquals(Path.of("E:/SteamLibrary/Celeste.exe"),
				CelesteIntake.parseUserPath("E:/SteamLibrary/Celeste.exe"));
	}

	@Test
	void refusesAnythingElse() throws IOException {
		Path root = Files.createTempDirectory("intake");
		Files.createDirectories(root.resolve("not-a-game"));

		assertNull(CelesteIntake.resolve(root.resolve("missing.exe")));
		assertNull(CelesteIntake.resolve(root.resolve("not-a-game")));
	}

	@Test
	void theContractNeedsEveryRequiredFolder(@TempDir Path root) throws IOException {
		Path content = root.resolve("Content");
		assertFalse(CelesteIntake.isValidContent(content));

		for (String folder : CelesteIntake.REQUIRED_CONTENT_FOLDERS) {
			Files.createDirectories(content.resolve(folder));
		}
		assertTrue(CelesteIntake.isValidContent(content));

		Files.walk(content.resolve("Tutorials"))
				.sorted((a, b) -> b.getNameCount() - a.getNameCount())
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException failure) {
						throw new RuntimeException(failure);
					}
				});
		assertFalse(CelesteIntake.isValidContent(content));
	}

	@Test
	void copiesTheInstallUnderTheExpectedNames(@TempDir Path root) throws IOException {
		Path install = createInstall(root, "Celeste.exe");
		Path celesteDirectory = root.resolve("game/celeste");

		CelesteIntake.CopyReport report = CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste.exe")), celesteDirectory);

		assertTrue(CelesteIntake.isReady(celesteDirectory));
		assertEquals("exe-bytes", Files.readString(celesteDirectory.resolve("Celeste.exe"), StandardCharsets.UTF_8));
		assertEquals("symbols", Files.readString(celesteDirectory.resolve("Celeste.pdb"), StandardCharsets.UTF_8));
		assertEquals("chapter",
				Files.readString(celesteDirectory.resolve("Content/Dialog/prologue.txt"), StandardCharsets.UTF_8));
		assertTrue(report.rootFiles() >= 2);
		// One file inside Content, under the Dialog folder.
		assertEquals(1, report.contentFiles());
	}

	@Test
	void storesAnotherExeNameUnderTheLoadersName(@TempDir Path root) throws IOException {
		Path install = createInstall(root, "Celeste Everest.exe");
		Path celesteDirectory = root.resolve("game/celeste");

		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste Everest.exe")), celesteDirectory);

		assertTrue(Files.isRegularFile(celesteDirectory.resolve("Celeste.exe")));
	}

	@Test
	void aSecondIntakeReplacesTheOldCopy() throws IOException {
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe", true);
		Path celesteDirectory = Files.createTempDirectory("game").resolve("celeste");
		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste.exe")), celesteDirectory);

		// A stale file from an earlier install - the shape a beta switch leaves behind - must not
		// survive a re-intake: the game directory is a mirror of the install, not a merge.
		Files.writeString(celesteDirectory.resolve("Celeste.exe.config"), "stale", StandardCharsets.UTF_8);
		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste.exe")), celesteDirectory);

		assertFalse(Files.exists(celesteDirectory.resolve("Celeste.exe.config")));
		assertTrue(CelesteIntake.isReady(celesteDirectory));
	}

	@Test
	void theMirrorMatchesTheInstallExactly() throws IOException {
		Path install = createInstall(Files.createTempDirectory("intake"), "Celeste.exe", true);
		Path celesteDirectory = Files.createTempDirectory("game").resolve("celeste");
		Files.createDirectories(celesteDirectory);
		Files.writeString(celesteDirectory.resolve("OnlyInOldBuild.dll"), "stale", StandardCharsets.UTF_8);

		CelesteIntake.copyIntoGameDirectory(
				CelesteIntake.resolve(install.resolve("Celeste.exe")), celesteDirectory);

		try (Stream<Path> installFiles = Files.list(install);
				Stream<Path> copiedFiles = Files.list(celesteDirectory)) {
			List<String> expected = installFiles.filter(Files::isRegularFile)
					.map(path -> path.getFileName().toString()).sorted().toList();
			List<String> actual = copiedFiles.filter(Files::isRegularFile)
					.map(path -> path.getFileName().toString()).sorted().toList();
			assertEquals(expected, actual);
		}
	}
}
