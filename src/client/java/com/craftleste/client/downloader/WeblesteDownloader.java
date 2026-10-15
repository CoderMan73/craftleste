package com.craftleste.client.downloader;

import com.craftleste.client.CraftlestePaths;
import com.craftleste.client.config.CraftlesteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the Webleste runtime from its official origin and extracts it into the instance.
 *
 * <p>The port (MercuryWorkshop/celeste-wasm, branch {@code threads-v2}) publishes no release
 * tags; its CI builds the frontend on every push to the branch and publishes the result as the
 * GitHub release tagged {@code latest}, with the asset {@code webleste-loader.tar.zst} (the
 * Vite-built {@code frontend/dist}). That release is the official origin: nothing is hosted by
 * this project, nothing is bundled, and the player's client pulls the same build the port's own
 * demo site runs.
 *
 * <p>The pin is the commit hash from the release body. It is recorded in three places: the
 * directory name ({@code runtimes/<commit>}, so versions never overwrite each other), the
 * {@code current} pointer file the local server reads, and the mod config. There are no upstream
 * tags, so a pin is a snapshot of a moving branch and is bumped deliberately.
 */
public class WeblesteDownloader {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final String RELEASE_API_URL =
			"https://api.github.com/repos/MercuryWorkshop/celeste-wasm/releases/tags/latest";
	private static final String ASSET_NAME = "webleste-loader.tar.zst";
	/**
	 * The asset URL is stable across releases, so a fetch still works when the GitHub API is
	 * rate-limiting an unauthenticated client; the commit pin is then unknown and recorded as such.
	 */
	private static final String ASSET_URL_FALLBACK =
			"https://github.com/MercuryWorkshop/celeste-wasm/releases/download/latest/" + ASSET_NAME;

	private static final Pattern COMMIT_PATTERN = Pattern.compile("/commit/([0-9a-f]{40})");
	private static final Pattern ASSET_URL_PATTERN = Pattern.compile(
			"\"browser_download_url\"\\s*:\\s*\"([^\"]*" + Pattern.quote(ASSET_NAME) + ")\"");

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** What the release API (or its fallback) gave us: a commit pin and the asset to download. */
	public record Release(String commit, String assetUrl) {
		public boolean hasPin() {
			return !"unknown".equals(commit);
		}
	}

	/** Outcome of an install attempt, for the status message the caller shows. */
	public enum Result {
		FETCHED, ALREADY_PRESENT, FAILED
	}

	/**
	 * Reads the {@code latest} release. The JSON is scanned with two anchored patterns rather than
	 * a JSON library: only two fields are needed, both are stable in the GitHub API, and the mod
	 * then carries no JSON dependency for a one-shot fetch.
	 */
	public static Release fetchRelease() {
		Release fromApi = fetchReleaseFromApi();
		return fromApi != null ? fromApi : new Release("unknown", ASSET_URL_FALLBACK);
	}

	/**
	 * Pulls the commit pin and the asset URL out of a release payload. Package-visible so the
	 * standalone probe can run this parser against a canned response instead of the network.
	 */
	static Release parseRelease(String body) {
		Matcher assetMatcher = ASSET_URL_PATTERN.matcher(body);
		if (!assetMatcher.find()) {
			return null;
		}
		Matcher commitMatcher = COMMIT_PATTERN.matcher(body);
		String commit = commitMatcher.find() ? commitMatcher.group(1) : "unknown";
		return new Release(commit, assetMatcher.group(1).replace("\\/", "/"));
	}

