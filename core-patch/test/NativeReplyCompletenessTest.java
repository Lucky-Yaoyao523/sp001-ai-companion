package org.sp001.core;
import java.io.IOException;
import org.json.*;
/** Whole public answer through the real native client and segmentation pipeline.
 * Synthetic provider, TTS and audio ports; no network, microphone or device. */
public final class NativeReplyCompletenessTest {
 private static int checks;
 private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 private static String frame(JSONObject event){return "data: "+event.toString()+"\n\n";}
 private static String wire(String answer)throws Exception{
  JSONObject item=new JSONObject().put("id","m1").put("type","message").put("role","assistant").put("status","in_progress");
  StringBuilder s=new StringBuilder(frame(new JSONObject().put("type","response.output_item.added").put("item",item)));
  for(int i=0;i<answer.length();i+=13)s.append(frame(new JSONObject().put("type","response.output_text.delta").put("item_id","m1").put("delta",answer.substring(i,Math.min(answer.length(),i+13)))));
  JSONObject done=new JSONObject(item.toString()).put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",answer)));
  s.append(frame(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",new JSONArray().put(done)))));
  return s.toString();
 }
 private static String envelope(String text,boolean listen)throws Exception{return new JSONObject().put("emotion","curious").put("continue_listening",listen).put("speech",text).toString();}
 private static void complete(String answer,final String expected,boolean ending)throws Exception{
  completeBodies(new String[]{wire(answer)},expected,ending);
 }
 private static void completeBodies(final String[] bodies,final String expected,boolean ending)throws Exception{
  final int[] requests={0},bytes={0},writes={0};final StringBuilder synthesized=new StringBuilder();
  final MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,url->{
   int at=requests[0]++;if(at>=bodies.length)throw new IOException("UNEXPECTED_MODEL_RETRY");try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=bodies[at];return c;}catch(Exception failure){throw new IOException(failure);}
  });
  try{
   client.beginTurn(()->false,r->{r.run();return true;});final SentencePlayback.Result result=new SentencePlayback.Result();
   if(bodies.length>1)client.bindDeviceExecutor(a->{writes[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","声音已调大。");});
   SentencePlayback.run(new SentencePlayback.Ports(){
    public void check(){}public void sentences(final SentencePlayback.TextSink sink)throws Exception{client.replySegments("请接着解释，完整说完。",s->{if(!s.speech.isEmpty())sink.accept(s.speech);return s.speech;});}
    public int synthesize(String text,SentencePlayback.AudioSink sink)throws Exception{synthesized.append(text);sink.accept(new byte[4]);return 4;}
    public void write(byte[] pcm){bytes[0]+=pcm.length;}public int finish(){return bytes[0];}public void close(){}
   },result,ChildCompanionPolicy.STORY_PCM_LIMIT);
   check(synthesized.toString().equals(expected),"full model answer must reach TTS: expected="+expected+"; actual="+synthesized);
   check(result.drained&&result.acceptedBytes==bytes[0],"all accepted audio drains");
   check(requests[0]==bodies.length&&client.knowledgeStatus().optString("state").equals("ANSWERED"),"no repeat model response and answer completed");
   check(writes[0]==(bodies.length>1?1:0),"device tool executes only once when requested");
   check(client.endAfterReply()==ending,"explicit lifecycle stays unchanged");
   client.commitReply();java.lang.reflect.Field f=MiniMaxVoiceClient.class.getDeclaredField("history");f.setAccessible(true);JSONArray h=(JSONArray)f.get(client);
   check(h.getJSONObject(h.length()-1).getString("content").equals(expected),"history must equal the full delivered answer");
  }finally{client.finishTurn();client.close();}
 }
 private static String twoMessages(String first,String second,boolean terminal)throws Exception{
  StringBuilder body=new StringBuilder();JSONArray output=new JSONArray();
  for(String text:new String[]{first,second}){
   String id="m"+output.length();JSONObject item=new JSONObject().put("id",id).put("type","message").put("role","assistant").put("status","in_progress");
   body.append(frame(new JSONObject().put("type","response.output_item.added").put("item",item)));
   body.append(frame(new JSONObject().put("type","response.output_text.delta").put("item_id",id).put("delta",text)));
   item.put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)));output.put(item);
   body.append(frame(new JSONObject().put("type","response.output_item.done").put("item",item)));
  }
  if(terminal)body.append(frame(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",output))));
  else body.append("event: response.completed\ndata: {\"type\":\"response.completed\",");
  return body.toString();
 }
 private static void laterMessageIncomplete(String tool,String first)throws Exception{
  String body=twoMessages(envelope(first,true),envelope("后面这一段还没有结束。",true),true);
  int second=body.indexOf("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m1\"");
  // JSONObject field ordering is not a transport contract: locate the second
  // delta by its already-recorded m1 ID instead of relying on key order.
  if(second<0){int at=body.indexOf("\"item_id\":\"m1\"");second=body.lastIndexOf("data: ",at);}
  check(second>=0,"second message fixture present");
  final String[] bodies={tool,body.substring(0,second)};
  final int[] requests={0},writes={0};final StringBuilder speech=new StringBuilder();
  MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,url->{int at=requests[0]++;if(at>=2)throw new IOException("UNEXPECTED_RETRY");try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=bodies[at];return c;}catch(Exception failure){throw new IOException(failure);}});
  try{client.beginTurn(()->false,r->{r.run();return true;});client.bindDeviceExecutor(a->{writes[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","声音已调大。");});
   boolean failed=false;try{client.replySegments("完整说完。",s->{speech.append(s.speech);return s.speech;});}catch(IOException expected){failed=true;}
   check(failed&&requests[0]==2&&writes[0]==1,"unfinished later message fails without repeating the tool or model response");
   check("ANSWER_FAILED".equals(client.knowledgeStatus().optString("state")),"completed first message cannot disguise a partial response");
   check(speech.toString().equals(first+"刚才这段还没说完。"),"heard prefix retained once with interruption notice");
  }finally{client.finishTurn();client.close();}
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=0)throw new IOException("OFFLINE_ONLY");
  String first="我们把海洋馆分成两个区域。",tail="掠食者区的水池彼此分开，参观者从安全通道观看。入口先验票，再安检；危险物品留在家里，之后进入参观区。接下来我来扮演介绍员，介绍第一只动物。";
  complete(envelope(first+tail,true),first+tail,false);
  String tool=frame(new JSONObject().put("type","response.completed").put("response",ApiOwnedDialogueTest.response(DeviceToolExecutionTest.call("once_complete","volume_up"))));
  completeBodies(new String[]{tool,twoMessages(envelope(first,true),envelope(tail,true),true)},first+tail,false);
  completeBodies(new String[]{tool,twoMessages(envelope(first,true),envelope(tail,true),false)},first+tail,false);
  laterMessageIncomplete(tool,first);
  complete(envelope(first,true)+"\n"+tail,first+tail,false);
  StringBuilder longBody=new StringBuilder();for(int i=0;i<15;i++)longBody.append("参观者沿通道依次观看，每个区域都有独立水池和清楚的介绍牌。 ");
  complete(envelope(first,true)+"\n"+longBody,first+longBody.toString().trim(),false);
  complete(envelope("再见，下次聊。",false)+"\n终止后这段不能继续播放。","再见，下次聊。",true);
  String reasoning="<think>internal-only</think>";
  complete(envelope(first,true)+"\n"+reasoning+tail,first+tail,false);
  boolean rejected=false;try{NativeDialogueEngine.parse(envelope(first,true)+"\n{\"speech\":\"bad\"","完整说完。",true);}catch(IOException expected){rejected=true;}
  check(rejected,"unfinished control-shaped trailing data cannot masquerade as plain speech");
  System.out.println("NATIVE_REPLY_COMPLETENESS "+checks+" passed; synthetic transport/TTS/audio, real client and pipeline, no live device/API");
 }
}
