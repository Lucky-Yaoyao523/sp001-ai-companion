package org.sp001.core;
import java.io.*;import java.nio.file.*;import java.util.*;import java.security.cert.*;import javax.net.ssl.*;import org.json.*;
/** Actual request/client/parser: fake transport only where explicitly injected, no keyword-selected replies. */
public final class NativeDialogueTest {
 static int checks;static void yes(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}interface Work{void run()throws Exception;}static void rejects(Work w)throws Exception{try{w.run();throw new AssertionError("expected rejection");}catch(IOException good){checks++;}}
 static JSONObject cfg()throws Exception{return TtsSocketAdapterTest.config().put("enabled",true).put("cloudConsent",true).put("chatModel","MiniMax-M3");}
 static JSONArray hist(String s)throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","persona")).put(new JSONObject().put("role","user").put("content",s));}
 static String msg(String speech)throws Exception{return new JSONObject().put("continue_listening",true).put("speech",speech).put("emotion","neutral").put("actions",new JSONArray()).put("end",false).toString();}
 static JSONObject response(String text)throws Exception{return new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)))));}
 static String stream(JSONObject r)throws Exception{return "data: "+new JSONObject().put("type","response.completed").put("response",r)+"\n\n";}
 static JSONObject weatherCall(String city,String date)throws Exception{return new JSONObject().put("type","function_call").put("name","get_weather").put("call_id","call_test").put("arguments",new JSONObject().put("city",city).put("date",date).toString());}
 static JSONObject memoryCall(String quote)throws Exception{return new JSONObject().put("type","function_call").put("name","remember_preference").put("call_id","memory_test").put("arguments",new JSONObject().put("quote",quote).toString());}
 static class Out extends NativeDialogueEngine.Output{StringBuilder text=new StringBuilder();JSONObject state;public void speak(ReplySegment s){text.append(s.speech);}public void status(JSONObject j){state=j;}}
 public static void main(String[] args)throws Exception{

  for(String q:new String[]{"北京明天天气怎么样","那后天呢","那上海呢","天气英语怎么说","关羽什么时候去世","迪士尼有什么","七加八多少","不要查天气，讲个故事","我说的是苹果，不是天气"}){
   JSONObject p=NativeDialogueProtocol.request(hist(q),"",null,null,null,1790125200000L,false,false);yes(p.getString("tool_choice").equals("auto")&&p.getJSONArray("tools").length()==5&&"web_search".equals(p.getJSONArray("tools").getJSONObject(0).getString("type"))&&"get_weather".equals(p.getJSONArray("tools").getJSONObject(1).getString("name"))&&"control_device".equals(p.getJSONArray("tools").getJSONObject(2).getString("name"))&&"end_session".equals(p.getJSONArray("tools").getJSONObject(3).getString("name"))&&"remember_preference".equals(p.getJSONArray("tools").getJSONObject(4).getString("name")),"same tools/model choice for "+q);yes(p.getJSONArray("input").getJSONObject(0).getString("content").equals(q),"unaltered user intent "+q);yes(!p.getBoolean("store"),"not persisted on provider");}
  yes(NativeDialogueProtocol.memoryArguments(memoryCall("我喜欢恐龙")).getString("quote").equals("我喜欢恐龙"),"exact current preference enters memory tool");
  rejects(()->NativeDialogueProtocol.memoryArguments(memoryCall("密码是12345678")));
  rejects(()->NativeDialogueProtocol.memoryArguments(memoryCall("我喜欢恐龙").put("name","delete_files")));
  rejects(()->NativeDialogueProtocol.weatherArguments(weatherCall("http://localhost","2026-09-24")));rejects(()->NativeDialogueProtocol.weatherArguments(weatherCall("北京","明天")));rejects(()->NativeDialogueProtocol.weatherArguments(weatherCall("北京","2026-09-24").put("name","delete_files")));
  rejects(()->WeatherTls.requireHost("https://api.open-meteo.com.evil.test/"));rejects(()->WeatherTls.requireHost("http://api.open-meteo.com/"));rejects(()->WeatherTls.requireHost("https://user:pass@api.open-meteo.com/"));WeatherTls.requireHost("https://api.open-meteo.com/v1/forecast");checks++;
  X509TrustManager trust=WeatherTls.manager();yes(trust.getAcceptedIssuers().length==1,"only official weather root");yes(trust.getAcceptedIssuers()[0].getSubjectX500Principal().getName().contains("ISRG Root X1"),"public trust anchor available without recorded certificate chain");
  try{trust.checkServerTrusted(new X509Certificate[0],"RSA");throw new AssertionError("empty chain trusted");}catch(IllegalArgumentException|CertificateException good){checks++;}
  yes(CityWeather.daysUntil("2026-09-24",1790125200000L,"Asia/Shanghai")==1,"absolute date from model");rejects(()->CityWeather.daysUntil("2026-02-30",1790125200000L,"Asia/Shanghai"));rejects(()->CityWeather.daysUntil("2026-10-20",1790125200000L,"Asia/Shanghai"));
  final int[] calls={0};NativeDialogueEngine engine=new NativeDialogueEngine(cfg(),url->{yes(url.getPath().equals("/v1/responses"),"native endpoint");DuplexIntegrationTest.Connection c;try{c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=stream(response(msg("天气的英语是 weather。")));}catch(Exception e){throw new IOException(e);}calls[0]++;return c;},(city,date,now,t)->{throw new AssertionError("translation must not query weather");});Out out=new Out();engine.reply(hist("天气英语怎么说"),"",false,out,()->false);yes(out.text.toString().contains("weather")&&calls[0]==1,"direct translation one model no search");engine.commit();
  NativeDialogueEngine historyEngine=new NativeDialogueEngine(cfg(),url->{throw new IOException("NO_NETWORK");});java.lang.reflect.Field toolsField=NativeDialogueEngine.class.getDeclaredField("toolHistory");toolsField.setAccessible(true);
  JSONArray toolsHistory=(JSONArray)toolsField.get(historyEngine);toolsHistory.put(new JSONObject().put("index",0).put("items",new JSONArray()));toolsHistory.put(new JSONObject().put("index",8).put("items",new JSONArray().put(new JSONObject().put("type","function_call_output").put("call_id","retained").put("output","ok"))));historyEngine.trimHistory(1);JSONArray afterTrim=(JSONArray)toolsField.get(historyEngine);yes(afterTrim.length()==1&&afterTrim.getJSONObject(0).getInt("index")==7&&afterTrim.getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("call_id").equals("retained"),"old evidence dropped and retained evidence reindexed");
  MiniMaxVoiceClient boundedClient=new MiniMaxVoiceClient(cfg(),()->false);java.lang.reflect.Field historyField=MiniMaxVoiceClient.class.getDeclaredField("history");historyField.setAccessible(true);JSONArray longHistory=new JSONArray().put(new JSONObject().put("role","system").put("content","system"));for(int i=0;i<30;i++){longHistory.put(new JSONObject().put("role","user").put("content","question"+i)).put(new JSONObject().put("role","assistant").put("content","answer"+i));}historyField.set(boundedClient,longHistory);java.lang.reflect.Method remember=MiniMaxVoiceClient.class.getDeclaredMethod("rememberUser",String.class);remember.setAccessible(true);remember.invoke(boundedClient,"latest");JSONArray boundedHistory=(JSONArray)historyField.get(boundedClient);yes(boundedHistory.length()==26&&boundedHistory.getJSONObject(1).getString("content").equals("question18")&&boundedHistory.getJSONObject(25).getString("content").equals("latest"),"complete recent history bounded before native request");NativeDialogueProtocol.request(boundedHistory,"",null,null,null,1790125200000L,false,false);boundedClient.close();
  final int[] memoryCalls={0},memoryResponses={0};NativeDialogueEngine memoryEngine=new NativeDialogueEngine(cfg(),url->{DuplexIntegrationTest.Connection c;try{c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=stream(memoryResponses[0]++==0?response("").put("output",new JSONArray().put(memoryCall("我喜欢恐龙"))):response(msg("你的喜好已经保存，咱们接着聊恐龙。")));}catch(Exception e){throw new IOException(e);}return c;});
  Out memoryOut=new Out(){public JSONObject rememberPreference(String quote)throws Exception{yes("我喜欢恐龙".equals(quote),"model quote preserved");memoryCalls[0]++;return new JSONObject().put("success",true).put("saved",true).put("answer","这项喜好已经保存到你的长期记忆里。");}};
  memoryEngine.reply(hist("以后记住我喜欢恐龙"),"",false,memoryOut,()->false);yes(memoryCalls[0]==1&&memoryOut.text.toString().contains("恐龙")&&"MODEL_EXECUTED_MEMORY_TOOL".equals(memoryOut.state.optString("answerMode")),"one receipt before memory claim: calls="+memoryCalls[0]+" text="+memoryOut.text+" state="+memoryOut.state);memoryEngine.commit();
  java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT);format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));java.util.Calendar tomorrowCal=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));tomorrowCal.add(java.util.Calendar.DATE,1);final String tomorrow=format.format(tomorrowCal.getTime());
  final int[] fetches={0},weatherResponses={0};NativeDialogueEngine we=new NativeDialogueEngine(cfg(),url->{DuplexIntegrationTest.Connection c;try{c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=stream(weatherResponses[0]++==0?response("").put("output",new JSONArray().put(weatherCall("北京",tomorrow))):response(msg("北京明天最低17度，最高26度，降雨概率30%。")));}catch(Exception e){throw new IOException(e);}return c;},(city,date,now,t)->{yes(city.equals("北京")&&date.equals(tomorrow),"model arguments not default home");fetches[0]++;return new JSONObject().put("kind","verified_weather").put("city",city).put("timezone","Asia/Shanghai").put("current",new JSONObject().put("weatherCode",1).put("temperatureC",21).put("feelsLikeC",21)).put("forecast",new JSONObject().put("date",date).put("weatherCode",2).put("lowC",17).put("highC",26).put("rainProbabilityPercent",30));});Out weather=new Out();we.reply(hist("北京明天天气怎么样"),"",false,weather,()->false);yes(fetches[0]==1&&weather.text.toString().contains("北京")&&weather.text.toString().contains("26")&&!weather.text.toString().contains("南京"),"typed weather values rendered after actual tool result");we.commit();
  rejects(()->we.reply(hist("天气"),"",false,new Out(),()->true));
  MiniMaxVoiceClient client=new MiniMaxVoiceClient(cfg(),()->false,null,null,url->{yes(url.getPath().equals("/v1/responses"),"real production entry native not legacy");DuplexIntegrationTest.Connection c;try{c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=stream(response(msg("苹果的英语是 apple。")));}catch(Exception e){throw new IOException(e);}return c;});client.beginTurn(()->false,r->{r.run();return true;});StringBuilder spoken=new StringBuilder();client.replySegments("蜘蛛侠，苹果英语怎么说？",s->{spoken.append(s.speech);return s.speech;});yes(spoken.toString().contains("apple")&&client.llmCalls==1,"actual parent integration");client.commitReply();client.finishTurn();client.close();
  JSONObject mixed=response(msg("最终有来源的内容。"));mixed.getJSONArray("output").put(0,new JSONObject().put("type","web_search_call").put("status","completed"));yes(NativeDialogueProtocol.searches(mixed)==1,"only completed calls count");
  for(String text:new String[]{"故事里的蜘蛛侠说：我去查一下再告诉你。","声音大一点的英语是 Please speak louder。"}){
   ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(text)));
   MiniMaxVoiceClient model=new MiniMaxVoiceClient(cfg(),()->false,null,null,wire);StringBuilder actual=new StringBuilder();
   try{model.beginTurn(()->false,r->{r.run();return true;});model.replySegments("解释这段台词。",s->{actual.append(s.speech);return s.speech;});
    yes(actual.toString().equals(text),"valid model text is not rewritten by quoted-command keywords");yes(wire.calls==1,"no second content judge or compulsory search");
   }finally{model.finishTurn();model.close();}
  }
  for(String prose:new String[]{"苹果的英语是 apple。","故事里的蜘蛛侠说：我去查一下再告诉你。"}){
   ApiOwnedDialogueTest.Wire rawWire=new ApiOwnedDialogueTest.Wire(response(prose));
   MiniMaxVoiceClient rawClient=new MiniMaxVoiceClient(cfg(),()->false,null,null,rawWire);StringBuilder actual=new StringBuilder();
   try{rawClient.beginTurn(()->false,r->{r.run();return true;});rawClient.replySegments("给我解释一下。",segment->{actual.append(segment.speech);yes(segment.actions.isEmpty()&&!segment.end,"plain output cannot actuate or end");return segment.speech;});
    yes(actual.toString().equals(prose)&&rawWire.calls==1,"ordinary plain answer is delivered once without a format repair");rawClient.commitReply();
   }finally{rawClient.finishTurn();rawClient.close();}
  }
  final int[] repairCalls={0};
  MiniMaxVoiceClient repairedFarewell=new MiniMaxVoiceClient(cfg(),()->false,null,null,url->{
   int attempt=++repairCalls[0];if(attempt==1)throw new IOException("HTTP_TRANSFER_FAILED",new java.net.SocketException("synthetic disconnect"));
   try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";
    c.body=stream(response(new JSONObject().put("emotion","caring").put("continue_listening",false).put("speech","拜拜，下次聊。").toString()));return c;}
   catch(Exception error){throw new IOException(error);}
  });
  StringBuilder repairedGoodbye=new StringBuilder();
  try{repairedFarewell.beginTurn(()->false,r->{r.run();return true;});repairedFarewell.replySegments("我先走了，下次聊。",s->{repairedGoodbye.append(s.speech);return s.speech;});
   yes(repairCalls[0]==2&&repairedFarewell.endAfterReply()&&repairedGoodbye.toString().equals("拜拜，下次聊。"),"transient disconnect retry preserves explicit model farewell without repeating speech");repairedFarewell.commitReply();}
  finally{repairedFarewell.finishTurn();repairedFarewell.close();}
  for(String prose:new String[]{"天气的英语是 weather。","故事里的侦探说：我去查一下再告诉你。","把音量调大一点的英语是 Turn up the volume a little。"}){
   List<ReplySegment> plain=NativeDialogueEngine.parse(prose,"解释一下。",true);
   yes(plain.size()==1&&plain.get(0).speech.equals(prose)&&plain.get(0).actions.isEmpty()&&!plain.get(0).end,"plain model speech never acquires tool or end authority");
  }
  rejects(()->NativeDialogueEngine.parse("<tool_call>control_device</tool_call>","你好",true));
  rejects(()->NativeDialogueEngine.parse("", "你好", true));
  String missingBrace=new JSONObject().put("emotion","neutral").put("continue_listening",true).put("speech","桥修好了，故事讲完了。").toString();
  missingBrace=missingBrace.substring(0,missingBrace.length()-1);
  List<ReplySegment> recovered=NativeDialogueEngine.parse(missingBrace,"讲完故事",true);
  yes(recovered.size()==1&&"桥修好了，故事讲完了。".equals(recovered.get(0).speech),"completed speech with only final brace missing retains full answer");
  rejects(()->NativeDialogueEngine.parse("{\"emotion\":\"neutral\",\"speech\":\"故事没结束\"","讲故事",true));
  JSONObject completedItem=response(msg("积木搭好了，真棒！")).getJSONArray("output").getJSONObject(0).put("status","completed");
  IOException lostUsage=new IOException("HTTP_TRANSFER_FAILED",new java.net.SocketException("synthetic usage event lost"));
  JSONObject recoveredItem=NativeDialogueEngine.completedAssistantAfterTransfer(completedItem,false,lostUsage);
  yes(recoveredItem!=null&&NativeDialogueProtocol.text(recoveredItem).contains("积木搭好了"),"completed assistant answer survives a later transport break");
  yes(NativeDialogueEngine.completedAssistantAfterTransfer(completedItem,true,lostUsage)==null,"transport break after a function call cannot infer a tool result");
  yes(ChildResponseGuard.sanitize("天气的英语是 weather，发音有点像威泽。").equals("天气的英语是 weather。"),"actual bad phonetic example removed");
  JSONObject internal=response(msg("最终回答。")).put("output",new JSONArray().put(new JSONObject().put("type","reasoning").put("content","synthetic-internal-state")).put(weatherCall("北京",tomorrow)));
  yes(NativeDialogueProtocol.toolRoundtrip(internal).toString().equals(internal.getJSONArray("output").toString()),"full provider reasoning and call preserved unchanged only inside tool context");yes(!NativeDialogueProtocol.text(internal).contains("synthetic-internal"),"internal provider fields never enter speech");
  System.out.println("NATIVE_DIALOGUE "+checks+" checks passed; synthetic HTTP, embedded public trust root, no network/device");
 }
}
