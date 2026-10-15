package com.craftleste.client;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Reads and runs JavaScript in whichever browser screen is open.
 *
 * <p>This exists because the browser is the only place the game's state is observable: the port
 * stores its files in OPFS, its pickers are unavailable, and a report from the user is a second-hand
 * description of a screen an agent cannot see. Two channels are used, both inherited from JCEF:
 * {@code executeJavaScript} to run code in the page, and a display handler to get a result back -
 * the expression's value is written into the page's title, and the title change is captured here.
 * Warnings and errors the page logs are also relayed into the Minecraft log, so a port boot failure
 * is visible without the user finding it.
 */
public final class PageInspector {

	private static final Logger LOGGER = LoggerFactory.getLogger("craftleste");

	/** Pages set their title to this prefix to hand a result back to the mod. */
	public static final String TITLE_PREFIX = "craftleste-probe:";
	private static final long RESULT_TIMEOUT_MILLIS = 10_000;

	private static final AtomicReference<String> pendingResult = new AtomicReference<>();
	private static volatile boolean installed;

	private PageInspector() {
	}

	/**
	 * What the page thinks its own geometry is, which is the only way to tell a rendering problem
	 * from a layout problem: a stretched 320x180 surface and a page that laid itself out narrow look
	 * identical in a screenshot, and only one of them is the browser's fault.
	 */
	public static final String LAYOUT_INSPECTION = """
			const out = [];
			try {
			  out.push('inner=' + window.innerWidth + 'x' + window.innerHeight);
			  out.push('docClient=' + document.documentElement.clientWidth + 'x' + document.documentElement.clientHeight);
			  out.push('dpr=' + window.devicePixelRatio + ' screen=' + screen.width + 'x' + screen.height);
			  out.push('scroll=' + document.documentElement.scrollWidth + 'x' + document.documentElement.scrollHeight);
			  const buttons = document.querySelectorAll('button');
			  out.push('buttons=' + buttons.length);
			  const canvas = document.querySelector('canvas');
			  if (canvas) {
			    const rect = canvas.getBoundingClientRect();
			    out.push('canvas backing=' + canvas.width + 'x' + canvas.height
			      + ' css=' + Math.round(rect.width) + 'x' + Math.round(rect.height)
			      + ' at ' + Math.round(rect.left) + ',' + Math.round(rect.top));
			  } else {
			    out.push('canvas=none');
			  }
			  const game = document.querySelector('.game');
			  if (game) {
			    const rect = game.getBoundingClientRect();
			    out.push('.game css=' + Math.round(rect.width) + 'x' + Math.round(rect.height)
			      + ' at ' + Math.round(rect.left) + ',' + Math.round(rect.top));
			  }
			  const bar = document.querySelector('body > div > div');
			  if (bar) {
			    const rect = bar.getBoundingClientRect();
			    out.push('topbar=' + Math.round(rect.width) + 'x' + Math.round(rect.height));
			  }
			  out.push('bodyChildren=' + document.body.children.length);
			  out.push('htmlStart=' + document.documentElement.outerHTML.substring(0, 120).replace(/\\s+/g, ' '));
			} catch (error) {
			  out.push('layout probe threw ' + error);
			}
			return out.join(' | ');
			""";

