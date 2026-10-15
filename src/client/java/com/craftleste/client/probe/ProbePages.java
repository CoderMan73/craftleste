package com.craftleste.client.probe;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mod-owned probe pages for the M1 probes (PLAN.md M1; RESEARCH.md unknowns 1 to 3).
 *
 * <p>Probe 1 answers the three questions that decide later milestones: whether the Chromium build
 * inside Rinku can open {@code showDirectoryPicker} (the CEF file-dialog unknown behind the intake
 * choice), whether {@code navigator.storage.getDirectory()} works (the OPFS write path the M2
 * bootstrap page depends on), and whether {@code navigator.getGamepads} sees connected gamepads
 * (the input unknown behind keyboard-first vs gamepad support, RESEARCH.md gap 8).
 *
 * <p>The page is bundled as a {@code data:} URL on purpose: M1 has no local HTTP server yet (that
 * arrives with M2), and a data URL needs no files on disk and no file-dialog to load. If the dev
 * run shows that CEF refuses a top-level data URL navigation, the fallback is already on the plan:
 * serve the same page from the M2 local server.
 */
public final class ProbePages {

	private static final String PROBE_HTML = """
			<!doctype html>
			<html>
			<head>
			<meta charset="utf-8">
			<title>craftleste probe 1</title>
			<style>
			  body { background: #0e1116; color: #e6edf3; font: 14px/1.45 monospace; margin: 16px; }
			  h1 { font-size: 16px; margin: 0 0 8px; }
			  p { max-width: 70em; }
			  button { font: 14px monospace; margin: 0 8px 8px 0; padding: 6px 10px; }
			  pre { background: #000; border: 1px solid #2d3742; padding: 8px; white-space: pre-wrap; max-width: 100em; }
			</style>
			</head>
			<body>
			<h1>craftleste Probe 1 - capability report</h1>
			<p>Three questions decide design choices: does the directory picker work (Celeste asset
			intake), does OPFS work (the bootstrap page writes the game's assets there), and can this
			page read gamepads (input mapping). Press each button once and read the report below.
			Every line is also written to the console, so the log has it too.</p>
			<p>
			<button id="btnDir">1. Directory picker</button>
			<button id="btnOpfs">2. OPFS write + read back</button>
			<button id="btnPads">3. Gamepads</button>
			<button id="btnAll">Run all</button>
			</p>
			<pre id="report">booting probe...</pre>
			<script>
			var reportEl = document.getElementById('report');
			var lines = [];
			function report(text) {
			  lines.push(text);
			  reportEl.textContent = lines.join('\\n');
			  console.log('[craftleste probe] ' + text);
			}
			function errorName(error) {
			  return error && error.name ? error.name : String(error);
			}

			report('page protocol: ' + location.protocol + ' (data URL, no server needed for this probe)');
			report('userAgent: ' + navigator.userAgent);
			report('isSecureContext: ' + window.isSecureContext + ' (OPFS needs a secure context; a data URL page is one only if the browser says so here)');
			report('crossOriginIsolated: ' + window.crossOriginIsolated + ' (the Webleste port needs this from the M2 local server, not from this probe)');

			async function testDirectoryPicker() {
			  var has = typeof window.showDirectoryPicker === 'function';
			  report('showDirectoryPicker present: ' + has);
			  if (!has) return;
			  try {
			    var handle = await window.showDirectoryPicker({ id: 'craftleste-probe', mode: 'read' });
			    report('RESULT: picker opened; picked directory: ' + handle.name);
			  } catch (error) {
			    report('RESULT: picker threw ' + errorName(error) + ' (AbortError means the dialog itself works)');
			  }
			}

			async function testOpfs() {
			  var has = !!(navigator.storage && typeof navigator.storage.getDirectory === 'function');
			  report('navigator.storage.getDirectory present: ' + has);
			  if (!has) return;
			  try {
			    var root = await navigator.storage.getDirectory();
			    var dir = await root.getDirectoryHandle('craftleste-probe', { create: true });
			    var file = await dir.getFileHandle('probe.txt', { create: true });
			    var writable = await file.createWritable();
			    await writable.write('craftleste probe ' + new Date().toISOString());
			    await writable.close();
			    var readBack = await new Promise(function (resolve, reject) {
			      if (typeof file.getFile === 'function') {
			        file.getFile().then(resolve, reject);
			      } else {
			        reject(new Error('getFile() missing on FileSystemFileHandle'));
			      }
			    }).then(function (f) { return f.text(); });
			    report('RESULT: OPFS round-trip ok; read back: ' + readBack);
			    await dir.removeEntry('probe.txt');
			    await root.removeEntry('craftleste-probe');
			    report('RESULT: OPFS entries removed again');
			  } catch (error) {
			    report('RESULT: OPFS threw ' + errorName(error) + ' (SecurityError here likely means the data URL page is not a secure context, not that OPFS is broken)');
			  }
			}

			function testGamepads() {
			  var has = typeof navigator.getGamepads === 'function';
			  report('navigator.getGamepads present: ' + has);
			  if (!has) return;
			  var pads = navigator.getGamepads();
			  report('gamepad slots: ' + pads.length);
			  var connected = 0;
			  for (var i = 0; i < pads.length; i++) {
			    var pad = pads[i];
			    if (!pad) continue;
			    connected++;
			    report('  connected: id="' + pad.id + '" index=' + pad.index + ' mapping=' + pad.mapping + ' buttons=' + pad.buttons.length + ' axes=' + pad.axes.length);
			  }
			  report('RESULT: ' + connected + ' gamepad(s) connected' + (connected === 0 ? ' (if a controller is plugged in, press one of its buttons and run this again - browsers only expose a pad after input)' : ''));
			}

			document.getElementById('btnDir').addEventListener('click', function () { testDirectoryPicker(); });
			document.getElementById('btnOpfs').addEventListener('click', function () { testOpfs(); });
			document.getElementById('btnPads').addEventListener('click', function () { testGamepads(); });
			document.getElementById('btnAll').addEventListener('click', function () { testDirectoryPicker().then(testOpfs).then(testGamepads); });
			</script>
			</body>
			</html>
			""";

	/**
	 * Probe 1's page, percent-encoded into a self-contained data URL.
	 */
	public static final String CAPABILITY_PROBE_URL = "data:text/html;charset=utf-8," + percentEncode(PROBE_HTML);

	/**
	 * Percent-encoding for a data URL body: {@link URLEncoder} form-escapes spaces as {@code +},
	 * which is only correct inside a query string, so those are turned back into {@code %20}.
	 */
	private static String percentEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private ProbePages() {
	}
}
