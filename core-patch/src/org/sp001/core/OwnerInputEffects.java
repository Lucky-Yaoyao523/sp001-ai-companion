package org.sp001.core;

import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import org.json.JSONObject;

/** Best-effort effects attached ONLY to our recorder. Unsupported hardware falls back unchanged. */
final class OwnerInputEffects {
    private AutomaticGainControl agc;private NoiseSuppressor ns;private String error="";
    OwnerInputEffects(int session){
        try{if(AutomaticGainControl.isAvailable()){agc=AutomaticGainControl.create(session);if(agc!=null)agc.setEnabled(true);}}catch(RuntimeException e){error="AGC_UNAVAILABLE";}
        try{if(NoiseSuppressor.isAvailable()){ns=NoiseSuppressor.create(session);if(ns!=null)ns.setEnabled(true);}}catch(RuntimeException e){error+=" NS_UNAVAILABLE";}
    }
    JSONObject status(){JSONObject o=new JSONObject();try{o.put("agcEnabled",agc!=null&&agc.getEnabled()).put("nsEnabled",ns!=null&&ns.getEnabled()).put("error",error.trim());}catch(Exception ignored){}return o;}
    void close(){if(agc!=null){try{agc.release();}catch(RuntimeException ignored){}agc=null;}if(ns!=null){try{ns.release();}catch(RuntimeException ignored){}ns=null;}}
}
