package org.sp001.core;

/** Production transaction with injectable device/preferences ports for fault replay. */
public final class VolumeChange {
    public interface Ports {
        int volume()throws Exception;int maximum()throws Exception;int gain()throws Exception;
        void setVolume(int level)throws Exception;
        boolean save(int level,int gain)throws Exception;
        void restoreSaved()throws Exception;
    }
    public static final class Result {
        public boolean success,changed,rollbackSucceeded=true;
        public int before,beforeGain,volume,gain,max,observedVolume=-1,observedGain=-1,percent;
        public String code="VOLUME_FAILED",answer="这次没能调好音量。";
    }
    private VolumeChange(){}
    public static int percent(int level,int maximum){
        if(maximum<1||level<0||level>maximum)throw new IllegalArgumentException("VOLUME_STATE_INVALID");
        return (int)Math.round(100.0*level/maximum);
    }
    public static Result run(Ports p,int action){
        if(p==null||(action!=AudioCommand.LOUDER&&action!=AudioCommand.QUIETER))throw new IllegalArgumentException("VOLUME_ACTION");
        Result r=new Result();boolean touched=false,saved=false;
        try{
            r.before=p.volume();r.max=p.maximum();int oldGain=p.gain();r.beforeGain=oldGain;
            if(r.max<1||r.before<0||r.before>r.max||oldGain<0||oldGain>2000)throw new java.io.IOException("VOLUME_STATE_INVALID");
            int step=action==AudioCommand.LOUDER?1:-1;
            // One original hardware step (about 7% on this 15-step device).
            // Speaker gain is fixed to the original playback profile, not a second volume dial.
            r.volume=Math.max(0,Math.min(r.max,r.before+step));r.gain=oldGain;
            // A quieter request must never unmute an already-muted stream.
            if(r.before==0&&step<0){r.volume=0;r.gain=oldGain;}
            r.changed=r.volume!=r.before||r.gain!=oldGain;
            if(r.changed){
                touched=true;p.setVolume(r.volume);
                if(p.volume()!=r.volume)throw new java.io.IOException("VOLUME_NOT_APPLIED");
            }
            // Persist even at a boundary: an older saved value must not undo this request.
            saved=true;if(!p.save(r.volume,r.gain))throw new java.io.IOException("VOLUME_SAVE_FAILED");
            if(p.volume()!=r.volume||p.gain()!=r.gain)throw new java.io.IOException("VOLUME_SETTINGS_READBACK_FAILED");
            r.success=true;r.code=r.changed?"VOLUME_APPLIED":"VOLUME_AT_BOUND";
            r.percent=percent(r.volume,r.max);
            r.answer=r.changed?"音量已调到百分之"+r.percent+"。":
                (step>0?"已经是最大音量，百分之100。":"音量已经是百分之0。");
        }catch(Exception e){
            // Independent rollback attempts: one failure must not skip the other.
            if(saved)try{p.restoreSaved();}catch(Exception ignored){r.rollbackSucceeded=false;}
            if(touched)try{p.setVolume(r.before);if(p.volume()!=r.before)r.rollbackSucceeded=false;}catch(Exception ignored){r.rollbackSucceeded=false;}
            String m=e.getMessage();if(m!=null&&m.matches("[A-Z0-9_]{1,80}"))r.code=m;
            if(!r.rollbackSucceeded)r.code="VOLUME_ROLLBACK_FAILED";
        }
        try{
            r.observedVolume=p.volume();r.observedGain=p.gain();
            // Another controller may change the setting between the transaction and acknowledgement.
            // Observe it, but do not overwrite a newer setting or claim that our target is still active.
            if(r.success&&(r.observedVolume!=r.volume||r.observedGain!=r.gain)){
                r.success=false;r.code="VOLUME_CHANGED_BEFORE_ACK";r.answer="音量状态又发生了变化，这次调整没有确认成功。";
            }
        }catch(Exception unavailable){
            if(r.success){r.success=false;r.code="VOLUME_ACK_READBACK_FAILED";r.answer="这次音量调整没有确认成功。";}
        }
        return r;
    }
}
