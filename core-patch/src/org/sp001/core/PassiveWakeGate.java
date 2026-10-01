package org.sp001.core;

/** Caller owns the session lock. No queue, timer, I/O or deferred sensor action. */
public final class PassiveWakeGate {
 public static final long EVENT_MAX_AGE_MS=5000,EVENT_FUTURE_SKEW_MS=2000,BURST_QUIET_MS=1500;
 public static final long START_COOLDOWN_MS=60000,EXIT_COOLDOWN_MS=30000,EXPLICIT_STOP_COOLDOWN_MS=120000;
 private long blockedUntil,lastSeen;private boolean seen;private int accepted,ignored;
 public static boolean source(String s){return "ir".equals(s)||"gsensor".equals(s);}
 public String offer(String source,long eventWallMs,long wallMs,long now,int hour,boolean available){
  if(!source(source))return "UNSUPPORTED";
  if(now<0||eventWallMs<=0||wallMs<=0||eventWallMs<wallMs-EVENT_MAX_AGE_MS||eventWallMs>wallMs+EVENT_FUTURE_SKEW_MS){ignored++;return "STALE_EVENT";}
  boolean burst=seen&&(now<lastSeen||now-lastSeen<BURST_QUIET_MS);seen=true;lastSeen=now;
  if(!available){ignored++;return "BUSY_OR_DISABLED";}
  if(hour<8||hour>=22){ignored++;return "QUIET_HOURS";}
  if(now<blockedUntil){ignored++;return "COOLDOWN";}
  if(burst){ignored++;return "BURST";}
  return "ACCEPT";
 }
 /** Lifecycle result, never a phrase matcher. Closing must not immediately re-wake. */
 public static boolean explicitEnd(String result,boolean cancelled){return cancelled||"SESSION_ENDED".equals(result)||"SESSION_ENDED_BY_REPLY".equals(result);}
 public void started(long now){accepted++;blockedUntil=Math.max(blockedUntil,now+START_COOLDOWN_MS);}
 public void ended(long now,boolean stop){blockedUntil=Math.max(blockedUntil,now+(stop?EXPLICIT_STOP_COOLDOWN_MS:EXIT_COOLDOWN_MS));}
 public int accepted(){return accepted;}public int ignored(){return ignored;}public long blockedUntil(){return blockedUntil;}
}