	/**
	 * The port's own content check, expressed as a probe: what the OPFS root actually holds right
	 * now, which of the required directories and files are missing, and how much storage the origin
	 * has used. Mirrors {@code hasContent()} in the port's fs.tsx plus the patcher's marker file.
	 *
	 * <p>The second half reads the .NET runtime's own view: the port exposes its Emscripten
	 * filesystem, so {@code /libsdl} (the OPFS mount) and {@code /bin} (the loader's assembly
	 * symlinks, built by MountDlls from the runtime's boot config) can be listed from the page.
	 * That is the difference between "the files are in storage" and "the runtime can see them",
	 * which is where a stale copy or a half-built /bin shows up.
	 */
	public static final String OPFS_INSPECTION = """
			const out = [];
			out.push('href=' + location.href);
			out.push('iso=' + window.crossOriginIsolated);
			try {
			  const root = await navigator.storage.getDirectory();
			  const names = [];
			  for await (const [name, entry] of root) names.push(entry.kind + ':' + name);
			  out.push('rootEntries=' + names.join(','));
			  try {
			    const content = await root.getDirectoryHandle('Content', { create: false });
			    const sub = [];
			    for await (const [name, entry] of content) sub.push(entry.kind + ':' + name);
			    out.push('contentEntries=' + sub.join(','));
			    const missing = [];
			    for (const d of ['Dialog','Effects','FMOD','Graphics','Maps','Monocle','Overworld','Tutorials']) {
			      try { await content.getDirectoryHandle(d, { create: false }); } catch { missing.push(d); }
			    }
			    out.push('missingDirs=' + (missing.length ? missing.join(',') : '(none)'));
			  } catch (error) { out.push('Content: MISSING (' + error.name + ')'); }
			  for (const f of ['.ContentExists', 'Celeste.exe', 'CustomCeleste.dll', 'FNA.dll']) {
			    try { await root.getFileHandle(f, { create: false }); out.push('has ' + f); }
			    catch { out.push('no ' + f); }
			  }
			  out.push('stamp=' + (localStorage.getItem('craftleste.installed') || '(none)'));
			  const estimate = await navigator.storage.estimate();
			  out.push('usage=' + estimate.usage + ' quota=' + estimate.quota);
			} catch (error) {
			  out.push('opfs FAILED: ' + error.name + ' ' + error.message);
			}

			// The runtime's own view. The port mounts the OPFS at /libsdl and symlinks the boot
			// config's assemblies into /bin; a mismatch between the two halves is the bug.
			try {
			  const wasm = (self).wasm;
			  if (!wasm || !wasm.FS) { out.push('wasm.FS unavailable (runtime not started?)'); }
			  else {
			    for (const dir of ['/libsdl', '/bin']) {
			      try {
			        const entries = wasm.FS.readdir(dir).filter((n) => n !== '.' && n !== '..');
			        out.push(dir + ': ' + (entries.length > 60 ? entries.slice(0, 60).join(',') + ', ...(' + entries.length + ')' : entries.join(',')));
			      } catch (error) { out.push(dir + ': unreadable (' + error.name + ')'); }
			    }
			    for (const f of ['/libsdl/FNA.dll', '/bin/FNA.dll', '/libsdl/CustomCeleste.dll', '/libsdl/Celeste.exe']) {
			      try { wasm.FS.stat(f); out.push('fs has ' + f); }
			      catch { out.push('fs MISSING ' + f); }
			    }
			    const config = (self).wasm && wasm.config ? wasm.config : null;
			    if (config && config.mainAssemblyName) out.push('runtime mainAssembly=' + config.mainAssemblyName);
			  }
			} catch (error) {
			  out.push('wasm probe threw ' + error);
			}

			return out.join(' | ');
			""";

