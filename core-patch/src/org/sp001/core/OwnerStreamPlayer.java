package org.sp001.core;

import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.*;

/** One response's speaker. Its watchdog stops queued sound independently of network I/O. */
final class OwnerStreamPlayer {
    interface Lifecycle {boolean cancelled();void firstAudio();}
    interface DuckingLifecycle extends Lifecycle {boolean ducking();}
    interface DuckingDriver extends Driver {void setGain(float gain);}

    interface Clock {long now();void pause(long ms)throws InterruptedException;}
    interface Driver {int write(byte[] pcm,int at,int count);void play();long head();void stopNow();void release();org.json.JSONObject status();}
    interface Factory {Driver create()throws Exception;}
    private final int maximumBytes;
    private final Lifecycle life;private final long deadline;private final Clock clock;private final Factory factory;
    private final Object outputLock=new Object();private final ScheduledExecutorService watchdog;
    private volatile boolean stopped,closed,cancelledOutput;private Driver track;private volatile int bytes;private volatile boolean started;
    private final PlaybackCueQueue cues=new PlaybackCueQueue();
    private org.json.JSONObject closedStatus;
    private long duckUntil;private float playbackGain=1f;private int duckTransitions,duckFailures;
    // Per-response playback receipts, not generated-text or topic state. Never persisted.
    private static final class SpeechMark {final long endFrame;final String text;SpeechMark(long end,String value){endFrame=end;text=value;}}
    private final java.util.List<SpeechMark> speechMarks=new java.util.ArrayList<SpeechMark>();
    private long playedFrames;private int markedCharacters;
    void markTextEnd(String text)throws IOException{
        if(text==null||text.isEmpty())return;
        synchronized(outputLock){
            if(stopped||closed)return;
            if(text.length()>ChildCompanionPolicy.STORY_TEXT_LIMIT-markedCharacters||speechMarks.size()>=96)throw new IOException("PLAYBACK_TEXT_BOUND");
            if(bytes==0)return;
            speechMarks.add(new SpeechMark(bytes/2,text));markedCharacters+=text.length();
        }
    }
    private void observePlayedFrames(){
        if(track==null||!started)return;
        try{long head=track.head();if(head>=0)playedFrames=Math.max(playedFrames,Math.min(head,bytes/2));}catch(RuntimeException ignored){}
    }
    String playedText(){synchronized(outputLock){
        if(!stopped&&!closed)observePlayedFrames();
        StringBuilder text=new StringBuilder();for(SpeechMark mark:speechMarks){if(mark.endFrame>playedFrames)break;text.append(mark.text);}return text.toString();
    }}

