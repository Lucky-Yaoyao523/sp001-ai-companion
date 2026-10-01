import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { ConsoleStore } from './store.mjs';
import { daySummary } from './model.mjs';
import { createDailySummary } from './daily-summary.mjs';
import { validateDeviceEvents, verifyDeviceBatch } from './device-sync.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const zone = 'Asia/Hong_Kong';
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
const digest = value => createHash('sha256').update(value).digest();
const json = (res, status, value) => reply(res, status, 'application/json; charset=utf-8', JSON.stringify(value));
function reply(res, status, type, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', ...headers });
  res.end(body);
}
function readJson(req) {
  if (!String(req.headers['content-type'] || '').startsWith('application/json')) throw new Error('需要 JSON 请求');
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', chunk => { body += chunk; if (body.length > 65536) { reject(new Error('请求过大')); req.destroy(); } });
    req.on('end', () => { try { resolve(JSON.parse(body)); } catch { reject(new Error('JSON 格式错误')); } });
    req.on('error', reject);
  });
}

function readDeviceJson(req) {
  if (!String(req.headers['content-type'] || '').startsWith('application/json')) throw new Error('需要 JSON 请求');
  return new Promise((resolve, reject) => {
    const chunks = []; let bytes = 0;
    req.on('data', chunk => { bytes += chunk.length; if (bytes > 65536) { reject(new Error('设备批次过大')); req.destroy(); } else chunks.push(chunk); });
    req.on('end', () => {
      try { const raw = Buffer.concat(chunks); resolve({ raw, input: JSON.parse(raw.toString('utf8')) }); }
      catch { reject(new Error('JSON 格式错误')); }
    });
    req.on('error', reject);
  });
}

