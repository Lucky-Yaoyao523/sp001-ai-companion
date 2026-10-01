package org.sp001.core;
import java.io.IOException;
import org.json.JSONObject;

/** Trial grants stay persisted; explicit owner use is bounded by each foreground conversation. */
final class StreamingAsrAccess {
 static final int MAX_OWNER_CONNECTIONS=ConversationSession.DEFAULT_TURNS+1;
 private final StreamingAsrCandidateBudget trial;
 private final OwnerAsrDailyQuota daily;
 private final long startedAt;
 private final int maximum;
 private int used;
 private boolean expired;
 private StreamingAsrAccess(StreamingAsrCandidateBudget trial,OwnerAsrDailyQuota daily,long now,int maximum){this.trial=trial;this.daily=daily;startedAt=now;this.maximum=maximum;}
 static StreamingAsrAccess create(JSONObject root,JSONObject asr,StreamingAsrCandidateBudget.Store store,long wall,long monotonic)throws IOException{
  if(root==null||asr==null)throw new IOException("ASR_ACCESS_CONFIG");
  String usage=asr.optString("usage","trial");
  if("owner".equals(usage)){
   if(!Boolean.TRUE.equals(root.opt("enabled"))||!Boolean.TRUE.equals(root.opt("cloudConsent"))||!Boolean.TRUE.equals(root.opt("recordingConsent"))||!Boolean.TRUE.equals(asr.opt("ownerEnabled"))||!"qwen-streaming".equals(root.optString("asrProvider"))||!"minimax".equals(root.optString("mode")))throw new IOException("ASR_OWNER_CONSENT_REQUIRED");
   if(asr.has("candidateId")||asr.has("issuedAtMs")||asr.has("expiresAtMs")||asr.has("maxCalls"))throw new IOException("ASR_OWNER_TRIAL_FIELDS_CONFLICT");
   Object cap=asr.opt("maxConnectionsPerSession");
   if(!(cap instanceof Integer)||((Integer)cap)<1||((Integer)cap)>MAX_OWNER_CONNECTIONS||monotonic<0)throw new IOException("ASR_OWNER_SESSION_BOUND");
   Object requestedDaily=asr.has("maxConnectionsPerDay")?asr.opt("maxConnectionsPerDay"):Integer.valueOf(OwnerAsrDailyQuota.DEFAULT_LIMIT);
   if(!(requestedDaily instanceof Integer)||((Integer)requestedDaily)<1||((Integer)requestedDaily)>OwnerAsrDailyQuota.LIMIT)throw new IOException("ASR_OWNER_DAILY_CONFIG");
   return new StreamingAsrAccess(null,new OwnerAsrDailyQuota(store,wall,monotonic,((Integer)requestedDaily).intValue()),monotonic,((Integer)cap).intValue());
  }
  if(!"trial".equals(usage))throw new IOException("ASR_USAGE_INVALID");
  return new StreamingAsrAccess(new StreamingAsrCandidateBudget(asr.optString("candidateId",""),asr.optLong("issuedAtMs",0),asr.optLong("expiresAtMs",0),asr.optInt("maxCalls",0),store,wall,monotonic),null,monotonic,0);
 }
 synchronized boolean valid(long wall,long monotonic)throws IOException{
  if(trial!=null)return trial.valid(wall,monotonic);
  if(monotonic<startedAt||monotonic-startedAt>=ConversationSession.DEFAULT_MS)expired=true;
  return !expired&&daily.valid(wall,monotonic);
 }
 synchronized boolean remaining()throws IOException{return trial!=null?trial.remaining():!expired&&used<maximum&&daily.remaining();}
 synchronized void reserve(long wall,long monotonic)throws IOException{
  if(trial!=null){trial.reserve(wall,monotonic);return;}
  if(!valid(wall,monotonic))throw new IOException("ASR_OWNER_SESSION_EXPIRED");
  if(used>=maximum)throw new IOException("ASR_OWNER_SESSION_EXHAUSTED");
  daily.reserve(wall,monotonic);used++;
 }
 synchronized String unavailableReason(long wall,long monotonic)throws IOException{
  if(!valid(wall,monotonic))return trial!=null?"ASR_CANDIDATE_EXPIRED":"ASR_OWNER_TIME_INVALID";
  if(trial!=null)return trial.remaining()?"":"ASR_CANDIDATE_EXHAUSTED";
  if(used>=maximum)return "ASR_OWNER_SESSION_EXHAUSTED";
  return daily.remaining()?"":"ASR_OWNER_DAILY_EXHAUSTED";
 }
 boolean ownerUse(){return trial==null;}
}
