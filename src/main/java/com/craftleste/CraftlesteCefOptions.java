package com.craftleste;

import org.cef.callback.CefCommandLine;
import org.cef.handler.CefAppHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chromium switches craftleste needs before the embedded browser starts.
 *
 * <p>Rinku owns CEF's startup and its own switch list, with no hook for a mod - but JCEF lets an
 * app handler edit the command line during startup, and Rinku only starts CEF on the first GUI
 * render, long after mod initializers have run. Registering here therefore lands in time.
 *
 * <p>Two switches matter for the Webleste port:
 *
 * <ul>
 *   <li>{@code enable-features=SharedArrayBuffer} - Chromium gates SharedArrayBuffer on cross-origin
 *       isolation, and .NET's threaded wasm checks for the constructor at startup. Our server does
 *       send COOP/COEP, but CEF without a concrete agent cluster for the origin grants only logical
 *       isolation, which does not expose the buffer; the feature flag is the documented bypass.</li>
 *   <li>{@code isolate-origins=...} - pins the served origin to its own process, which is the other
 *       route to real (concrete) isolation. The port is registered with the default port because the
 *       command line is fixed before anything knows which port the local server will bind; the
 *       feature flag covers the rest.</li>
 * </ul>
 */
public final class CraftlesteCefOptions {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	private static final String SERVED_ORIGIN = "http://localhost:18081";

	private static volatile boolean registered;

	private CraftlesteCefOptions() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		try {
			org.cef.CefApp.addAppHandler(new CefAppHandlerAdapter(new String[0]) {
				@Override
				public void onBeforeCommandLineProcessing(String processType, CefCommandLine commandLine) {
					commandLine.appendSwitchWithValue("enable-features", "SharedArrayBuffer");
					if (!commandLine.hasSwitch("isolate-origins")) {
						commandLine.appendSwitchWithValue("isolate-origins", SERVED_ORIGIN);
					}
					LOGGER.debug("Added craftleste Chromium switches for {}: {}", processType,
							commandLine.getCommandLineString());
				}
			});
			registered = true;
			LOGGER.info("Registered craftleste Chromium switches (SharedArrayBuffer, isolated origin)");
		} catch (IllegalStateException | UnsatisfiedLinkError failure) {
			// Either CEF is already running (Rinku started first somehow) or the native layer is
			// absent; the game then reports missing threads, which is the same failure as before.
			LOGGER.warn("Could not register the Chromium switches; threaded wasm may be unavailable", failure);
		}
	}
}
