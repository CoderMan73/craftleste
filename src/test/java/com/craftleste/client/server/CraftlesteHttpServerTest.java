package com.craftleste.client.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone probe for the local server: the cross-origin isolation headers, the two-root split,
 * the wasm chunk MIME type, the traversal guard and the mod-owned endpoints, all exercised over
 * real HTTP without a browser (PLAN.md M2 Probe 2).
 */
class CraftlesteHttpServerTest {

	@TempDir
	Path work;

	private CraftlesteHttpServer server;
	// HTTP/1.1 on purpose: the default HTTP/2 client sends an h2c upgrade the JDK's HttpServer
	// answers with a 400, which has nothing to do with the mod.
	private final HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	@BeforeEach
	void startServer() throws IOException {
		Path runtime = work.resolve("runtime");
		Files.createDirectories(runtime.resolve("_framework"));
		Files.writeString(runtime.resolve("index.html"), "<html>port</html>", StandardCharsets.UTF_8);
		Files.write(runtime.resolve("_framework/dotnet.native.test.wasm0"),
				new byte[]{0x00, 0x61, 0x73, 0x6d});

		Path celeste = work.resolve("celeste");
		Files.createDirectories(celeste.resolve("Content/Dialog"));
		Files.writeString(celeste.resolve("Celeste.exe"), "not a real exe", StandardCharsets.UTF_8);
		Files.writeString(celeste.resolve("Celeste.exe.config"), "<config/>", StandardCharsets.UTF_8);
		Files.writeString(celeste.resolve("Content/Dialog/prologue.txt"), "chapter", StandardCharsets.UTF_8);

		server = new CraftlesteHttpServer(runtime, celeste);
		assertTrue(server.start(0), "server should have started");
	}

	@AfterEach
	void stopServer() {
		server.stop();
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		// The base URL already ends in a slash; adding the path's own one would send a "//" target,
		// which the JDK's HttpServer rejects outright.
		String url = server.getBaseUrl() + (path.startsWith("/") ? path.substring(1) : path);
		return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void everyResponseCarriesTheIsolationHeaders() throws IOException, InterruptedException {
		for (String path : new String[]{"/", "/Celeste.exe", "/craftleste/bootstrap.html", "/missing"}) {
			HttpResponse<String> response = get(path);
			assertEquals("same-origin", response.headers().firstValue("Cross-Origin-Opener-Policy").orElse(null),
					"missing COOP on " + path);
			assertEquals("require-corp", response.headers().firstValue("Cross-Origin-Embedder-Policy").orElse(null),
					"missing COEP on " + path);
			assertEquals("cross-origin", response.headers().firstValue("Cross-Origin-Resource-Policy").orElse(null),
					"missing CORP on " + path);
		}
	}

	@Test
	void nothingIsCached() throws IOException, InterruptedException {
		assertEquals("no-store", get("/").headers().firstValue("Cache-Control").orElse(null));
	}

	@Test
	void theRuntimeRootIsServedAtTheRoot() throws IOException, InterruptedException {
		assertEquals("<html>port</html>", get("/").body());
		assertEquals("text/html; charset=utf-8", get("/").headers().firstValue("Content-Type").orElse(null));
	}

	@Test
	void thePlayersFilesAreServedNextToTheRuntime() throws IOException, InterruptedException {
		assertEquals(200, get("/Celeste.exe").statusCode());
		assertEquals("not a real exe", get("/Celeste.exe").body());
		assertEquals(200, get("/Content/Dialog/prologue.txt").statusCode());
	}

	@Test
	void everyInstallRootFileIsServedNotJustTheKnownNames() throws IOException, InterruptedException {
		// The intake copies every sibling of the exe (the pdb, the config file, the side
		// assemblies), and the bootstrap copies whatever the manifest lists, so a player file with
		// no special name must still resolve from the player's folder.
		HttpResponse<String> response = get("/Celeste.exe.config");
		assertEquals(200, response.statusCode());
		assertEquals("<config/>", response.body());
	}

	@Test
	void wasmChunksGetTheWasmMimeType() throws IOException, InterruptedException {
		HttpResponse<String> response = get("/_framework/dotnet.native.test.wasm0");
		assertEquals(200, response.statusCode());
		assertEquals("application/wasm", response.headers().firstValue("Content-Type").orElse(null));
	}

	@Test
	void aMissingChunkIsAPlain404NotAPage() throws IOException, InterruptedException {
		HttpResponse<String> response = get("/_framework/dotnet.native.test.wasm9");
		assertEquals(404, response.statusCode());
		assertFalse(response.body().contains("<html"), "a 404 must never answer with HTML: the split-wasm loader stops at the first non-200");
		assertEquals("text/plain", response.headers().firstValue("Content-Type").orElse(null));
	}

	@Test
	void headGetsTheHeadersWithoutABody() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(server.getBaseUrl() + "Celeste.exe"))
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		// The JDK's server does not emit a Content-Length for a body it was told not to receive,
		// and the exe's MIME type comes from the platform's table, so this pins only what the mod
		// itself promises: a real 200 with no body and some content type.
		assertEquals(200, response.statusCode());
		assertTrue(response.body().isEmpty());
		assertNotNull(response.headers().firstValue("Content-Type").orElse(null));
	}

	@Test
	void traversalOutsideBothRootsIsRefused() throws IOException, InterruptedException {
		assertEquals(404, get("/Content/../../outside.txt").statusCode());
		assertEquals(404, get("/../secret.txt").statusCode());
	}

	@Test
	void theManifestListsThePlayersFiles() throws IOException, InterruptedException {
		String body = get("/craftleste/manifest.json").body();
		assertTrue(body.contains("Celeste.exe"), body);
		assertTrue(body.contains("Content/Dialog/prologue.txt"), body);
		assertFalse(body.contains("index.html"), "the manifest must not list the runtime's own files");
		assertEquals("application/json", get("/craftleste/manifest.json").headers().firstValue("Content-Type").orElse(null));
	}

	@Test
	void theBootstrapPageIsServedFromMemory() throws IOException, InterruptedException {
		HttpResponse<String> response = get("/craftleste/bootstrap.html");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("/craftleste/manifest.json"));
		assertTrue(response.body().contains("navigator.storage.getDirectory"));
		assertTrue(response.body().contains("ContentExists"));
	}

	@Test
	void theThreadedWasmProbeIsServedFromMemory() throws IOException, InterruptedException {
		HttpResponse<String> response = get("/craftleste/probe2.html");
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("SharedArrayBuffer"));
		assertTrue(response.body().contains("crossOriginIsolated"));
	}
}
