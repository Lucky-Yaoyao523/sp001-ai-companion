package org.sp001.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import org.json.JSONObject;

/** Persistent persona choice, independent of API credentials and current conversation. */
public final class CharacterMode {
    private static final String PREF="sp001-character-mode";
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static long lastReaction;
    private static volatile Boolean stagedMode;
    static void stage(Boolean legacy){stagedMode=legacy;}
    static void rememberEnabled(boolean enabled) throws Exception {
        SharedPreferences p=OwnerVoiceBridge.nativeContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(!p.contains("ownerManaged")||p.getBoolean("ownerManaged",false)!=enabled)
            if(!p.edit().putBoolean("ownerManaged",enabled).commit())throw new java.io.IOException("MODE_OWNERSHIP_SAVE_FAILED");
    }
    private CharacterMode(){}
    public static boolean managed(){
        Boolean stored=null;boolean selected=false;
        try {
            SharedPreferences p=OwnerVoiceBridge.nativeContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
            selected=p.contains("mode");stored=p.contains("ownerManaged")?Boolean.valueOf(p.getBoolean("ownerManaged",false)):null;
            Class<?> comm=Class.forName("com.smarttoy.f");
            if(((Boolean)comm.getMethod("ce").invoke(null))||((Boolean)comm.getMethod("cf").invoke(null)))return false;
            int state=ModePolicy.UNAVAILABLE;
            try{
                JSONObject c=OwnerVoiceBridge.readConfig(OwnerVoiceBridge.nativeContext());
                if(Boolean.FALSE.equals(c.opt("enabled")))state=ModePolicy.DISABLED;
                else if(Boolean.TRUE.equals(c.opt("enabled"))&&ModePolicy.voiceKind(c.optString("mode")))state=ModePolicy.ENABLED;
            }catch(Exception unreadable){ /* Keep the saved persona; never open English on an I/O error. */ }
            boolean result=ModePolicy.managed(state,stored,selected);
            if(state!=ModePolicy.UNAVAILABLE)rememberEnabled(result);
            return result;
        }catch(Exception e){return ModePolicy.managed(ModePolicy.UNAVAILABLE,stored,selected);}
    }
    public static boolean legacy(){
        Boolean staged=stagedMode;if(staged!=null)return staged.booleanValue();
        try{return "legacy".equals(OwnerVoiceBridge.nativeContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("mode","chinese"));}
        catch(Exception e){return false;}
    }
    public static String name(){return legacy()?"legacy":"chinese";}
    static void save(boolean legacy)throws Exception {
        SharedPreferences p=OwnerVoiceBridge.nativeContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String value=legacy?"legacy":"chinese";
        if(!p.edit().putString("mode",value).commit()||!value.equals(p.getString("mode","")))throw new java.io.IOException("MODE_SAVE_FAILED");
        receipt("MODE_SELECTED",value);
    }
    /** Root stock activity gate covers idle, menu, story and sensor-triggered English.
     * Sleep remains owned by original power management; factory/setup is not intercepted.
     */
    public static boolean allowActivity(String activity){
        if(!managed()||legacy()||"sleep".equals(activity))return true;
        // Passive native events are admitted synchronously; never queue an old blink here.
        receipt("LEGACY_ACTIVITY_BLOCKED",activity==null?"null":activity);
        return false;
    }
    private static synchronized void react(){
        long now=SystemClock.elapsedRealtime();if(now-lastReaction<15000)return;lastReaction=now;
        UI.post(new Runnable(){public void run(){
            try{if(!legacy()&&!OwnerHeadless.status().optBoolean("busy")){
                Object eyes=Class.forName("com.smarttoy.embedded.a.b").getMethod("iq").invoke(null);
                eyes.getClass().getMethod("g",String.class,Integer.TYPE).invoke(eyes,"BLINK",Integer.valueOf(1));
            }}catch(Exception ignored){}
        }});
    }
    static void announce(final boolean english)throws Exception {
        // Missing/failed prompt is a failed transaction, not a silent successful switch.
        MediaPlayer player=null;OwnerPlaybackEffects effects=null;
        final java.util.concurrent.atomic.AtomicBoolean complete=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            File f=new File(OwnerVoiceBridge.nativeContext().getFilesDir(),english?"sp001-mode-legacy.wav":"sp001-mode-chinese.wav");
            if(!f.isFile())throw new java.io.IOException("MODE_PROMPT_MISSING");
            player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);player.setDataSource(f.getPath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){complete.set(true);}});
            player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int a,int b){failed.set(true);return true;}});
            player.prepare();effects=new OwnerPlaybackEffects(player.getAudioSessionId());
            player.start();long until=SystemClock.elapsedRealtime()+9000;
            while(!complete.get()&&!failed.get()&&SystemClock.elapsedRealtime()<until)Thread.sleep(25);
            if(!complete.get()||failed.get())throw new java.io.IOException("MODE_PROMPT_NOT_COMPLETED");
            receipt("MODE_PROMPT_PLAYED",english?"legacy":"chinese");
        }finally{if(effects!=null)effects.close();if(player!=null)try{player.release();}catch(Exception ignored){}}
    }
    static void receipt(String event,String detail){
        try{
            JSONObject o=new JSONObject().put("event",event).put("detail",detail).put("characterMode",name()).put("atMs",System.currentTimeMillis());
            java.io.FileOutputStream f=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-character-mode-event.json",Context.MODE_PRIVATE);
            try{f.write(o.toString().getBytes("UTF-8"));}finally{f.close();}
            if(event.startsWith("MODE_SWITCH_")){
                java.io.FileOutputStream stable=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-mode-transition.json",Context.MODE_PRIVATE);
                try{stable.write(o.toString().getBytes("UTF-8"));}finally{stable.close();}
            }
        }catch(Exception ignored){}
    }
}
