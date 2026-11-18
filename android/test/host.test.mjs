// The host command as the bot runs it: node android/.build/host/host.js.
// Run after build.py: node --test android/test/host.test.mjs
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, readFileSync, writeFileSync, existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const android = join(dirname(fileURLToPath(import.meta.url)), '..');
const host = join(android, '.build', 'host', 'host.js');
const run = (...args) => spawnSync('node', [host, ...args], {encoding: 'utf8'});
const task = JSON.parse(readFileSync(join(android, 'profiles', 'tasks', 'laptop-on-lap.json'), 'utf8'));

test('a valid task writes a candidate and nothing else', () => {
  const out = mkdtempSync(join(tmpdir(), 'suji-host-'));
  const r = run('run', join(android, 'profiles', 'tasks', 'laptop-on-lap.json'), out);
  assert.equal(r.status, 0, r.stderr);
  const c = JSON.parse(readFileSync(join(out, 'candidate.json'), 'utf8'));
  assert.equal(c.schema, 'awai.suji.candidate.v1');
  assert.equal(c.status, 'candidate-not-reviewed');
  assert.deepEqual(c.task, task);
  assert.match(c.suji.commit, /^[0-9a-f]{40}$/);
  assert.equal(existsSync(join(out, 'refusal.json')), false);
  assert.match(r.stdout, /^HEADLINE loads\.cervical\.compressive-load-kgf 27\.9/m);
});

test('the bundled refusal example is refused with exit 2 and no candidate', () => {
  const out = mkdtempSync(join(tmpdir(), 'suji-host-'));
  const r = run('run', join(android, 'profiles', 'tasks', 'invalid-missing-angle.json'), out);
  assert.equal(r.status, 2);
  assert.match(r.stderr, /REFUSED posture\.head_flexion_deg is missing/);
  const f = JSON.parse(readFileSync(join(out, 'refusal.json'), 'utf8'));
  assert.equal(f.schema, 'awai.suji.refusal.v1');
  assert.equal(existsSync(join(out, 'candidate.json')), false);
});

test('an out-of-range angle is refused', () => {
  const dir = mkdtempSync(join(tmpdir(), 'suji-host-'));
  const bad = structuredClone(task); bad.posture.knee_flexion_deg = 200;
  writeFileSync(join(dir, 'task.json'), JSON.stringify(bad));
  const r = run('run', join(dir, 'task.json'), join(dir, 'out'));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /knee_flexion_deg must be between 0 and 130/);
});

test('usage errors exit 64', () => {
  assert.equal(run().status, 64);
  assert.equal(run('run').status, 64);
});
