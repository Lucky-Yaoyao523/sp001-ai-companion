package org.sp001.core;

/** No I/O. OwnerHeadless holds its session lock for every use. */
public final class WakeGate {
    public static final long COOLDOWN_MS=2500,SETTLE_MS=1000;
    private long revision,quietUntil,sleepRevision;
    private boolean tailPending;
    public boolean canStart(long now,boolean busy,boolean switching,boolean gesture,boolean enabled){
        return enabled&&!busy&&!switching&&!gesture&&!tailPending&&now>=quietUntil;
    }
    public void begin(){revision++;tailPending=false;}
    public void ending(){revision++;tailPending=true;}
    public void tailEnded(long now){revision++;tailPending=false;quietUntil=now+COOLDOWN_MS;}
    public void invalidate(){revision++;}
    public long sleepRevision(){return sleepRevision;}
    public void sleepRequested(){sleepRevision++;revision++;}
    public boolean canPrepare(long observedSleepRevision,boolean initiallySleeping,boolean nowSleeping){
        return sleepRevision==observedSleepRevision&&(initiallySleeping||!nowSleeping);
    }
    public boolean tailPending(){return tailPending;}
    public long ticket(){return ++revision;}
    public boolean current(long ticket){return ticket==revision;}
    public long delay(long now){return tailPending?-1:Math.max(SETTLE_MS,quietUntil-now);}
    public boolean canArm(long ticket,long now,boolean busy,boolean switching,boolean gesture,boolean eligible){
        return current(ticket)&&canStart(now,busy,switching,gesture,eligible);
    }
    public static boolean allowSleep(boolean owned,boolean busy,boolean explicitSleepOrShutdown){
        return !owned||!busy||explicitSleepOrShutdown;
    }
}
