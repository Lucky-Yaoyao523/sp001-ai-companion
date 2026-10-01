import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
import {ackBytes,assertLiveAllowed,decodePlain,encodePlain,envelope,frames,parseMessage,Reassembler,setupDone,summarize} from '../src/protocol.mjs';
import {replay} from '../src/replay.mjs';
const P='slt-v1.2.0';
const message={OP:67,DT:JSON.stringify({SSS:false,text:'你好，"蜘蛛侠"\n反斜杠\\🙂'})};
test('UTF8/Chinese/escaping/nested DT roundtrip for both explicit profiles',()=>{
  for(const p of [P,'helen-0a9dc13']){
    const r=new Reassembler(p);let result;for(const b of frames(p,message))result=r.feed(b);
    assert.deepEqual(result,message);assert.equal(typeof result.DT,'string');
  }
});
test('independent fixed XOR/base64 vector and framing boundary',()=>{
  assert.equal(encodePlain('{}'),'Xwg=');assert.equal(decodePlain('Xwg='),'{}');
  for(let n=0;n<80;n++){
    const m={OP:44,DT:'x'.repeat(n)};const f=frames(P,m);
    assert.equal(f[0].toString(),'#MS');assert.equal(f.at(-1).toString(),'#ME');
    assert.ok(f.slice(1,-2).every(b=>b.length===20));
    assert.deepEqual(parseMessage(decodePlain(Buffer.concat(f.slice(1,-1)).toString())),m);
  }
});
test('profile envelopes preserve ID/ACC difference, no ID invention',()=>{
  assert.deepEqual(envelope(P,44),{OP:44,DT:''});
  assert.deepEqual(envelope('helen-0a9dc13',44),{OP:44,DT:'',ACC:'0'});
  assert.throws(()=>envelope('helen-0a9dc13',66),/DEVICE_ID_REQUIRED/);
  assert.equal(envelope('helen-0a9dc13',66,'',{deviceId:'SYNTHETIC'}).ID,'SYNTHETIC');
  assert.throws(()=>envelope(P,44,{}),/DT_MUST_BE_STRING/);
});
test('binary ACK and ASCII ACK cannot be interchanged; live remains locked',()=>{
  assert.equal(ackBytes(P).toString('hex'),'01');assert.equal(ackBytes('helen-0a9dc13').toString('hex'),'31');
  assert.throws(()=>assertLiveAllowed({profileName:'unknown'}),/UNKNOWN_PROFILE/);
  assert.throws(()=>assertLiveAllowed({profileName:'__proto__'}),/UNKNOWN_PROFILE/);
  assert.throws(()=>assertLiveAllowed({profileName:P}),/LIVE_UNCONFIRMED/);
  assert.throws(()=>assertLiveAllowed({profileName:P,confirmedDevice:'SYNTHETIC',protocolEvidence:'SYNTHETIC'}),/LIVE_NOT_IMPLEMENTED/);
});
test('missing start/end, nested start, duplicate end are rejected',()=>{
  const r=new Reassembler(P);
  assert.throws(()=>r.feed(Buffer.from('AAAA')),/MISSING_START/);
  r.feed(Buffer.from('#MS'));assert.throws(()=>r.finish(),/MISSING_END/);
  r.feed(Buffer.from('#MS'));assert.throws(()=>r.feed(Buffer.from('#MS')),/NESTED_START/);
  for(const b of frames(P,message))r.feed(b);
  assert.throws(()=>r.feed(Buffer.from('#ME')),/MISSING_START/);
});
test('bad encoding, base64, JSON, UTF8 and truncated payload fail closed',()=>{
  for(const s of ['A','!!!!','AB==','AAAA\n'])assert.throws(()=>decodePlain(s),/BAD_BASE64/);
  assert.throws(()=>parseMessage('not json'),/BAD_JSON/);
  assert.throws(()=>parseMessage('{"OP":16,"DT":{}}'),/BAD_ENVELOPE/);
  // 0xff XOR first key byte => 0xdb, decrypts to invalid UTF8.
  assert.throws(()=>decodePlain('2w=='),/BAD_UTF8/);
  const r=new Reassembler(P);r.feed(Buffer.from('#MS'));
  assert.throws(()=>r.feed(Buffer.from([255])),/BAD_ENCODING/);
  const f=frames(P,message);const rr=new Reassembler(P);for(const b of f.slice(0,-2))rr.feed(b);
  assert.throws(()=>rr.feed(Buffer.from('#ME')));
});
test('input limits, chunk limits, total frame timeout and time reversal',()=>{
  assert.throws(()=>encodePlain('中'.repeat(30000)),/PLAIN_LIMIT/);
  const r=new Reassembler(P,{maxBytes:20,timeoutMs:10});r.feed(Buffer.from('#MS'),0);
  r.feed(Buffer.from('A'.repeat(20)),1);assert.throws(()=>r.feed(Buffer.from('AAAA'),2),/FRAME_LIMIT/);
  r.feed(Buffer.from('#MS'),0);assert.throws(()=>r.tick(10),/FRAME_TIMEOUT/);
  r.feed(Buffer.from('#MS'),10);assert.throws(()=>r.tick(9),/BAD_TIME/);
  assert.throws(()=>r.feed(Buffer.alloc(21)),/BAD_CHUNK/);
});
test('setupDone missing/false/true are distinct and logs omit private fields',()=>{
  assert.equal(setupDone({DT:'{}'}),null);assert.equal(setupDone(message),false);
  assert.equal(setupDone({OP:67,DT:'{"SSS":true}'}),true);
  assert.equal(setupDone({OP:67,DT:'{"SSS":"false"}'}),null);
  assert.equal(setupDone({OP:67,DT:'{"setupDone":false}'}),null);
  assert.equal(setupDone({OP:16,DT:'{"SSS":false}'}),null);
  const secret='SYNTHETIC_PRIVATE_VALUE';
  const out=JSON.stringify(summarize({OP:16,ID:secret,ACC:secret,DT:JSON.stringify({apiKey:secret,password:secret,SSID:secret,name:secret,[secret]:secret})}));
  assert.ok(!out.includes(secret));
});
test('replay structured reports label synthetic evidence and never assert device ready',()=>{
  const events=frames(P,message).map((b,i)=>({type:'notify',atMs:i,hex:b.toString('hex')}));
  const report=replay({schema:1,origin:'SYNTHETIC',profile:P,events});
  assert.equal(report.ok,true);assert.equal(report.messages.length,1);assert.equal(report.ready,false);assert.equal(report.deviceVerified,false);
  const bad=replay({schema:1,origin:'UNVERIFIED_CAPTURE',profile:P,events:events.slice(0,-1)});
  assert.equal(bad.errors[0].code,'MISSING_END');assert.equal(bad.messages.length,0);
  assert.throws(()=>replay({schema:1,origin:'DEVICE_VERIFIED',profile:P,events}),/BAD_CAPTURE/);
});
test('replay cancels partial frames and reports malformed hex without leaking it',()=>{
  const r=replay({schema:1,origin:'SYNTHETIC',profile:P,events:[{type:'notify',atMs:0,hex:'234d53'},{type:'cancel',atMs:1},{type:'notify',atMs:2,hex:'PRIVATE_SECRET'}]});
  assert.equal(r.messages.length,0);assert.equal(r.errors[0].code,'BAD_EVENT');assert.ok(!JSON.stringify(r).includes('PRIVATE_SECRET'));
});
test('importing offline modules has no network/device/process side effects',()=>{
  const r=spawnSync(process.execPath,['--input-type=module','-e',`globalThis.fetch=()=>{throw Error('NETWORK_FORBIDDEN')};globalThis.WebSocket=class{constructor(){throw Error('NETWORK_FORBIDDEN')}};await import('./src/protocol.mjs');await import('./src/diagnostic.mjs');await import('./src/voice.mjs');`],{encoding:'utf8'});
  assert.equal(r.status,0,r.stderr);assert.equal(r.stdout,'');
});
test('CLI rejects unknown/live commands and malformed inputs with sanitized error',()=>{
  const result=spawnSync(process.execPath,['src/cli.mjs','live','SECRET'],{encoding:'utf8'});
  assert.equal(result.status,1);assert.ok(!result.stderr.includes('SECRET'));
  assert.ok(fs.existsSync('src/cli.mjs'));
});
