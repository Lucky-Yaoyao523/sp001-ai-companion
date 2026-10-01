package org.sp001.core;

import android.os.SystemClock;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One preconnected TTS session per explicit owner conversation, never ambient recording.
 * No reconnect or text retry. A failed preconnection may be discarded before ANY text is sent.
 */
final class MiniMaxTtsSocket {
    interface Clock {long now();void pause(long ms)throws InterruptedException;}
    interface Events {void message(String text);void failure(String code);}
    interface Wire {void open(String origin,String key,Events events)throws Exception;boolean send(String text)throws Exception;void close();}
    private static final class PlatformClock implements Clock {
        public long now(){return SystemClock.elapsedRealtime();}
        public void pause(long ms)throws InterruptedException{Thread.sleep(ms);}
    }
    private final Object lock=new Object();
    private final String origin,key,voice,session="sp001-"+UUID.randomUUID().toString();
    private final MiniMaxVoiceClient.Cancel cancel;
    private final Wire wire;private final Clock clock;
    private final TtsEventBuffer inbox=new TtsEventBuffer();
    private final CountDownLatch prepared=new CountDownLatch(1);
    private volatile boolean closed,ready;private boolean started;
    private volatile String failure;
    private volatile long setupMs=-1;
    private volatile JSONObject lastFailedText;
    private int textAttempts,completedTexts;private boolean speaking;
    MiniMaxTtsSocket(String origin,String key,String voice,MiniMaxVoiceClient.Cancel cancel){
        this(origin,key,voice,cancel,new MiniMaxSocketWire(),new PlatformClock());
    }
    MiniMaxTtsSocket(String origin,String key,String voice,MiniMaxVoiceClient.Cancel cancel,Wire wire,Clock clock){
        this.origin=origin;this.key=key;this.voice=voice;this.cancel=cancel;this.wire=wire;this.clock=clock;
    }
    private void check()throws IOException{
        if(closed||cancel.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
        if(failure!=null)throw new IOException(failure);
    }
    void prepare(){
        synchronized(lock){if(started||closed)return;started=true;}
        try{new Thread(new Runnable(){public void run(){connect();}},"SP001-TtsPrepare").start();}
        catch(RuntimeException e){failure="TTS_PREPARE_THREAD";prepared.countDown();close();}
    }
    private void connect(){
        long begin=clock.now();
        try{
            check();
            wire.open(origin,key,new Events(){
                public void message(String value){if(closed)return;if(!inbox.offer(value)&&!closed){String code=inbox.failure();fail(code==null?"TTS_TRANSPORT_CLOSED":code);wire.close();}}
                public void failure(String code){if(!closed)fail(code);}
            });
            check();
            JSONObject connected=nextControl(begin+20000);
            if(!"connected_success".equals(connected.optString("event")))throw new IOException("TTS_CONNECT_EVENT");
            send(new JSONObject().put("event","task_start").put("session_id",session).put("model","speech-2.8-turbo")
                .put("language_boost","auto").put("voice_setting",new JSONObject().put("voice_id",voice)
                    .put("speed",InteractionPolicy.SPEECH_SPEED).put("vol",1.4).put("pitch",0)
                    .put("english_normalization",true))
                .put("audio_setting",new JSONObject().put("sample_rate",32000).put("format","pcm").put("channel",1)));
            JSONObject task=nextControl(begin+20000);
            if(!"task_started".equals(task.optString("event"))||!session.equals(task.optString("session_id")))throw new IOException("TTS_START_EVENT");
            synchronized(lock){check();setupMs=clock.now()-begin;ready=true;}
        }catch(Exception e){fail(safe(e));close();}
        finally{prepared.countDown();}
    }
    private static String safe(Exception e){String text=e.getMessage();return text!=null&&text.matches("[A-Z0-9_]+")?text:"TTS_SOCKET_ERROR";}
    private void fail(String value){synchronized(lock){if(failure==null)failure=value;}inbox.fail(value);}
    private JSONObject nextControl(long deadline)throws Exception{
        TtsEventBuffer.Frame frame=next(deadline);
        try{if(frame.pcm!=null)throw new IOException("TTS_HANDSHAKE_AUDIO");return frame.event;}
        finally{frame.release();}
    }
    private TtsEventBuffer.Frame next(long deadline)throws Exception{
        for(;;){check();if(clock.now()>=deadline)throw new IOException("TTS_SOCKET_DEADLINE");
            TtsEventBuffer.Frame frame=inbox.poll();if(frame==null){clock.pause(10);continue;}
            try{
                JSONObject event=frame.event,base=event.optJSONObject("base_resp");
                if(base!=null&&base.optInt("status_code",-1)!=0)throw new IOException("TTS_API_"+base.optInt("status_code",-1));
                if(event.has("error")||event.optBoolean("input_sensitive")||event.optBoolean("output_sensitive"))throw new IOException("MINIMAX_CONTENT_FILTERED");
                return frame;
            }catch(Exception error){frame.release();throw error;}
        }
    }
    private void send(JSONObject event)throws Exception{synchronized(lock){check();if(!wire.send(event.toString()))throw new IOException("TTS_SEND_FAILED");}}
    boolean awaitReady()throws Exception{
        return awaitReady(200);
    }
    boolean awaitReady(long extraWaitMs)throws Exception{
        if(extraWaitMs<0||extraWaitMs>1000)throw new IllegalArgumentException("TTS_EXTRA_WAIT_BOUND");
        if(cancel.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
        if(closed)return false;
        prepare();long deadline=clock.now()+extraWaitMs;
        while(!prepared.await(50,TimeUnit.MILLISECONDS)){
            if(cancel.cancelled()||Thread.currentThread().isInterrupted()){close();throw new IOException("TURN_CANCELLED");}
            if(clock.now()>=deadline){fail("TTS_PREPARE_BUDGET");close();break;}
        }
        if(cancel.cancelled()||Thread.currentThread().isInterrupted())throw new IOException("TURN_CANCELLED");
        return ready&&!closed&&failure==null;
    }
    int synthesize(String text,MiniMaxVoiceClient.PcmSink sink)throws Exception{
        synchronized(lock){check();if(!ready||speaking)throw new IOException("TTS_SESSION_NOT_READY");speaking=true;}
        final TtsTurnState turn=new TtsTurnState(session);
        try{
            if(!inbox.empty())throw new IOException("TTS_STALE_EVENTS");
            long deadline=clock.now()+120000;
            // Once counted, any send/receive/playback failure ends the session; never retry this text.
            synchronized(lock){textAttempts++;}
            send(new JSONObject().put("event","task_continue").put("text",MiniMaxCodec.speech(text)));
            send(new JSONObject().put("event","task_flush"));
            while(!turn.flushed()){
                TtsEventBuffer.Frame frame=next(Math.min(deadline,clock.now()+15000));
                try{
                JSONObject event=frame.event;String type=event.optString("event");
                turn.event(type,event.optString("session_id"),event.optString("sentence_id"));
                // Reject contradictory metadata BEFORE handing this event's audio to the speaker.
                JSONObject info=event.optJSONObject("extra_info");
                if(info!=null)turn.format(info.optInt("audio_sample_rate"),info.optInt("audio_channel"),info.optString("audio_format"));
                if(frame.pcm!=null){turn.audio(frame.pcm.length);check();sink.write(frame.pcm);check();}
                if(event.optBoolean("is_final"))turn.requestFinal();
                }finally{frame.release();}
            }
            check();if(clock.now()>=deadline)throw new IOException("TTS_TIME_LIMIT");
            synchronized(lock){completedTexts++;}return turn.bytes();
        }catch(Exception e){lastFailedText=turn.diagnostic();fail(safe(e));close();throw e;}
        finally{synchronized(lock){speaking=false;}}
    }
    boolean anyTextAttempted(){synchronized(lock){return textAttempts>0;}}
    /** Earlier flushed text does not make a not-yet-sent new sentence ambiguous. */
    boolean safeBeforeNextText(){synchronized(lock){return !speaking&&textAttempts==completedTexts;}}
    JSONObject status(){JSONObject r=new JSONObject();try{r.put("kind","minimax_websocket_bidi").put("ready",ready&&!closed).put("setupMs",setupMs)
        .put("textAttempts",textAttempts).put("failure",failure==null?JSONObject.NULL:failure)
        .put("lastFailedText",lastFailedText==null?JSONObject.NULL:lastFailedText).put("buffer",inbox.diagnostic());}catch(Exception ignored){}return r;}
    void close(){
        synchronized(lock){closed=true;ready=false;}
        inbox.close();prepared.countDown();wire.close();
    }
}
