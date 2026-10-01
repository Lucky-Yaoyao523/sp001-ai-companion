package org.sp001.core;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Actual protocol/client regressions for absent lifecycle and end after an action. */
public final class ActionLifecycleRegressionTest {
 static int checks;
 static void yes(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static JSONObject delta(String text)throws Exception{return new JSONObject().put("type","response.output_text.delta").put("item_id","m").put("delta",text);}
 static NativeReplyStream stream()throws Exception{NativeReplyStream p=new NativeReplyStream("接着聊。");p.accept(new JSONObject().put("type","response.output_item.added").put("item",new JSONObject().put("type","message").put("role","assistant").put("id","m")));return p;}
 static void lifecycle()throws Exception{
  for(String absent:new String[]{"{\"speech\":\"拜拜。\"}","{\"speech\":\"Goodbye.\",\"continue_listening\":null}"}){
   yes(stream().accept(delta(absent)).isEmpty(),"missing lifecycle cannot play before repair");
   try{NativeDialogueEngine.parse(absent,"只是问一个词。",false);throw new AssertionError("missing flag accepted");}
   catch(IOException expected){yes("NATIVE_LIFECYCLE_REQUIRED".equals(expected.getMessage()),"typed missing-field error");}
  }
  NativeReplyStream late=stream();yes(late.accept(delta("{\"speech\":\"好，拜拜。\",")).isEmpty(),"speech-first waits for model flag");
  List<ReplySegment> spoken=late.accept(delta("\"continue_listening\":false}"));yes(spoken.size()==1&&!spoken.get(0).end,"flag allows speech but only completed reply closes");
  List<ReplySegment> finalParts=late.remaining(NativeDialogueEngine.parse("{\"speech\":\"好，拜拜。\",\"continue_listening\":false}","再见。",false));
  yes(finalParts.size()==1&&finalParts.get(0).end&&finalParts.get(0).speech.isEmpty(),"terminal control survives without farewell replay");
  NativeReplyStream early=stream();yes(early.accept(delta("{\"continue_listening\":true,\"speech\":\"第一句。接着")).size()==1,"valid header retains early speech");
 }
 static void endAfterDevice()throws Exception{
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(DeviceToolExecutionTest.call("volume","volume_down")),ApiOwnedDialogueTest.response(DeviceToolExecutionTest.endCall("end","好，下次聊。")));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] actions={0};StringBuilder said=new StringBuilder();
  try{c.beginTurn(()->false,r->{r.run();return true;});c.bindDeviceExecutor(a->{actions[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","已调小音量。");});
   c.replySegments("声音小一点，然后我们下次聊。",s->{said.append(s.speech);return s.speech;});
   yes(actions[0]==1&&wire.calls==2,"device action executes once across terminal follow-up");
   yes(c.endAfterReply()&&said.toString().equals("已调小音量。好，下次聊。"),"receipt and farewell delivered once, session ends");
   yes(!c.knowledgeStatus().optBoolean("wordingFallback"),"terminal end is not discarded as a tool loop");c.commitReply();
  }finally{c.finishTurn();c.close();}
 }
 static void volumeQuery()throws Exception{
  JSONObject call=DeviceToolExecutionTest.call("query","get_volume");
  yes(NativeDialogueProtocol.functions(ApiOwnedDialogueTest.response(call)).length()==1,"read volume is a formal model tool");
  ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(call),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("当前音量是百分之80。")));
  MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);int[] queries={0};
  try{c.beginTurn(()->false,r->{r.run();return true;});c.bindDeviceExecutor(a->{yes(a.equals("get_volume"),"query never becomes volume adjustment");queries[0]++;return DeviceToolExecutionTest.receipt(true,false,"VOLUME_READ","当前音量是百分之80。").put("percent",80);});
   StringBuilder said=new StringBuilder();c.replySegments("现在音量有多少？",s->{said.append(s.speech);return s.speech;});
   yes(queries[0]==1&&said.toString().equals("当前音量是百分之80。")&&!c.endAfterReply(),"query reports current observed percentage and continues listening");
  }finally{c.finishTurn();c.close();}
  int previous=-1;for(int level=0;level<=15;level++){int percent=VolumeChange.percent(level,15);yes(percent>previous&&percent>=0&&percent<=100,"each real hardware step has a distinct increasing percentage");previous=percent;}
  yes(VolumeChange.percent(0,15)==0&&VolumeChange.percent(15,15)==100,"real endpoints map to zero and one hundred");
 }
 static void commentaryIsNotSpeech()throws Exception{
  String valid="{\"continue_listening\":false,\"speech\":\"拜拜。\"}";
  for(String suffix:new String[]{"\n\n模型额外的说明。","\n额外说明。\n另一行。\n"}){
   List<ReplySegment> parts=NativeDialogueEngine.parse(valid+suffix,"先走了。",false);
   yes(parts.size()==1&&parts.get(0).end&&parts.get(0).speech.equals("拜拜。"),"stray provider commentary cannot be spoken or undo valid final control");
  }
  try{NativeDialogueEngine.parse("{\"continue_listening\":true,\"speech\":\"你好。\"}\n{\"speech\":","你好。",false);throw new AssertionError("incomplete second JSON accepted");}
  catch(IOException expected){yes(true,"incomplete JSON remains a format failure");}
 }
 public static void main(String[] args)throws Exception{lifecycle();endAfterDevice();volumeQuery();commentaryIsNotSpeech();System.out.println("ACTION_LIFECYCLE "+checks+" passed; no device/network");}
}
