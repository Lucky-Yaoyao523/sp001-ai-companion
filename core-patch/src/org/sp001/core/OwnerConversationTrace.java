package org.sp001.core;
import java.io.*;import java.util.*;import org.json.*;
/** Explicit short-lived, single-session test evidence. Absent arm file means no trace.
 * Called only by the conversation consumer, never microphone/provider callbacks. */
final class OwnerConversationTrace {
 static final String ARM="sp001-owner-trace-arm.json";
 private final File root;private final String sessionId;private final long deadline;
 private final int maximumTurns,maximumBytes;private final boolean storeText,storePcm;
 private final Set<Long> captured=new HashSet<Long>();private int events,bytes;private boolean closed,failed,textSaved,audioSaved;
 private OwnerConversationTrace(File root,String sid,long deadline,int turns,int bound,boolean text,boolean pcm){this.root=root;sessionId=sid;this.deadline=deadline;maximumTurns=turns;maximumBytes=bound;storeText=text;storePcm=pcm;}
 static OwnerConversationTrace claim(File files,JSONObject config,String sid,int version,long wall,long monotonic){
  try{
   File arm=new File(files,ARM);if(!arm.isFile())return null;
   if(arm.length()<2||arm.length()>2048||!arm.getCanonicalFile().getParentFile().equals(files.getCanonicalFile()))return null;
   if(!Boolean.TRUE.equals(config.opt("enabled"))||!Boolean.TRUE.equals(config.opt("recordingConsent"))||!Boolean.TRUE.equals(config.opt("cloudConsent")))return null;
   if(sid==null||!sid.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||wall<0||monotonic<0)return null;
   byte[] buffer=new byte[(int)arm.length()];DataInputStream in=new DataInputStream(new FileInputStream(arm));try{in.readFully(buffer);}finally{in.close();}
   JSONObject p=new JSONObject(new String(buffer,"UTF-8"));Arrays.fill(buffer,(byte)0);
   if(p.length()!=10||p.optInt("schema")!=1||!Boolean.TRUE.equals(p.opt("testOnly"))||p.optInt("expectedVersion")!=version)return null;
   for(String field:new String[]{"schema","expectedVersion","maxTurns","maxPcmBytes"})if(!(p.opt(field) instanceof Integer))return null;
   for(String field:new String[]{"issuedAtMs","expiresAtMs"})if(!(p.opt(field) instanceof Integer)&&!(p.opt(field) instanceof Long))return null;
   String id=p.optString("runId","");long issued=p.optLong("issuedAtMs",-1),expiry=p.optLong("expiresAtMs",-1);
   int turns=p.optInt("maxTurns",0),bound=p.optInt("maxPcmBytes",0);
   if(!id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||issued<0||issued>wall+2000||wall-issued>20000||expiry<=wall||expiry-issued>180000||turns<1||turns>4||bound<0||bound>3840000)return null;
   Object text=p.opt("storeTranscript"),pcm=p.opt("storePcm");if(!(text instanceof Boolean)||!(pcm instanceof Boolean)||(!(Boolean)text&&!(Boolean)pcm))return null;
   File root=new File(files,"sp001-test-trace-"+id);if(!root.mkdir())return null;
   if(!arm.renameTo(new File(root,"request.json"))){root.delete();return null;}
   OwnerConversationTrace trace=new OwnerConversationTrace(root,sid,monotonic+(expiry-wall),turns,bound,(Boolean)text,(Boolean)pcm);
   trace.event(0,"CLAIMED","",monotonic);return trace;
  }catch(Exception ignored){return null;}
 }
 private boolean allowed(long now){return !closed&&!failed&&now>=0&&now<deadline;}
 synchronized void capture(long id,byte[] pcm,long now){
  if(!allowed(now)||id<=0||captured.contains(id)||captured.size()>=maximumTurns||pcm==null||pcm.length<2||pcm.length>960000||(pcm.length&1)!=0)return;
  captured.add(id);if(!storePcm||pcm.length>maximumBytes-bytes)return;
  try{FileOutputStream out=new FileOutputStream(new File(root,"capture-"+id+".pcm"));try{out.write(pcm);}finally{out.close();}bytes+=pcm.length;audioSaved=true;event(id,"CAPTURE",String.valueOf(pcm.length),now);}catch(Exception e){failed=true;}
 }
 synchronized void text(long id,String kind,String value,long now){
  if(!allowed(now)||!storeText||!captured.contains(id)||value==null||value.length()>1000||!("ASR".equals(kind)||"REPLY".equals(kind)||"ACTION".equals(kind)))return;
  if(event(id,kind,value,now))textSaved=true;
 }
 private boolean event(long id,String kind,String value,long now){
  if(!allowed(now)||events>=48)return false;
  try{JSONObject row=new JSONObject().put("sessionId",sessionId).put("utteranceId",id).put("kind",kind).put("value",value).put("monotonicMs",now);byte[] b=(row.toString()+"\n").getBytes("UTF-8");FileOutputStream out=new FileOutputStream(new File(root,"events.jsonl"),true);try{out.write(b);}finally{out.close();Arrays.fill(b,(byte)0);}events++;return true;}catch(Exception e){failed=true;return false;}
 }
 synchronized boolean transcriptSaved(){return textSaved;}
 synchronized boolean audioSaved(){return audioSaved;}
 synchronized void close(){
  if(closed)return;closed=true;
  try{JSONObject result=new JSONObject().put("sessionId",sessionId).put("events",events).put("capturedTurns",captured.size()).put("audioBytes",bytes).put("transcriptSaved",textSaved).put("audioSaved",audioSaved).put("failed",failed).put("closed",true);FileOutputStream out=new FileOutputStream(new File(root,"result.json"));try{out.write(result.toString().getBytes("UTF-8"));}finally{out.close();}}catch(Exception ignored){}
 }
}