	private static final CefDisplayHandlerAdapter RELAY = new CefDisplayHandlerAdapter() {
		@Override
		public void onTitleChange(CefBrowser browser, String title) {
			if (title != null && title.startsWith(TITLE_PREFIX)) {
				pendingResult.set(title.substring(TITLE_PREFIX.length()));
			}
		}

		@Override
		public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity severity,
				String message, String source, int line) {
			// Warnings and errors, plus the probe's own tagged lines, which are the only INFO-level
			// noise worth having: a full relay would drown the log during a game boot, and the probe
			// lines are the channel that still works if the title ever stops relaying.
			boolean tagged = message != null && message.startsWith("[craftleste probe]");
			if (tagged || severity == CefSettings.LogSeverity.LOGSEVERITY_WARNING
					|| severity == CefSettings.LogSeverity.LOGSEVERITY_ERROR) {
				LOGGER.info("[browser {}] {} ({}:{})", severity, message, source, line);
			}
			return false;
		}
	};

	private static synchronized void install() {
		if (installed || !Rinku.isInitialized()) {
			return;
		}
		Rinku.getClient().addDisplayHandler(RELAY);
		installed = true;
	}

	/**
	 * Runs an async expression in the given browser and hands its value to {@code onResult} on the
	 * client thread. The expression may {@code await}: it is wrapped into an async function whose
	 * resolved value becomes the page title, which the display relay captures.
	 *
	 * <p>Async because it is called from the screen's key handler, where the client thread must not
	 * be held while the page settles. One probe at a time: the result relay is a single slot.
	 */
	public static void evaluateAsync(RinkuBrowser browser, String expression, Consumer<String> onResult) {
		install();
		if (browser == null) {
			deliver(onResult, "(no browser is open)");
			return;
		}
		pendingResult.set(null);
		new Thread(() -> {
			// A first attempt can land while the page is still navigating, which drops the script
			// without a trace, so the probe repeats before reporting nothing.
			String result = attempt(browser, expression);
			if (result == null) {
				for (int retry = 0; retry < 2 && result == null; retry++) {
					sleep(1000);
					result = attempt(browser, expression);
				}
			}
			String reported = result != null ? result : "(no result title within 10s)";
			try {
				deliver(onResult, reported);
			} catch (RuntimeException failure) {
				LOGGER.warn("Could not deliver a page-evaluation result", failure);
			}
		}, "craftleste-page-eval").start();
	}

	private static String attempt(RinkuBrowser browser, String expression) {
		try {
			// The heartbeat and the result are both logged: if the title channel is the broken half,
			// the log still gets the answer, and if nothing is logged the script never ran.
			//
			// The expression is wrapped in its own async function body, NOT in parentheses: the
			// probes are statement blocks (`const out = []; ... return ...`), and `await (const ...)`
			// is a syntax error - which is indistinguishable from the probe working until you notice
			// the browser logging "Unexpected token 'const'" against the page, once per retry.
			browser.executeJavaScript(
					"(async () => { console.log('[craftleste probe] evaluating'); try { const result = await (async () => {"
							+ expression + "})(); document.title = 'craftleste-probe:' + result;"
							+ " console.log('[craftleste probe] result: ' + result); } catch (error) {"
							+ " document.title = 'craftleste-probe:THREW ' + (error && error.stack ? error.stack : error);"
							+ " console.log('[craftleste probe] THREW ' + (error && error.stack ? error.stack : error)); } })();",
					"craftleste", 1);
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not run the page evaluation", failure);
			return "THREW " + failure;
		}

		long deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MILLIS;
		while (pendingResult.get() == null && System.currentTimeMillis() < deadline) {
			sleep(50);
		}
		return pendingResult.getAndSet(null);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Runs the same expression and returns its value, blocking the calling thread. Only for a
	 * command, which already runs on the client thread and has nothing else to render.
	 */
	public static String evaluate(RinkuBrowser browser, String expression) {
		install();
		if (browser == null) {
			return "(no browser is open)";
		}
		pendingResult.set(null);
		browser.executeJavaScript(
				"(async () => { try { const result = await (" + expression + ");"
						+ " document.title = 'craftleste-probe:' + result; } catch (error) {"
						+ " document.title = 'craftleste-probe:THREW ' + (error && error.stack ? error.stack : error); } })();",
				"craftleste", 1);

		long deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MILLIS;
		while (pendingResult.get() == null && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		String result = pendingResult.getAndSet(null);
		return result != null ? result : "(no result title within " + (RESULT_TIMEOUT_MILLIS / 1000) + "s)";
	}

	private static void deliver(Consumer<String> onResult, String result) {
		Minecraft.getInstance().execute(() -> onResult.accept(result));
	}
}
