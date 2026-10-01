import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DEFAULT_SETTINGS, daySummary, normalizeEvent, validateSettings } from '../model.mjs';
import { ConsoleStore } from '../store.mjs';

const folder = path.dirname(fileURLToPath(import.meta.url));
const now = Date.parse('2026-09-25T10:00:00+08:00');
const source = { at: '2026-09-25T09:00:00+08:00', sessionId: 's1', kind: 'tool_requested', childText: '我家在某条街', replyText: '去查天气' };
test('attention count and drilldown use exactly the same events and preserve unknown endings',()=>{
 const rows=[
  {kind:'session_end',reason:'SESSION_SILENCE',evidence:'device_event'},
  {kind:'session_end',reason:'TURN_CANCELLED',evidence:'device_event'},
  {kind:'session_end',reason:'SESSION_CLOSE_FAILED',evidence:'device_event'},
  {kind:'tool_result',toolStatus:'failed',evidence:'device_receipt'},
  {kind:'asr_error',reason:'ASR_OPEN_TIMEOUT',evidence:'device_event'},
 ].map(e=>normalizeEvent({...source,...e},true,now));
 const day=daySummary(rows,'2026-09-25');assert.equal(day.failures,4);assert.equal(day.attention.length,day.failures);
 assert.equal(day.attention[0].reason,'TURN_CANCELLED');assert.ok(day.attention.every(e=>e.at&&e.id&&e.sessionId));
});

test('a fully played fixed answer failure is still attention, not a successful answer',()=>{
 const fallback=normalizeEvent({at:source.at,sessionId:'failed-reply',kind:'answer_complete',evidence:'device_event',
  childText:'继续讲故事',replyText:'这次没能得到可靠答案，我不能随便猜。我们可以继续聊。'},true,now);
 const day=daySummary([fallback],'2026-09-25');
 assert.equal(day.answers,1);
 assert.equal(day.failures,1);
 assert.equal(day.attention[0].reason,'ANSWER_FALLBACK');
});

test('device failure code stays visible when literal transcript storage is off',()=>{
 const fallback=normalizeEvent({at:source.at,sessionId:'coded-failure',kind:'answer_complete',evidence:'device_event',
  reason:'NATIVE_REPLY_ENVELOPE_REQUIRED',childText:'继续讲故事',replyText:'这次没能得到可靠答案，我不能随便猜。我们可以继续聊。'},false,now);
 const day=daySummary([fallback],'2026-09-25');
 assert.equal(day.failures,1);
 assert.equal(day.attention[0].reason,'NATIVE_REPLY_ENVELOPE_REQUIRED');
 assert.equal(day.attention[0].replyText,undefined);
});

test('default recording omits literal conversation and rejects invalid settings', () => {
  const e = normalizeEvent(source, false, now);
  assert.equal(e.childText, undefined);
  assert.equal(e.replyText, undefined);
  assert.throws(() => validateSettings({ ...DEFAULT_SETTINGS, dailyMinutes: 0 }));
  assert.throws(() => validateSettings({ ...DEFAULT_SETTINGS, quietEnd: DEFAULT_SETTINGS.quietStart }));
});

test('request is distinct from verified successful receipt', () => {
  const events = [
    normalizeEvent(source, false, now),
    normalizeEvent({ ...source, kind: 'tool_result', toolStatus: 'success', evidence: 'manual_import' }, false, now),
    normalizeEvent({ ...source, kind: 'tool_result', toolStatus: 'success', evidence: 'device_receipt' }, false, now),
  ];
  const day = daySummary(events, '2026-09-25');
  assert.equal(day.toolRequests, 1);
  assert.equal(day.toolSuccesses, 1);
});

