// Open bot candidates in the installed debug app's "bot results" page on one
// Android device, over the WebView DevTools socket (debug builds only).
//   node verify-results.mjs <adb-serial> <candidate.json> [out.json]
// Checks:
//   1. the index page links to the results page
//   2. the candidate as written by the host is accepted (recomputed on the device)
//   3. the same candidate with one number changed by 1e-9 is refused and the
//      first differing value is named
//   4. the same candidate with the task changed but the old results is refused
// The screen must be on and unlocked. Exit 0 = pass, 1 = fail, 2 = unmeasured.
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {chromium} from 'playwright-core';

const [serial, candidatePath, out = '.agent-output/results-check.json'] = process.argv.slice(2);
if (!serial || !candidatePath) { console.error('usage: node verify-results.mjs <adb-serial> <candidate.json> [out.json]'); process.exit(2); }
const ADB = process.env.ADB || 'adb';
const adb = (...a) => execFileSync(ADB, ['-s', serial, ...a], {encoding: 'utf8'}).trim();
const pkg = 'network.awai.suji', port = 9300 + Math.floor(Math.random() * 500);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const result = {schema: 'awai.suji.results-check.v1', at: new Date().toISOString(), checks: []};
const check = (name, ok, detail) => { result.checks.push({name, ok: !!ok, detail}); console.log((ok ? 'PASS ' : 'FAIL ') + name + ' — ' + detail); };
const finish = code => { result.status = ['pass', 'fail', 'unmeasured'][code]; writeFileSync(out, JSON.stringify(result, null, 2) + '\n'); process.exit(code); };

const original = readFileSync(candidatePath, 'utf8');
const cand = JSON.parse(original);
const tampered = structuredClone(cand); tampered.results.loads.cervical['compressive-load-kgf'] += 1e-9;
const retasked = structuredClone(cand); retasked.task.posture.head_flexion_deg = 10;

let browser;
try {
  result.device = {release: adb('shell', 'getprop', 'ro.build.version.release'), model: adb('shell', 'getprop', 'ro.product.model')};
  adb('shell', 'am', 'force-stop', pkg);
  adb('shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1');
  let pid = '';
  for (let i = 0; i < 20 && !pid; i++) { await sleep(500); try { pid = adb('shell', 'pidof', pkg); } catch { pid = ''; } }
  let socket = '';
  for (let i = 0; i < 20 && pid && !socket; i++) {
    await sleep(500);
    socket = adb('shell', 'cat /proc/net/unix').split('\n').map(l => l.trim().split(/\s+/).pop()).find(s => s === `@webview_devtools_remote_${pid}`) || '';
  }
  if (!socket) { check('WebView DevTools socket', false, 'not found'); finish(2); }
  execFileSync(ADB, ['-s', serial, 'forward', `tcp:${port}`, `localabstract:${socket.slice(1)}`]);
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const page = browser.contexts().flatMap(c => c.pages())[0];
  await page.waitForSelector('a[href="results.html"]', {timeout: 30000}).catch(() => null);
  const link = await page.$('a[href="results.html"]');
  check('the simulator links to the results page', !!link, link ? 'link present' : 'no link');
  if (!link) finish(1);
  await link.click();
  await page.waitForSelector('#candidate-file', {timeout: 30000});
  const open = async (name, text) => {
    await page.setInputFiles('#candidate-file', {name, mimeType: 'application/json', buffer: Buffer.from(text)});
    for (let i = 0; i < 120; i++) { if (await page.$('.dads-notification-banner')) break; await sleep(500); }
    return page.evaluate(() => ({type: document.querySelector('.dads-notification-banner')?.dataset.type,
      text: document.querySelector('.dads-notification-banner')?.innerText || '',
      figure: document.querySelector('.suji-figure')?.textContent || ''}));
  };
  const a = await open('candidate.json', original);
  check('the host candidate is accepted on the device', a.type === 'success', `${a.type}: ${a.figure} kgf ${a.type === 'success' ? '' : a.text.replace(/\s+/g, ' ').slice(0, 300)}`);
  await page.reload(); await page.waitForSelector('#candidate-file');
  const b = await open('tampered.json', JSON.stringify(tampered));
  check('a number changed by 1e-9 is refused and located', b.type === 'error' && /compressive-load-kgf/.test(b.text), `${b.type}: ${b.text.replace(/\s+/g, ' ').slice(0, 160)}`);
  await page.reload(); await page.waitForSelector('#candidate-file');
  const c = await open('retasked.json', JSON.stringify(retasked));
  check('a changed task with the old results is refused', c.type === 'error', `${c.type}: ${c.text.replace(/\s+/g, ' ').slice(0, 160)}`);
  finish(result.checks.every(x => x.ok) ? 0 : 1);
} catch (e) {
  check('measurement', false, String(e.message || e)); finish(2);
} finally {
  await browser?.close().catch(() => {});
  try { execFileSync(ADB, ['-s', serial, 'forward', '--remove', `tcp:${port}`]); } catch {}
}
