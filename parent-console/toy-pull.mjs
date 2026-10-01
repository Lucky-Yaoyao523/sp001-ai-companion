import https from 'node:https';
import net from 'node:net';
// Socket idle timeouts alone do not bound a peer that slowly drips bytes.
function boundedRequest(request) {
  return (options, callback) => {
    let timer;
    const clear = () => clearTimeout(timer);
    const req = request(options, res => { res.once('end', clear); res.once('error', clear); res.once('close', clear); callback(res); });
    timer = setTimeout(() => req.destroy(new Error('TOY_DEADLINE')), 15000);
    timer.unref?.(); req.once('error', clear); req.once('close', clear);
    return req;
  };
}
import { createHmac } from 'node:crypto';
import { validateDeviceEvents } from './device-sync.mjs';
import { ConsoleStore } from './store.mjs';

const certificatePem = der => `-----BEGIN CERTIFICATE-----\n${der.toString('base64').match(/.{1,64}/g).join('\n')}\n-----END CERTIFICATE-----\n`;
const signature = (key, id, after, sentAt) => createHmac('sha256', key)
  .update(`GET\n/api/device/events\n${id}\n${after}\n${sentAt}`).digest('hex');
export const commandSignature = (key, id, seq, expiresAt, sentAt, body) => createHmac('sha256', key)
  .update(`POST\n/api/device/command\n${id}\n${seq}\n${expiresAt}\n${sentAt}\n`)
  .update(body).digest('hex');

export async function pullToyOnce({ store, host, deviceId, deviceKey, certificateDer, now = () => Date.now(), request = https.request }) {
  // The GET cursor is an acknowledgment: never issue it from a disposable diagnostic store.
  if (!(store instanceof ConsoleStore)) throw new Error('TOY_DURABLE_CURSOR_REQUIRED');
  const after = store.data.device.lastEventSeq || 0;
  const sentAt = now();
  const body = await new Promise((resolve, reject) => {
    const req = boundedRequest(request)({ hostname: host, port: 8789,
      path: `/api/device/events?after=${after}`, method: 'GET', timeout: 7000,
      ca: certificatePem(certificateDer), minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2',
      headers: { 'X-SP001-Device-ID': deviceId, 'X-SP001-After': String(after),
        'X-SP001-Sent-At': String(sentAt), 'X-SP001-Signature': signature(deviceKey, deviceId, after, sentAt) },
    }, res => {
      if (res.statusCode !== 200) { res.resume(); reject(new Error(`TOY_HTTP_${res.statusCode}`)); return; }
      const chunks = []; let bytes = 0;
      res.on('data', chunk => { bytes += chunk.length; if (bytes > 65536) { req.destroy(new Error('TOY_RESPONSE_TOO_LARGE')); return; } chunks.push(chunk); });
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('TOY_TIMEOUT')));
    req.on('error', reject); req.end();
  });
  const input = JSON.parse(body.toString('utf8'));
  const events = validateDeviceEvents(input);
  let sync;
  if (input.sync != null) {
    const s = input.sync;
    for (const name of ['pendingEvents', 'capacityEvents', 'journalBytes', 'capacityBytes', 'droppedEvents']) {
      if (!Number.isSafeInteger(s[name]) || s[name] < 0) throw new Error('TOY_SYNC_STATUS_INVALID');
    }
    if (s.pendingEvents < events.length || s.capacityEvents !== 5000 || s.capacityBytes !== 8388608) throw new Error('TOY_SYNC_STATUS_INVALID');
    sync = { pendingEvents: s.pendingEvents - events.length, capacityEvents: s.capacityEvents,
      journalBytes: s.journalBytes, capacityBytes: s.capacityBytes, droppedEvents: s.droppedEvents,
      observedAt: new Date(sentAt).toISOString() };
  }
  if (events.some(event => event.seq <= after)) throw new Error('TOY_REPLAY_ORDER');
  if (events.length) store.ingestDeviceBatch(events.at(-1).seq, events);
  else store.markDeviceSeen();
  if (sync) { store.data.device.sync = sync; store.save(); }
  if (input.battery && Number.isInteger(input.battery.percent) && input.battery.percent >= 0 && input.battery.percent <= 100 && typeof input.battery.charging === 'boolean') {
    store.data.device.battery = {percent:input.battery.percent,charging:input.battery.charging,observedAt:new Date(sentAt).toISOString()};
    store.save();
  }
  return { connected: true, received: events.length, acceptedThrough: store.data.device.lastEventSeq };
}

