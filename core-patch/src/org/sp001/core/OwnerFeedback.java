package org.sp001.core;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioManager;
import android.media.ToneGenerator;

/** Session-owned chest PWM using the ORIGINAL LED manager. No arbitrary sysfs or shell commands.
 * Slow processing pulse is <= 0.77 Hz. Stale queued callbacks cannot affect a newer session.
 */
public final class OwnerFeedback {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long owner, began;
    private static boolean active;
    private static String phase = "IDLE";
    private static long pendingUntil,lastErrorCue;
    static void pressAccepted(){
        synchronized(OwnerFeedback.class){if(active)return;pendingUntil=SystemClock.elapsedRealtime()+500;}
        UI.post(new Runnable(){public void run(){write(80);}});
        UI.postDelayed(new Runnable(){public void run(){synchronized(OwnerFeedback.class){if(!active&&SystemClock.elapsedRealtime()>=pendingUntil)write(0);}}},510);
    }
    static synchronized void errorCue(){
        long now=SystemClock.elapsedRealtime();if(now-lastErrorCue<2000)return;lastErrorCue=now;
        UI.post(new Runnable(){public void run(){
            try{final ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,65);
                tone.startTone(ToneGenerator.TONE_PROP_NACK,240);
                UI.postDelayed(new Runnable(){public void run(){tone.release();}},300);
            }catch(RuntimeException ignored){}
        }});
    }
    private OwnerFeedback() {}
    /** Called by the original PWM entry wrapper: old idle/battery callbacks cannot fake LISTENING.
     * Only the chest PWM is owned; the original RGB/battery indicators are unchanged.
     */
    public static synchronized int filterBrightness(int requested) {
        if(active) return InteractionPolicy.brightness(phase,SystemClock.elapsedRealtime()-began);
        if(SystemClock.elapsedRealtime()<pendingUntil)return 80;
        try {
            org.json.JSONObject c=OwnerVoiceBridge.readConfig(OwnerVoiceBridge.nativeContext());
            String mode=c.optString("mode");
            if(c.optBoolean("enabled") && !CharacterMode.legacy() && ("minimax".equals(mode)||"diagnostic".equals(mode))) return 0;
        }catch(Exception ignored){}
        return requested;
    }

    static synchronized void begin(final long token) {
        owner=token; active=true; pendingUntil=0; began=SystemClock.elapsedRealtime(); phase="STARTING";
        UI.post(new Runnable(){public void run(){
            synchronized(OwnerFeedback.class){if(!active||owner!=token)return;}
            try { Class<?> led=Class.forName("com.smarttoy.embedded.managers.d");
                led.getMethod("onResume").invoke(null); led.getMethod("jj").invoke(null);
            }catch(Exception ignored){}
            tick(token);
        }});
    }
    private static void tick(final long token) {
        synchronized(OwnerFeedback.class){
            if(!active||owner!=token)return;
            write(InteractionPolicy.brightness(phase,SystemClock.elapsedRealtime()-began));
        }
        UI.postDelayed(new Runnable(){public void run(){tick(token);}},150L);
    }
    private static void write(int value) {
        try{Class.forName("com.smarttoy.embedded.managers.d").getMethod("w",Integer.TYPE).invoke(null,Integer.valueOf(value));}
        catch(Exception ignored){}
    }
    static synchronized void state(String next) { if(active){phase=next;began=SystemClock.elapsedRealtime();} }
    static synchronized void end(final long token) {
        if(owner!=token)return; active=false;phase="IDLE";
        UI.post(new Runnable(){public void run(){synchronized(OwnerFeedback.class){if(owner==token&&!active)write(0);}}});
    }
    /** Short local cue, always before opening the microphone. No speech gets sent to a provider. */
    static void readyCue() throws InterruptedException {
        ToneGenerator tone=null;
        try { tone=new ToneGenerator(AudioManager.STREAM_MUSIC,65); tone.startTone(ToneGenerator.TONE_PROP_ACK,140); Thread.sleep(180); }
        catch(RuntimeException ignored){}
        finally{if(tone!=null)tone.release();}
    }
    static void exitCue(final long token) {
        UI.post(new Runnable(){public void run(){
            synchronized(OwnerFeedback.class){if(active||owner!=token)return;}
            try{final ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,50);
                tone.startTone(ToneGenerator.TONE_PROP_NACK,120);
                UI.postDelayed(new Runnable(){public void run(){try{tone.release();}finally{OwnerHeadless.feedbackTailEnded(token);}}},180);
            }catch(RuntimeException ignored){OwnerHeadless.feedbackTailEnded(token);}
        }});
    }
}
