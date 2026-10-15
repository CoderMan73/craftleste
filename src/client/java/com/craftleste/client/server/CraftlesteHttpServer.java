package com.craftleste.client.server;

import com.craftleste.client.bootstrap.BootstrapPages;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The local server that feeds the embedded browser.
 *
 * <p>Two roots are served under one origin, which keeps the port's own URL layout intact while
 * keeping the player's files separate: the player's files take precedence and everything else
 * comes from the extracted Webleste build. The port's index page derives its own relative paths
 * from its URL (it imports {@code ../_framework/dotnet.js}), so the runtime root is served at
 * {@code /} and the port is none the wiser.
 *
 * <p>Every response, errors included, carries the cross-origin isolation headers the port's
 * threaded wasm requires (COOP/COEP gate SharedArrayBuffer, CORP lets the split wasm chunks cross
 * the worker boundary), and nothing is cached: the served directory is version-pinned on disk, so
 * a runtime change must never be answered from a stale browser cache (the craftlatro 440f734
 * lesson, carried over).
 */
public class CraftlesteHttpServer {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	public static final int DEFAULT_PORT = 18081;
	private static final int MAX_PORT_ATTEMPTS = 10;
	private static final String MOD_CONTEXT = "/craftleste/";

	private final Path runtimeRoot;
	private final Path celesteRoot;

	private HttpServer server;
	private int port;
	private boolean running;

	public CraftlesteHttpServer(Path runtimeRoot, Path celesteRoot) {
		this.runtimeRoot = runtimeRoot.normalize().toAbsolutePath();
		this.celesteRoot = celesteRoot.normalize().toAbsolutePath();
	}

	public boolean start() {
		return start(DEFAULT_PORT);
	}