export async function sendToyCommandOnce({ store, host, deviceId, deviceKey, certificateDer, now = () => Date.now(), request = https.request }) {
  const command = store.data.commands.find(item => ['queued', 'sent'].includes(item.status) && Date.parse(item.expiresAt) > now());
  if (!command) return { sent: false };
  const type = { set_limits: 'SET_LIMITS', pause: 'PAUSE', resume: 'RESUME' }[command.type];
  if (!type || !Number.isSafeInteger(command.seq) || command.seq < 1) throw new Error('TOY_COMMAND_INVALID');
  const expiresAt = Date.parse(command.expiresAt), sentAt = now();
  const body = Buffer.from(JSON.stringify({ type, payload: command.payload }), 'utf8');
  if (body.length < 1 || body.length > 2048) throw new Error('TOY_COMMAND_BOUND');
  if (command.status === 'queued' && !store.markSent(command.id)) return { sent: false };
  const result = await new Promise((resolve, reject) => {
    const req = boundedRequest(request)({ hostname: host, port: 8789, path: '/api/device/command', method: 'POST', timeout: 7000,
      ca: certificatePem(certificateDer), minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2',
      headers: { 'Content-Type': 'application/json', 'Content-Length': String(body.length),
        'X-SP001-Device-ID': deviceId, 'X-SP001-Command-Seq': String(command.seq),
        'X-SP001-Expires-At': String(expiresAt), 'X-SP001-Sent-At': String(sentAt),
        'X-SP001-Signature': commandSignature(deviceKey, deviceId, command.seq, expiresAt, sentAt, body) },
    }, res => {
      if (res.statusCode !== 200) { res.resume(); reject(new Error(`TOY_COMMAND_HTTP_${res.statusCode}`)); return; }
      const chunks = []; let bytes = 0;
      res.on('data', chunk => { bytes += chunk.length; if (bytes > 4096) { req.destroy(new Error('TOY_COMMAND_RESPONSE_BOUND')); return; } chunks.push(chunk); });
      res.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(new Error('TOY_COMMAND_RESPONSE_JSON')); } });
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('TOY_COMMAND_TIMEOUT')));
    req.on('error', reject); req.end(body);
  });
  if (result.seq !== command.seq || !['APPLIED', 'DUPLICATE'].includes(result.code)) throw new Error('TOY_COMMAND_NOT_APPLIED');
  if (!store.acknowledge(command.id, command.seq)) throw new Error('TOY_COMMAND_ACK_STORE');
  return { sent: true, acknowledged: true, seq: command.seq };
}

export function startToyPolling(options, intervalMs = 15000) {
  return pollingLoop(options, intervalMs);
}

export function toyPortReachable({ host }, connect = net.connect) {
  return new Promise(resolve => {
    let done = false, socket;
    const finish = reachable => { if (done) return; done = true; socket?.destroy(); resolve(reachable); };
    try {
      socket = connect({ host, port: 8789 });
      socket.setTimeout(2000, () => finish(false));
      socket.once('connect', () => finish(true));
      socket.once('error', () => finish(false));
    } catch { finish(false); }
  });
}

export function pollingLoop(options, intervalMs = 15000, runtime = {}) {
  const pull = runtime.pull || pullToyOnce, command = runtime.command || sendToyCommandOnce;
  const probe = runtime.probe || toyPortReachable;
  const schedule = runtime.schedule || setTimeout, cancel = runtime.cancel || clearTimeout;
  const now = runtime.now || Date.now;
  let stopped = false, timer, nextCommandAt = 0, consecutiveFailures = 0, lastFullAttemptAt = now();
  const report = (channel, error) => { try { options.onError?.(error, channel); } catch {} };
  const success = channel => { try { options.onSuccess?.(channel); } catch {} };
  const checkOffline = async () => {
    let reachable = false;
    try { reachable = await probe(options); } catch {}
    if (stopped) return;
    if (reachable || now() - lastFullAttemptAt >= 30 * 60000) timer = schedule(poll, 0);
    else timer = schedule(checkOffline, 5 * 60000);
  };
  const poll = async () => {
    lastFullAttemptAt = now();
    let delay = intervalMs;
    try {
      const result = await pull(options);
      success('pull');
      consecutiveFailures = 0;
      if (result.received >= 8) delay = 1000;
      if (!stopped && now() >= nextCommandAt) {
        nextCommandAt = now() + intervalMs;
        try { const result = await command(options); if (result?.acknowledged) success('command'); } catch (error) { report('command', error); }
      }
    } catch (error) {
      // One quick retry for a handoff, then cheap presence checks; full offline sync at most twice per hour.
      if (++consecutiveFailures > 1) delay = null;
      report('pull', error);
    } finally { if (!stopped) timer = schedule(delay === null ? checkOffline : poll, delay === null ? 5 * 60000 : delay); }
  };
  void poll();
  return () => { stopped = true; if (timer !== undefined) cancel(timer); };
}
