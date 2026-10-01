import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createConsoleServer } from '../server.mjs';
import { signDeviceBatch } from '../device-sync.mjs';

test('authenticated toy batch becomes a durable daily conversation once', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sp001-sync-'));
  const file = path.join(dir, 'console.json');
  const key = 'test-key-with-enough-entropy-for-this-fixture';
  const deviceId = 'toy-fixture';
  const now = Date.now();
  const setup = () => createConsoleServer({ file, code: 'parent-code', now: () => now, deviceKey: key, deviceId });
  let { server, store } = setup();
  store.updateSettings({ ...store.data.settings, saveTranscript: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const at = new Date(now).toISOString();
  const body = JSON.stringify({ events: [
    { seq: 1, kind: 'session_start', at, sessionId: 's1' },
    { seq: 2, kind: 'answer_complete', at, sessionId: 's1', turnId: '1', childText: '今天聊什么', replyText: '我们聊星星。' },
    { seq: 3, kind: 'session_end', at, sessionId: 's1', durationMs: 60000, reason: 'SESSION_ENDED_BY_REPLY' },
  ] });
  const send = (seq, signature) => fetch(`${base}/api/device/sync`, { method: 'POST', headers: {
    'Content-Type': 'application/json', 'X-SP001-Device-ID': deviceId,
    'X-SP001-Batch-Seq': String(seq), 'X-SP001-Sent-At': String(now),
    'X-SP001-Signature': signature,
  }, body });
  try {
    assert.equal((await send(1, '0'.repeat(64))).status, 401);
    const signature = signDeviceBatch(key, deviceId, 1, now, body);
    const first = await send(1, signature);
    assert.equal(first.status, 200);
    assert.deepEqual({ duplicate: (await first.json()).duplicate, count: store.data.events.length }, { duplicate: false, count: 3 });
    const replay = await send(1, signature);
    assert.equal((await replay.json()).duplicate, true);
    assert.equal(store.data.events.length, 3);
    const login = await fetch(`${base}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: 'parent-code' }) });
    const cookie = login.headers.get('set-cookie').split(';')[0];
    const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
    const result = await fetch(`${base}/api/day?date=${day}`, { headers: { Cookie: cookie } });
    const report = await result.json();
    assert.equal(report.sessions, 1);
    assert.equal(report.answers, 1);
    assert.equal(report.minutes, 1);
    assert.equal(report.events.find(e => e.kind === 'answer_complete').childText, '今天聊什么');
    assert.equal(report.events.find(e => e.kind === 'answer_complete').replyText, '我们聊星星。');
    const state = await (await fetch(`${base}/api/state`, { headers: { Cookie: cookie } })).json();
    assert.equal(state.transport, 'device_recent');
    assert.equal(state.device.lastEventSeq, 3);
    await new Promise(resolve => server.close(resolve));
    ({ server, store } = setup());
    assert.equal(store.data.device.lastEventSeq, 3);
    assert.equal(store.data.events.length, 3);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