	public boolean start(int preferredPort) {
		if (running) {
			return false;
		}

		for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
			int candidatePort = preferredPort + attempt;
			try {
				server = HttpServer.create(new InetSocketAddress("localhost", candidatePort), 0);
				server.createContext("/", new GameFileHandler());
				server.createContext(MOD_CONTEXT + "bootstrap.html",
						new FixedPageHandler(BootstrapPages.BOOTSTRAP_HTML));
				server.createContext(MOD_CONTEXT + "probe2.html",
						new FixedPageHandler(BootstrapPages.THREADED_WASM_PROBE_HTML));
				server.createContext(MOD_CONTEXT + "manifest.json", new ManifestHandler());
				server.setExecutor(newServerExecutor());
				server.start();
				running = true;
				// Read the port back rather than trusting the requested one: 0 asks the OS for any
				// free port, which is what a standalone probe uses.
				this.port = server.getAddress().getPort();
				LOGGER.info("craftleste local server on {} (runtime={}, celeste={})",
						candidatePort, runtimeRoot, celesteRoot);
				return true;
			} catch (IOException failure) {
				LOGGER.warn("Port {} unavailable, trying the next one", candidatePort);
			}
		}
		LOGGER.error("Could not start the local server on ports {}-{}",
				preferredPort, preferredPort + MAX_PORT_ATTEMPTS - 1);
		return false;
	}

	public void stop() {
		if (server != null && running) {
			server.stop(0);
			running = false;
			LOGGER.info("craftleste local server stopped");
		}
	}

	public String getBaseUrl() {
		return "http://localhost:" + port + "/";
	}

	public boolean isRunning() {
		return running;
	}

	private ExecutorService newServerExecutor() {
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable task) {
				Thread thread = new Thread(task, "craftleste-http-" + threadNumber.getAndIncrement());
				thread.setDaemon(true);
				return thread;
			}
		};
		return Executors.newFixedThreadPool(8, threadFactory);
	}

	/**
	 * Serves the game files, splitting the two roots: the player's files take precedence and the
	 * extracted build is the fallback for everything else. A missing file 404s and stays 404 - the
	 * port's split-wasm loader stops appending chunk numbers at the first non-200, so a 404 must
	 * never be answered with a page.
	 */
	private class GameFileHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String path = exchange.getRequestURI().getPath();
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			if (path.isEmpty() || path.endsWith("/")) {
				path = path + "index.html";
			}

			Path file = resolveGameFile(path);
			if (file == null || Files.isDirectory(file) || !Files.isRegularFile(file)) {
				sendError(exchange, 404, "Not Found");
				return;
			}

			setIsolationHeaders(exchange);
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.getResponseHeaders().set("Content-Type", mimeTypeFor(file));

			if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(200, Files.size(file));
				exchange.close();
				return;
			}

			// Streamed chunked rather than buffered whole: the copy runs several files at once and
			// the biggest of them is the whole game executable. Com.sun's server takes 0 as "no
			// fixed length, use chunked encoding".
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				Files.copy(file, body);
			}
		}

		/**
		 * The player's folder first, the runtime build second. Precedence rather than a name list,
		 * because the install root holds more than the exe and Content (the loader reads
		 * Celeste.exe, but the intake copies every sibling: the pdb, the config file, the side
		 * assemblies) and the port's dist contains none of those names, so nothing is shadowed.
		 * Both candidates are checked against their own root, so no path escapes either.
		 */
		private Path resolveGameFile(String path) {
			String normalized = Path.of(path).normalize().toString().replace('\\', '/');
			if (normalized.isEmpty() || normalized.equals(".")) {
				return null;
			}
			Path fromPlayer = celesteRoot.resolve(normalized).normalize().toAbsolutePath();
			if (fromPlayer.startsWith(celesteRoot) && Files.isRegularFile(fromPlayer)) {
				return fromPlayer;
			}
			Path fromRuntime = runtimeRoot.resolve(normalized).normalize().toAbsolutePath();
			if (fromRuntime.startsWith(runtimeRoot) && Files.isRegularFile(fromRuntime)) {
				return fromRuntime;
			}
			return null;
		}
	}

	/**
	 * The file list the bootstrap page copies into OPFS: every file in the player's folder, so the
	 * page needs no directory listing that HTTP does not have.
	 */
	private class ManifestHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			StringBuilder json = new StringBuilder("{\"files\":[");
			boolean first = true;
			if (Files.isDirectory(celesteRoot)) {
				try (Stream<Path> files = Files.walk(celesteRoot)) {
					for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
						String name = celesteRoot.relativize(file).toString().replace('\\', '/');
						if (!first) {
							json.append(',');
						}
						first = false;
						json.append("{\"name\":\"").append(name)
								.append("\",\"size\":").append(sizeOf(file)).append('}');
					}
				}
			}
			json.append("]}");

			byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);
			setIsolationHeaders(exchange);
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, content.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(content);
			}
		}

		private long sizeOf(Path file) {
			try {
				return Files.size(file);
			} catch (IOException failure) {
				return 0;
			}
		}
	}

	/** A mod-owned page served from memory, so nothing is written into the game directory for it. */
	private record FixedPageHandler(String html) implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			byte[] content = html.getBytes(StandardCharsets.UTF_8);
			setIsolationHeaders(exchange);
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, content.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(content);
			}
		}
	}

	private static void setIsolationHeaders(HttpExchange exchange) {
		exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
		exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
		exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "cross-origin");
	}

	private static String mimeTypeFor(Path file) {
		String name = file.getFileName().toString().toLowerCase();
		if (name.endsWith(".wasm") || name.matches(".*\\.wasm\\d+$")) {
			// The port splits its native wasm into numbered chunks (dotnet.native.<hash>.wasm0,
			// .wasm1, ...), which the runtime requests with the index appended to the default URI.
			return "application/wasm";
		}
		if (name.endsWith(".js") || name.endsWith(".mjs")) {
			return "application/javascript";
		}
		if (name.endsWith(".css")) {
			return "text/css";
		}
		if (name.endsWith(".html")) {
			return "text/html; charset=utf-8";
		}
		if (name.endsWith(".json")) {
			return "application/json";
		}
		if (name.endsWith(".webp")) {
			return "image/webp";
		}
		if (name.endsWith(".woff2")) {
			return "font/woff2";
		}
		if (name.endsWith(".woff")) {
			return "font/woff";
		}
		if (name.endsWith(".ttf")) {
			return "font/ttf";
		}
		if (name.endsWith(".dat")) {
			return "application/octet-stream";
		}
		try {
			String probed = Files.probeContentType(file);
			return probed != null ? probed : "application/octet-stream";
		} catch (IOException failure) {
			return "application/octet-stream";
		}
	}

	private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
		setIsolationHeaders(exchange);
		exchange.getResponseHeaders().set("Content-Type", "text/plain");
		exchange.sendResponseHeaders(code, message.length());
		try (OutputStream body = exchange.getResponseBody()) {
			body.write(message.getBytes(StandardCharsets.UTF_8));
		}
	}
}