export function createConsoleServer({ file = path.join(root, 'data', 'console.json'), code = randomBytes(12).toString('hex'), now = () => Date.now(), deviceKey = null, deviceId = null, allowedHost = null, requireTls = false, tls = null, summaryGenerator = null, localAutoAccess = false, readOnly = false } = {}) {
  const store = new ConsoleStore(file, now, { readOnly });
  const summaries = createDailySummary(store, summaryGenerator);
  const sessions = new Map();
  const expected = digest(code);
  let failedLogins = 0;
  const handler = async (req, res) => {
    try {
      const host = req.headers.host || '';
      if (allowedHost ? !(Array.isArray(allowedHost) ? allowedHost.includes(host) : host === allowedHost) : !/^((127\.0\.0\.1)|(localhost))(\:\d+)?$/.test(host)) return json(res, 403, { error: '访问地址不匹配' });
      const scheme = req.socket.encrypted ? 'https' : 'http';
      const url = new URL(req.url, `${scheme}://${host}`);
      const origin = req.headers.origin;
      if (origin && origin !== `${scheme}://${host}`) return json(res, 403, { error: '来源不匹配' });
      if (readOnly && req.method !== 'GET' && !(req.method === 'POST' && url.pathname === '/api/login')) {
        return json(res, 423, { error: '备用后台只读；切换同步后再修改设置' });
      }
      if (req.method === 'POST' && url.pathname === '/api/device/sync') {
        if (requireTls && !req.socket.encrypted) return json(res, 403, { error: '设备同步需要 HTTPS' });
        if (!deviceKey || !deviceId) return json(res, 503, { error: '设备同步未配置' });
        const { raw, input } = await readDeviceJson(req);
        let batchSeq;
        try { batchSeq = verifyDeviceBatch(req.headers, raw, { key: deviceKey, deviceId, now: now() }); }
        catch { return json(res, 401, { error: '设备认证失败' }); }
        const events = validateDeviceEvents(input);
        return json(res, 200, { ok: true, ...store.ingestDeviceBatch(batchSeq, events), receivedAt: new Date(now()).toISOString() });
      }
      const cookie = /(?:^|;\s*)parent_session=([0-9a-f]{64})(?:;|$)/.exec(req.headers.cookie || '')?.[1];
      // Only the loopback-bound PC page skips the access code. The LAN HTTPS
      // listener still requires its parent session, even with a forged Host.
      const localAccess = localAutoAccess && !req.socket.encrypted &&
        req.socket.localAddress === '127.0.0.1' && req.socket.remoteAddress === '127.0.0.1' &&
        (host === `127.0.0.1:${req.socket.localPort}` || host === `localhost:${req.socket.localPort}`);
      const authenticated = localAccess || (!!cookie && (sessions.get(cookie) || 0) > now());
      if (req.method === 'POST' && url.pathname === '/api/login') {
        if (failedLogins >= 10) return json(res, 429, { error: '尝试次数过多，请重启本地服务' });
        const input = await readJson(req);
        const candidate = digest(String(input.code || ''));
        if (!timingSafeEqual(candidate, expected)) { failedLogins++; return json(res, 401, { error: '访问码错误' }); }
        failedLogins = 0;
        const token = randomBytes(32).toString('hex');
        sessions.set(token, now() + 12 * 3600000);
        return reply(res, 200, 'application/json; charset=utf-8', JSON.stringify({ ok: true }), { 'Set-Cookie': `parent_session=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200${req.socket.encrypted ? '; Secure' : ''}` });
      }
      if (url.pathname === '/' && req.method === 'GET') return serveStatic(res, 'index.html');
      if (url.pathname === '/styles.css' && req.method === 'GET') return serveStatic(res, 'styles.css');
      if (url.pathname === '/app.js' && req.method === 'GET') return serveStatic(res, 'app.js');
      if (!authenticated) return json(res, 401, { error: '请先登录' });
      if (url.pathname === '/api/summary' && req.method === 'GET') return json(res, 200, summaries.current(url.searchParams.get('date') || today()));
      if (url.pathname === '/api/summary' && req.method === 'POST') {
        const input = await readJson(req);
        return json(res, 200, {summary:await summaries.run(input.date || today())});
      }
      if (url.pathname === '/api/logout' && req.method === 'POST') {
        sessions.delete(cookie);
        return reply(res, 200, 'application/json; charset=utf-8', JSON.stringify({ ok: true }), { 'Set-Cookie': 'parent_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0' });
      }
      if (url.pathname === '/api/state' && req.method === 'GET') {
        const snap = store.snapshot();
        const fresh = snap.device?.lastSeenAt && now() - Date.parse(snap.device.lastSeenAt) < 10 * 60000;
        return json(res, 200, { ...snap, today: today(), access: localAccess ? 'local' : 'session', transport: fresh ? 'device_recent' : 'not_connected', storage: snap.device?.pairedAt ? 'home_lan' : 'local_prototype', mode: readOnly ? 'standby' : 'active' });
      }
      if (url.pathname === '/api/day' && req.method === 'GET') {
        const date = url.searchParams.get('date') || today();
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json(res, 400, { error: '日期格式错误' });
        return json(res, 200, daySummary(store.snapshot().events, date, zone));
      }
      if (url.pathname === '/api/export' && req.method === 'GET') {
        const date = url.searchParams.get('date') || today();
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json(res, 400, { error: '日期格式错误' });
        if (url.searchParams.get('format') === 'txt') {
          const day = daySummary(store.snapshot().events, date, zone);
          const clock = new Intl.DateTimeFormat('zh-CN', { timeZone: zone, hour: '2-digit', minute: '2-digit', hour12: false });
          const lines = [`${date} 聊天记录`, '时间为香港时间，来自每轮日志的记录时间。', ''];
          for (const event of day.events) {
            const time = clock.format(new Date(event.at));
            if (event.childText) lines.push(`[${time}] 孩子：${event.childText}`);
            if (event.replyText) lines.push(`[${time}] 蜘蛛侠：${event.replyText}`);
            if (event.kind === 'answer_partial') lines.push('（回答未完成，仅保留可确认的文字）');
            if (event.childText || event.replyText || event.kind === 'answer_partial') lines.push('');
          }
          if (lines.length === 3) lines.push('这一天暂无已保存的聊天文字。');
          return reply(res, 200, 'text/plain; charset=utf-8', '\uFEFF' + lines.join('\r\n'), { 'Content-Disposition': `attachment; filename="sp001-chat-${date}.txt"` });
        }
        return reply(res, 200, 'application/json; charset=utf-8', JSON.stringify(daySummary(store.snapshot().events, date, zone), null, 2), { 'Content-Disposition': `attachment; filename="sp001-${date}.json"` });
      }
      if (url.pathname === '/api/settings' && req.method === 'PUT') {
        const settings = store.updateSettings(await readJson(req));
        if (!settings.saveTranscript) summaries.clear();
        return json(res, 200, {settings, transport:'queued_for_toy'});
      }
      if (url.pathname === '/api/events' && req.method === 'POST') return json(res, 201, { event: store.addEvent({ ...await readJson(req), evidence: 'manual_import' }) });
      if (url.pathname === '/api/demo' && req.method === 'POST') {
        const input = await readJson(req);
        if (input.confirm !== true) return json(res, 400, { error: '需要确认载入演示记录' });
        const base = `${today()}T10:00:00+08:00`;
        const at = minutes => new Date(Date.parse(base) + minutes * 60000).toISOString();
        const sample = [
          { kind: 'session_start', at: at(0), sessionId: '演示会话-1', topic: '太空与星星' },
          { kind: 'turn_start', at: at(1), sessionId: '演示会话-1', turnId: '1', topic: '太空与星星' },
          { kind: 'answer_complete', at: at(2), sessionId: '演示会话-1', turnId: '1', topic: '太空与星星' },
          { kind: 'tool_requested', at: at(5), sessionId: '演示会话-1', turnId: '2', topic: '天气', toolName: '天气查询' },
          { kind: 'tool_result', at: at(6), sessionId: '演示会话-1', turnId: '2', topic: '天气', toolName: '天气查询', toolStatus: 'failed', reason: '查询超时（演示）' },
          { kind: 'answer_complete', at: at(7), sessionId: '演示会话-1', turnId: '2', topic: '天气' },
          { kind: 'session_end', at: at(12), sessionId: '演示会话-1', durationMs: 720000, reason: '正常结束' },
        ];
        for (const e of sample) store.addEvent({ ...e, evidence: 'demo' });
        return json(res, 201, { imported: sample.length, evidence: 'demo' });
      }
      if (url.pathname === '/api/day' && req.method === 'DELETE') { summaries.clear(); return json(res, 200, { deleted: store.deleteDay(url.searchParams.get('date') || '') }); }
      return json(res, 404, { error: '未找到' });
    } catch (error) {
      return json(res, 400, { error: error.message || '请求失败' });
    }
  };
  const server = http.createServer(handler);
  const lanServer = tls ? https.createServer(tls, handler) : null;
  function serveStatic(res, name) {
    const type = name.endsWith('.css') ? 'text/css; charset=utf-8' : name.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8';
    const body = fs.readFileSync(path.join(root, 'public', name));
    reply(res, 200, type, body, { 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'" });
  }
  return { server, lanServer, code, store };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { server, code } = createConsoleServer();
  server.listen(8787, '127.0.0.1', () => {
    console.log('家长网页本地原型：http://127.0.0.1:8787');
    console.log(`本次启动访问码：${code}`);
    console.log('仅本机访问；未连接玩具、未部署远程服务。');
  });
}
