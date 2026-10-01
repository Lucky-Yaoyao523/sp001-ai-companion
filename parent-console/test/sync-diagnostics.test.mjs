import test from 'node:test';
import assert from 'node:assert/strict';
import { recordSyncDiagnostic, safeSyncCode } from '../sync-diagnostics.mjs';
import { pollingLoop } from '../toy-pull.mjs';
test('sync diagnostics retain only safe codes and separate command recovery from pull', () => {
  let saves = 0;
  const store = { data: {device: {lastEventSeq: 885}}, save() { saves++; } };
  recordSyncDiagnostic(store, 'pull', new Error('secret address key stack'), 0);
  recordSyncDiagnostic(store, 'pull', Object.assign(new Error('credentials'), {code:'ECONNREFUSED'}), 1000);
  recordSyncDiagnostic(store, 'command', new Error('TOY_COMMAND_HTTP_401'), 2000);
  recordSyncDiagnostic(store, 'pull', null, 3000);
  const d = store.data.device.syncDiagnostics;
  assert.equal(d.pull.status, 'ok'); assert.equal(d.pull.consecutiveFailures, 0);
  assert.equal(d.pull.lastErrorCode, 'ECONNREFUSED'); assert.equal(d.pull.recoveredAt, new Date(3000).toISOString());
  assert.equal(d.command.status, 'error'); assert.equal(d.command.consecutiveFailures, 1);
  assert.equal(store.data.device.lastEventSeq, 885); assert.equal(saves, 4);
  assert.doesNotMatch(JSON.stringify(d), /secret|credentials|stack|address/);
  assert.equal(safeSyncCode(new Error('TOY_HTTP_401 credentials')), 'SYNC_ERROR');
});
test('poll diagnostics distinguish pull and commands; no pending command is not a recovery', async () => {
  let next, failPull = true, failCommand = true, pending = true;
  const events = [];
  const stop = pollingLoop({onError:(e,c)=>events.push([c,e.message]),onSuccess:c=>events.push([c,'ok'])}, 15000, {
    now:()=>100000, pull:async()=>{if(failPull) throw Error('TOY_TIMEOUT'); return {received:0};},
    command:async()=>{if(failCommand) throw Error('TOY_COMMAND_TIMEOUT');return pending ? {acknowledged:true}:{sent:false};},
    schedule:f=>{next=f;return 1;},cancel:()=>{},
  });
  await new Promise(r=>setImmediate(r)); failPull=false; await next();
  assert.deepEqual(events, [['pull','TOY_TIMEOUT'],['pull','ok'],['command','TOY_COMMAND_TIMEOUT']]);
  stop();
  const clean = pollingLoop({onSuccess:c=>events.push([c,'ok'])},15000, {
    pull:async()=>({received:0}),command:async()=>({sent:false}),schedule:()=>1,cancel:()=>{},
  });
  await new Promise(r=>setImmediate(r)); clean();
  assert.deepEqual(events.at(-1),['pull','ok']);
  assert.equal(events.filter(([c,v])=>c==='command'&&v==='ok').length,0);
});
