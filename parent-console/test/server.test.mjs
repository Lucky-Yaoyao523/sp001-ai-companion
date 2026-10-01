import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { createConsoleServer } from '../server.mjs';

test('home loopback page opens without code but LAN Host and cross-site requests stay gated', async () => {
  const dir = fs.mkdtempSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '.test-'));
  const { server } = createConsoleServer({ file: path.join(dir, 'db.json'), code: 'test-code', localAutoAccess: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const local = await fetch(`${base}/api/state`);
    assert.equal(local.status, 200);
    assert.equal((await local.json()).access, 'local');
    const spoofedHost = await new Promise((resolve, reject) => {
      const req = http.request(base + '/api/state', { headers: { Host: '192.0.2.10:8788' } }, response => {
        response.resume(); response.on('end', () => resolve(response.statusCode));
      });
      req.on('error', reject); req.end();
    });
    assert.equal(spoofedHost, 403);
    assert.equal((await fetch(`${base}/api/state`, { headers: { Origin: 'https://bad.example' } })).status, 403);
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('standby webpage remains readable and rejects writes while Windows owns toy sync', async () => {
  const dir = fs.mkdtempSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '.test-'));
  const file = path.join(dir, 'db.json');
  const seeded = createConsoleServer({ file });
  seeded.store.data.events.push({ kind: 'answer_complete', at: '2020-01-01T00:00:00.000Z',
    sessionId: 'old', childText: '旧问题', replyText: '旧回答', evidence: 'device_event' });
  seeded.store.save();
  const before = fs.readFileSync(file);
  const { server, store } = createConsoleServer({ file,
    code: 'test-code', localAutoAccess: true, readOnly: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const state = await fetch(`${base}/api/state`);
    assert.equal(state.status, 200);
    const snapshot = await state.json();
    assert.equal(snapshot.mode, 'standby');
    assert.equal(snapshot.events.length, 1);
    assert.equal((await fetch(`${base}/api/summary?date=2020-01-01`)).status, 200);
    assert.deepEqual(fs.readFileSync(file), before);
    assert.equal((await fetch(`${base}/api/settings`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 423);
    assert.equal((await fetch(`${base}/api/events`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 423);
    assert.equal(store.data.commands.length, 0);
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('API requires login and imported events cannot claim a device receipt', async () => {
  const folder = path.dirname(fileURLToPath(import.meta.url));
  const dir = fs.mkdtempSync(path.join(folder, '.test-'));
  const { server } = createConsoleServer({ file: path.join(dir, 'db.json'), code: 'test-code' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await fetch(`${base}/api/state`)).status, 401);
    const login = await fetch(`${base}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: 'test-code' }) });
    assert.equal(login.status, 200);
    const cookie = login.headers.get('set-cookie').split(';')[0];
    const event = await fetch(`${base}/api/events`, { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: cookie }, body: JSON.stringify({ kind: 'tool_result', at: new Date().toISOString(), sessionId: 's1', toolStatus: 'success', evidence: 'device_receipt' }) });
    assert.equal(event.status, 201);
    assert.equal((await event.json()).event.evidence, 'manual_import');
    const state = await fetch(`${base}/api/state`, { headers: { Cookie: cookie } });
    assert.equal((await state.json()).transport, 'not_connected');
    const crossSite = await fetch(`${base}/api/state`, { headers: { Cookie: cookie, Origin: 'https://bad.example' } });
    assert.equal(crossSite.status, 403);
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('text export requires login and preserves both sides, local time and incomplete status', async () => {
  const dir = fs.mkdtempSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '.test-'));
  const { server, store } = createConsoleServer({ file: path.join(dir, 'db.json'), code: 'test-code' });
  store.data.settings.saveTranscript = true;
  const at = new Date().toISOString();
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(at));
  const time = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(at));
  store.addEvent({ kind: 'answer_partial', at, sessionId: 's1', childText: '讲个故事', replyText: '从前有只猴子。' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const url = `${base}/api/export?date=${date}&format=txt`;
    assert.equal((await fetch(url)).status, 401);
    const login = await fetch(`${base}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: 'test-code' }) });
    const cookie = login.headers.get('set-cookie').split(';')[0];
    const result = await fetch(url, { headers: { Cookie: cookie } });
    assert.equal(result.status, 200);
    assert.match(result.headers.get('content-disposition'), /\.txt/);
    const content = await result.text();
    assert.ok(content.includes(`[${time}] 孩子：讲个故事`));
    assert.ok(content.includes(`[${time}] 蜘蛛侠：从前有只猴子。`));
    assert.ok(content.includes('回答未完成'));
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
