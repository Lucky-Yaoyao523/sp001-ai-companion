import {fail,profile,Reassembler,summarize} from './protocol.mjs';
export function replay(capture) {
  if(!capture||capture.schema!==1||!['SYNTHETIC','UNVERIFIED_CAPTURE'].includes(capture.origin)||!Array.isArray(capture.events)||capture.events.length>10000) fail('BAD_CAPTURE');
  profile(capture.profile);
  const r=new Reassembler(capture.profile), messages=[],errors=[];
  let last=-Infinity;
  for(let index=0;index<capture.events.length;index++) {
    const e=capture.events[index];
    try {
      if(!e||!Number.isFinite(e.atMs)||e.atMs<0||e.atMs<last) fail('BAD_TIME');
      last=e.atMs;
      if(e.type==='disconnect'||e.type==='cancel'){r.reset();continue;}
      if(e.type==='tick'){r.tick(e.atMs);continue;}
      if(e.type!=='notify'||typeof e.hex!=='string'||e.hex.length>40||! /^(?:[0-9a-fA-F]{2})+$/.test(e.hex)) fail('BAD_EVENT');
      const m=r.feed(Buffer.from(e.hex,'hex'),e.atMs);if(m) messages.push({index,...summarize(m)});
    }catch(e){errors.push({index,code:e.code??'REPLAY_ERROR'});r.reset();}
  }
  try{r.finish();}catch(e){errors.push({index:capture.events.length,code:e.code});}
  return {schema:1,category:'OFFLINE_REPLAY',origin:capture.origin,profile:capture.profile,deviceVerified:false,ready:false,messages,errors,ok:errors.length===0};
}
