package com.craftleste.client.bootstrap;

/**
 * The mod-owned pages the local server hands to the embedded browser.
 *
 * <p>The bootstrap page is the no-file-dialog intake path (PLAN.md M2, closing RESEARCH.md unknown
 * 1): the player's files are already on disk in the instance, the local server serves them
 * same-origin, and this page copies them into OPFS at the layout the Webleste loader expects -
 * {@code Celeste.exe} and {@code Content/...} at the OPFS root, plus the {@code .ContentExists}
 * marker the port's {@code hasContent()} checks. Once the copy is done it hands off to the port's
 * own index page, which is what runs the game. A second open finds the files already in OPFS and
 * skips straight to the game, which is why the copy is idempotent.
 *
 * <p>The threaded-wasm probe page retires RESEARCH.md unknown 2 through the real server and the
 * real browser: the port is a .NET threaded-wasm build ({@code WasmEnableThreads},
 * {@code pthreadPoolInitialSize: 16}), and SharedArrayBuffer plus workers plus Atomics are the
 * gate those threads sit behind. The page creates the cross-origin-isolated state, 16 workers and
 * a ping-pong barrier through a SharedArrayBuffer, exactly the prerequisites the port needs.
 */
public final class BootstrapPages {

	private BootstrapPages() {
	}

