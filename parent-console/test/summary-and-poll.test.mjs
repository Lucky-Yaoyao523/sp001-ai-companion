import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import { pollingLoop, toyPortReachable } from '../toy-pull.mjs';import { createDailySummary, summarySchema } from '../daily-summary.mjs';import { ConsoleStore } from '../store.mjs';import { EventEmitter } from 'node:events';
const settle=()=>new Promise(r=>setImmediate(r));
test('online polls stay fast; offline uses five minute presence checks and half hour full checks',async()=>{
 let failures=true,reachable=false,call=0,probes=0,commands=0,clock=0,next;const delays=[];
 const stop=pollingLoop({},15000,{now:()=>clock,pull:async()=>{call++;if(failures)throw Error('offline');return{received:8};},probe:async()=>{probes++;return reachable;},command:async()=>{commands++;throw Error('command offline');},schedule:(fn,ms)=>{next=fn;delays.push(ms);return 1;},cancel:()=>{next=null;}});
 await settle();assert.equal(delays.at(-1),15000);
 clock+=15000;await next();assert.equal(delays.at(-1),5*60000);
 for(let i=0;i<5;i++){clock+=5*60000;await next();assert.equal(delays.at(-1),5*60000);}
 assert.equal(call,2);assert.equal(probes,5);
 clock+=5*60000;await next();assert.equal(delays.at(-1),0);
 await next();assert.equal(call,3);assert.equal(delays.at(-1),5*60000);
 reachable=true;failures=false;clock+=5*60000;await next();assert.equal(delays.at(-1),0);
 await next();assert.equal(delays.at(-1),1000);assert.equal(commands,1);
 clock+=1000;await next();assert.equal(commands,1);stop();assert.equal(next,null);assert.equal(call,5);
 let resolve;const scheduled=[];const end=pollingLoop({},15000,{pull:()=>new Promise(r=>resolve=r),schedule:(f,t)=>scheduled.push(t),command:async()=>assert.fail('stopped')});end();resolve({received:8});await settle();assert.deepEqual(scheduled,[]);
});
test('a new short outage after recovery gets a fresh single fast retry',async()=>{
 let failed=false,next;const delays=[];
 const stop=pollingLoop({},15000,{pull:async()=>{if(failed)throw Error('handoff');return{received:0};},probe:async()=>true,command:async()=>{},schedule:(fn,ms)=>{next=fn;delays.push(ms);return 1;}});
 await settle();failed=true;await next();await next();assert.deepEqual(delays,[15000,15000,5*60000]);
 await next();assert.equal(delays.at(-1),0);failed=false;await next();failed=true;await next();assert.deepEqual(delays.slice(-2),[15000,15000]);stop();
});
test('presence probe closes the socket after connection or timeout',async()=>{
 const sockets=[];const connect=()=>{const socket=new EventEmitter();socket.setTimeout=(ms,fn)=>{socket.timeout=fn;assert.equal(ms,2000);};socket.destroy=()=>{socket.destroyed=true;};sockets.push(socket);return socket;};
 const online=toyPortReachable({host:'toy.invalid'},connect);sockets[0].emit('connect');assert.equal(await online,true);assert.equal(sockets[0].destroyed,true);
 const offline=toyPortReachable({host:'toy.invalid'},connect);sockets[1].timeout();assert.equal(await offline,false);assert.equal(sockets[1].destroyed,true);
});
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'sp001-summary-'));const s=new ConsoleStore(path.join(dir,'db.json'));s.data.settings.saveTranscript=true;const at=new Date().toISOString();s.ingestDeviceBatch(1,[{seq:1,kind:'answer_complete',at,sessionId:'test',childText:'三加四',replyText:'七'}]);const date=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Hong_Kong'}).format(new Date(at));return{dir,s,date};}
const output=JSON.stringify(Object.fromEntries(summarySchema.required.map(k=>[k,'合成总结'])));
test('summary calls only on demand, coalesces clicks and invalidates after deletion',async()=>{
 const f=fixture();try{let calls=0,done;const summary=createDailySummary(f.s,async args=>{calls++;assert.match(args.input,/三加四/);return new Promise(r=>done=r);});
 assert.equal(summary.current(f.date).summary,null);assert.equal(calls,0);
 const a=summary.run(f.date),b=summary.run(f.date);assert.equal(calls,1);done(output);assert.deepEqual(await a,await b);
 await summary.run(f.date);assert.equal(calls,1);
 const restored=createDailySummary(new ConsoleStore(f.s.file),async()=>assert.fail('cached summary must survive restart'));assert.ok((await restored.run(f.date)).content.overview);
 f.s.deleteDay(f.date);assert.equal(summary.current(f.date).summary,null);await assert.rejects(summary.run(f.date),/暂无/);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('summary rejects malformed output and a deleted source while in flight',async()=>{
 const f=fixture();try{await assert.rejects(createDailySummary(f.s,async()=>'{bad').run(f.date),/格式/);
 let done;const s=createDailySummary(f.s,()=>new Promise(r=>done=r));const pending=s.run(f.date);f.s.deleteDay(f.date);done(output);await assert.rejects(pending,/记录已更新/);assert.equal(s.current(f.date).summary,null);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('new conversations arriving during generation keep the completed snapshot with a visible stale flag',async()=>{
 const f=fixture();try{let done;const summary=createDailySummary(f.s,()=>new Promise(r=>done=r));const pending=summary.run(f.date);
  f.s.ingestDeviceBatch(2,[{seq:2,kind:'answer_complete',at:new Date().toISOString(),sessionId:'new',childText:'你好',replyText:'你好'}]);
  done(output);assert.equal((await pending).stale,true);assert.equal(summary.current(f.date).summary.stale,true);assert.equal(summary.current(f.date).summary.recordCount,1);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
test('updated summary guidance preserves old report for reading but regenerates on explicit click',async()=>{
 const f=fixture();try{
  let calls=0;const summary=createDailySummary(f.s,async()=>{calls++;return output;});
  await summary.run(f.date);f.s.data.dailySummaries[f.date].revision=1;f.s.save();
  assert.equal(summary.current(f.date).summary.stale,true);assert.equal(calls,1);
  await summary.run(f.date);assert.equal(calls,2);assert.equal(summary.current(f.date).summary.stale,false);
 }finally{fs.rmSync(f.dir,{recursive:true,force:true});}
});
