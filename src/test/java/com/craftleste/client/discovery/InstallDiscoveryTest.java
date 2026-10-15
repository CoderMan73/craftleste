package com.craftleste.client.discovery;

import com.craftleste.client.intake.CelesteIntake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone probe for install discovery: a fake Steam root with a library listing and two
 * candidate installs, exercising the same path and validation logic a real machine hits.
 */
class InstallDiscoveryTest {

	/**
	 * A Steam install whose libraryfolders.vdf points at two libraries: one holding the XNA build
	 * (the Steam default, the shape that must be refused with an explanation) and one holding the
	 * FNA build (what a beta switcher ends up with).
	 */
	private Path createSteamLayout(Path root) throws IOException {
		Path steam = root.resolve("Steam");
		Path libraryA = root.resolve("LibraryA");
		Path libraryB = root.resolve("LibraryB");
		Files.createDirectories(steam.resolve("steamapps"));
		Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"), """
				"libraryfolders"
				{
					"0"
					{
						"path"		"%STEAM%"
					}
					"1"
					{
						"path"		"%LIBRARY_A%"
					}
					"2"
					{
						"path"		"%LIBRARY_B%"
					}
				}
				""".replace("%STEAM%", steam.toString().replace("\\", "\\\\"))
				.replace("%LIBRARY_A%", libraryA.toString().replace("\\", "\\\\"))
				.replace("%LIBRARY_B%", libraryB.toString().replace("\\", "\\\\")),
				StandardCharsets.UTF_8);

		createInstall(libraryA.resolve("steamapps").resolve("common").resolve("Celeste"), false);
		createInstall(libraryB.resolve("steamapps").resolve("common").resolve("Celeste"), true);
		return steam;
	}

	private void createInstall(Path directory, boolean fnaBuild) throws IOException {
		Files.createDirectories(directory.resolve("Content"));
		for (String folder : CelesteIntake.REQUIRED_CONTENT_FOLDERS) {
			Files.createDirectories(directory.resolve("Content").resolve(folder));
		}
		Files.writeString(directory.resolve("Celeste.exe"), "exe", StandardCharsets.UTF_8);
		if (fnaBuild) {
			Files.writeString(directory.resolve("FNA.dll"), "fna", StandardCharsets.UTF_8);
		}
	}

	@Test
	void parsesEveryLibraryFromTheListing(@TempDir Path root) throws IOException {
		List<String> paths = InstallDiscovery.parseLibraryPaths("""
				"libraryfolders"
				{
					"0"
					{
						"path"		"C:\\\\Program Files (x86)\\\\Steam"
					}
					"1"
					{
						"path"		"E:\\\\SteamLibrary"
					}
				}
				""");

		assertEquals(List.of("C:\\Program Files (x86)\\Steam", "E:\\SteamLibrary"), paths);
	}

	@Test
	void findsTheFnaBuildAmongCandidates(@TempDir Path root) throws IOException {
		Path steam = createSteamLayout(root);
		Path fnaInstall = root.resolve("LibraryB/steamapps/common/Celeste");

		InstallDiscovery.DiscoveryResult result = InstallDiscovery.findFnaInstall(
				List.of(root.resolve("LibraryA/steamapps/common/Celeste"), fnaInstall));

		assertTrue(result.found());
		assertEquals(fnaInstall, result.installDirectory());
		assertNull(result.problem());
	}

	@Test
	void anXnaBuildIsRefusedWithTheReason(@TempDir Path root) throws IOException {
		Path steam = createSteamLayout(root);
		Path xnaInstall = root.resolve("LibraryA/steamapps/common/Celeste");

		InstallDiscovery.DiscoveryResult result = InstallDiscovery.findFnaInstall(
				List.of(xnaInstall));

		assertFalse(result.found());
		assertNotNull(result.problem());
		assertTrue(result.problem().contains("XNA"), result.problem());
	}

	@Test
	void anEmptyCandidateListIsAQuietMiss(@TempDir Path root) throws IOException {
		InstallDiscovery.DiscoveryResult result =
				InstallDiscovery.findFnaInstall(List.of(root.resolve("nope")));

		assertFalse(result.found());
		assertNull(result.problem());
	}
}
