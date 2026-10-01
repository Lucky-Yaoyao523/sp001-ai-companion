package org.sp001.core;
import java.io.IOException;
/** Persisted UTC-day budget. Explicit owner approval permits up to 2000.
 * Legacy configurations stay at 60; increasing the ceiling never resets usage. */
final class OwnerAsrDailyQuota {
 static final int LIMIT=2000,DEFAULT_LIMIT=60;private static final long DAY_MS=86400000L;
 private static final String KEY="owner-qwen-daily-v1";private static final Object LOCK=new Object();
 private final StreamingAsrCandidateBudget.Store store;private final long startWall,startMonotonic;
 private final int ceiling;
 private long day;private boolean invalid;
 OwnerAsrDailyQuota(StreamingAsrCandidateBudget.Store store,long wall,long monotonic)throws IOException{
  this(store,wall,monotonic,DEFAULT_LIMIT);
 }
 OwnerAsrDailyQuota(StreamingAsrCandidateBudget.Store store,long wall,long monotonic,int ceiling)throws IOException{
  if(store==null||wall<0||monotonic<0||ceiling<1||ceiling>LIMIT)throw new IOException("ASR_OWNER_DAILY_CONFIG");
  this.store=store;this.ceiling=ceiling;startWall=wall;startMonotonic=monotonic;day=wall/DAY_MS;synchronized(LOCK){count();}
 }
 private int count()throws IOException{
  String value=store.read(KEY);if(value==null)return 0;
  if(!value.matches("v1:[0-9]{1,9}:[0-9]{1,4}"))throw new IOException("ASR_OWNER_DAILY_LEDGER_INVALID");
  String[] p=value.split(":");long stored;int used;
  try{stored=Long.parseLong(p[1]);used=Integer.parseInt(p[2]);}catch(NumberFormatException e){throw new IOException("ASR_OWNER_DAILY_LEDGER_INVALID");}
  if(used>LIMIT)throw new IOException("ASR_OWNER_DAILY_LEDGER_INVALID");
  if(stored>day){invalid=true;return ceiling;}return stored==day?used:0;
 }
 boolean valid(long wall,long monotonic)throws IOException{synchronized(LOCK){
  long elapsed=monotonic-startMonotonic;
  if(invalid||wall<0||elapsed<0||Math.abs((wall-startWall)-elapsed)>5000){invalid=true;return false;}
  long current=wall/DAY_MS;if(current<day){invalid=true;return false;}day=current;count();return !invalid;
 }}
 boolean remaining()throws IOException{synchronized(LOCK){int used=count();return !invalid&&used<ceiling;}}
 void reserve(long wall,long monotonic)throws IOException{synchronized(LOCK){
  if(!valid(wall,monotonic))throw new IOException("ASR_OWNER_DAILY_EXPIRED");int used=count();
  if(invalid||used>=ceiling)throw new IOException("ASR_OWNER_DAILY_EXHAUSTED");
  if(!store.write(KEY,"v1:"+day+":"+(used+1)))throw new IOException("ASR_OWNER_DAILY_PERSIST_FAILED");
 }}
 int used()throws IOException{synchronized(LOCK){return count();}}
}
