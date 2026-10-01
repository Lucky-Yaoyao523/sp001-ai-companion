import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import {createConsoleServer} from '../server.mjs';import {summarySchema} from '../daily-summary.mjs';
test('summary API needs authentication and generation is explicit; delete clears saved analysis',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'sp001-summary-api-'));let calls=0;
 const {server,store}=createConsoleServer({file:path.join(dir,'db.json'),code:'fixture',summaryGenerator:async()=>{calls++;return JSON.stringify(Object.fromEntries(summarySchema.required.map(k=>[k,'测试内容'])));}});
 store.data.settings.saveTranscript=true;const at=new Date().toISOString();const date=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Hong_Kong'}).format(new Date(at));
 store.ingestDeviceBatch(1,[{seq:1,kind:'answer_complete',at,sessionId:'fixture',childText:'三加四',replyText:'七'}]);
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
 try{
  assert.equal((await fetch(base+'/api/summary',{method:'POST'})).status,401);
  const login=await fetch(base+'/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:'fixture'})});const headers={Cookie:login.headers.get('set-cookie').split(';')[0],'Content-Type':'application/json'};
  assert.equal((await fetch(base+`/api/summary?date=${date}`,{headers})).status,200);assert.equal(calls,0);
  const r=await fetch(base+'/api/summary',{method:'POST',headers,body:JSON.stringify({date})});assert.equal(r.status,200);assert.equal((await r.json()).summary.model,'gpt-6-astra');assert.equal(calls,1);
  await fetch(base+`/api/day?date=${date}`,{method:'DELETE',headers});assert.deepEqual(store.data.dailySummaries,{});
 }finally{await new Promise(r=>server.close(r));fs.rmSync(dir,{recursive:true,force:true});}
});