    OwnerStreamPlayer(long deadline,Lifecycle life){this(deadline,life,PcmStreamState.MAX_BYTES);}
    OwnerStreamPlayer(long deadline,Lifecycle life,int limit){this(deadline,life,new Clock(){public long now(){return SystemClock.elapsedRealtime();}public void pause(long ms)throws InterruptedException{Thread.sleep(ms);}},new Factory(){public Driver create()throws Exception{return new PlatformDriver();}},limit);}
    OwnerStreamPlayer(long deadline,Lifecycle life,Clock clock,Factory factory){this(deadline,life,clock,factory,PcmStreamState.MAX_BYTES);}
    OwnerStreamPlayer(long deadline,Lifecycle life,Clock clock,Factory factory,int limit){
        if(limit<PcmStreamState.MAX_BYTES||limit>12800000)throw new IllegalArgumentException("PLAYER_OUTPUT_BUDGET");maximumBytes=limit;
        this.deadline=deadline;this.life=life;this.clock=clock;this.factory=factory;
        watchdog=Executors.newSingleThreadScheduledExecutor();
        watchdog.scheduleAtFixedRate(new Runnable(){public void run(){if(life.cancelled()||OwnerStreamPlayer.this.clock.now()>=OwnerStreamPlayer.this.deadline)cancel();else{refreshDucking();dispatchCues();}}},25,25,TimeUnit.MILLISECONDS);
    }
    private void check()throws IOException{if(stopped||closed||life.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");if(clock.now()>=deadline)throw new IOException("SESSION_TIME_LIMIT");}
    void cue(Runnable task)throws IOException{check();cues.add(bytes/2,task);}
    void refreshDucking(){
        synchronized(outputLock){
            if(stopped||closed||!started||!(track instanceof DuckingDriver)||!(life instanceof DuckingLifecycle)||life.cancelled()||duckFailures>0)return;
            try{
                long now=clock.now();if(((DuckingLifecycle)life).ducking())duckUntil=now+300;
                float next=now<duckUntil?.28f:1f;
                if(next!=playbackGain){((DuckingDriver)track).setGain(next);playbackGain=next;duckTransitions++;}
            }catch(RuntimeException error){duckFailures++;}
        }
    }
    private synchronized void dispatchCues(){
        java.util.List<Runnable> ready;
        try{synchronized(outputLock){if(stopped||closed||!started||track==null||life.cancelled())return;observePlayedFrames();ready=cues.ready(playedFrames,bytes/2);}}
        catch(RuntimeException unavailable){cues.failed();return;}
        for(Runnable task:ready){if(stopped||closed||life.cancelled())return;try{task.run();}catch(RuntimeException failed){cues.failed();}}
    }
    void write(byte[] pcm)throws Exception{
        check();if(pcm==null||pcm.length%2!=0||pcm.length>maximumBytes-bytes)throw new IOException("STREAM_PCM_BOUND");if(pcm.length==0)return;
        Driver target;synchronized(outputLock){target=track;}
        if(target==null){
            Driver fresh=factory.create();boolean owned=false;
            try{synchronized(outputLock){check();track=fresh;target=fresh;owned=true;}}
            finally{if(!owned)fresh.release();}
        }
        for(int at=0;at<pcm.length;){
            check();int n=target.write(pcm,at,Math.min(2048,pcm.length-at));
            check();if(n<=0||n%2!=0)throw new IOException("STREAM_OUTPUT_WRITE");at+=n;bytes+=n;
            boolean first=false;
            // Blocking write stays outside this private lock so stopNow can unblock it.
            // Cancel cannot pause/flush and then be followed by a late play().
            synchronized(outputLock){check();if(!started){target.play();started=true;first=true;}}
            if(first){check();life.firstAudio();check();}
        }
    }
    int finish()throws Exception{
        check();if(!started||bytes<3200)throw new IOException("STREAM_OUTPUT_EMPTY");
        long until=Math.min(deadline,clock.now()+5000);Driver target;synchronized(outputLock){target=track;}
        while(target.head()<bytes/2){check();if(clock.now()>until)throw new IOException("STREAM_DRAIN_TIMEOUT");clock.pause(15);}
        check();dispatchCues();check();return bytes;
    }
    void cancel(){cancelledOutput=true;stopped=true;cues.close();watchdog.shutdown();synchronized(outputLock){observePlayedFrames();if(track!=null){try{track.stopNow();}catch(RuntimeException ignored){}}}}
    org.json.JSONObject status(){synchronized(outputLock){if(closedStatus!=null)return closedStatus;org.json.JSONObject o=track==null?new org.json.JSONObject():track.status();try{o.put("streaming",true).put("bytesWritten",bytes).put("cancelled",cancelledOutput).put("cuesSubmitted",cues.submitted()).put("cuesDispatched",cues.dispatched()).put("cueFailures",cues.failures()).put("duckTransitions",duckTransitions).put("duckFailures",duckFailures).put("currentPlaybackGain",playbackGain);}catch(Exception ignored){}return o;}}
    void close(){
        stopped=true;closed=true;cues.close();watchdog.shutdownNow();
        synchronized(outputLock){observePlayedFrames();if(closedStatus==null)closedStatus=status();Driver owned=track;track=null;if(owned!=null){try{owned.stopNow();}finally{owned.release();}}}
    }
    private static final class PlatformDriver implements DuckingDriver {
        private final AudioTrack audio;private OwnerPlaybackEffects effects;
        PlatformDriver()throws Exception{
            int min=AudioTrack.getMinBufferSize(InteractionPolicy.OUTPUT_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("STREAM_OUTPUT_FORMAT");
            audio=new AudioTrack(AudioManager.STREAM_MUSIC,InteractionPolicy.OUTPUT_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192),AudioTrack.MODE_STREAM);
            try{if(audio.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("STREAM_OUTPUT_INIT");effects=new OwnerPlaybackEffects(audio.getAudioSessionId());}
            catch(Exception e){audio.release();throw e;}
        }
        public void setGain(float gain){if(gain<.28f||gain>1f||audio.setVolume(gain)!=AudioTrack.SUCCESS)throw new IllegalStateException("PLAYBACK_DUCK_FAILED");}
        public int write(byte[] pcm,int at,int count){if(effects!=null)effects.refreshGain();return audio.write(pcm,at,count);}
        public void play(){audio.play();}public long head(){return audio.getPlaybackHeadPosition()&0xffffffffL;}
        public void stopNow(){try{audio.pause();}catch(RuntimeException ignored){}try{audio.flush();}catch(RuntimeException ignored){}try{audio.stop();}catch(RuntimeException ignored){}}
        public void release(){try{audio.release();}finally{if(effects!=null){effects.close();effects=null;}}}
        public org.json.JSONObject status(){return effects==null?new org.json.JSONObject():effects.status(InteractionPolicy.OUTPUT_RATE);}
    }
}
