import {VoiceSession,sessionConfig} from './voice.mjs';
const v=new VoiceSession();v.open();await v.receive({type:'session.updated',session:sessionConfig()});
for(let i=0;i<3;i++){
  v.begin();v.capture.emit(Buffer.alloc(3200));v.end();
  await v.receive({type:'input_audio_buffer.committed',item_id:`synthetic_user_${i}`});
  await v.receive({type:'response.created',response:{id:`synthetic_reply_${i}`}});
  await v.receive({type:'response.audio.delta',response_id:`synthetic_reply_${i}`,delta:Buffer.alloc(4800).toString('base64')});
  await v.receive({type:'response.audio.done',response_id:`synthetic_reply_${i}`});
  await v.receive({type:'response.done',response:{id:`synthetic_reply_${i}`,status:'completed'}});
}
const report={category:'MOCK',input:'SYNTHETIC_SILENCE',deviceVerified:false,paidApiCalls:0,turns:v.turns,states:v.audit,playback:v.player.calls,state:v.state};
v.close();console.log(JSON.stringify(report,null,2));
