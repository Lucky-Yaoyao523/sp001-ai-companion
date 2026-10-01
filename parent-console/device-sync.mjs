import { createHmac, timingSafeEqual } from 'node:crypto';

const numeric = value => typeof value === 'string' && /^(?:[1-9]\d{0,14})$/.test(value);
const signature = (key, deviceId, batchSeq, sentAt, body) => createHmac('sha256', key)
  .update(`${deviceId}\n${batchSeq}\n${sentAt}\n`).update(body).digest('hex');

export function signDeviceBatch(key, deviceId, batchSeq, sentAt, body) {
  if (!key || !deviceId || !numeric(String(batchSeq)) || !numeric(String(sentAt))) throw new Error('设备签名参数错误');
  return signature(key, deviceId, String(batchSeq), String(sentAt), body);
}

export function verifyDeviceBatch(headers, body, { key, deviceId, now = Date.now() }) {
  if (!key || !deviceId) throw new Error('设备同步未配置');
  const actualId = headers['x-sp001-device-id'];
  const batchSeq = headers['x-sp001-batch-seq'];
  const sentAt = headers['x-sp001-sent-at'];
  const actual = headers['x-sp001-signature'];
  if (actualId !== deviceId || !numeric(batchSeq) || !numeric(sentAt)
      || Math.abs(Number(sentAt) - now) > 5 * 60000 || typeof actual !== 'string' || !/^[a-f0-9]{64}$/.test(actual)) {
    throw new Error('设备身份或时间无效');
  }
  const expected = signature(key, deviceId, batchSeq, sentAt, body);
  if (!timingSafeEqual(Buffer.from(actual, 'hex'), Buffer.from(expected, 'hex'))) throw new Error('设备签名无效');
  return Number(batchSeq);
}

export function validateDeviceEvents(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || !Array.isArray(input.events) || input.events.length > 32) throw new Error('设备批次格式错误');
  let previous = 0;
  return input.events.map(event => {
    if (!event || typeof event !== 'object' || Array.isArray(event) || !Number.isSafeInteger(event.seq) || event.seq <= previous) throw new Error('设备事件序号错误');
    previous = event.seq;
    return event;
  });
}
