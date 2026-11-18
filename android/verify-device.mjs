// Verify the installed debug app on one Android device over the WebView's
// DevTools socket (debug builds only).
//   node verify-device.mjs <adb-serial> [out.json]
// Checks, in the app as installed, the claims the upstream browser check makes:
//   1. the GPU backend the page states is WebGPU (a WebGL 2.0 fallback fails here)
//   2. the viewport published a draw list with bone meshes
//   3. moving the head-flexion slider changes the computed load
//   4. the same move changes the head bone's transform in the draw list
//   5. the page fits the screen width (no layout wider than the device)
// The screen must be on and unlocked: the UI re-renders on animation frames.
// Exit 0 = all pass, 1 = a check failed, 2 = could not measure.
import {execFileSync} from 'node:child_process';
import {writeFileSync} from 'node:fs';
import {chromium} from 'playwright-core';

const [serial, out = `.agent-output/device-${(process.argv[2] || 'x').replace(/[^\w.-]/g, '_')}.json`] = process.argv.slice(2);
if (!serial) { console.error('usage: node verify-device.mjs <adb-serial> [out.json]'); process.exit(2); }
const adb = (...a) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...a], {encoding: 'utf8'}).trim();
const pkg = 'network.awai.suji', port = 9300 + Math.floor(Math.random() * 500);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const result = {schema: 'awai.suji.device-check.v1', at: new Date().toISOString(), checks: []};
const check = (name, ok, detail) => { result.checks.push({name, ok: !!ok, detail}); console.log((ok ? 'PASS ' : 'FAIL ') + name + ' — ' + detail); };
const finish = code => { result.status = code === 0 ? 'pass' : code === 1 ? 'fail' : 'unmeasured'; writeFileSync(out, JSON.stringify(result, null, 2) + '\n'); process.exit(code); };

let browser;
try {
  result.device = {release: adb('shell', 'getprop', 'ro.build.version.release'), sdk: adb('shell', 'getprop', 'ro.build.version.sdk'),
    model: adb('shell', 'getprop', 'ro.product.model'), webview: adb('shell', 'dumpsys webviewupdate | grep "Current WebView package"')};
  adb('shell', 'am', 'force-stop', pkg);
  adb('shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1');
  let pid = '';
  for (let i = 0; i < 20 && !pid; i++) { await sleep(500); try { pid = adb('shell', 'pidof', pkg); } catch { pid = ''; } }
  if (!pid) { check('app process started', false, 'no pid'); finish(2); }
  let socket = '';
  for (let i = 0; i < 20 && !socket; i++) {
    await sleep(500);
    socket = adb('shell', 'cat /proc/net/unix').split('\n').map(l => l.trim().split(/\s+/).pop()).find(s => s === `@webview_devtools_remote_${pid}`) || '';
  }
  if (!socket) { check('WebView DevTools socket', false, 'not found for pid ' + pid); finish(2); }
  execFileSync(process.env.ADB || 'adb', ['-s', serial, 'forward', `tcp:${port}`, `localabstract:${socket.slice(1)}`]);
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts().flatMap(c => c.pages())[0];
  if (!page) { check('page attached', false, 'no page'); finish(2); }
  result.userAgent = await page.evaluate(() => navigator.userAgent);
  let backend = '';
  // "…" is the placeholder before init-canvas! answers.
  const settled = b => b && !/^[.…\s]*$/.test(b);
  for (let i = 0; i < 90 && !settled(backend); i++) { await sleep(500); backend = (await page.evaluate(() => document.querySelector('.suji-backend')?.textContent || '')).trim(); }
  if (!settled(backend)) { check('page stated a GPU backend', false, 'no .suji-backend text within 30 s'); finish(2); }
  check('GPU backend is WebGPU', backend === 'webgpu', 'page states: ' + backend);
  const geo = () => page.evaluate(() => JSON.parse(JSON.stringify(window.__sujiGeometry || null)));
  const g0 = await geo();
  check('viewport published bone meshes', g0 && g0.drawCount > 0 && g0.bones?.length > 0,
    g0 ? `drawCount ${g0.drawCount}, bones ${g0.bones?.length}` : 'no __sujiGeometry');
  const figure = () => page.evaluate(() => document.querySelector('.suji-figure')?.textContent?.trim() || '');
  const before = await figure();
  await page.evaluate(() => { const el = document.getElementById('posture-head-flexion-deg'); el.value = 10; el.dispatchEvent(new Event('input', {bubbles: true})); });
  await sleep(800);
  const after = await figure();
  check('head-flexion slider changes the computed load', before && before !== after, `${JSON.stringify(before)} -> ${JSON.stringify(after)}`);
  const g1 = await geo();
  const head = g => JSON.stringify(g?.bones?.find(b => /head/.test(b.label))?.transform ?? null);
  check('the move reaches the head bone transform', head(g0) !== 'null' && head(g0) !== head(g1), `${head(g0)} -> ${head(g1)}`);
  // A layout wider than the screen widens the layout viewport past the visual one.
  const fit = await page.evaluate(() => ({layout: innerWidth, visual: Math.round(visualViewport.width), scroll: document.documentElement.scrollWidth}));
  check('the page fits the screen width', fit.layout <= fit.visual + 1 && fit.scroll <= fit.visual + 1,
    `layout ${fit.layout}px, visual ${fit.visual}px, scroll ${fit.scroll}px`);
  await page.screenshot({path: out.replace(/\.json$/, '.png')}).catch(e => { result.screenshotError = String(e.message || e); });
  finish(result.checks.every(c => c.ok) ? 0 : 1);
} catch (e) {
  check('measurement', false, String(e.message || e)); finish(2);
} finally {
  await browser?.close().catch(() => {});
  try { execFileSync(process.env.ADB || 'adb', ['-s', serial, 'forward', '--remove', `tcp:${port}`]); } catch {}
}