test('daily report shows heard questions and counts a real reply failure', () => {
  const events = [
    normalizeEvent({ at: source.at, kind: 'answer_complete', sessionId: 'answered',
      childText: '蜘蛛侠，三加四等于几？', replyText: '七。', evidence: 'device_event' }, true, now),
    normalizeEvent({ at: source.at, kind: 'session_end', sessionId: 'answered',
      reason: 'SESSION_SILENCE', evidence: 'device_event' }, true, now),
    normalizeEvent({ at: source.at, kind: 'session_end', sessionId: 'failed',
      reason: 'NATIVE_REPLY_ENVELOPE_REQUIRED', evidence: 'device_event' }, true, now),
    normalizeEvent({ at: source.at, kind: 'answer_partial', sessionId: 'failed',
      childText: '讲个故事', replyText: '从前有一只猴子……', evidence: 'device_event' }, true, now),
  ];
  const day = daySummary(events, '2026-09-25');
  assert.deepEqual(day.heardQuestions, ['蜘蛛侠，三加四等于几？', '讲个故事']);
  assert.equal(day.answers, 1);
  assert.equal(day.partialAnswers, 1);
  assert.equal(day.failures, 1);
});

test('a conversation crossing midnight contributes time and a session to both days', () => {
  const end = '2026-09-26T00:05:00+08:00';
  const events = [
    normalizeEvent({ at: '2026-09-25T23:55:00+08:00', kind: 'session_start', sessionId: 'late' }, false, Date.parse(end)),
    normalizeEvent({ at: end, kind: 'session_end', sessionId: 'late', durationMs: 10 * 60000,
      reason: 'SESSION_SILENCE', evidence: 'device_event' }, false, Date.parse(end)),
  ];
  const before = daySummary(events, '2026-09-25');
  const after = daySummary(events, '2026-09-26');
  assert.deepEqual([before.minutes, after.minutes], [5, 5]);
  assert.deepEqual([before.sessions, after.sessions], [1, 1]);
});

test('a missing device sequence is rejected without advancing the saved cursor', () => {
  const dir = fs.mkdtempSync(path.join(folder, '.test-'));
  try {
    const store = new ConsoleStore(path.join(dir, 'db.json'), () => now);
    store.ingestDeviceBatch(1, [{ seq: 1, at: source.at, kind: 'session_start', sessionId: 's1' }]);
    assert.throws(() => store.ingestDeviceBatch(3, [{ seq: 3, at: source.at,
      kind: 'session_end', sessionId: 's1', durationMs: 60000 }]), /序号缺失/);
    assert.equal(store.data.device.lastEventSeq, 1);
    assert.deepEqual(store.data.events.map(e => e.sourceSeq), [1]);
    assert.equal(new ConsoleStore(path.join(dir, 'db.json'), () => now).data.device.lastEventSeq, 1);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('settings enqueue commands but never imply toy acknowledgement', () => {
  const dir = fs.mkdtempSync(path.join(folder, '.test-'));
  try {
    const store = new ConsoleStore(path.join(dir, 'db.json'), () => now);
    store.updateSettings({ ...DEFAULT_SETTINGS, dailyMinutes: 30, paused: true });
    assert.deepEqual(store.data.commands.map(c => c.type), ['set_limits', 'pause']);
    assert.deepEqual(store.data.commands.map(c => c.status), ['queued', 'queued']);
    assert.equal(store.acknowledge(store.data.commands[1].id, store.data.commands[1].seq), false);
    assert.equal(store.markSent(store.data.commands[1].id), true);
    assert.equal(store.acknowledge(store.data.commands[1].id, store.data.commands[1].seq + 1), false);
    assert.equal(store.acknowledge(store.data.commands[1].id, store.data.commands[1].seq), true);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('turning off transcripts removes previously saved text', () => {
  const dir = fs.mkdtempSync(path.join(folder, '.test-'));
  try {
    const store = new ConsoleStore(path.join(dir, 'db.json'), () => now);
    store.updateSettings({ ...DEFAULT_SETTINGS, saveTranscript: true });
    store.addEvent(source);
    assert.equal(store.data.events[0].childText, source.childText);
    store.updateSettings({ ...DEFAULT_SETTINGS, saveTranscript: false });
    assert.equal(store.data.events[0].childText, undefined);
    assert.equal(new ConsoleStore(path.join(dir, 'db.json'), () => now).data.events[0].childText, undefined);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
