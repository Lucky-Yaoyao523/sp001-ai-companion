package org.sp001.core;
import java.io.IOException;import java.util.*;import org.json.*;
/** Incremental, bounded JSON sentence records. A complete object is the admission
 * unit; malformed records are discarded rather than read aloud. Legacy replies remain valid. */
public final class ReplyEnvelopeStream {
 public static final int MAX_WIRE=8192,MAX_RECORD=2048,MAX_SEGMENTS=24,MAX_SPEECH=500;
 private final StringBuilder buffer=new StringBuilder(),spoken=new StringBuilder();
 private SpokenReplyStream legacy;private int mode,wire,segments,errors;
 private boolean finished,end,actionRecord,fallbackGenerated;
 private String lastEmotion="neutral";
 private String validationCode="NATIVE_REPLY_FORMAT";
 private final int speechLimit,wireLimit,segmentLimit,recordLimit;
 private final String currentUser;private final boolean allowActions;
 public ReplyEnvelopeStream(){this(false);}
 public ReplyEnvelopeStream(boolean story){this(story?1200:MAX_SPEECH,story?24576:MAX_WIRE,story?96:MAX_SEGMENTS,null,true);}
 private ReplyEnvelopeStream(int text,int wire,int count,String user,boolean actions){
  speechLimit=text;wireLimit=wire;segmentLimit=count;currentUser=user;allowActions=actions;
  // Native records contain a whole answer. Escaping changes wire length, not
  // decoded speech length; retain both bounds without the legacy sentence cap.
  recordLimit=user==null?MAX_RECORD:wire;
 }
 /** One native wire schema and capacity for every topic; no local topic classification. */
 public static ReplyEnvelopeStream forModel(String user,boolean actions)throws IOException{
  if(user==null||user.trim().isEmpty()||user.length()>1000)throw new IOException("NATIVE_USER_REQUIRED");
  return new ReplyEnvelopeStream(ChildCompanionPolicy.STORY_TEXT_LIMIT,24576,96,user.trim(),actions);
 }
 public String validationCode(){return validationCode;}
 public int malformedRecords(){return errors;}
 public boolean generatedFallback(){return fallbackGenerated;}
 public boolean endRequested(){return end;}
 public String text(){return spoken.toString();}
 private void retain(ReplySegment s,List<ReplySegment> out)throws IOException{
  if(++segments>segmentLimit||spoken.length()+s.speech.length()>speechLimit)throw new IOException("REPLY_ENVELOPE_BOUND");
  spoken.append(s.speech);lastEmotion=s.emotion;end|=s.end;actionRecord|=!s.actions.isEmpty();out.add(s);
 }
 public List<ReplySegment> append(String delta)throws IOException{
  if(finished)throw new IOException("CHAT_CONTENT_AFTER_FINISH");
  if(delta==null)throw new IOException("CHAT_DELTA_TYPE");
  if(wire+delta.length()>wireLimit)throw new IOException("REPLY_ENVELOPE_BOUND");wire+=delta.length();
  List<ReplySegment> out=new ArrayList<ReplySegment>();
  if(mode==2){for(String s:legacy.append(delta))retain(ReplySegment.speech(s,legacy.emotion()),out);return out;}
  buffer.append(delta);
  if(mode==0){
   String s=buffer.toString().trim();if(s.isEmpty())return out;
   if(s.startsWith("`")&&!s.contains("\n")){if(s.length()>16)throw new IOException("REPLY_PREFIX_INVALID");return out;}
   if(s.startsWith("```")){s=s.substring(s.indexOf('\n')+1).trim();buffer.setLength(0);buffer.append(s);if(s.isEmpty())return out;}
   if(currentUser!=null&&s.charAt(0)!='{')throw new IOException("NATIVE_REPLY_FORMAT");
   if(s.charAt(0)=='{'){mode=1;buffer.setLength(0);buffer.append(s);}
   else if(s.charAt(0)=='['){
    if(s.length()>1&&(s.charAt(1)=='{'||s.charAt(1)=='"')){mode=1;}
    else{
     int close=s.indexOf(']');if(close<0){if(s.length()>32)throw new IOException("REPLY_PREFIX_INVALID");return out;}
     String mood=s.substring(1,close);if(!mood.matches("neutral|happy|playful|surprised|curious|caring"))s="[neutral]"+s.substring(close+1);
     mode=2;legacy=new SpokenReplyStream(speechLimit);buffer.setLength(0);
     for(String sentence:legacy.append(s))retain(ReplySegment.speech(sentence,legacy.emotion()),out);return out;
    }
   }else{
    mode=2;legacy=new SpokenReplyStream(speechLimit);buffer.setLength(0);
    for(String sentence:legacy.append(s))retain(ReplySegment.speech(sentence,legacy.emotion()),out);return out;
   }
  }
  while(mode==1){
   int at=0;while(at<buffer.length()&&Character.isWhitespace(buffer.charAt(at)))at++;
   if(at>0)buffer.delete(0,at);if(buffer.length()==0)break;
   if(buffer.charAt(0)!='{'){
    if(currentUser!=null&&segments==1&&buffer.charAt(0)!='[')break;
    int newline=buffer.indexOf("\n");if(newline<0){if(buffer.length()>recordLimit)throw new IOException("REPLY_RECORD_BOUND");break;}
    if(!buffer.substring(0,newline).trim().equals("```"))errors++;
    buffer.delete(0,newline+1);continue;
   }
   int depth=0,close=-1,discardLine=-1;boolean quoted=false,escape=false;
   for(int i=0;i<buffer.length();i++){
    char c=buffer.charAt(i);
    // Raw newline inside a JSON string is invalid. Resume at the next record,
    // not at an invented repaired string; valid escaped newlines remain untouched.
    if(c=='\n'&&quoted){discardLine=i+1;break;}
    if(c=='\n'&&!quoted&&depth==1){
     int j=i+1;while(j<buffer.length()&&Character.isWhitespace(buffer.charAt(j)))j++;
     int k=i-1;while(k>=0&&Character.isWhitespace(buffer.charAt(k)))k--;
     if(j<buffer.length()&&buffer.charAt(j)=='{'&&k>=0&&buffer.charAt(k)!=':'&&buffer.charAt(k)!='['&&buffer.charAt(k)!=','){discardLine=i+1;break;}
    }
    if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
    else if(c=='"')quoted=true;
    else if(c=='{'||c=='[')depth++;
    else if(c=='}'||c==']'){if(--depth==0){close=i+1;break;}if(depth<0)break;}
   }
   if(discardLine>=0){errors++;buffer.delete(0,discardLine);continue;}
   if(close<0){if(buffer.length()>recordLimit)throw new IOException("REPLY_RECORD_BOUND");break;}
   String record=buffer.substring(0,close);buffer.delete(0,close);
   if(record.length()>recordLimit)throw new IOException("REPLY_RECORD_BOUND");
   if(currentUser!=null&&end)continue; // Terminal session control owns the rest of this response.
   ReplySegment s=parse(record);if(s!=null)retain(s,out);
  }
  return out;
 }
 private ReplySegment parse(String record){
  try{
   JSONObject obj=new JSONObject(record);Object value=obj.opt("speech");
   if(!(value instanceof String)){errors++;return null;}
   String text=((String)value).trim();
   if(currentUser!=null&&text.length()>speechLimit){validationCode="NATIVE_REPLY_TEXT_LIMIT";errors++;return null;}
   if(!text.isEmpty())text=MiniMaxCodec.speech(text,speechLimit);
   String mood=obj.opt("emotion") instanceof String?obj.getString("emotion"):"neutral";
   List<String> commands=new ArrayList<String>();Object raw=obj.opt("actions");
   if(raw!=null&&raw!=JSONObject.NULL){
    if(!(raw instanceof JSONArray)||((JSONArray)raw).length()>3){errors++;commands.add("invalid_action");}
    else{
     JSONArray a=(JSONArray)raw;
     for(int i=0;i<a.length();i++){
      Object command=a.opt(i);
      if(command instanceof String&&((String)command).matches("[a-z_]{2,28}"))commands.add((String)command);
      else{errors++;commands.add("invalid_action");}
     }
    }
   }
   boolean closing=Boolean.TRUE.equals(obj.opt("end"));
   if(currentUser!=null){
    // Device actions still require completed function_call items. Session lifetime is
    // presentation metadata owned by the same model response, not a local phrase classifier.
    Object listen=obj.opt("continue_listening");
    if(!(listen instanceof Boolean)){validationCode="NATIVE_LIFECYCLE_REQUIRED";errors++;return null;}
    boolean modelClose=Boolean.FALSE.equals(listen);
    if(closing||!commands.isEmpty()||obj.has("session_control")||obj.has("action_request")){validationCode="NATIVE_CONTROL_IN_TEXT";errors++;return null;}
    closing=modelClose;
   }
   String follow=obj.opt("follow_up") instanceof String?obj.getString("follow_up"):"";
   if(follow.length()>60)follow="";
   JSONObject memory=obj.optJSONObject("memory");String quote="";
   if(memory!=null&&"preference".equals(memory.optString("kind"))&&memory.opt("quote") instanceof String&&CompanionMemory.safeText(memory.getString("quote")))quote=memory.getString("quote");
   if(text.isEmpty()&&commands.isEmpty()&&follow.isEmpty()&&quote.isEmpty())return null;
   return new ReplySegment(text,mood,commands,closing,follow,quote,obj.opt("lesson") instanceof String?obj.getString("lesson"):"");
  }catch(Exception malformed){errors++;return null;}
 }
 public List<ReplySegment> finish(String reason)throws IOException{
  if(finished)throw new IOException("CHAT_DUPLICATE_FINISH");
  if(!"stop".equals(reason))throw new IOException("MINIMAX_REPLY_INCOMPLETE");
  List<ReplySegment> out=new ArrayList<ReplySegment>();
  if(mode==2){String tail=legacy.finish(reason);if(!tail.isEmpty())retain(ReplySegment.speech(tail,legacy.emotion()),out);end=legacy.result().endSession;}
  else if(buffer.toString().trim().length()>0&&!buffer.toString().trim().equals("```")){
   String tail=buffer.toString().trim();
   // A provider's public output_text may continue as prose after its envelope.
   // Preserve that continuation as speech, never as commands or lifecycle data.
   // Explicit session closure still owns the response; malformed JSON tails fail.
   if(currentUser==null||segments!=1||tail.startsWith("{")||tail.startsWith("[")||tail.startsWith("}")||tail.startsWith("]"))errors++;
   else if(!end)retain(ReplySegment.speech(MiniMaxCodec.speech(tail,speechLimit),lastEmotion),out);
  }
  finished=true;
  if(segments==0||(spoken.length()==0&&!actionRecord)){fallbackGenerated=true;retain(ReplySegment.speech("刚才没有接好，我们继续聊。","caring"),out);}
  return out;
 }
}
