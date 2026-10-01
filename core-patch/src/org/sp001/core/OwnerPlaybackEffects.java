package org.sp001.core;

import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import org.json.JSONObject;

/** Original speaker EQ and fixed original +20 dB enhancement; system volume owns user adjustment.
 * Owned effects attach to exactly one AudioTrack session and are always released.
 */
final class OwnerPlaybackEffects {
    private Equalizer eq;
    private LoudnessEnhancer loudness;
    private String error="";
    OwnerPlaybackEffects(int session) {
        try {eq=(Equalizer)Class.forName("com.smarttoy.util.c").getMethod("J",Integer.TYPE).invoke(null,Integer.valueOf(session));}
        catch(Exception e){error="EQ_UNAVAILABLE";}
        try {loudness=new LoudnessEnhancer(session);loudness.setTargetGain(OwnerAudioSettings.gainMb());loudness.setEnabled(true);}
        catch(RuntimeException e){error=error+" LOUDNESS_UNAVAILABLE";}
    }
    /** Called only by the playback owner before writing PCM, not a new worker or gain boost. */
    void refreshGain(){
        if(loudness==null)return;
        try{int requested=OwnerAudioSettings.gainMb();
            if(Math.abs(loudness.getTargetGain()-requested)>.5f)loudness.setTargetGain(requested);
            if(!loudness.getEnabled()||Math.abs(loudness.getTargetGain()-requested)>.5f)error="LOUDNESS_READBACK_FAILED";
        }catch(RuntimeException unavailable){error="LOUDNESS_UPDATE_FAILED";}
    }
    JSONObject status(int rate) {
        JSONObject j=new JSONObject();
        try{j.put("sampleRate",rate).put("stockEqEnabled",eq!=null&&eq.getEnabled())
            .put("loudnessEnabled",loudness!=null&&loudness.getEnabled())
            .put("targetGainMb",loudness==null?0:loudness.getTargetGain()).put("error",error.trim());}catch(Exception ignored){}
        return j;
    }
    void close() { if(eq!=null){try{eq.release();}catch(RuntimeException ignored){}eq=null;}
        if(loudness!=null){try{loudness.release();}catch(RuntimeException ignored){}loudness=null;} }
}
