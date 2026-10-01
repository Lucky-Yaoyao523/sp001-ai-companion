import fs from 'node:fs';
import path from 'node:path';
import { DEFAULT_SETTINGS, makeCommand, normalizeEvent, pruneEvents, validateSettings } from './model.mjs';

export class ConsoleStore {
  constructor(file, now = () => Date.now(), { readOnly = false } = {}) {
    this.file = file;
    this.now = now;
    this.readOnly = readOnly;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    this.data = fs.existsSync(file)
      ? JSON.parse(fs.readFileSync(file, 'utf8'))
      : { settings: { ...DEFAULT_SETTINGS }, events: [], commands: [] };
    this.data.settings = validateSettings(this.data.settings);
    this.data.events ??= [];
    this.data.commands ??= [];
    this.data.device ??= { lastBatchSeq: 0, lastEventSeq: 0, lastSeenAt: null };
  }

  save() {
    if (this.readOnly) throw Error('备用后台只读，不能保存记录');
    const temp = `${this.file}.${process.pid}.tmp`;
    const fd = fs.openSync(temp, 'w', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(this.data, null, 2)); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temp, this.file);
  }

  snapshot() {
    if (this.readOnly) return structuredClone({ settings: this.data.settings, events: this.data.events,
      commands: this.data.commands, device: this.data.device });
    const current = this.now();
    const before = this.data.events.length;
    this.data.events = pruneEvents(this.data.events, this.data.settings.retentionDays, current);
    if (this.data.events.length !== before) this.data.dailySummaries = {};
    this.data.commands = this.data.commands.filter(c => Date.parse(c.createdAt) >= current - 30 * 86400000);
    this.save();
    return { settings: this.data.settings, events: this.data.events, commands: this.data.commands, device: this.data.device };
  }

  updateSettings(input) {
    const next = validateSettings(input);
    const previous = this.data.settings;
    this.data.settings = next;
    if (!next.saveTranscript) {
      this.data.dailySummaries = {};
      for (const e of this.data.events) { delete e.childText; delete e.replyText; }
    }
    const payload = { dailyMinutes: next.dailyMinutes, dailySessions: next.dailySessions, quietStart: next.quietStart, quietEnd: next.quietEnd };
    const oldPayload = { dailyMinutes: previous.dailyMinutes, dailySessions: previous.dailySessions, quietStart: previous.quietStart, quietEnd: previous.quietEnd };
    if (JSON.stringify(payload) !== JSON.stringify(oldPayload)) this.enqueue('set_limits', payload);
    if (next.paused !== previous.paused) this.enqueue(next.paused ? 'pause' : 'resume');
    this.save();
    return next;
  }

  enqueue(type, payload = {}) {
    const command = makeCommand(type, payload, this.now());
    command.seq = Math.max(command.seq, (this.data.commands.at(-1)?.seq || 0) + 1);
    this.data.commands.push(command);
    return command;
  }

  addEvent(input) {
    const event = normalizeEvent(input, this.data.settings.saveTranscript, this.now());
    this.data.events.push(event);
    const beforePrune = this.data.events.length;
    this.data.events = pruneEvents(this.data.events, this.data.settings.retentionDays, this.now());
    if (this.data.events.length > 10000) this.data.events = this.data.events.slice(-10000);
    if (this.data.events.length !== beforePrune) this.data.dailySummaries = {};
    this.save();
    return event;
  }

  ingestDeviceBatch(batchSeq, events) {
    const device = this.data.device;
    if (batchSeq < device.lastBatchSeq) throw new Error('设备批次序号倒退');
    if (batchSeq === device.lastBatchSeq) return { duplicate: true, acceptedThrough: device.lastEventSeq };
    const original = structuredClone(this.data);
    try {
      for (const item of events) {
        if (item.seq <= device.lastEventSeq) continue;
        if (item.seq !== device.lastEventSeq + 1) throw new Error('设备事件序号缺失');
        const evidence = item.kind === 'tool_result' ? 'device_receipt' : 'device_event';
        const event = normalizeEvent({ ...item, evidence }, this.data.settings.saveTranscript, this.now(), true);
        event.sourceSeq = item.seq;
        event.receivedAt = new Date(this.now()).toISOString();
        this.data.events.push(event);
        device.lastEventSeq = item.seq;
      }
      const beforePrune = this.data.events.length;
      this.data.events = pruneEvents(this.data.events, this.data.settings.retentionDays, this.now());
      if (this.data.events.length > 10000) this.data.events = this.data.events.slice(-10000);
      if (this.data.events.length !== beforePrune) this.data.dailySummaries = {};
      device.lastBatchSeq = batchSeq;
      device.lastSeenAt = new Date(this.now()).toISOString();
      this.save();
      return { duplicate: false, acceptedThrough: device.lastEventSeq };
    } catch (error) { this.data = original; throw error; }
  }
  markDeviceSeen() {
    this.data.device.lastSeenAt = new Date(this.now()).toISOString();
    this.save();
  }

  // Explicit local recovery only. Never infer a missing range from a remote batch.
  recordDeviceGap(first, last, reason) {
    const device = this.data.device;
    if (!Number.isSafeInteger(first) || !Number.isSafeInteger(last) || first !== device.lastEventSeq + 1 || last < first
        || reason !== 'DIAGNOSTIC_PREMATURE_ACK') throw new Error('DEVICE_GAP_REQUIRES_EXACT_REVIEW');
    const prior = structuredClone(this.data);
    try {
      device.gaps ??= [];
      device.gaps.push({ first, last, count: last - first + 1, reason, recordedAt: new Date(this.now()).toISOString() });
      device.lastEventSeq = last;
      device.lastBatchSeq = Math.max(device.lastBatchSeq || 0, last);
      this.save();
    } catch (error) { this.data = prior; throw error; }
  }

  deleteDay(date, timeZone = 'Asia/Hong_Kong') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('日期格式错误');
    const localDay = iso => new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(iso));
    const before = this.data.events.length;
    if (this.data.dailySummaries) delete this.data.dailySummaries[date];
    this.data.events = this.data.events.filter(e => localDay(e.at) !== date);
    this.save();
    return before - this.data.events.length;
  }

  // Used only after an authenticated toy transport attempt; UI requests queue commands.
  markSent(id) {
    const c = this.data.commands.find(c => c.id === id);
    if (!c || c.status !== 'queued' || Date.parse(c.expiresAt) <= this.now()) return false;
    c.status = 'sent'; c.sentAt = new Date(this.now()).toISOString(); this.save(); return true;
  }

  acknowledge(id, seq) {
    const c = this.data.commands.find(c => c.id === id);
    if (!c || c.status !== 'sent' || c.seq !== seq || Date.parse(c.expiresAt) <= this.now()) return false;
    c.status = 'acknowledged'; c.acknowledgedAt = new Date(this.now()).toISOString(); this.save(); return true;
  }
}
