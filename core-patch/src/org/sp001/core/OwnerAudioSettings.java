package org.sp001.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import org.json.JSONObject;

/** Device-owned preferences. Never changes Wi-Fi, credentials or recording consent. */
final class OwnerAudioSettings {
    private static final int DEFAULT_SENSITIVITY=1;
    private static SharedPreferences prefs()throws Exception{return OwnerVoiceBridge.nativeContext().getSharedPreferences("sp001-audio-settings",Context.MODE_PRIVATE);}
    static int sensitivity(){try{return Math.max(0,Math.min(2,prefs().getInt("sensitivity",DEFAULT_SENSITIVITY)));}catch(Exception e){return DEFAULT_SENSITIVITY;}}
    // Match the original speaker profile. System volume is the sole user-facing
    // control; old gain preferences remain on disk for rollback, but cannot add hidden steps.
    static int gainMb(){return 2000;}
    static JSONObject readVolume()throws Exception{
        AudioManager a=(AudioManager)OwnerVoiceBridge.nativeContext().getSystemService(Context.AUDIO_SERVICE);
        int level=a.getStreamVolume(AudioManager.STREAM_MUSIC),max=a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int percent=VolumeChange.percent(level,max);
        return new JSONObject().put("success",true).put("changed",false).put("code","VOLUME_READ")
            .put("volume",level).put("maxVolume",max).put("percent",percent).put("gainMb",gainMb())
            .put("acousticVerified",false).put("answer","当前音量是百分之"+percent+"。");
    }
    // AudioManager is the common volume authority for voice and original controls.
    // Playback never reapplies a cached preference over a newer system setting.
    static final class Outcome {
        final String answer,code;final boolean verified;final JSONObject receipt;
        Outcome(String answer,String code,boolean verified,JSONObject receipt){this.answer=answer;this.code=code;this.verified=verified;this.receipt=receipt;}
    }
    static void describeTo(MiniMaxVoiceClient client){
        try{AudioManager a=(AudioManager)OwnerVoiceBridge.nativeContext().getSystemService(Context.AUDIO_SERVICE);
            client.deviceState(a.getStreamVolume(AudioManager.STREAM_MUSIC),a.getStreamMaxVolume(AudioManager.STREAM_MUSIC),gainMb());
        }catch(Exception ignored){/* Missing telemetry cannot change settings or block dialogue. */}
    }
    static String apply(int action)throws Exception{return applyChecked(action).answer;}
    static Outcome applyChecked(int action)throws Exception {
        if(action<AudioCommand.LOUDER||action>AudioCommand.MIC_LESS)throw new java.io.IOException("AUDIO_ACTION_INVALID");
        Context c=OwnerVoiceBridge.nativeContext();final SharedPreferences p=prefs();String answer,code="SETTING_CONFIRMED";boolean verified=true;
        JSONObject event=new JSONObject().put("atMs",System.currentTimeMillis()).put("command",action);
        if(action==AudioCommand.LOUDER||action==AudioCommand.QUIETER){
            final AudioManager a=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
            final boolean hadVolume=p.contains("volume"),hadGain=p.contains("gainMb");
            final int oldVolume=p.getInt("volume",13),oldGain=p.getInt("gainMb",1200);
            final LegacyVolumePreference legacy=LegacyVolumePreference.original();
            VolumeChange.Result result=VolumeChange.run(new VolumeChange.Ports(){
                public int volume(){return a.getStreamVolume(AudioManager.STREAM_MUSIC);}
                public int maximum(){return a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}
                public int gain(){return gainMb();}
                public void setVolume(int level){a.setStreamVolume(AudioManager.STREAM_MUSIC,level,0);}
                public boolean save(int level,int gain)throws Exception{legacy.save(level,maximum());return p.edit().putInt("volume",level).putInt("gainMb",gain).commit();}
                public void restoreSaved()throws Exception{
                    Exception stockFailure=null;try{legacy.restore();}catch(Exception error){stockFailure=error;}
                    SharedPreferences.Editor editor=p.edit();if(hadVolume)editor.putInt("volume",oldVolume);else editor.remove("volume");
                    if(hadGain)editor.putInt("gainMb",oldGain);else editor.remove("gainMb");
                    if(!editor.commit()){java.io.IOException failure=new java.io.IOException("VOLUME_RESTORE_FAILED");if(stockFailure!=null)failure.addSuppressed(stockFailure);throw failure;}
                    if(stockFailure!=null)throw stockFailure;
                }
            },action);
            answer=result.answer;verified=result.success;code=result.code;
            event.put("before",result.before).put("beforeGainMb",result.beforeGain).put("volume",result.observedVolume).put("requestedVolume",result.volume).put("requestedGainMb",result.gain).put("maxVolume",result.max)
                .put("success",result.success).put("changed",result.changed).put("percent",result.success?result.percent:JSONObject.NULL).put("observedGainMb",result.observedGain).put("acousticVerified",false).put("code",result.code).put("rollbackSucceeded",result.rollbackSucceeded);
        }else{
            int before=sensitivity(),level=action==AudioCommand.MIC_MORE?Math.min(2,before+1):action==AudioCommand.MIC_LESS?Math.max(0,before-1):DEFAULT_SENSITIVITY;
            if(!p.edit().putInt("sensitivity",level).commit())throw new java.io.IOException("SENSITIVITY_SAVE_FAILED");
            if(p.getInt("sensitivity",-1)!=level)throw new java.io.IOException("SENSITIVITY_READBACK_FAILED");
            answer=action==AudioCommand.MIC_NORMAL?"收音已恢复标准。":action==AudioCommand.MIC_LESS?(level==before?"收音灵敏度已经最低了。":"收音灵敏度已降低。"):(level==before?"收音已经很灵敏了。":"收音更灵敏了。");event.put("sensitivity",level).put("before",before).put("changed",before!=level);
        }
        event.put("success",verified).put("code",code).put("answer",answer);
        try{java.io.FileOutputStream f=c.openFileOutput("sp001-audio-settings-event.json",Context.MODE_PRIVATE);
            try{f.write(event.toString().getBytes("UTF-8"));}finally{f.close();}
        }catch(java.io.IOException ignored){/* Diagnostic storage does not undo an applied setting. */}
        return new Outcome(answer,code,verified,event);
    }
}
