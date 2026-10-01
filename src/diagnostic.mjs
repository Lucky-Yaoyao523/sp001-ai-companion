import {ackBytes,assertLiveAllowed,envelope,fail,frames,LabError,payload,profile,Reassembler,summarize} from './protocol.mjs';
// Deliberately narrower than upstream: only these two business queries are supported.
export const QUERIES=Object.freeze({INFO:{request:44,response:16},SETUP:{request:66,response:67}});
export function queryMessage(name,query,options={}) {
  if(!Object.hasOwn(QUERIES,query)) fail('QUERY_NOT_ALLOWED');
  const q=QUERIES[query];
  return envelope(name,q.request,name==='helen-0a9dc13'&&query==='INFO'?JSON.stringify({CIA:'[]'}):'',options);
}
function validReply(query,m) {
  if(m.OP!==QUERIES[query].response) return false;
  const p=payload(m); if(!p) return false;
  if(query==='SETUP') return typeof p.SSS==='boolean';
  // Conservative evidence threshold, not a claim that other firmware schemas are invalid.
  return ['VER','FW','TCI'].some(k=>typeof p[k]==='string'&&p[k].trim().length>0);
}
export class MockTransport {
  constructor({rejectAt=-1,callbackFailAt=-1,dropCallbackAt=-1}={}) {
    Object.assign(this,{rejectAt,callbackFailAt,dropCallbackAt}); this.writes=[]; this.active=0; this.maxActive=0; this.kind='MOCK';
  }
  write(action,callback) {
    const i=this.writes.length; this.writes.push({kind:action.kind,bytes:Buffer.from(action.bytes)});
    if(i===this.rejectAt) return false;
    this.active++; this.maxActive=Math.max(this.active,this.maxActive);
    if(i!==this.dropCallbackAt) queueMicrotask(()=>{this.active--;callback(i===this.callbackFailAt?133:0);});
    return true;
  }
  close() { this.active=0; }
}
export class Diagnostic {
  constructor({profileName='slt-v1.2.0',transport=new MockTransport(),timeoutMs=1000,mode='mock',...confirmation}={}) {
    profile(profileName);
    if(mode!=='mock'||!(transport instanceof MockTransport)) assertLiveAllowed({profileName,...confirmation});
    this.profileName=profileName; this.transport=transport; this.timeoutMs=timeoutMs;
    this.epoch=0; this.queue=[]; this.current=null; this.pending=null; this.ready=false; this.stopped=false;
    this.reassembler=new Reassembler(profileName); this.audit=[];
  }
  #write(kind,bytes) {
    if(this.stopped) return Promise.reject(new LabError('SESSION_STOPPED'));
    if(this.queue.length>=256) {this.stop('QUEUE_LIMIT');return Promise.reject(new LabError('QUEUE_LIMIT'));}
    return new Promise((resolve,reject)=>{this.queue.push({kind,bytes,resolve,reject});this.#pump();});
  }
  #pump() {
    if(this.current||this.stopped||!this.queue.length) return;
    const a=this.queue.shift(), epoch=this.epoch; this.current=a;
    a.timer=setTimeout(()=>this.stop('WRITE_TIMEOUT'),this.timeoutMs);
    let accepted;
    try { accepted=this.transport.write(a,status=>{
      if(epoch!==this.epoch||this.current!==a) return;
      if(status!==0) return this.stop('GATT_CALLBACK_FAILED');
      clearTimeout(a.timer);this.current=null;a.resolve();this.#pump();
    }); } catch { return this.stop('TRANSPORT_ERROR'); }
    if(accepted!==true) this.stop('GATT_REJECTED');
  }
  async query(query,options={}) {
    if(this.pending) fail('QUERY_BUSY');
    if(this.stopped) fail('SESSION_STOPPED');
    const message=queryMessage(this.profileName,query,options); // all business writes go through allowlist
    this.ready=false;
    let resolve,reject; const reply=new Promise((a,b)=>{resolve=a;reject=b;});
    // Install a handler immediately; failure can arrive while frame writes are still pending.
    reply.catch(()=>{});
    const p={query,resolve,reject,sent:false,reply:null}; this.pending=p;
    p.timer=null;
    try {
      for(const b of frames(this.profileName,message)) {
        if(this.pending!==p) fail('SESSION_STOPPED');
        await this.#write('business-query',b);
      }
      p.sent=true;
      p.timer=setTimeout(()=>this.stop('REPLY_TIMEOUT'),this.timeoutMs);
      this.complete(); return await reply;
    } catch(e) { if(this.pending===p) this.stop(e.code??'QUERY_FAILED'); throw e; }
  }
  complete() {
    const p=this.pending;
    if(p?.sent&&p.reply) {
      clearTimeout(p.timer);this.pending=null;this.ready=true;
      const result=summarize(p.reply);this.audit.push({kind:'MOCK_REPLY',...result});p.resolve(result);
    }
  }
  async notify(bytes,epoch=this.epoch,now=0) {
    if(epoch!==this.epoch||this.stopped||!this.pending) return false;
    try {
      const m=this.reassembler.feed(bytes,now);
      await this.#write('transport-ack',ackBytes(this.profileName));
      if(epoch!==this.epoch||this.stopped) return false;
      if(m&&this.pending&&validReply(this.pending.query,m)) {
        this.pending.reply=m;this.complete();return true;
      }
      return false;
    } catch(e) { this.stop(e.code??'NOTIFY_FAILED');return false; }
  }
  stop(code='CANCELLED') {
    this.epoch++;this.stopped=true;this.ready=false;this.reassembler.reset();
    const e=new LabError(code);
    if(this.current){clearTimeout(this.current.timer);this.current.reject(e);this.current=null;}
    for(const a of this.queue) a.reject(e); this.queue=[];
    if(this.pending){clearTimeout(this.pending.timer);this.pending.reject(e);this.pending=null;}
    this.transport.close();this.audit.push({kind:'STOP',code});
  }
  reconnect(transport=new MockTransport()) {
    if(!(transport instanceof MockTransport)) fail('LIVE_NOT_IMPLEMENTED');
    this.stop('RECONNECT');this.transport=transport;this.stopped=false;
  }
}
