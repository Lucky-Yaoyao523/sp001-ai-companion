// Persist only reviewed codes, never provider messages, addresses or stack traces.
const allowed = new Set(['TOY_DEADLINE', 'TOY_TIMEOUT', 'TOY_RESPONSE_TOO_LARGE',
  'TOY_SYNC_STATUS_INVALID', 'TOY_REPLAY_ORDER', 'TOY_DURABLE_CURSOR_REQUIRED',
  'TOY_COMMAND_INVALID', 'TOY_COMMAND_BOUND', 'TOY_COMMAND_RESPONSE_BOUND',
  'TOY_COMMAND_RESPONSE_JSON', 'TOY_COMMAND_TIMEOUT', 'TOY_COMMAND_NOT_APPLIED',
  'TOY_COMMAND_ACK_STORE', 'ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'EHOSTUNREACH',
  'ENETUNREACH', 'ENOTFOUND', 'CERT_HAS_EXPIRED', 'ERR_TLS_CERT_ALTNAME_INVALID']);
export function safeSyncCode(error) {
  for (const value of [error?.code, error?.message]) {
    if (allowed.has(value)) return value;
    if (typeof value === 'string' && /^TOY_(COMMAND_)?HTTP_[1-5][0-9]{2}$/.test(value)) return value;
  }
  return 'SYNC_ERROR';
}
export function recordSyncDiagnostic(store, channel, error, now = Date.now()) {
  if (!['pull', 'command'].includes(channel)) return;
  store.data.device.syncDiagnostics ??= {};
  const previous = store.data.device.syncDiagnostics[channel] || {};
  const at = new Date(now).toISOString();
  const next = error ? { ...previous, status: 'error', consecutiveFailures: Math.min(1000000, (previous.consecutiveFailures || 0) + 1),
    lastErrorCode: safeSyncCode(error), lastErrorAt: at }
    : { ...previous, status: 'ok', consecutiveFailures: 0, lastSuccessAt: at,
      ...(previous.status === 'error' ? { recoveredAt: at } : {}) };
  store.data.device.syncDiagnostics[channel] = next;
  store.save();
}