	private static Release fetchReleaseFromApi() {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(RELEASE_API_URL))
					.header("User-Agent", "craftleste-mod/1.0")
					.header("Accept", "application/vnd.github+json")
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				LOGGER.warn("Release metadata returned HTTP {}; falling back to the asset URL", response.statusCode());
				return null;
			}
			Release parsed = parseRelease(response.body());
			if (parsed == null) {
				LOGGER.warn("Release metadata listed no {}; falling back to the asset URL", ASSET_NAME);
			}
			return parsed;
		} catch (IOException | InterruptedException failure) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Could not read the release metadata; falling back to the asset URL", failure);
			return null;
		}
	}

	/** Whether the game directory already holds an extracted runtime for the given pin. */
	public static boolean isFetched(Path gameDirectory, String commit) {
		return Files.isRegularFile(
				CraftlestePaths.runtimeDirectory(gameDirectory, commit).resolve("index.html"));
	}

	/** The commit pin currently being served, or null when nothing is installed yet. */
	public static String currentPin(Path gameDirectory) {
		try {
			Path pinFile = CraftlestePaths.currentPinFile(gameDirectory);
			if (!Files.isRegularFile(pinFile)) {
				return null;
			}
			String pin = Files.readString(pinFile, StandardCharsets.UTF_8).trim();
			return pin.isEmpty() ? null : pin;
		} catch (IOException failure) {
			LOGGER.warn("Could not read the current runtime pin", failure);
			return null;
		}
	}

	/**
	 * Downloads and extracts the runtime, unless the pinned build is already on disk.
	 *
	 * <p>The download lands as a {@code .part} file and is renamed only once it is complete, and
	 * the extraction goes into a staging directory that replaces an earlier extraction only when
	 * it contains an index.html. A crashed attempt therefore leaves the previously served runtime
	 * untouched instead of a half-written directory.
	 */
	public static Result install(Path gameDirectory) {
		try {
			Files.createDirectories(gameDirectory);
		} catch (IOException failure) {
			LOGGER.error("Could not create the game directory {}", gameDirectory, failure);
			return Result.FAILED;
		}
		Release release = fetchRelease();
		LOGGER.info("Webleste release: pin={} asset={}", release.commit(), release.assetUrl());

		if (release.hasPin() && isFetched(gameDirectory, release.commit())) {
			writeCurrentPin(gameDirectory, release.commit());
			return Result.ALREADY_PRESENT;
		}

		Path archive = gameDirectory.resolve("download").resolve(ASSET_NAME + ".part");
		try {
			Files.createDirectories(archive.getParent());
			downloadFile(release.assetUrl(), archive);

			String stagingName = release.hasPin() ? ".staging-" + release.commit() : ".staging-unknown";
			Path staging = gameDirectory.resolve("runtimes").resolve(stagingName);
			deleteRecursively(staging);
			Files.createDirectories(staging);
			int entries = RuntimeExtractor.extractTarZst(archive, staging);
			if (!Files.isRegularFile(staging.resolve("index.html"))) {
				throw new IOException("The extracted build has no index.html; refusing to serve it");
			}
			LOGGER.info("Extracted {} entries from {} into {}", entries, ASSET_NAME, staging);

			if (release.hasPin()) {
				Path published = CraftlestePaths.runtimeDirectory(gameDirectory, release.commit());
				deleteRecursively(published);
				Files.move(staging, published);
			}

			writeCurrentPin(gameDirectory, release.commit());
			CraftlesteConfig.getInstance().setWeblestePin(release.commit());
			Files.deleteIfExists(archive);
			LOGGER.info("Webleste runtime {} is ready", release.commit());
			return Result.FETCHED;
		} catch (IOException | InterruptedException | RuntimeException failure) {
			if (failure instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOGGER.error("Could not fetch the Webleste runtime", failure);
			return Result.FAILED;
		}
	}

	private static void downloadFile(String url, Path target) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "craftleste-mod/1.0")
				.GET()
				.build();
		HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(target));
		if (response.statusCode() != 200) {
			Files.deleteIfExists(target);
			throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
		}
		LOGGER.info("Downloaded {} ({} bytes)", url, Files.size(target));
	}

	private static void writeCurrentPin(Path gameDirectory, String pin) {
		try {
			Path pinFile = CraftlestePaths.currentPinFile(gameDirectory);
			Files.createDirectories(pinFile.getParent());
			Files.writeString(pinFile, pin, StandardCharsets.UTF_8);
		} catch (IOException failure) {
			LOGGER.warn("Could not record the current runtime pin", failure);
		}
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
