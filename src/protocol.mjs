// Independently written offline codec. Synthetic/offline reference only; no live device transport.
export class LabError extends Error {
  constructor(code) { super(code); this.name = 'LabError'; this.code = code; }
}
export const fail = code => { throw new LabError(code); };
export const LIMIT = 65536;
const KEY = Buffer.from([36,117,63,52,100,77,107,63,105,105,47,59,98,48,103]);
export const PROFILES = Object.freeze({
  'slt-v1.2.0': Object.freeze({ack:1, envelope:'minimal', descriptor:'740563d6-3736-4033-87b5-029c38ccc893'}),
  'helen-0a9dc13': Object.freeze({ack:49, envelope:'id-acc', descriptor:'740563d5-3736-4033-87b5-029c38ccc894'})
});
export function profile(name) { return Object.hasOwn(PROFILES,name)?PROFILES[name]:fail('UNKNOWN_PROFILE'); }
export function ackBytes(name) { return Buffer.from([profile(name).ack]); }
export function assertLiveAllowed({profileName, confirmedDevice, protocolEvidence} = {}) {
  profile(profileName);
  if(!confirmedDevice || !protocolEvidence) fail('LIVE_UNCONFIRMED');
  // No actual transport exists in this release, even if callers supply confirmation strings.
  fail('LIVE_NOT_IMPLEMENTED');
}
function xor(b) { return Buffer.from(b.map((v,i) => v ^ KEY[i % KEY.length])); }
export function encodePlain(plain) {
  if(typeof plain !== 'string' || Buffer.byteLength(plain)>LIMIT) fail('PLAIN_LIMIT');
  return xor(Buffer.from(plain,'utf8')).toString('base64');
}
export function strictBase64(s, limit=LIMIT*2) {
  if(typeof s!=='string'||!s.length||s.length>limit||s.length%4!==0||!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(s)) fail('BAD_BASE64');
  const b=Buffer.from(s,'base64');
  if(b.toString('base64')!==s) fail('BAD_BASE64');
  return b;
}
export function decodePlain(s) {
  const b=xor(strictBase64(s));
  if(b.length>LIMIT) fail('PLAIN_LIMIT');
  try { return new TextDecoder('utf-8',{fatal:true}).decode(b); } catch { fail('BAD_UTF8'); }
}
export function parseMessage(plain) {
  if(typeof plain!=='string'||Buffer.byteLength(plain)>LIMIT) fail('PLAIN_LIMIT');
  let m; try { m=JSON.parse(plain); } catch { fail('BAD_JSON'); }
  if(!m||Array.isArray(m)||!Number.isSafeInteger(m.OP)||m.OP<0||m.OP>65535||typeof m.DT!=='string') fail('BAD_ENVELOPE');
  if(('ID' in m&&typeof m.ID!=='string')||('ACC' in m&&typeof m.ACC!=='string')) fail('BAD_ENVELOPE');
  return m;
}
export function envelope(name,op,dt='',{deviceId,acc='0'}={}) {
  const p=profile(name);
  if(typeof dt!=='string') fail('DT_MUST_BE_STRING');
  const m={OP:op,DT:dt};
  if(p.envelope==='id-acc') {
    if(op!==44) { if(typeof deviceId!=='string'||!deviceId) fail('DEVICE_ID_REQUIRED'); m.ID=deviceId; }
    m.ACC=acc;
  }
  parseMessage(JSON.stringify(m));
  return m;
}
export function frames(name,message) {
  profile(name);
  const s=encodePlain(JSON.stringify(parseMessage(JSON.stringify(message))));
  return [Buffer.from('#MS'),...Array.from({length:Math.ceil(s.length/20)},(_,i)=>Buffer.from(s.slice(i*20,i*20+20))),Buffer.from('#ME')];
}
export class Reassembler {
  constructor(name,{timeoutMs=3000,maxBytes=LIMIT*2}={}) {
    profile(name); this.timeoutMs=timeoutMs; this.maxBytes=maxBytes; this.reset();
  }
  reset() { this.active=false; this.parts=[]; this.size=0; this.started=0; this.last=-Infinity; }
  reject(code) { this.reset(); fail(code); }
  tick(now) {
    if(!Number.isFinite(now)||now<this.last) this.reject('BAD_TIME');
    this.last=now;
    if(this.active&&now-this.started>=this.timeoutMs) this.reject('FRAME_TIMEOUT');
  }
  feed(b,now=0) {
    this.tick(now);
    if(!Buffer.isBuffer(b)||!b.length||b.length>20) this.reject('BAD_CHUNK');
    if(b.equals(Buffer.from('#MS'))) {
      if(this.active) this.reject('NESTED_START');
      this.active=true; this.started=now; return null;
    }
    if(!this.active) this.reject('MISSING_START');
    if(b.equals(Buffer.from('#ME'))) {
      const s=Buffer.concat(this.parts).toString('latin1'); this.reset();
      return parseMessage(decodePlain(s));
    }
    if([...b].some(v=>v>127)||!/^[A-Za-z0-9+/=]+$/.test(b.toString('ascii'))) this.reject('BAD_ENCODING');
    this.size+=b.length;
    if(this.size>this.maxBytes) this.reject('FRAME_LIMIT');
    this.parts.push(Buffer.from(b)); return null;
  }
  finish() { if(this.active) this.reject('MISSING_END'); }
}
export function payload(m) {
  try { const p=JSON.parse(m.DT); return p&&typeof p==='object'&&!Array.isArray(p)?p:null; } catch { return null; }
}
export function setupDone(m) {
  // SSS in OP67 maps to the app's internal setupDone. STM is a separate mode flag.
  const p=payload(m); return m.OP===67&&typeof p?.SSS==='boolean'?p.SSS:null;
}
// Allowlisted summaries only: never emit arbitrary keys, IDs, raw frames, DT, transcripts or error text.
export function summarize(m) {
  return {op:m.OP,dtBytes:Buffer.byteLength(m.DT),idPresent:'ID' in m,accPresent:'ACC' in m,setupDone:setupDone(m)};
}
