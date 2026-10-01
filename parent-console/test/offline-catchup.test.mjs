import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import { EventEmitter } from 'node:events';
import { ConsoleStore } from '../store.mjs';import { pullToyOnce } from '../toy-pull.mjs';
const at='2026-09-26T01:00:00.000Z',now=()=>Date.parse('2026-09-27T01:00:00.000Z');
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'sp001-outbox-'));return{dir,file:path.join(dir,'db.json')}}
function requestFor(input,seen){return(options,callback)=>{seen.push(Number(options.headers['X-SP001-After']));const req=new EventEmitter();req.end=()=>queueMicrotask(()=>{const res=new EventEmitter();res.statusCode=200;callback(res);res.emit('data',Buffer.from(JSON.stringify(input)));res.emit('end');});req.destroy=e=>req.emit('error',e);return req;};}
const events=(from,to)=>Array.from({length:to-from+1},(_,i)=>({seq:from+i,kind:'answer_complete',at,sessionId:'offline',turnId:String(from+i),childText:'三加四等于几？',replyText:'七。'}));
const batch=(rows,pending=rows.length)=>({events:rows,nextSeq:13,sync:{pendingEvents:pending,capacityEvents:5000,journalBytes:4096,capacityBytes:8388608,droppedEvents:0}});
function pull(store,input,seen=[]){return pullToyOnce({store,host:'toy.invalid',deviceId:'testdevice',deviceKey:'a'.repeat(64),certificateDer:Buffer.alloc(1),now,request:requestFor(input,seen)});}
test('offline text survives service restart, catches up in order, and only durable cursor acknowledges toy',async()=>{
 const f=fixture();try{let s=new ConsoleStore(f.file,now);s.data.settings.saveTranscript=true;const seen=[];
  await pull(s,batch(events(1,8),12),seen);assert.equal(s.data.device.sync.pendingEvents,4);
  s=new ConsoleStore(f.file,now);await pull(s,batch(events(9,12)),seen);
  s=new ConsoleStore(f.file,now);await pull(s,batch([]),seen);
  assert.deepEqual(seen,[0,8,12]);assert.equal(s.data.events.length,12);assert.equal(s.data.device.sync.pendingEvents,0);
  assert.ok(s.data.events.every(e=>e.childText==='三加四等于几？'&&e.replyText==='七。'&&e.at===at));
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('a disposable probe cannot acknowledge toy events before durable storage',async()=>{
 let requested=false;
 await assert.rejects(pullToyOnce({store:{data:{device:{lastEventSeq:8}}},host:'toy.invalid',deviceId:'testdevice',deviceKey:'a'.repeat(64),certificateDer:Buffer.alloc(1),now,request:()=>{requested=true;}}),/TOY_DURABLE_CURSOR_REQUIRED/);
 assert.equal(requested,false);
});
test('an explicitly reviewed gap stays visible while later genuine toy events resume',()=>{
 const f=fixture();try{const s=new ConsoleStore(f.file,now);s.ingestDeviceBatch(1,events(1,1));
  assert.throws(()=>s.ingestDeviceBatch(4,events(4,4)),/设备事件序号缺失/);
  assert.throws(()=>s.recordDeviceGap(3,3,'DIAGNOSTIC_PREMATURE_ACK'),/DEVICE_GAP_REQUIRES_EXACT_REVIEW/);
  s.recordDeviceGap(2,3,'DIAGNOSTIC_PREMATURE_ACK');assert.equal(s.data.device.lastEventSeq,3);
  s.ingestDeviceBatch(4,events(4,4));const restored=new ConsoleStore(f.file,now);
  assert.deepEqual(restored.data.device.gaps.map(g=>[g.first,g.last,g.count]),[[2,3,2]]);
  assert.deepEqual(restored.data.events.map(e=>e.sourceSeq),[1,4]);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('failed durable save never advances the acknowledgment cursor',async()=>{
 const f=fixture();try{const s=new ConsoleStore(f.file,now);s.save();const save=s.save;s.save=()=>{throw Error('DISK_FULL');};
  await assert.rejects(pull(s,batch(events(1,2))),/DISK_FULL/);assert.equal(s.data.device.lastEventSeq,0);
  const restored=new ConsoleStore(f.file,now);assert.equal(restored.data.device.lastEventSeq,0);
  s.save=save;const seen=[];await pull(s,batch(events(1,2)),seen);assert.deepEqual(seen,[0]);assert.equal(s.data.events.length,2);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('overflow is visible and malformed storage metadata is rejected before accepting text',async()=>{
 const f=fixture();try{const s=new ConsoleStore(f.file,now);const full=batch(events(1,2));full.sync.droppedEvents=3;await pull(s,full);assert.equal(s.data.device.sync.droppedEvents,3);
  const invalid=batch(events(3,4));invalid.sync.pendingEvents=0;await assert.rejects(pull(s,invalid),/TOY_SYNC_STATUS_INVALID/);assert.equal(s.data.device.lastEventSeq,2);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('old offline conversations are not discarded on arrival and duplicates cannot extend retention',()=>{
 const f=fixture();try{let clock=Date.parse('2027-01-20T01:00:00.000Z');const s=new ConsoleStore(f.file,()=>clock);s.data.settings.saveTranscript=true;
  s.ingestDeviceBatch(2,events(1,2));assert.equal(s.data.events.length,2);assert.equal(s.data.events[0].at,at);
  const received=s.data.events[0].receivedAt;clock+=6*86400000;s.ingestDeviceBatch(2,events(1,2));assert.equal(s.snapshot().events.length,2);assert.equal(s.data.events[0].receivedAt,received);
  clock+=2*86400000;assert.equal(s.snapshot().events.length,0);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('real battery metadata is bounded, persists, and missing or invalid telemetry never fabricates a level',async()=>{
 const f=fixture();try{const s=new ConsoleStore(f.file,now);const input=batch(events(1,2));input.battery={percent:37,charging:false};await pull(s,input);
  assert.equal(new ConsoleStore(f.file,now).data.device.battery.percent,37);
  const invalid=batch(events(3,4));invalid.battery={percent:101,charging:true};await pull(s,invalid);assert.equal(s.data.device.lastEventSeq,4);assert.equal(s.data.device.battery.percent,37);
  const old=batch(events(5,6));await pull(s,old);assert.equal(s.data.device.battery.observedAt,new Date(now()).toISOString());
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
