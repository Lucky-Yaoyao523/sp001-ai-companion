package org.sp001.core;

/** Exactly three releases switch modes after a quiet gap; one/two mean one ordinary press.
 * Late UI callbacks cannot discard a completed older gesture. No hardware or clock I/O.
 */
public final class ButtonGesture {
    public static final long GAP_MS=600, WINDOW_MS=1200, BOUNCE_MS=90;
    public static final int IGNORE=0, PENDING=1, SINGLE=2, TRIPLE=4, EXCESS=8, STOP=16;
    private String source;
    private long last=-10000, generation, deadline, first, suppressedUntil=-1;
    private int count, expired;
    public int press(String channel,long now) { return press(channel,now,false); }
    public int press(String channel,long now,boolean talking) {
        if(channel==null||now<0)throw new IllegalArgumentException("BUTTON_EVENT");
        if(now<=suppressedUntil)return IGNORE;
        long delta=now-last;
        if(delta<0 || delta<BOUNCE_MS || (delta<500 && source!=null&&!source.equals(channel)))return IGNORE;
        // A stop never waits for the idle triple-click window. Remaining taps in the
        // same burst are quarantined so they cannot restart or change the persona.
        if(talking){source=channel;last=now;suppress(now);return STOP;}
        // A fourth tap within the quiet gap belongs to the SAME burst, even when
        // the burst crossed the total triple window. Never dispatch three early.
        if(count>0 && now>deadline) expired=settle();
        source=channel;last=now;
        if(count==0)first=now;
        count=Math.min(4,count+1);deadline=now+GAP_MS;generation++;
        return PENDING;
    }
    public void suppress(long now){count=0;expired=IGNORE;generation++;suppressedUntil=now+WINDOW_MS;}
    private int settle(){int n=count;count=0;return n>=4?EXCESS:n==3&&last-first<=WINDOW_MS?TRIPLE:n>0?SINGLE:IGNORE;}
    public int takeExpired(){int action=expired;expired=IGNORE;return action;}
    public long generation(){return generation;}
    public long waitMs(long now){return Math.max(1,deadline-now+1);}
    public int consume(long ticket,long now){
        if(ticket!=generation||count==0||now<=deadline)return IGNORE;
        return settle();
    }
    public void reset(){count=0;expired=0;generation++;source=null;last=-10000;suppressedUntil=-1;}
}