	public static final String BOOTSTRAP_HTML = """
			<!doctype html>
			<html>
			<head>
			<meta charset="utf-8">
			<title>craftleste bootstrap</title>
			<style>
			  body { background: #0e1116; color: #e6edf3; font: 14px/1.45 monospace; margin: 16px; }
			  h1 { font-size: 16px; margin: 0 0 8px; }
			  #bar { height: 12px; border: 1px solid #2d3742; margin: 12px 0; }
			  #fill { height: 100%; width: 0; background: #3ba8ff; }
			  pre { background: #000; border: 1px solid #2d3742; padding: 8px; white-space: pre-wrap; }
			</style>
			</head>
			<body>
			<h1>craftleste</h1>
			<p>Copying your Celeste files into the browser's storage, then starting the game.
			This happens once; later opens go straight to the game.</p>
			<div id="bar"><div id="fill"></div></div>
			<div id="status">checking storage...</div>
			<pre id="log"></pre>
			<script>
			var statusEl = document.getElementById('status');
			var fillEl = document.getElementById('fill');
			var logEl = document.getElementById('log');
			var lines = [];
			function report(text) {
			  lines.push(text);
			  logEl.textContent = lines.join('\\n');
			  logEl.scrollTop = logEl.scrollHeight;
			  console.log('[craftleste bootstrap] ' + text);
			}
			function progress(done, total) {
			  fillEl.style.width = (total > 0 ? (done / total) * 100 : 0) + '%';
			  statusEl.textContent = 'copied ' + done + ' / ' + total + ' files';
			}

			// The copy is file-parallel: a single sequential stream over loopback managed about
			// 4 MB/s on a 1 GB install, and Chromium's OPFS has no trouble with concurrent writes
			// to different files.
			var LANES = 8;
			var REQUIRED_FOLDERS = ['Dialog','Effects','FMOD','Graphics','Maps','Monocle','Overworld','Tutorials'];

			async function opfsRoot() {
			  return await navigator.storage.getDirectory();
			}

			async function alreadyInstalled() {
			  try {
			    var root = await opfsRoot();
			    var content = await root.getDirectoryHandle('Content', { create: false });
			    for (const folder of REQUIRED_FOLDERS) {
			      await content.getDirectoryHandle(folder, { create: false });
			    }
			    await root.getFileHandle('Celeste.exe', { create: false });
			    // FNA.dll too: an older copy made before the FNA check existed would otherwise read
			    // as installed and boot straight into the XNA assembly error.
			    await root.getFileHandle('FNA.dll', { create: false });
			    return true;
			  } catch {
			    return false;
			  }
			}

			// The port's hasContent() checks for these directories whether or not they hold
			// anything, so an install with an empty required folder would read as "no content" to
			// the port even after a complete copy. Create them up front, like the port's own
			// skipOobe() helper does.
			async function ensureRequiredFolders(root) {
			  var content = await root.getDirectoryHandle('Content', { create: true });
			  for (const folder of REQUIRED_FOLDERS) {
			    await content.getDirectoryHandle(folder, { create: true });
			  }
			}

			async function ensureDirectory(root, parts) {
			  var handle = root;
			  for (const part of parts) {
			    handle = await handle.getDirectoryHandle(part, { create: true });
			  }
			  return handle;
			}

			async function writeFile(root, name) {
			  var parts = name.split('/');
			  var fileName = parts.pop();
			  var directory = await ensureDirectory(root, parts);
			  var handle = await directory.getFileHandle(fileName, { create: true });
			  var writable = await handle.createWritable();
			  var response = await fetch('/' + name);
			  if (!response.ok) throw new Error('fetch ' + name + ' -> HTTP ' + response.status);
			  // pipeTo closes the writable itself on success (the Streams default, preventClose:
			  // false), which is what commits the file; closing it again throws. The port's own
			  // copy helpers pipe without a close, and so must this.
			  await response.body.pipeTo(writable);
			}

			// The patched game is derived state, and the port treats it as such only by accident:
			// it decides whether to patch by whether CustomCeleste.dll exists (splash.tsx's
			// initialIsPatched). Copying new files over the old ones without removing that file
			// therefore boots the patch made from the PREVIOUS build - which is how an FNA install
			// ends up dying on Microsoft.Xna.Framework.Game, the XNA build's assembly identity.
			// Removing it whenever the files are copied forces the port to patch again.
			// The Celeste/ folder is deliberately left alone: that is the port's save data.
			var PATCHER_OUTPUTS = ['CustomCeleste.dll', 'Celeste.Mod.mm.dll', 'MMHOOK_Celeste.dll', 'everest.zip'];
			async function removePatcherOutputs(root) {
			  for (const name of PATCHER_OUTPUTS) {
			    try {
			      await root.removeEntry(name);
			      report('removed stale ' + name + ' (the port will patch again)');
			    } catch {
			      /* not present */
			    }
			  }
			}

			// Whether the patched assembly was made from the files that are installed now. The port
			// writes CustomCeleste.dll when it patches, so its modification time is the record, and
			// the input's own timestamp is what it is compared against: a patch older than the
			// Celeste.exe it was patched from came from a different build.
			//
			// Comparing against the file rather than against a recorded copy time matters: the copy
			// time is only written on the full-copy path, so a run that takes the skip path never
			// records one - and a missing record used to read as "stale", which re-patched on every
			// single open. The files carry their own timestamps, so the answer is there without any
			// bookkeeping on our side.
			async function patchIsCurrent(root) {
			  try {
			    var patched = await (await root.getFileHandle('CustomCeleste.dll', { create: false })).getFile();
			    var source = await (await root.getFileHandle('Celeste.exe', { create: false })).getFile();
			    return patched.lastModified > source.lastModified;
			  } catch {
			    return false;
			  }
			}

			// The exact check the port runs before it boots, plus the patcher's output file, run
			// here before the hand-off: if this reports a problem, the port's setup screen would
			// say "no content" and nobody would know why. FNA.dll is checked for the same reason
			// the intake checks it: without it the port boots, patches, and then dies in C# asking
			// for Microsoft.Xna.Framework.Game - the XNA build's assemblies are in the GAC, not in
			// the install - which reads like a mystery error rather than "wrong build".
			async function verifyInstall(root) {
			  var problems = [];
			  try {
			    var content = await root.getDirectoryHandle('Content', { create: false });
			    for (const folder of REQUIRED_FOLDERS) {
			      try {
			        await content.getDirectoryHandle(folder, { create: false });
			      } catch {
			        problems.push('missing Content/' + folder);
			      }
			    }
			  } catch {
			    problems.push('missing Content directory');
			  }
			  for (const file of ['.ContentExists', 'Celeste.exe', 'FNA.dll']) {
			    try {
			      await root.getFileHandle(file, { create: false });
			    } catch {
			      problems.push('missing ' + file);
			    }
			  }
			  return problems;
			}

			async function run() {
			  var root = await opfsRoot();
			  var manifest = await (await fetch('/craftleste/manifest.json')).json();
			  var total = manifest.files.length;
			  var totalBytes = manifest.files.reduce(function (sum, entry) { return sum + entry.size; }, 0);
			  // The port's hasContent() only checks that the Content folders exist, so an
			  // interrupted copy that left them empty would read as installed. The stamp records
			  // the finished copy's size, so a partial one is always redone rather than booted into.
			  var stamp = 'craftleste.installed';
			  var expected = total + ':' + totalBytes;
			  if (localStorage.getItem(stamp) === expected && await alreadyInstalled()) {
			    // Same files as last time, but the patch may still predate them: a build switch
			    // recopies under a new identity, yet an identity that did not change can still be
			    // sitting on top of a patch made from the previous build.
			    if (await patchIsCurrent(root)) {
			      report('Celeste files are already in browser storage; starting the game.');
			      location.replace('../index.html');
			      return;
			    }
			    report('the patched game predates these files; the port will patch again');
			    await removePatcherOutputs(root);
			    location.replace('../index.html');
			    return;
			  }

			  report('copying ' + total + ' files (' + (totalBytes / 1048576).toFixed(0) + ' MB) into browser storage, ' + LANES + ' at a time');
			  // Drop the patcher's output from any earlier build before the new files land, so the
			  // port patches against what is being copied rather than trusting a stale assembly.
			  await removePatcherOutputs(root);
			  await ensureRequiredFolders(root);
			  progress(0, total);
			  var next = 0;
			  var done = 0;
			  var failed = null;
			  async function lane() {
			    while (failed === null) {
			      var index = next++;
			      if (index >= total) return;
			      try {
			        await writeFile(root, manifest.files[index].name);
			      } catch (error) {
			        if (failed === null) failed = manifest.files[index].name + ': ' + error;
			        return;
			      }
			      done++;
			      progress(done, total);
			      if (done % 50 === 0 || done === total) report('copied ' + done + ' / ' + total);
			    }
			  }
			  var lanes = [];
			  for (var i = 0; i < LANES; i++) lanes.push(lane());
			  await Promise.all(lanes);
			  if (failed !== null) throw new Error(failed);

			  // Creating the handle is itself what writes the empty marker file.
			  await root.getFileHandle('.ContentExists', { create: true });
			  localStorage.setItem(stamp, expected);
			  var problems = await verifyInstall(root);
			  if (problems.length > 0) {
			    report('WARNING: verification found problems: ' + problems.join(', '));
			    if (problems.indexOf('missing FNA.dll') >= 0) {
			      report('The game will not start: this copy is the XNA build of Celeste, and the port runs the FNA build.');
			      report('On Steam, select the "opengl" beta of celeste (Properties > Betas > opengl) and reload; the port\\'s own site also offers a 64-bit Windows FNA build.');
			      statusEl.textContent = 'wrong Celeste build - see the note below';
			      return;
			    }
			  } else {
			    report('verified: Content/, the required folders, Celeste.exe, FNA.dll and the marker are in browser storage');
			  }
			  report('done; starting the game');
			  location.replace('../index.html');
			}

			run().catch(function (error) {
			  statusEl.textContent = 'failed';
			  report('FAILED: ' + (error && error.stack ? error.stack : error));
			  report('The copy restarts from scratch on the next open.');
			});
			</script>
			</body>
			</html>
			""";

