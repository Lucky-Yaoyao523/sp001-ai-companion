import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createHmac } from 'node:crypto';
import { ConsoleStore } from '../store.mjs';
import { commandSignature, sendToyCommandOnce } from '../toy-pull.mjs';

function fakeRequest(answer, inspect) {
  return (options, callback) => {
    const req = new EventEmitter();
    req.end = body => {
      inspect(options, body);
      queueMicrotask(() => {
        const response = new PassThrough(); response.statusCode = 200;
        callback(response); response.end(JSON.stringify(answer));
      });
    };
    req.destroy = error => req.emit('error', error);
    return req;
  };
}

test('toy settings are acknowledged only after a signed matching device receipt', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'sp001-command-'));
  const fixedNow = Date.now(), key = 'test-secret', id = 'toy-fixture';
  try {
    const store = new ConsoleStore(path.join(directory, 'data.json'), () => fixedNow);
    store.updateSettings({ ...store.data.settings, dailyMinutes: 30 });
    const command = store.data.commands[0];
    let attempts = 0;
    const inspect = (options, body) => {
      attempts++;
      assert.equal(options.method, 'POST');
      assert.equal(options.path, '/api/device/command');
      assert.equal(options.headers['X-SP001-Command-Seq'], String(command.seq));
      assert.equal(options.headers['X-SP001-Expires-At'], String(Date.parse(command.expiresAt)));
      assert.deepEqual(JSON.parse(body), { type: 'SET_LIMITS', payload: command.payload });
      const expected = createHmac('sha256', key)
        .update(`POST\n/api/device/command\n${id}\n${command.seq}\n${Date.parse(command.expiresAt)}\n${fixedNow}\n`)
        .update(body).digest('hex');
      assert.equal(options.headers['X-SP001-Signature'], expected);
      assert.equal(commandSignature(key, id, command.seq, Date.parse(command.expiresAt), fixedNow, body), expected);
    };
    const options = { store, host: '192.0.2.10', deviceId: id, deviceKey: key,
      certificateDer: Buffer.from('fixture'), now: () => fixedNow };
    await assert.rejects(sendToyCommandOnce({ ...options, request: fakeRequest({ seq: command.seq + 1, code: 'APPLIED' }, inspect) }), /TOY_COMMAND_NOT_APPLIED/);
    assert.equal(command.status, 'sent');
    assert.equal(command.acknowledgedAt, null);
    const reply = await sendToyCommandOnce({ ...options, request: fakeRequest({ seq: command.seq, code: 'DUPLICATE' }, inspect) });
    assert.equal(reply.acknowledged, true);
    assert.equal(command.status, 'acknowledged');
    assert.equal(attempts, 2);
    assert.deepEqual(await sendToyCommandOnce({ ...options, request: () => { throw Error('unexpected network'); } }), { sent: false });
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
