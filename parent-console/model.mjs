import { randomUUID } from 'node:crypto';

export const DEFAULT_SETTINGS = Object.freeze({
  dailyMinutes: 45,
  dailySessions: 4,
  quietStart: '20:30',
  quietEnd: '07:00',
  paused: false,
  saveTranscript: false,
  retentionDays: 7,
});

const eventKinds = new Set(['session_start', 'session_end', 'turn_start', 'answer_complete', 'answer_partial', 'tool_requested', 'tool_result', 'asr_error', 'tts_error', 'interrupted', 'cancelled']);
const toolStatuses = new Set(['success', 'failed', 'unknown']);
const evidenceLevels = new Set(['device_receipt', 'device_event', 'manual_import', 'demo']);
// These are the normal end codes emitted by ConversationSession.run(). Unknown
// device end codes stay visible as incomplete sessions instead of disappearing
// from the parent's daily problem count.
const normalSessionEnds = new Set(['SESSION_SILENCE', 'SESSION_ENDED', 'SESSION_ENDED_BY_REPLY', 'SESSION_TURN_LIMIT',
  'PARENT_PAUSED', 'PARENT_QUIET_HOURS', 'PARENT_SESSION_LIMIT', 'PARENT_TIME_LIMIT']);
export function attentionEvents(events) {
  return events.filter(e => (e.kind === 'session_end' && e.evidence === 'device_event' && e.reason && !normalSessionEnds.has(e.reason))
    || ['asr_error', 'tts_error'].includes(e.kind) || (e.kind === 'tool_result' && e.toolStatus === 'failed')
    || (e.kind === 'answer_complete' && e.evidence === 'device_event' &&
      (e.reason || e.replyText?.trim() === '这次没能得到可靠答案，我不能随便猜。我们可以继续聊。')))
    .map(e => e.kind === 'answer_complete' && !e.reason ? { ...e, reason: 'ANSWER_FALLBACK' } : e);
}
const text = (value, max = 180) => String(value ?? '').trim().slice(0, max);
const asInt = (value, low, high, name) => {
  if (!Number.isInteger(value) || value < low || value > high) throw new Error(`${name} 必须在 ${low}–${high} 之间`);
  return value;
};
const hhmm = value => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);

export function validateSettings(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('设置格式错误');
  const result = {
    dailyMinutes: asInt(input.dailyMinutes, 1, 240, '每日分钟数'),
    dailySessions: asInt(input.dailySessions, 1, 30, '每日会话次数'),
    quietStart: input.quietStart,
    quietEnd: input.quietEnd,
    paused: input.paused,
    saveTranscript: input.saveTranscript,
    retentionDays: asInt(input.retentionDays, 1, 30, '保留天数'),
  };
  if (!hhmm(result.quietStart) || !hhmm(result.quietEnd) || result.quietStart === result.quietEnd) throw new Error('安静时段格式错误');
  if (typeof result.paused !== 'boolean' || typeof result.saveTranscript !== 'boolean') throw new Error('开关格式错误');
  return result;
}

export function normalizeEvent(input, saveTranscript = false, now = Date.now(), authenticatedBacklog = false) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('事件格式错误');
  if (!eventKinds.has(input.kind)) throw new Error('未知事件类型');
  const at = Date.parse(input.at);
  if (!Number.isFinite(at) || at > now + 31 * 86400000 || (!authenticatedBacklog && at < now - 31 * 86400000)) throw new Error('事件时间超出范围');
  const durationMs = input.durationMs == null ? null : asInt(input.durationMs, 0, 4 * 3600000, '时长');
  const event = {
    id: randomUUID(), at: new Date(at).toISOString(), kind: input.kind,
    sessionId: text(input.sessionId, 64), turnId: text(input.turnId, 64),
    topic: text(input.topic, 60), durationMs,
    evidence: evidenceLevels.has(input.evidence) ? input.evidence : 'manual_import',
    reason: text(input.reason, 100),
    toolName: text(input.toolName, 50),
    toolStatus: toolStatuses.has(input.toolStatus) ? input.toolStatus : 'unknown',
  };
  if (!event.sessionId) throw new Error('缺少会话标识');
  if (saveTranscript) {
    event.childText = text(input.childText, 1000);
    event.replyText = text(input.replyText, 1000);
  }
  return event;
}

export function daySummary(events, date, timeZone = 'Asia/Hong_Kong') {
  const formatter = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' });
  const localDayMs = ms => formatter.format(new Date(ms));
  const localDay = iso => localDayMs(Date.parse(iso));
  const selected = events.filter(e => localDay(e.at) === date).sort((a, b) => a.at.localeCompare(b.at));
  const durationOnDay = e => {
    if (e.kind !== 'session_end' || !e.durationMs) return 0;
    const end = Date.parse(e.at); let cursor = end - e.durationMs, total = 0;
    while (cursor < end) {
      const day = localDayMs(cursor);
      let next = end;
      if (localDayMs(end - 1) !== day) {
        let low = cursor + 1, high = end;
        while (low < high) { const middle = Math.floor((low + high) / 2);
          if (localDayMs(middle) === day) low = middle + 1; else high = middle;
        }
        next = low;
      }
      if (day === date) total += next - cursor;
      cursor = next;
    }
    return total;
  };
  const durations = events.filter(e => e.kind === 'session_end').map(e => ({ event: e, ms: durationOnDay(e) }));
  const sessions = new Set(selected.filter(e => e.kind === 'session_start').map(e => e.sessionId));
  for (const row of durations) if (row.ms > 0) sessions.add(row.event.sessionId);
  const topics = [...new Set(selected.map(e => e.topic).filter(Boolean))];
  const heardQuestions = [...new Set(selected.filter(e => e.evidence === 'device_event' && ['answer_complete', 'answer_partial'].includes(e.kind))
    .map(e => e.childText?.trim()).filter(Boolean))].slice(0, 20).map(value => value.slice(0, 60));
  return {
    date, events: selected,
    minutes: Math.round(durations.reduce((n, row) => n + row.ms, 0) / 60000),
    sessions: sessions.size,
    answers: selected.filter(e => e.kind === 'answer_complete').length,
    partialAnswers: selected.filter(e => e.kind === 'answer_partial').length,
    toolRequests: selected.filter(e => e.kind === 'tool_requested').length,
    toolSuccesses: selected.filter(e => e.kind === 'tool_result' && e.toolStatus === 'success' && e.evidence === 'device_receipt').length,
    failures: attentionEvents(selected).length,
    attention: attentionEvents(selected),
    topics, heardQuestions,
  };
}

export function makeCommand(type, payload = {}, now = Date.now()) {
  if (!['set_limits', 'pause', 'resume'].includes(type)) throw new Error('未知指令');
  return { id: randomUUID(), seq: now, type, payload, createdAt: new Date(now).toISOString(), expiresAt: new Date(now + 23 * 3600000).toISOString(), status: 'queued', sentAt: null, acknowledgedAt: null };
}

export function pruneEvents(events, days, now = Date.now()) {
  const threshold = now - days * 86400000;
  // Offline arrivals get a full viewing period without changing their chat time.
  // receivedAt is assigned locally at first authenticated ingestion, never by the toy.
  return events.filter(e => Math.max(Date.parse(e.at), Date.parse(e.receivedAt) || 0) >= threshold);
}
