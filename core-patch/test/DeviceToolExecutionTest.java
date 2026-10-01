package org.sp001.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Real native client/engine and typed functions; fake provider and device ports. No live effects. */
public final class DeviceToolExecutionTest {
 static int checks;
 static void yes(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static JSONObject call(String id,String action)throws Exception{return new JSONObject().put("type","function_call").put("name","control_device").put("call_id",id).put("arguments",new JSONObject().put("action",action).toString());}
 static JSONObject receipt(boolean success,boolean changed,String code,String answer)throws Exception{return new JSONObject().put("success",success).put("changed",changed).put("code",code).put("answer",answer).put("acousticVerified",false);}
 static JSONObject returned(ApiOwnedDialogueTest.Wire wire,int request,String id)throws Exception{
  JSONArray input=wire.request(request).getJSONArray("input");
  for(int i=0;i<input.length();i++){JSONObject item=input.getJSONObject(i);if("function_call_output".equals(item.optString("type"))&&id.equals(item.optString("call_id")))return new JSONObject(item.getString("output"));}
  throw new AssertionError("missing correlated execution receipt");
 }
 static void runReceipt(boolean success,boolean changed,String code)throws Exception{
  String answer=changed?"已调整音量。":success?"已经达到最大音量。":"本次没有调好音量。";
  JSONObject r=receipt(success,changed,code,answer);ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("volume", "volume_up")),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(answer)));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] calls={0};StringBuilder spoken=new StringBuilder();
  try{
   c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(action->{yes(action.equals("volume_up"),"typed device id, not language classification");calls[0]++;return r;});
   c.replySegments("你的声音有点轻，能再响一些吗？",s->{yes(s.actions.isEmpty(),"speech does not carry executable actions");spoken.append(s.speech);return s.speech;});
   JSONObject actual=returned(wire,1,"volume");yes(actual.getBoolean("success")==success&&actual.getBoolean("changed")==changed&&actual.getString("code").equals(code),"unchanged actual receipt enters model");
   yes(calls[0]==1&&wire.calls==2,"one operation and one result round trip");yes(spoken.toString().contains(answer)&&!c.endAfterReply(),"truthful reply does not close conversation");
   yes(wire.request(0).getString("tool_choice").equals("auto"),"LLM chooses tool");c.commitReply();
  }finally{c.finishTurn();c.close();}
 }
 public static void directActionRequiresTool()throws Exception{
  String user="你的声音有点轻，我听起来费劲。";
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ModelTurnControlTest.result(false,"volume_up",user),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("这轮没有调整设备。")));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] calls={0};
  try{c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(a->{calls[0]++;throw new AssertionError("text must not actuate");});c.replySegments(user,s->{yes(s.actions.isEmpty(),"unplayed old action JSON rejected before delivery");return s.speech;});yes(calls[0]==0,"no textual action executed");}finally{c.finishTurn();c.close();}
 }
 static void rejectedCalls()throws Exception{
  JSONObject a=call("same","volume_up"),b=call("same","eye_smile");
  for(JSONObject[] batch:new JSONObject[][]{{a,b},{call("one","volume_up"),call("two","volume_down")},{call("bad","delete_files")}}){
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(batch));int[] writes={0};MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);
   try{c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(action->{writes[0]++;return receipt(true,true,"APPLIED","完成。");});c.replySegments("帮我调整一下。",s->s.speech);yes(writes[0]==0&&"ANSWER_FAILED".equals(c.knowledgeStatus().optString("state")),"whole call batch validated before effects");}finally{c.finishTurn();c.close();}
  }
 }
 /** Malformed provider output is a failed response, never partial device authorization. */
 static void malformedProviderDoesNotActuate()throws Exception{
  JSONObject brokenDevice=call("broken","volume_up").put("arguments","{\"action\":");
  JSONObject brokenWeather=new JSONObject().put("type","function_call").put("name","get_weather").put("call_id","weather").put("arguments","{\"city\":");
  JSONObject primitive=ApiOwnedDialogueTest.response().put("output",new JSONArray().put("not an output item"));
  JSONObject[] responses={ApiOwnedDialogueTest.response(brokenDevice),ApiOwnedDialogueTest.response(call("valid","volume_up"),brokenWeather),primitive,
      ApiOwnedDialogueTest.response(call("unfinished","volume_up").put("status","in_progress")),
      ApiOwnedDialogueTest.response(call("bad-id","volume_up").put("call_id",7))};
  for(JSONObject response:responses){
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(response);int[] writes={0};StringBuilder said=new StringBuilder();
   MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);
   try{
    c.beginTurn(()->false,x->{x.run();return true;});
    c.bindDeviceExecutor(action->{writes[0]++;return receipt(true,true,"VOLUME_APPLIED","已调整。");});
    c.replySegments("声音大一点，然后告诉我北京明天天气。",s->{said.append(s.speech);return s.speech;});
    yes(writes[0]==0,"malformed or unfinished batch cannot partially actuate");
    yes(wire.calls==1&&"ANSWER_FAILED".equals(c.knowledgeStatus().optString("state")),"malformed provider input has a bounded, explicit failure path");
    yes(said.length()>0&&!c.endAfterReply(),"failure tells the user instead of going silent or ending chat");
   }finally{c.finishTurn();c.close();}
  }
 }
 static JSONObject endCall(String id,String farewell)throws Exception{
  return new JSONObject().put("type","function_call").put("name","end_session").put("call_id",id).put("arguments",new JSONObject().put("farewell",farewell).toString());
 }
 static void standardEndTool()throws Exception{
  for(boolean compound:new boolean[]{false,true}){
   JSONObject end=endCall("farewell","好，我们下次再聊。");
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(compound?ApiOwnedDialogueTest.response(call("volume","volume_down"),end):ApiOwnedDialogueTest.response(end));
   MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] actions={0};StringBuilder said=new StringBuilder();
   try{
    c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(action->{actions[0]++;return receipt(true,true,"VOLUME_APPLIED","已调小音量。");});
    c.replySegments(compound?"先小声点，然后退下吧。":"我得去吃饭了，咱们下次见。",s->{yes(s.actions.isEmpty()&&s.followUp.isEmpty(),"terminal tool has no extra actions or follow-up");said.append(s.speech);return s.speech;});
    yes(c.endAfterReply()&&wire.calls==1,"typed end tool closes after farewell without extra model formatting");
    yes(actions[0]==(compound?1:0)&&said.toString().endsWith("好，我们下次再聊。"),"compound actions complete before farewell");
    yes("MODEL_SESSION_END_TOOL".equals(c.knowledgeStatus().optString("answerMode")),"terminal tool has explicit receipt");c.commitReply();
   }finally{c.finishTurn();c.close();}
  }
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("volume","volume_up"),endCall("one","再见。"),endCall("two","再见。")));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] actions={0};
  try{c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(a->{actions[0]++;return receipt(true,true,"APPLIED","完成。");});c.replySegments("大声点再退下。",s->s.speech);yes(actions[0]==0&&!c.endAfterReply(),"conflicting terminal batch rejected before any effects");}finally{c.finishTurn();c.close();}
 }
 static void compoundAndHistory()throws Exception{
  StringBuilder story=new StringBuilder();for(int i=0;i<35;i++)story.append("小猴子观察线索，试了一个办法，再换个办法。");
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("compound","volume_down")),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(story.toString())),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("后来他们一起修好了小桥。")));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] writes={0};StringBuilder output=new StringBuilder();
  try{
   c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(a->{writes[0]++;return receipt(true,true,"VOLUME_APPLIED","已调小音量。");});c.replySegments("先小声些，再讲个完整故事。",s->{output.append(s.speech);return s.speech;});
   yes(output.toString().equals(story.toString())&&output.length()>500&&!c.endAfterReply(),"tool result does not truncate or replace requested story");yes(c.childOutputLimit()>PcmStreamState.MAX_BYTES,"full reply budget not topic mode");c.commitReply();c.finishTurn();
   c.beginTurn(()->false,x->{x.run();return true;});c.replySegments("后来呢？",s->s.speech);yes(writes[0]==1,"new turn cannot replay previous device operation");yes(returned(wire,2,"compound").getBoolean("success"),"actual result retained with conversation history");
  }finally{c.finishTurn();c.close();}
 }
 static void failedActionPreservesIndependentAnswer()throws Exception{
  for(boolean atBound:new boolean[]{false,true}){
   String story="孙悟空请小猴子先退到安全的山坡，等成年猴子确认路面安全。小猴子想到画一张绕行地图，大家照着地图回到了家。";
   String notice=atBound?"已经达到最大音量，本次没有变化。":"这次没能调整音量，音量保持不变。";
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("no_change","volume_up")),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(notice+story)));
   MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] writes={0};StringBuilder said=new StringBuilder();
   try{c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(a->{writes[0]++;return receipt(atBound,false,atBound?"VOLUME_AT_BOUND":"VOLUME_NOT_APPLIED",notice);});
    c.replySegments("声音大一点，再讲一个完整的原创故事。",s->{said.append(s.speech);return s.speech;});
    yes(said.toString().contains(story),"failed or no-change action must not erase an independent story");
    yes(said.toString().equals(notice+story),"model answer is delivered exactly once, not prefixed or rewritten locally");
    yes(!returned(wire,1,"no_change").getBoolean("changed"),"actual unchanged device result still reaches model");
    yes(writes[0]==1&&wire.calls==2&&!c.endAfterReply(),"single execution, same model loop, chat remains open");
   }finally{c.finishTurn();c.close();}
  }
 }
 static void postToolRepairNeverRepeatsActuation()throws Exception{
  String notice="这次没能调整音量，音量保持不变。",story="小猴子在安全的坡顶画出绕路地图，大家跟着地图回家了。";
  JSONObject invalid=ApiOwnedDialogueTest.message("");invalid.getJSONArray("content").getJSONObject(0).put("text","<tool_call>control_device</tool_call>");
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("done_once","volume_down")),ApiOwnedDialogueTest.response(invalid),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(notice+story)));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] writes={0};StringBuilder said=new StringBuilder();
  try{c.beginTurn(()->false,r->{r.run();return true;});c.bindDeviceExecutor(a->{writes[0]++;return receipt(false,false,"VOLUME_NOT_APPLIED",notice);});
   c.replySegments("先小声点，再完整讲完。",s->{said.append(s.speech);return s.speech;});
   yes(writes[0]==1&&wire.calls==3&&said.toString().equals(notice+story),"one bounded formatting repair preserves independent answer and never repeats hardware action");
   yes("none".equals(wire.request(2).getString("tool_choice"))&&wire.request(2).getJSONArray("tools").length()==0,"post-result repair has no executable tools");
   yes(!returned(wire,2,"done_once").getBoolean("success")&&!c.endAfterReply(),"original failed receipt and conversation retained");
  }finally{c.finishTurn();c.close();}
 }
 static void receiptPrefixIsSpokenOnce()throws Exception{
  for(boolean bound:new boolean[]{false,true}){
   String notice=bound?"已经达到最大音量，本次没有变化。":"这次没能调整音量，音量保持不变。";
   String content="孙悟空先让小猴子到安全的地方，大家想出了绕路回家的办法。";
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("receipt_once","volume_up")),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(notice+content)));
   MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);StringBuilder output=new StringBuilder();int[] executed={0};
   try{client.beginTurn(()->false,r->{r.run();return true;});client.bindDeviceExecutor(a->{executed[0]++;return receipt(bound,false,bound?"VOLUME_AT_BOUND":"VOLUME_NOT_APPLIED",notice);});
    client.replySegments("调整音量，然后继续讲。",s->{output.append(s.speech);return s.speech;});
    yes(output.toString().equals(notice+content),"authoritative receipt prefix delivered once, independent content preserved");
    yes(executed[0]==1&&wire.calls==2&&!client.endAfterReply(),"deduplication never reruns action or closes chat");
   }finally{client.finishTurn();client.close();}
  }
 }
 static void fallbackAndCancel()throws Exception{
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("fallback","volume_up")));MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] writes={0};StringBuilder said=new StringBuilder();
  try{c.beginTurn(()->false,x->{x.run();return true;});c.bindDeviceExecutor(a->{writes[0]++;return receipt(true,false,"VOLUME_AT_BOUND","已经达到最大音量。");});c.replySegments("再大声一点。",s->{said.append(s.speech);return s.speech;});yes(writes[0]==1&&said.toString().contains("已经达到最大音量")&&c.knowledgeStatus().optBoolean("wordingFallback"),"wording failure preserves actual device outcome without retrying actuation");}finally{c.finishTurn();c.close();}
  AtomicBoolean cancelled=new AtomicBoolean();wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call("cancel","volume_up")));c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);List<String> speech=new ArrayList<>();
  try{c.beginTurn(cancelled::get,x->{if(cancelled.get())return false;x.run();return true;});c.bindDeviceExecutor(a->{cancelled.set(true);return receipt(true,true,"VOLUME_APPLIED","已调整。");});boolean failed=false;try{c.replySegments("大声一点。",s->{speech.add(s.speech);return s.speech;});}catch(IOException expected){failed=true;}yes(failed&&speech.isEmpty()&&wire.calls==1,"barge-in suppresses stale result speech and extra model request");}finally{c.finishTurn();c.close();}
 }
 /** Explicit bounded live MODEL test. Actuators remain simulated; never reads the toy or records audio. */
 static void live(String ignored)throws Exception{throw new IOException("PUBLIC_TESTS_OFFLINE_ONLY");}
 public static void main(String[] args)throws Exception{
  if(args.length!=0){if(args.length!=2||!"--live".equals(args[0]))throw new IOException("EXPLICIT_LIVE_REQUIRED");live(args[1]);return;}runReceipt(true,true,"VOLUME_APPLIED");runReceipt(true,false,"VOLUME_AT_BOUND");runReceipt(false,false,"VOLUME_NOT_APPLIED");directActionRequiresTool();rejectedCalls();malformedProviderDoesNotActuate();standardEndTool();compoundAndHistory();failedActionPreservesIndependentAnswer();receiptPrefixIsSpokenOnce();postToolRepairNeverRepeatsActuation();fallbackAndCancel();
  System.out.println("DEVICE_TOOL_EXECUTION "+checks+" checks passed; production engine/client, simulated provider and hardware; no live device/API");
 }
}
