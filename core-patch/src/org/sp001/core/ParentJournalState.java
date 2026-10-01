package org.sp001.core;
import org.json.*;

/** Persisted text outbox bounds; acknowledgment is the parent's durable cursor. */
final class ParentJournalState {
 static final int MAX_EVENTS=5000,MAX_BYTES=8*1024*1024;
 private ParentJournalState(){}
 static void append(JSONObject state,String serialized)throws Exception{
  JSONArray events=state.getJSONArray("events");long seq=state.optLong("nextSeq",1);
  if(seq<1||seq>=9007199254740000L)throw new IllegalStateException("PARENT_SEQ_INVALID");
  JSONObject event=new JSONObject(serialized).put("seq",seq);
  // Reserve metadata space as well as the full UTF-8 event, not a character count.
  int current=state.toString().getBytes("UTF-8").length;
  if(events.length()>=MAX_EVENTS||(long)current+event.toString().getBytes("UTF-8").length+256>MAX_BYTES){
   long dropped=state.optLong("droppedEvents",0);state.put("droppedEvents",Math.min(9007199254740000L,dropped+1));
   if(!state.has("firstDroppedAt"))state.put("firstDroppedAt",event.optString("at",""));
   state.put("lastDroppedAt",event.optString("at",""));return;
  }
  events.put(event);state.put("nextSeq",seq+1);
 }
 static JSONObject after(JSONObject state,long acknowledged)throws Exception{
  long next=state.optLong("nextSeq",1);
  if(acknowledged<0||acknowledged>=next)throw new IllegalStateException("PARENT_ACK_AHEAD");
  JSONArray source=state.getJSONArray("events"),remaining=new JSONArray(),batch=new JSONArray();
  for(int i=0;i<source.length();i++){JSONObject event=source.getJSONObject(i);if(event.getLong("seq")>acknowledged){remaining.put(event);if(batch.length()<8)batch.put(event);}}
  state.put("events",remaining);
  JSONObject sync=new JSONObject().put("pendingEvents",remaining.length()).put("capacityEvents",MAX_EVENTS)
   .put("journalBytes",state.toString().getBytes("UTF-8").length).put("capacityBytes",MAX_BYTES)
   .put("droppedEvents",state.optLong("droppedEvents",0)).put("firstDroppedAt",state.optString("firstDroppedAt",""))
   .put("lastDroppedAt",state.optString("lastDroppedAt",""));
  return new JSONObject().put("events",batch).put("nextSeq",next).put("sync",sync);
 }
}
