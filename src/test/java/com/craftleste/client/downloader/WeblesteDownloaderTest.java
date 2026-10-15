package com.craftleste.client.downloader;

import com.craftleste.client.CraftlestePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone probe for the release-metadata parsing and the on-disk pin bookkeeping - the parts
 * of the downloader that are decision logic rather than network I/O (PLAN.md M2 Probe 2, the
 * standalone-probe pattern).
 */
class WeblesteDownloaderTest {

	/** Shaped like the real {@code .../releases/tags/latest} payload, down to the escaped slashes. */
	private static final String RELEASE_JSON = """
			{
			  "tag_name": "latest",
			  "body": "https://github.com/MercuryWorkshop/celeste-wasm/commit/b4b19eb24a8499e4de96aee79f1272ffdb57281f cache _framework and vite assets",
			  "assets": [
			    {
			      "name": "webleste-loader.tar.zst",
			      "browser_download_url": "https:\\/\\/github.com\\/MercuryWorkshop\\/celeste-wasm\\/releases\\/download\\/latest\\/webleste-loader.tar.zst"
			    }
			  ]
			}
			""";

	@Test
	void parsesCommitPinAndAssetUrl() {
		WeblesteDownloader.Release release = WeblesteDownloader.parseRelease(RELEASE_JSON);

		assertEquals("b4b19eb24a8499e4de96aee79f1272ffdb57281f", release.commit());
		assertTrue(release.hasPin());
		assertEquals(
				"https://github.com/MercuryWorkshop/celeste-wasm/releases/download/latest/webleste-loader.tar.zst",
				release.assetUrl());
	}

	@Test
	void releaseWithoutTheAssetIsUnparseable() {
		assertNull(WeblesteDownloader.parseRelease("{\"assets\": []}"));
	}

	@Test
	void releaseWithoutACommitBodyStillGivesAnAsset() {
		String body = RELEASE_JSON.replace("/commit/b4b19eb24a8499e4de96aee79f1272ffdb57281f", "");
		WeblesteDownloader.Release release = WeblesteDownloader.parseRelease(body);

		assertNotNull(release);
		assertEquals("unknown", release.commit());
		assertFalse(release.hasPin());
	}

	@Test
	void isFetchedLooksForTheExtractedIndex(@TempDir Path gameDirectory) throws Exception {
		Path runtime = CraftlestePaths.runtimeDirectory(gameDirectory, "abc123");
		Files.createDirectories(runtime);
		Files.writeString(runtime.resolve("index.html"), "<html>", StandardCharsets.UTF_8);

		assertTrue(WeblesteDownloader.isFetched(gameDirectory, "abc123"));
		assertFalse(WeblesteDownloader.isFetched(gameDirectory, "def456"));
	}

	@Test
	void currentPinReadsThePointerFile(@TempDir Path gameDirectory) throws Exception {
		assertNull(WeblesteDownloader.currentPin(gameDirectory));

		Files.writeString(CraftlestePaths.currentPinFile(gameDirectory),
				"b4b19eb24a8499e4de96aee79f1272ffdb57281f\n", StandardCharsets.UTF_8);

		assertEquals("b4b19eb24a8499e4de96aee79f1272ffdb57281f",
				WeblesteDownloader.currentPin(gameDirectory));
	}
}
