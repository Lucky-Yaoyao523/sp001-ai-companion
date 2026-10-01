package org.sp001.core;
import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;
/** Provider-native dialogue/search, bounded and cancellable. No user-keyword lookup dispatcher. */
public final class NativeDialogueEngine {
 /** Consumer-owned presentation port. An ordinary class also works on API 22
  * without compiler-generated default-interface dispatch classes. */
 public static abstract class Output {
  public abstract void speak(ReplySegment segment)throws Exception;
  public abstract void status(JSONObject status)throws Exception;
  /** Transient feedback is not a completed answer, memory, action or end decision. */
  public void feedback(ReplySegment segment)throws Exception{speak(segment);}
   /** Text-only callers do not acquire hardware authority. */
   public JSONObject executeDevice(String action)throws Exception{return new JSONObject().put("success",false).put("changed",false).put("code","DEVICE_CONTROL_UNAVAILABLE").put("answer","本次没有设备控制通道，设置没有改变。");}
   public JSONObject rememberPreference(String quote)throws Exception{return new JSONObject().put("success",false).put("saved",false).put("answer","这次记忆没有确认保存。");}
 }
 static final long FEEDBACK_DELAY_MS=2500;
 // Native socket inactivity, not total answer/playback duration. A retained live
 // trace stalled in an incomplete terminal event for 40s before its one retry.
 // Keep the existing complete-event and once-only execution rules; recover a
 // silent transport sooner without truncating a normally progressing long reply.
 static final int READ_STALL_MS=10000;
 public interface Weather {JSONObject fetch(String city,String date,long now,MiniMaxVoiceClient.Cancel token)throws Exception;}
 private final String origin,key;private final MiniMaxVoiceClient.ConnectionFactory factory;private final Weather weather;
 private JSONObject committedWeather,pendingWeather,pendingToolTurn;private JSONArray toolHistory=new JSONArray();private boolean pendingCommit;private int modelCalls,searchCalls;private JSONObject status=new JSONObject();
 public NativeDialogueEngine(JSONObject config,MiniMaxVoiceClient.ConnectionFactory connections)throws Exception{this(config,connections,new Weather(){public JSONObject fetch(String city,String date,long now,MiniMaxVoiceClient.Cancel t)throws Exception{return CityWeather.fetchDate(city,date,now,t);}});}
 NativeDialogueEngine(JSONObject config,MiniMaxVoiceClient.ConnectionFactory connections,Weather backend)throws Exception{
  if(!config.optBoolean("enabled")||!config.optBoolean("cloudConsent")||!"MiniMax-M3".equals(config.optString("chatModel"))||backend==null)throw new IOException("NATIVE_DIALOGUE_CONSENT_REQUIRED");origin=MiniMaxCodec.origin(config.getString("region"));key=config.getString("apiKey");if(!MiniMaxCodec.validKey(key))throw new IOException("NATIVE_DIALOGUE_CONFIG");weather=backend;factory=connections==null?new MiniMaxVoiceClient.ConnectionFactory(){public HttpsURLConnection open(URL u)throws IOException{return (HttpsURLConnection)u.openConnection(Proxy.NO_PROXY);}}:connections;
 }
 public int modelCalls(){return modelCalls;}public int searchCalls(){return searchCalls;}
 public JSONObject status(){try{return new JSONObject(status.toString());}catch(Exception e){return new JSONObject();}}
 public void commit(){if(pendingCommit){committedWeather=pendingWeather;if(pendingToolTurn!=null)toolHistory.put(pendingToolTurn);}pendingToolTurn=null;pendingCommit=false;}
 public void rollback(){pendingWeather=committedWeather;pendingToolTurn=null;pendingCommit=false;}
 void trimHistory(int removed){
  JSONArray retained=new JSONArray();
  for(int i=0;i<toolHistory.length();i++){
   JSONObject old=toolHistory.optJSONObject(i);if(old==null||old.optInt("index",-1)<removed)continue;
   try{retained.put(new JSONObject(old.toString()).put("index",old.getInt("index")-removed));}catch(Exception invalid){/* Drop unusable historical evidence; never execute it. */}
  }
  toolHistory=retained;
 }
 public void clear(){committedWeather=pendingWeather=pendingToolTurn=null;toolHistory=new JSONArray();pendingCommit=false;}
 private static void check(MiniMaxVoiceClient.Cancel token)throws IOException{if(token==null||token.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");}
 private void state(Output out,String phase)throws Exception{status.put("state",phase).put("protocol","responses-native-auto").put("modelCalls",modelCalls).put("searchCalls",searchCalls);out.status(status());}
 private void cue(Output out,boolean[] said,String text,MiniMaxVoiceClient.Cancel token)throws Exception{
  if(said[0]||status.optInt("streamedSegments",0)>0)return;check(token);said[0]=true;
  status.put("feedbackCount",1).put("feedbackMonotonicMs",android.os.SystemClock.elapsedRealtime());
  out.status(status());out.feedback(ReplySegment.speech(text,"curious"));check(token);
 }
 private JSONObject call(JSONObject payload,final MiniMaxVoiceClient.Cancel token,final Output out,final boolean[] cue,long end,NativeReplyStream stream)throws Exception{
  check(token);byte[] bytes=payload.toString().getBytes("UTF-8");BoundedHttp http=null;modelCalls++;final long started=android.os.SystemClock.elapsedRealtime();final NetworkWaitBudget net=new NetworkWaitBudget(started,65000);
  try{http=BoundedHttp.open(factory,new URL(origin+"/v1/responses"),"application/json","text/event-stream","Bearer "+key,bytes,READ_STALL_MS,new BoundedHttp.Guard(){public void check()throws IOException{NativeDialogueEngine.check(token);if(android.os.SystemClock.elapsedRealtime()>=end||net.expired(android.os.SystemClock.elapsedRealtime()))throw new IOException("NATIVE_DIALOGUE_TIMEOUT");}},null,new BoundedHttp.WaitFeedback(){
    public void waiting()throws IOException{
     check(token);if(cue[0]||status.optInt("streamedSegments",0)>0||android.os.SystemClock.elapsedRealtime()-started<FEEDBACK_DELAY_MS)return;
     net.pause(android.os.SystemClock.elapsedRealtime());
     try{cue(out,cue,"我想一想。",token);}
     catch(IOException failure){check(token);throw new IOException("NATIVE_FEEDBACK_FAILED",failure);}
     catch(Exception failure){throw new IOException("NATIVE_FEEDBACK_FAILED",failure);}
     finally{net.resume(android.os.SystemClock.elapsedRealtime());}
    }
   });
   if(http.status()!=200)throw new IOException("NATIVE_HTTP_"+http.status());String ct=http.contentType();if(ct==null||!ct.toLowerCase(Locale.ROOT).contains("text/event-stream"))throw new IOException("NATIVE_STREAM_TYPE");
   Reader reader=SseEventReader.utf8(http.input());Map<String,JSONObject> completedAssistants=new LinkedHashMap<String,JSONObject>();Set<String> pendingAssistants=new HashSet<String>();boolean functionSeen=false;final boolean toolsDisabled="none".equals(payload.optString("tool_choice"));try{SseEventReader events=new SseEventReader(reader,262144,786432);String raw;while((raw=events.next())!=null){check(token);if("[DONE]".equals(raw))break;JSONObject e=new JSONObject(raw);String type=e.optString("type");
    JSONObject item=e.optJSONObject("item");if(item!=null&&"function_call".equals(item.optString("type")))functionSeen=true;
    if(item!=null&&"message".equals(item.optString("type"))&&"assistant".equals(item.optString("role"))){
     String id=item.optString("id");if(id.isEmpty())id="@"+e.optInt("output_index",-1);
     if("response.output_item.added".equals(type))pendingAssistants.add(id);
     if("response.output_item.done".equals(type)&&"completed".equals(item.optString("status"))){pendingAssistants.remove(id);completedAssistants.put(id,item);}
     if(pendingAssistants.size()+completedAssistants.size()>64)throw new IOException("NATIVE_OUTPUT_BOUND");
    }
    for(ReplySegment part:stream.accept(e)){
     check(token);net.pause(android.os.SystemClock.elapsedRealtime());
     try{
      if(!status.has("firstSegmentMonotonicMs"))status.put("firstSegmentMonotonicMs",android.os.SystemClock.elapsedRealtime());
      status.put("streamedSegments",status.optInt("streamedSegments",0)+1).put("textDelivery","INCREMENTAL_RECORDS");out.status(status());
      out.speak(part);check(token);
     }finally{net.resume(android.os.SystemClock.elapsedRealtime());}
    }
    if("response.web_search_call.in_progress".equals(type)||"response.web_search_call.searching".equals(type))state(out,"SEARCHING");
    if("error".equals(type)||"response.failed".equals(type)||"response.incomplete".equals(type))throw new IOException("NATIVE_RESPONSE_FAILED");
    // A completed item is not necessarily the whole answer. Continue reading
    // later messages; recover completed items only if the transport then ends.
    if("response.completed".equals(type)){JSONObject r=e.optJSONObject("response");if(r==null||!"completed".equals(r.optString("status"))||(!r.isNull("error"))||(r.has("base_resp")&&r.getJSONObject("base_resp").optInt("status_code",-1)!=0))throw new IOException("NATIVE_NOT_COMPLETED");check(token);status.put("responseCompletedMonotonicMs",android.os.SystemClock.elapsedRealtime());out.status(status());return r;}
   }throw new IOException("NATIVE_STREAM_INCOMPLETE");}catch(IOException failure){
    JSONObject recovered=completedAssistantsAfterTransfer(completedAssistants,pendingAssistants,functionSeen,toolsDisabled,failure);
    if(recovered!=null){status.put("completionEvidence","COMPLETED_ASSISTANT_AFTER_TRANSFER").put("transportWarning",safeError(failure));check(token);out.status(status());return recovered;}
    throw failure;
   }finally{reader.close();}
  }catch(JSONException malformed){throw new IOException("NATIVE_EVENT_INVALID",malformed);}
  finally{Arrays.fill(bytes,(byte)0);if(http!=null)http.close();}
 }
 private static JSONObject completedAssistantsAfterTransfer(Map<String,JSONObject> items,Set<String> pending,boolean functionSeen,boolean toolsDisabled,IOException failure){
  if(items.isEmpty()||!pending.isEmpty()||functionSeen||failure==null)return null;
  String code=failure.getMessage();
  if(!transientNetworkFailure(failure)&&!(toolsDisabled&&("SSE_TRUNCATED_EVENT".equals(code)||"NATIVE_STREAM_INCOMPLETE".equals(code))))return null;
  try{JSONArray output=new JSONArray();for(JSONObject item:items.values())output.put(item);return new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",output);}
  catch(JSONException invalid){return null;}
 }
 /** Only transient transport failures are eligible before any delivered output
  * or local tool execution. TLS/authentication and unknown faults never retry. */
 static boolean transientNetworkFailure(IOException failure){
  if(failure==null||!"HTTP_TRANSFER_FAILED".equals(failure.getMessage()))return false;
  boolean transientCause=false;Throwable cause=failure;
  for(int depth=0;cause!=null&&depth<12;depth++,cause=cause.getCause()){
   if(cause instanceof javax.net.ssl.SSLException||cause instanceof java.security.GeneralSecurityException||cause instanceof SecurityException)return false;
   transientCause|=cause instanceof java.net.SocketException||cause instanceof java.net.SocketTimeoutException||cause instanceof java.net.UnknownHostException||cause instanceof java.io.EOFException;
  }
  return transientCause;
 }
 /** A full assistant item can survive a later truncated usage event. Never infer a tool result. */
 static JSONObject completedAssistantAfterTransfer(JSONObject item,boolean functionSeen,IOException failure){
  if(item==null||functionSeen||!transientNetworkFailure(failure)||!"completed".equals(item.optString("status"))||!"message".equals(item.optString("type"))||!"assistant".equals(item.optString("role")))return null;
  try{return new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",new JSONArray().put(item));}
  catch(JSONException invalid){return null;}
 }
 private static String safeError(IOException failure){
  String code=failure.getMessage();
  return code!=null&&code.matches("[A-Z0-9_]{1,100}")?code:"MODEL_ROUNDTRIP_FAILED";
 }
 /** Keep model text usable when it omits the optional presentation envelope.
  * Only an explicit model tool or boolean may end a session or operate hardware. */
 static List<ReplySegment> parse(String text,String user,boolean allowActions)throws Exception{
  if(text==null||text.trim().isEmpty())throw new IOException("NATIVE_NO_ANSWER");
  String content=text.trim();
  if(!content.startsWith("{")&&!content.startsWith("```")){
   if(content.startsWith("<")||content.contains("]<]minimax[>"))throw new IOException("NATIVE_CONTROL_IN_TEXT");
   if(content.length()>ChildCompanionPolicy.STORY_TEXT_LIMIT)throw new IOException("NATIVE_REPLY_TEXT_LIMIT");
   return Collections.singletonList(ReplySegment.speech(MiniMaxCodec.speech(content,ChildCompanionPolicy.STORY_TEXT_LIMIT),"neutral"));
  }
  try{return parseEnvelope(text,user,allowActions);}
  catch(IOException invalid){
   if(!"NATIVE_REPLY_FORMAT".equals(invalid.getMessage()))throw invalid;
   String closed=missingFinalBraceEnvelope(content);if(closed!=null)return parseEnvelope(closed,user,allowActions);
   String repaired=quotedSpeechEnvelope(content);if(repaired==null)throw invalid;
   return parseEnvelope(repaired,user,allowActions);
  }
 }
 /** Recover only a completed speech string with its final object brace omitted. */
 static String missingFinalBraceEnvelope(String content){
  if(content==null||content.length()>24000||!content.startsWith("{")||!content.endsWith("\""))return null;
  try{JSONTokener wire=new JSONTokener(content+"}");Object value=wire.nextValue();if(!(value instanceof JSONObject)||wire.nextClean()!=0)return null;return value.toString();}catch(JSONException invalid){return null;}
 }
 private static List<ReplySegment> parseEnvelope(String text,String user,boolean allowActions)throws Exception{
  ReplyEnvelopeStream parser=ReplyEnvelopeStream.forModel(user,allowActions);List<ReplySegment> parts=new ArrayList<ReplySegment>();parts.addAll(parser.append(text));parts.addAll(parser.finish("stop"));if(parser.generatedFallback()||parser.malformedRecords()!=0||parts.isEmpty())throw new IOException(parser.validationCode());for(ReplySegment part:parts)if(!part.actions.isEmpty())throw new IOException("NATIVE_DEVICE_TOOL_REQUIRED");return parts;
 }
 /** A completed presentation envelope sometimes contains literal quoted dialogue
  * without JSON escaping. Recover that syntax only when speech is the final field
  * and the prefix contains no executable/extra fields. Never infer or repair tools.
  * Existing streamed speech stays a prefix; remaining() prevents replay. */
 static String quotedSpeechEnvelope(String content){
  if(content==null||content.length()>24000||!content.startsWith("{"))return null;
  java.util.regex.Matcher field=java.util.regex.Pattern.compile("\\\"speech\\\"\\s*:\\s*\\\"").matcher(content);
  if(!field.find())return null;int begin=field.end(),last=content.lastIndexOf('"');
  if(last<begin||!content.substring(last+1).trim().equals("}"))return null;
  String prefix=content.substring(0,begin-1),raw=content.substring(begin,last);
  try{
   JSONObject shape=new JSONObject(prefix+"\"\"}");
   Object listen=shape.opt("continue_listening");if(listen!=null&&!(listen instanceof Boolean))return null;
   int allowed=1+(shape.has("emotion")?1:0)+(shape.has("continue_listening")?1:0);
   if(!shape.has("speech")||shape.length()>allowed)return null;
   if(java.util.regex.Pattern.compile("\\\"\\s*,\\s*\\\"[^\\\"\\r\\n]{1,80}\\\"\\s*:").matcher(raw).find())return null;
   StringBuilder escaped=new StringBuilder();int innerQuotes=0;
   for(int i=0;i<raw.length();i++){
    char c=raw.charAt(i);
    if(c=='\\'){if(i+1>=raw.length())return null;escaped.append(c).append(raw.charAt(++i));}
    else if(c=='"'){escaped.append('\\').append(c);innerQuotes++;}
    else escaped.append(c);
   }
   if(innerQuotes<2||(innerQuotes&1)!=0)return null;
   return new JSONObject(prefix+"\""+escaped.toString()+"\"}").toString();
  }catch(JSONException invalid){return null;}
 }
 private static JSONObject deviceReceipt(JSONObject result)throws Exception{
  if(result==null||!(result.opt("success") instanceof Boolean)||!result.optString("code").matches("[A-Z0-9_]{1,80}")||!(result.opt("answer") instanceof String)||result.getString("answer").trim().isEmpty()||result.getString("answer").length()>500||result.toString().length()>4096)throw new IOException("DEVICE_RECEIPT_INVALID");
  if(result.has("changed")&&!(result.opt("changed") instanceof Boolean))throw new IOException("DEVICE_RECEIPT_INVALID");return new JSONObject(result.toString());
 }
 public void reply(JSONArray history,String guidance,boolean story,Output out,MiniMaxVoiceClient.Cancel token)throws Exception{
  check(token);if(out==null)throw new IOException("NATIVE_OUTPUT_REQUIRED");status=new JSONObject();pendingWeather=committedWeather;pendingToolTurn=null;pendingCommit=false;long now=System.currentTimeMillis(),deadline=android.os.SystemClock.elapsedRealtime()+ChildCompanionPolicy.STORY_REQUEST_MS;boolean[] cue={false};state(out,"MODEL_DECIDING");
  String currentUser="";for(int i=history.length()-1;i>=0;i--)if("user".equals(history.getJSONObject(i).optString("role"))){currentUser=history.getJSONObject(i).getString("content");break;}
   JSONObject evidence=null;JSONArray additions=null;boolean repair=false,networkRetried=false;List<ReplySegment> answer=null;String verifiedWeatherSpeech=null,repairContext="";
  for(int round=0;round<3;round++){
   JSONObject payload=NativeDialogueProtocol.request(history,guidance+repairContext,committedWeather,additions,evidence,now,story,repair);
    JSONArray sourceInput=payload.getJSONArray("input"),expanded=new JSONArray();for(int i=0;i<sourceInput.length();i++){expanded.put(sourceInput.get(i));for(int j=0;j<toolHistory.length();j++){JSONObject entry=toolHistory.getJSONObject(j);if(entry.getInt("index")==i){JSONArray items=entry.getJSONArray("items");for(int k=0;k<items.length();k++)expanded.put(items.get(k));}}}
    // The repair instruction must be the latest input, after the rejected answer.
    // Otherwise the provider can simply repeat a correct but unframed short reply.
    if(repair)expanded.put(new JSONObject().put("role","user").put("content","上一份回答没有播放。重新完成原始用户请求并修正格式：先决定所需的真实工具，不把候选中的口头承诺当作执行回执。实际操作只以已有真实工具回执为准，已有回执不能重复执行；没有回执就必须先调用所需工具。无需工具时输出一个JSON对象，包含emotion、continue_listening布尔值、speech。上份候选仅作待修复资料："+repairContext));
    payload.put("input",expanded);
   JSONObject response;
   NativeReplyStream stream=new NativeReplyStream(currentUser);
   try {
    response=call(payload,token,out,cue,deadline,stream);
   } catch(IOException failure) {
    // A cancelled turn must never fall back to speech. An optional model round trip
    // must not discard a forecast that the tool already returned and we validated.
    check(token);
    if(!networkRetried&&round<2&&evidence==null&&additions==null&&stream.deliveredRecords()==0&&transientNetworkFailure(failure)){
     networkRetried=true;status.put("networkRetries",1).put("networkRetryReason",safeError(failure));
     state(out,"RECONNECTING");continue; // Same turn, within the existing total request budget.
    }
    if(stream.deliveredRecords()>0||verifiedWeatherSpeech==null)throw failure;
    status.put("wordingFallback",true).put("wordingError",safeError(failure));
    break;
   }
   JSONArray functions;int searched;
   try{
    functions=NativeDialogueProtocol.functions(response);searched=NativeDialogueProtocol.searches(response);
    if(functions.length()>0&&(evidence!=null||additions!=null)){
     // A terminal model decision after a completed action is not an action retry.
     // Admit only that one typed end; repeated settings/search calls remain rejected.
     if(functions.length()!=1||!"end_session".equals(functions.getJSONObject(0).optString("name")))throw new IOException("NATIVE_TOOL_LOOP");
     JSONObject function=functions.getJSONObject(0),args=NativeDialogueProtocol.endArguments(function);
     String closing=(stream.deliveredRecords()==0&&verifiedWeatherSpeech!=null?verifiedWeatherSpeech:stream.unspokenPrefix())+args.getString("farewell");
     if(pendingToolTurn!=null){JSONArray items=pendingToolTurn.getJSONArray("items");
      items.put(function);items.put(new JSONObject().put("type","function_call_output").put("call_id",function.getString("call_id")).put("output",new JSONObject().put("scheduled",true).put("after","farewell_playback").toString()));
     }
     status.put("sessionEndTool",true);answer=Collections.singletonList(new ReplySegment(closing,"caring",Collections.<String>emptyList(),true));break;
    }
   }catch(IOException invalid){
    check(token);if(stream.deliveredRecords()>0||verifiedWeatherSpeech==null)throw invalid;
    // Tool selection/execution already succeeded. Reject a second or malformed call
    // without executing it and without discarding the validated original result.
    status.put("wordingFallback",true).put("wordingError",safeError(invalid));break;
   }
   searchCalls+=searched;
   if(searched>0)for(int i=0;i<functions.length();i++)if(!"get_weather".equals(functions.getJSONObject(i).optString("name")))throw new IOException("NATIVE_DEVICE_AFTER_SEARCH");
   if(functions.length()>0){
    boolean hasDevice=false,hasWeather=false,hasMemory=false,hasToolErrors=false;for(int i=0;i<functions.length();i++){String name=functions.getJSONObject(i).optString("name");hasDevice|="control_device".equals(name);hasWeather|="get_weather".equals(name);hasMemory|="remember_preference".equals(name);}
    // Tool selection is not a result. Do not play a search promise before execution.
    state(out,"TOOL_EXECUTION");
    JSONArray records=new JSONArray();StringBuilder verified=new StringBuilder();String farewell=null;additions=NativeDialogueProtocol.toolRoundtrip(response);
    for(int i=0;i<functions.length();i++){
     check(token);JSONObject function=functions.getJSONObject(i);
     if("end_session".equals(function.optString("name"))){
      JSONObject args=NativeDialogueProtocol.endArguments(function);farewell=args.getString("farewell");
      JSONObject result=new JSONObject().put("scheduled",true).put("after","farewell_playback");
      records.put(new JSONObject().put("tool","end_session").put("arguments",args).put("result",result));
      additions.put(new JSONObject().put("type","function_call_output").put("call_id",function.getString("call_id")).put("output",result.toString()));
      continue;
     }
     if("control_device".equals(function.optString("name"))){
      JSONObject args=NativeDialogueProtocol.deviceArguments(function),result;
      status.put("deviceCalls",status.optInt("deviceCalls",0)+1);
      try{result=deviceReceipt(out.executeDevice(args.getString("action")));check(token);}
      catch(Exception failure){check(token);result=new JSONObject().put("success",false).put("code","DEVICE_EXECUTION_UNCONFIRMED").put("answer","这次设备操作没有确认成功，我不会重复执行或当作已经完成。");}
      hasToolErrors|=!result.getBoolean("success");records.put(new JSONObject().put("tool","control_device").put("arguments",args).put("result",result));verified.append(result.getString("answer"));
      additions.put(new JSONObject().put("type","function_call_output").put("call_id",function.getString("call_id")).put("output",result.toString()));
      continue;
     }
     if("remember_preference".equals(function.optString("name"))){
      JSONObject args=NativeDialogueProtocol.memoryArguments(function),result;
      status.put("memoryCalls",status.optInt("memoryCalls",0)+1);
      try{result=out.rememberPreference(args.getString("quote"));check(token);
       if(!(result.opt("success") instanceof Boolean)||!(result.opt("saved") instanceof Boolean)||!(result.opt("answer") instanceof String))throw new IOException("MEMORY_RECEIPT_INVALID");}
      catch(Exception failure){check(token);result=new JSONObject().put("success",false).put("saved",false).put("answer","这次记忆没有确认保存。");}
      hasToolErrors|=!result.getBoolean("success");records.put(new JSONObject().put("tool","remember_preference").put("result",result));verified.append(result.getString("answer"));
      additions.put(new JSONObject().put("type","function_call_output").put("call_id",function.getString("call_id")).put("output",result.toString()));
      continue;
     }
     JSONObject args=NativeDialogueProtocol.weatherArguments(function);JSONObject data;
     try{data=weather.fetch(args.getString("city"),args.getString("date"),now,token);check(token);if(!"verified_weather".equals(data.optString("kind"))||!args.getString("date").equals(data.getJSONObject("forecast").getString("date")))throw new IOException("WEATHER_EVIDENCE_MISMATCH");
      int days=CityWeather.daysUntil(args.getString("date"),now,data.getString("timezone"));String spoken=CityWeather.speech(data,days);verified.append(spoken);records.put(new JSONObject().put("arguments",args).put("data",data));pendingWeather=new JSONObject().put("city",data.getString("city")).put("date",args.getString("date")).put("queriedAtMs",now);
     }catch(Exception error){check(token);String code=error.getMessage();if(code==null||!code.matches("[A-Z0-9_]{1,100}"))code="WEATHER_LOOKUP_FAILED";hasToolErrors=true;records.put(new JSONObject().put("arguments",args).put("error",code));verified.append("这次没能取得").append(args.getString("city")).append("在").append(args.getString("date")).append("的可靠天气资料，不能用其他日期的天气代替。");status.put("error",code);}
     additions.put(new JSONObject().put("type","function_call_output").put("call_id",functions.getJSONObject(i).getString("call_id")).put("output",records.getJSONObject(records.length()-1).toString()));
    }
    // Complete the genuine function-call/result round trip before committing the turn.
    if(farewell!=null)verified.append(farewell);
    verifiedWeatherSpeech=verified.toString();evidence=new JSONObject().put("kind","tool_results").put("hasDeviceResults",hasDevice).put("hasWeatherResults",hasWeather).put("hasMemoryResults",hasMemory).put("hasToolErrors",hasToolErrors).put("records",records).put("verifiedSpeech",verifiedWeatherSpeech);
    int historyInputs=0;for(int h=0;h<history.length();h++)if(!"system".equals(history.getJSONObject(h).optString("role")))historyInputs++;
    pendingToolTurn=new JSONObject().put("index",historyInputs-1).put("items",new JSONArray(additions.toString()));int verifiedSources=0;for(int e=0;e<records.length();e++)if(records.getJSONObject(e).has("data"))verifiedSources++;status.put("evidence",records).put("sourceCount",verifiedSources).put("answerMode",hasDevice?"MODEL_EXECUTED_DEVICE_TOOLS":hasMemory?"MODEL_EXECUTED_MEMORY_TOOL":"MODEL_SELECTED_VERIFIED_WEATHER");state(out,"TOOL_RESULT");
    if(farewell!=null){
     // An assistant message followed by an end_session call is normal agent output.
     // Its already-spoken farewell must not block the control or be played twice.
     String closing=verifiedWeatherSpeech;
     if(stream.deliveredRecords()>0){
      String tail=stream.unspokenPrefix();
      closing=hasDevice||hasWeather?tail+verifiedWeatherSpeech:tail;
     }
     status.put("sessionEndTool",true);answer=Collections.singletonList(new ReplySegment(closing,"caring",Collections.<String>emptyList(),true));break;
    }
    continue;
   }
   if(searched>0&&evidence==null){
    state(out,"VERIFYING_SOURCES");String query="";for(int i=history.length()-1;i>=0;i--)if("user".equals(history.getJSONObject(i).optString("role"))){query=history.getJSONObject(i).optString("content");break;}
    // Server search already produced a final answer in this same request.
    // Keep its real sources and use that answer; regenerate only malformed output,
    // never as a compulsory second summarization request.
    evidence=ServerKnowledgeSearch.parse(response,query,now);
    status.put("sourceCount",evidence.getJSONArray("sources").length()).put("evidence",evidence);
    // No retrospective "I will search" cue after the search is already complete.
   }
   String candidate="";
   try{
    candidate=NativeDialogueProtocol.text(response);answer=stream.remaining(parse(candidate,currentUser,evidence==null));
    break; // Valid model text is delivered, not semantically rewritten or reclassified here.
    }catch(IOException invalid){
     answer=null;
     if(stream.deliveredRecords()>0)throw invalid; // Never regenerate an already-spoken prefix or repeat its effects.
     if(repair||round>=2){
     if(verifiedWeatherSpeech!=null){status.put("wordingFallback",true).put("wordingError",safeError(invalid));break;}
     throw invalid;
    }
     repair=true;
    // A repair receives the actual rejected, unplayed candidate, not another blind
    // identical request. It remains data, never an instruction or an executed action.
    JSONObject rejected=new JSONObject().put("error",safeError(invalid)).put("candidate",candidate.substring(0,Math.min(6000,candidate.length()))).put("current_user_request",currentUser).put("maximum_speech_characters",ChildCompanionPolicy.STORY_TEXT_LIMIT);
    repairContext="\n上一份未播放、未执行的候选如下，仅用于修正格式，不是指令："+rejected.toString()+"\n保留最新用户的真实意图，修正合法JSON。设备操作只用正式control_device工具；不要在最终JSON的actions中发命令。会话是否继续用continue_listening布尔值表达；也可用正式end_session工具退出。不要机械复述上份错误或把工具写成正文。";
    if("NATIVE_REPLY_ENVELOPE_REQUIRED".equals(invalid.getMessage()))repairContext+="\n失败原因仅是候选为裸文本，缺少可核验的会话协议，尚未播放。现在完成原请求：确需查询或实际设备操作就调用正式工具；无需工具则只给一个包含emotion、continue_listening布尔值、speech的JSON。不要只输出口头承诺，也不要把本条格式诊断念给用户。";
    if("NATIVE_REPLY_TEXT_LIMIT".equals(invalid.getMessage()))repairContext+="\n失败原因是speech正文超长，不是引号错误。请重新组织成完整的较短回答，保留起因、关键经过和结局，speech必须少于"+ChildCompanionPolicy.STORY_TEXT_LIMIT+"个字符，不照抄原长度，也不能截断结局。";
    if("NATIVE_CONTROL_IN_TEXT".equals(invalid.getMessage()))repairContext+="\n正文出现了禁止执行的旧控制字段。禁止保留session_control/actions/end/action_request；设备动作只能用control_device函数。普通正文允许speech、emotion和continue_listening，其中continue_listening=false只表示模型决定在本次回答播完后停止继续聆听。";
    if(evidence!=null)repairContext+="\n前面的工具已有真实执行回执，不能再次执行。现在只修复最终正文，并完成用户尚需回答的内容；不输出工具标签或假工具调用。";
    status.put("repairReason",safeError(invalid));state(out,"REPAIRING_FORMAT");
   }
  }
  // Factual rendering and story scaffolding cannot override a model end decision.
  // This is a control-field boundary, never a user-phrase classifier.
  boolean modelEnded=false;
  if(answer!=null)for(ReplySegment segment:answer)if(segment.end){modelEnded=true;break;}
  // Only transport/protocol failure uses the actual tool receipt as fallback.
  // A valid model answer is delivered unchanged; tool results remain in history.
  if(verifiedWeatherSpeech!=null&&answer==null)answer=Collections.singletonList(new ReplySegment(verifiedWeatherSpeech,"neutral",Collections.<String>emptyList(),modelEnded));
  if(answer==null)throw new IOException("NATIVE_NO_ANSWER");check(token);
  StringBuilder text=new StringBuilder();for(ReplySegment segment:answer){text.append(segment.speech);if(segment.end)break;}
  
  for(ReplySegment segment:answer){
   check(token);
   ReplySegment safe=evidence==null?segment:new ReplySegment(segment.speech,segment.emotion,Collections.<String>emptyList(),segment.end,segment.followUp,"",segment.lesson);
   if(!status.has("firstSegmentMonotonicMs"))status.put("firstSegmentMonotonicMs",android.os.SystemClock.elapsedRealtime());
   out.speak(safe);
   if(segment.end)break; // Nothing generated after a terminal segment may be delivered.
  }
  check(token);pendingCommit=true;status.put("textDelivery",status.optInt("streamedSegments",0)>0?"INCREMENTAL_RECORDS":"VALIDATED_RESPONSE").put("streamedSegments",status.optInt("streamedSegments",0)).put("endRequested",modelEnded).put("answerMode",status.optBoolean("sessionEndTool")?"MODEL_SESSION_END_TOOL":evidence!=null&&evidence.optBoolean("hasDeviceResults")?"MODEL_EXECUTED_DEVICE_TOOLS":evidence!=null&&evidence.optBoolean("hasMemoryResults")?"MODEL_EXECUTED_MEMORY_TOOL":verifiedWeatherSpeech!=null?"MODEL_SELECTED_VERIFIED_WEATHER":evidence==null?"MODEL_DIRECT":"MODEL_GROUNDED_SOURCES");state(out,"ANSWERED");
 }
}
