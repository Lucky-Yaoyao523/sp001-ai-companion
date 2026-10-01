// Platform-neutral reference core, NOT an Android app. No network or real audio implementation.
import {fail,LabError,strictBase64} from './protocol.mjs';
export const CLOUD=Object.freeze({
  checked:'2026-09-14',model:'qwen3.5-omni-plus-realtime',voice:'Ethan',
  region:'cn-beijing',inputRate:16000,outputRate:24000,
  endpointTemplate:'wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime',
  docs:'https://help.aliyun.com/zh/model-studio/realtime'
});
export function sessionConfig() {
  return {modalities:['text','audio'],voice:CLOUD.voice,turn_detection:null,enable_search:false,
    instructions:'请用自然、简洁的中文与我轮流交谈。',
    audio:{input:{format:{type:'pcm',sample_rate:16000}},output:{format:{type:'pcm',sample_rate:24000}}}};
}
export class MockSocket {
  constructor(){this.sent=[];this.closed=false;this.failSend=false;}
  send(event){if(this.closed||this.failSend) return false;this.sent.push(structuredClone(event));return true;}
  close(){this.closed=true;}
}
export class MockCapture {
  start(callback){if(this.active) fail('CAPTURE_BUSY');this.active=true;this.callback=callback;}
  emit(pcm){if(this.active)this.callback(pcm);}
  stop(){this.active=false;this.callback=null;}
}
export class MockPlayer {
  constructor(){this.calls=[];this.active=false;}
  async play(pcm,rate){this.active=true;this.calls.push({bytes:pcm.length,rate});this.active=false;}
  stop(){this.active=false;}
}
export class VoiceSession {
  constructor({socket=new MockSocket(),capture=new MockCapture(),player=new MockPlayer(),maxInputBytes=960000,maxOutputBytes=2880000,maxTurns=20,turnTimeoutMs=60000,sessionTimeoutMs=600000}={}) {
    // Current release is exclusively executable with Mock ports. This is intentional, not an SDK22 runtime claim.
    if(!(socket instanceof MockSocket)||!(capture instanceof MockCapture)||!(player instanceof MockPlayer)) fail('LIVE_AUDIO_API_DISABLED');
    Object.assign(this,{socket,capture,player,maxInputBytes,maxOutputBytes,maxTurns,turnTimeoutMs,sessionTimeoutMs});
    this.state='IDLE';this.configured=false;this.closed=false;this.epoch=0;this.serial=0;this.turns=0;this.seen=new Set();this.audit=[];
    this.resetTurn();
  }
  resetTurn(){this.inputBytes=0;this.inputChunks=0;this.outputBytes=0;this.output=[];this.responseId=null;this.awaitCommit=false;this.createdRequested=false;this.audioDone=false;}
  send(type,data={}) {
    if(this.closed) fail('VOICE_CLOSED');
    let ok=false;try{ok=this.socket.send({event_id:`lab_${++this.serial}`,type,...data});}catch{}
    if(ok!==true){this.close('SEND_FAILED');fail('SEND_FAILED');}
  }
  open(){
    if(this.closed||this.opened) fail('VOICE_ALREADY_OPEN_OR_CLOSED');
    this.opened=true;this.send('session.update',{session:sessionConfig()});
    this.timer=setTimeout(()=>this.close('CONFIG_TIMEOUT'),this.turnTimeoutMs);
    this.sessionTimer=setTimeout(()=>this.close('SESSION_LIMIT'),this.sessionTimeoutMs);
  }
  transition(next){this.state=next;this.audit.push({state:next,category:'MOCK'});}
  begin(){
    if(this.closed||!this.configured||this.state!=='IDLE') fail('NOT_READY_TO_RECORD');
    if(this.turns>=this.maxTurns){this.close('TURN_LIMIT');fail('TURN_LIMIT');}
    this.resetTurn();this.turns++;this.transition('RECORDING');
    this.timer=setTimeout(()=>this.close('TURN_TIMEOUT'),this.turnTimeoutMs);
    const epoch=this.epoch;
    try{this.capture.start(pcm=>{if(epoch===this.epoch&&this.state==='RECORDING'&&!this.closed){try{this.append(pcm);}catch(e){this.close(e.code??'CAPTURE_FAILED');}}});}
    catch{this.close('CAPTURE_FAILED');fail('CAPTURE_FAILED');}
  }
  append(pcm){
    if(this.state!=='RECORDING'||this.closed) fail('NOT_RECORDING');
    if(!Buffer.isBuffer(pcm)||pcm.length===0||pcm.length%2||pcm.length>6400){this.close('BAD_PCM');fail('BAD_PCM');}
    this.inputBytes+=pcm.length;
    this.inputChunks++;
    if(this.inputBytes>this.maxInputBytes||this.inputChunks>1024){this.close('INPUT_LIMIT');fail('INPUT_LIMIT');}
    this.send('input_audio_buffer.append',{audio:pcm.toString('base64')});
  }
  end(){
    if(this.state!=='RECORDING') fail('NOT_RECORDING');
    try{this.capture.stop();}catch{this.close('CAPTURE_STOP_FAILED');fail('CAPTURE_STOP_FAILED');}
    // Local minimum 100 ms at 16 kHz PCM16; deliberately do not commit empty/very short buffers.
    if(this.inputBytes<3200){this.close('INPUT_TOO_SHORT');fail('INPUT_TOO_SHORT');}
    this.transition('WAITING');this.awaitCommit=true;this.send('input_audio_buffer.commit');
  }
  async receive(event,epoch=this.epoch){
    if(epoch!==this.epoch||this.closed) return;
    try{
      // Bound frame text before parsing in a future socket adapter; also bound object-shaped Mock input here.
      const json=JSON.stringify(event);if(!json||json.length>262144) fail('EVENT_LIMIT');
      if(!event||typeof event.type!=='string') fail('BAD_EVENT');
      if(event.type==='error') fail('PROVIDER_ERROR');
      if(event.type==='session.updated'){
        if(!this.opened||this.configured) return;
        const s=event.session;
        if(s?.turn_detection!==null||s.audio?.input?.format?.sample_rate!==16000||s.audio?.output?.format?.sample_rate!==24000||s.audio?.input?.format?.type!=='pcm'||s.audio?.output?.format?.type!=='pcm'||s.voice!==CLOUD.voice||!s.modalities?.includes('audio')) fail('CONFIG_MISMATCH');
        this.configured=true;clearTimeout(this.timer);return;
      }
      if(event.type==='input_audio_buffer.committed'){
        if(this.state!=='WAITING'||!this.awaitCommit) return;
        if(typeof event.item_id!=='string'||!event.item_id) fail('BAD_COMMIT');
        this.awaitCommit=false;this.createdRequested=true;this.send('response.create');return;
      }
      if(event.type==='response.created'){
        const id=event.response?.id;
        if(typeof id!=='string'||!id||id.length>256) fail('BAD_RESPONSE_ID');
        if(this.state!=='WAITING'||!this.createdRequested||this.responseId||this.seen.has(id)) return;
        this.responseId=id;this.seen.add(id);return;
      }
      if(event.type==='response.audio.delta'){
        if(this.state!=='WAITING'||!this.responseId||event.response_id!==this.responseId) return;
        if(this.audioDone) fail('AUDIO_AFTER_DONE');
        const pcm=strictBase64(event.delta,262144);
        if(pcm.length%2) fail('BAD_PCM');
        this.outputBytes+=pcm.length;if(this.outputBytes>this.maxOutputBytes||this.output.length>=4096) fail('OUTPUT_LIMIT');
        this.output.push(pcm);return;
      }
      if(event.type==='response.audio.done'){
        if(this.state==='WAITING'&&this.responseId&&event.response_id===this.responseId)this.audioDone=true;
        return;
      }
      if(event.type==='response.done'){
        if(this.state!=='WAITING'||!this.responseId||event.response?.id!==this.responseId) return;
        if(event.response.status!=='completed') fail('RESPONSE_FAILED');
        if(!this.audioDone||!this.outputBytes) fail('MISSING_AUDIO');
        const pcm=Buffer.concat(this.output);this.output=[];this.outputBytes=0;
        this.transition('PLAYING');
        // Playback resolves only when drained. Cancellation invalidates its completion.
        await this.player.play(pcm,24000);
        if(epoch!==this.epoch||this.closed) return;
        clearTimeout(this.timer);this.resetTurn();this.transition('IDLE');return;
      }
      // Text and transcript events are neither logged nor accumulated.
    }catch(e){this.close(e instanceof LabError?e.code:'VOICE_EVENT_FAILED');}
  }
  close(code='CANCELLED'){
    if(this.closed) return;
    this.closed=true;this.epoch++;clearTimeout(this.timer);clearTimeout(this.sessionTimer);
    // Close the connection on error/cancel so old remote audio cannot leak into a fresh session.
    for(const cleanup of [()=>this.capture.stop(),()=>this.player.stop(),()=>this.socket.close()]){try{cleanup();}catch{}}
    this.configured=false;this.resetTurn();this.transition('IDLE');this.audit.push({code,category:'MOCK'});
  }
}
