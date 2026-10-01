import test from 'node:test';
import assert from 'node:assert/strict';
import {MockPlayer,VoiceSession,sessionConfig} from '../src/voice.mjs';
async function ready(options={}){const v=new VoiceSession(options);v.open();await v.receive({type:'session.updated',session:sessionConfig()});return v;}
async function waiting(v,id='SYNTHETIC_REPLY'){
  v.begin();v.capture.emit(Buffer.alloc(3200));v.end();
  await v.receive({type:'input_audio_buffer.committed',item_id:'SYNTHETIC_USER'});
  await v.receive({type:'response.created',response:{id}});
}
async function audio(v,id='SYNTHETIC_REPLY'){
  await v.receive({type:'response.audio.delta',response_id:id,delta:Buffer.alloc(4800).toString('base64')});
  await v.receive({type:'response.audio.done',response_id:id});
}
const done=(id='SYNTHETIC_REPLY',status='completed')=>({type:'response.done',response:{id,status}});
test('no microphone until explicit start and confirmed matching session config',async()=>{
  const v=new VoiceSession();assert.throws(()=>v.begin(),/NOT_READY/);v.open();assert.throws(()=>v.begin(),/NOT_READY/);
  assert.ok(!v.capture.active);await v.receive({type:'session.updated',session:{}});assert.equal(v.closed,true);
});
test('three multi-turn half-duplex Mock rounds, one session.update, manual commit/create ordering',async()=>{
  const v=await ready();for(let i=0;i<3;i++){
    await waiting(v,`reply${i}`);assert.equal(v.capture.active,false);
    assert.throws(()=>v.begin(),/NOT_READY/);await audio(v,`reply${i}`);await v.receive(done(`reply${i}`));assert.equal(v.state,'IDLE');
  }
  assert.equal(v.socket.sent.filter(e=>e.type==='session.update').length,1);assert.equal(v.player.calls.length,3);
  assert.deepEqual(v.socket.sent.slice(1,4).map(e=>e.type),['input_audio_buffer.append','input_audio_buffer.commit','response.create']);
  assert.deepEqual(v.audit.slice(0,4).map(x=>x.state),['RECORDING','WAITING','PLAYING','IDLE']);v.close();
});
test('input and output queues bounded; malformed PCM stops capture',async()=>{
  const a=await ready({maxInputBytes:100});a.begin();a.capture.emit(Buffer.alloc(3200));assert.equal(a.closed,true);assert.equal(a.capture.active,false);
  const b=await ready({maxOutputBytes:10});await waiting(b);await audio(b);assert.equal(b.closed,true);assert.equal(b.output.length,0);
  const c=await ready();c.begin();c.capture.emit(Buffer.alloc(3));assert.equal(c.closed,true);
});
test('short recording does not commit or request billable response',async()=>{
  const v=await ready();v.begin();assert.throws(()=>v.end(),/INPUT_TOO_SHORT/);assert.equal(v.socket.sent.length,1);
});
test('send failure closes socket and releases recording',async()=>{
  const v=await ready();v.begin();v.socket.failSend=true;v.capture.emit(Buffer.alloc(3200));
  assert.equal(v.closed,true);assert.equal(v.socket.closed,true);assert.equal(v.capture.active,false);
});
test('provider errors discard buffered output, release ports and redact provider text',async()=>{
  const v=await ready();await waiting(v);await audio(v);await v.receive({type:'error',error:{message:'SECRET_API_KEY DEVICE_OWNER'}});
  assert.equal(v.output.length,0);assert.equal(v.closed,true);assert.equal(v.player.calls.length,0);assert.ok(!JSON.stringify(v.audit).includes('SECRET'));
});
test('stale epoch/response ID and duplicate completed responses cannot play',async()=>{
  const v=await ready();await waiting(v);await audio(v,'OLD_REPLY');assert.equal(v.outputBytes,0);
  await audio(v);await v.receive(done());await v.receive(done());assert.equal(v.player.calls.length,1);
  const epoch=v.epoch;v.close();await v.receive({type:'response.audio.delta',response_id:'SYNTHETIC_REPLY',delta:'AAAA'},epoch);assert.equal(v.output.length,0);
});
test('playback must drain before IDLE; cancellation invalidates pending completion',async()=>{
  class HeldPlayer extends MockPlayer{play(){this.active=true;return new Promise(resolve=>this.release=resolve);}}
  const player=new HeldPlayer(),v=await ready({player});await waiting(v);await audio(v);const p=v.receive(done());
  assert.equal(v.state,'PLAYING');assert.throws(()=>v.begin(),/NOT_READY/);assert.throws(()=>v.append(Buffer.alloc(100)),/NOT_RECORDING/);
  v.close();player.release();await p;assert.equal(v.closed,true);assert.equal(player.active,false);
});
test('failed response, empty audio, malformed base64 stop without playback',async()=>{
  for(const type of ['failed','empty','base64']){
    const v=await ready();await waiting(v);
    if(type==='base64')await v.receive({type:'response.audio.delta',response_id:'SYNTHETIC_REPLY',delta:'!secret!'});
    else await v.receive(done('SYNTHETIC_REPLY',type==='failed'?'failed':'completed'));
    assert.equal(v.closed,true);assert.equal(v.player.calls.length,0);
  }
});
test('configuration/turn/session timeouts release all resources',async()=>{
  const a=new VoiceSession({turnTimeoutMs:10});a.open();await new Promise(r=>setTimeout(r,20));assert.equal(a.closed,true);
  const b=await ready({turnTimeoutMs:10});b.begin();await new Promise(r=>setTimeout(r,20));assert.equal(b.closed,true);assert.equal(b.capture.active,false);
  const c=await ready({sessionTimeoutMs:10});await new Promise(r=>setTimeout(r,20));assert.equal(c.closed,true);
});
test('turn limit bounds context; no live API/audio ports accepted',async()=>{
  const v=await ready({maxTurns:1});await waiting(v);await audio(v);await v.receive(done());assert.throws(()=>v.begin(),/TURN_LIMIT/);
  assert.throws(()=>new VoiceSession({socket:{send(){}}}),/LIVE_AUDIO_API_DISABLED/);
});
test('tiny chunks cannot bypass bounded memory via excessive queue objects',async()=>{
  const a=await ready();a.begin();for(let i=0;i<1025;i++)a.capture.emit(Buffer.alloc(2));assert.equal(a.closed,true);
  const b=await ready();await waiting(b);for(let i=0;i<4097;i++)await b.receive({type:'response.audio.delta',response_id:'SYNTHETIC_REPLY',delta:'AAA='});
  assert.equal(b.closed,true);assert.equal(b.output.length,0);
});