	public static final String THREADED_WASM_PROBE_HTML = """
			<!doctype html>
			<html>
			<head>
			<meta charset="utf-8">
			<title>craftleste probe 2 - threaded wasm</title>
			<style>
			  body { background: #0e1116; color: #e6edf3; font: 14px/1.45 monospace; margin: 16px; }
			  h1 { font-size: 16px; margin: 0 0 8px; }
			  button { font: 14px monospace; margin: 0 8px 8px 0; padding: 6px 10px; }
			  pre { background: #000; border: 1px solid #2d3742; padding: 8px; white-space: pre-wrap; }
			</style>
			</head>
			<body>
			<h1>craftleste Probe 2 - threaded wasm prerequisites</h1>
			<p>The game runtime is a .NET threaded-wasm build with a 16-thread pool. Those threads sit
			behind SharedArrayBuffer, workers and Atomics, which Chromium gates on cross-origin
			isolation - which in turn depends on the local server's COOP/COEP headers. Press the
			button; every line also goes to the console.</p>
			<button id="btnRun">Run the 16-worker barrier</button>
			<pre id="report">booting probe...</pre>
			<script>
			var reportEl = document.getElementById('report');
			var lines = [];
			function report(text) {
			  lines.push(text);
			  reportEl.textContent = lines.join('\\n');
			  console.log('[craftleste probe 2] ' + text);
			}

			report('userAgent: ' + navigator.userAgent);
			report('crossOriginIsolated: ' + window.crossOriginIsolated + ' (the port needs this; it comes from the local server headers)');
			report('typeof SharedArrayBuffer: ' + (typeof SharedArrayBuffer));
			report('typeof Worker: ' + (typeof Worker));
			report('hardwareConcurrency: ' + navigator.hardwareConcurrency);

			var WORKER_SOURCE = `
			onmessage = function (event) {
			  var data = event.data;
			  var ia = new Int32Array(data.shared);
			  for (var round = 0; round < data.rounds; round++) {
			    Atomics.wait(ia, 0, 0);
			    Atomics.add(ia, 1, 1);
			    Atomics.notify(ia, 1);
			  }
			  postMessage(data.id);
			};
			`;

			var WORKERS = 16;
			var ROUNDS = 50;

			// The facts that decide the runtime question do not need a gesture, so they report the
			// moment the page opens; the barrier itself stays behind a click. The header line reads
			// this document's own response, which is the one thing the server could theoretically
			// get wrong and the browser alone knows the truth about.
			async function quickFacts() {
			  report('userAgent: ' + navigator.userAgent);
			  report('isSecureContext: ' + window.isSecureContext);
			  report('crossOriginIsolated: ' + window.crossOriginIsolated);
			  report('typeof SharedArrayBuffer: ' + (typeof SharedArrayBuffer));
			  report('typeof Worker: ' + (typeof Worker));
			  report('hardwareConcurrency: ' + navigator.hardwareConcurrency);
			  try {
			    var headers = (await fetch(location.href, { method: 'HEAD' })).headers;
			    report('document headers: COOP=' + headers.get('cross-origin-opener-policy')
			      + ' COEP=' + headers.get('cross-origin-embedder-policy')
			      + ' CORP=' + headers.get('cross-origin-resource-policy'));
			  } catch (error) { report('header probe threw ' + error); }
			  document.title = 'craftleste-probe2: iso=' + window.crossOriginIsolated
			    + ' sab=' + (typeof SharedArrayBuffer);
			}
			quickFacts();

			async function run() {
			  if (typeof SharedArrayBuffer !== 'function') {
			    report('RESULT: FAIL - no SharedArrayBuffer; threaded wasm cannot start.');
			    return;
			  }
			  var shared = new SharedArrayBuffer(8);
			  var ia = new Int32Array(shared);
			  var blob = new Blob([WORKER_SOURCE], { type: 'text/javascript' });
			  var url = URL.createObjectURL(blob);

			  var workers = [];
			  for (var id = 0; id < WORKERS; id++) {
			    var worker = new Worker(url);
			    worker.postMessage({ id: id, shared: shared, rounds: ROUNDS });
			    workers.push(worker);
			  }

			  var started = performance.now();
			  for (var round = 0; round < ROUNDS; round++) {
			    Atomics.store(ia, 0, 1);
			    Atomics.notify(ia, 0);
			    while (Atomics.load(ia, 1) < (round + 1) * WORKERS) {
			      await Atomics.waitAsync(ia, 1, round * WORKERS).value;
			    }
			    Atomics.store(ia, 0, 0);
			    Atomics.notify(ia, 0);
			  }
			  var elapsed = performance.now() - started;

			  for (const worker of workers) worker.terminate();
			  URL.revokeObjectURL(url);
			  report('completed ' + ROUNDS + ' barrier rounds across ' + WORKERS + ' workers in ' + elapsed.toFixed(1) + ' ms');
			  report('RESULT: threaded wasm prerequisites OK (SharedArrayBuffer + workers + Atomics, cross-origin isolated).');
			}

			document.getElementById('btnRun').addEventListener('click', function () { run(); });
			</script>
			</body>
			</html>
			""";
}
