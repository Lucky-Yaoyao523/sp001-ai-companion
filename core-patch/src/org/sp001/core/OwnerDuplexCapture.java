package org.sp001.core;

import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Session-long recorder. Only its worker processes/releases AEC and releases AudioRecord.
 * Stop may be requested externally to unblock read, but never closes JNI concurrently.
 */
final class OwnerDuplexCapture {
    interface Life {boolean cancelled();}
    static final class ReadReceipt {final int bytes;final long monotonicMs;ReadReceipt(int bytes,long ms){this.bytes=bytes;monotonicMs=ms;}}
    private final Life life;private final ContinuousSpeechInput input;
    private final ContinuousSpeechInput.Stream stream;
    private final AecSignalMetrics metrics=new AecSignalMetrics();
    private volatile OnsetEvidence lastOnset;
    private static final class OnsetEvidence {
        final long utterance,monotonicMs;final double noise,threshold;final AecSignalMetrics.Snapshot signals;
        OnsetEvidence(long id,long ms,double noise,double threshold,AecSignalMetrics.Snapshot signals){this.utterance=id;this.monotonicMs=ms;this.noise=noise;this.threshold=threshold;this.signals=signals;}
    }
    private volatile boolean closed;private volatile boolean released=true;private volatile AudioRecord recorder;
    private volatile long lastRead;private volatile ReadReceipt receipt;private Thread worker;
    OwnerDuplexCapture(Life life,int sensitivity,final ContinuousSpeechInput.Onset onset){this(life,sensitivity,onset,null);}
    OwnerDuplexCapture(Life life,int sensitivity,final ContinuousSpeechInput.Onset onset,ContinuousSpeechInput.Stream stream){this.life=life;this.stream=stream;input=new ContinuousSpeechInput(sensitivity,new ContinuousSpeechInput.Onset(){public void started(long id){
        // Cancel the response first. Evidence must never delay the interruption gate.
        onset.started(id);rememberOnset(id);
    }},stream);}
    private void rememberOnset(long id){lastOnset=new OnsetEvidence(id,SystemClock.elapsedRealtime(),input.noiseLevel(),input.startThreshold(),metrics.snapshot());}
    org.json.JSONObject onsetStatus()throws org.json.JSONException{
        OnsetEvidence e=lastOnset;if(e==null)return new org.json.JSONObject().put("startupCalibration",input.calibrationStatus()).put("vadNoiseRms",input.noiseLevel()).put("endpointLimits",input.endpointLimits());AecSignalMetrics.Snapshot s=e.signals;
        return new org.json.JSONObject().put("utteranceId",e.utterance).put("monotonicMs",e.monotonicMs).put("endpointLimits",input.endpointLimits()).put("aecEchoMode",OwnerAecProcessor.ECHO_MODE)
            .put("referenceFilter","causal-nlms128-0.15").put("startupCalibration",input.calibrationStatus()).put("vadAnalysisHighPass",0.94).put("vadNoiseRms",e.noise).put("vadStartThreshold",e.threshold).put("audioFrame10ms",s.frame).put("windowMs",s.windowMs)
            .put("sampleRate",16000).put("frameSamples",160).put("signalUnits","pcm16_sample_amplitude").put("cleanHighPass","y=x-prevX+0.97*prevY;continuous")
            .put("nearRms",s.nearRms).put("referenceRms",s.referenceRms).put("cleanRms",s.cleanRms).put("cleanHighPassRms",s.cleanHighPassRms)
            .put("nearAcRms",s.nearAcRms).put("referenceAcRms",s.referenceAcRms).put("cleanAcRms",s.cleanAcRms)
            .put("nearReferenceAcSimilarity",s.acSimilarityAvailable?Double.valueOf(s.acSimilarity):org.json.JSONObject.NULL)
            .put("nearReferenceZeroLagSimilarity",s.similarityAvailable?Double.valueOf(s.zeroLagSimilarity):org.json.JSONObject.NULL).put("nearClippedSamples",s.nearClipped).put("referenceClippedSamples",s.referenceClipped).put("cleanClippedSamples",s.cleanClipped);
    }
    void start()throws Exception{
        if(worker!=null)throw new IllegalStateException("DUPLEX_ALREADY_STARTED");
        worker=new Thread(new Runnable(){public void run(){capture();}},"SP001-ContinuousMic");released=false;
        try{worker.start();}catch(RuntimeException e){released=true;throw e;}
        long until=SystemClock.elapsedRealtime()+5000;
        while(!input.ready()){check();if(SystemClock.elapsedRealtime()>=until)throw new IOException("DUPLEX_START_TIMEOUT");Thread.sleep(10);}
    }
    private void check()throws IOException{input.check();if(closed||life.cancelled())throw new IOException("TURN_CANCELLED");}
    private void capture(){
        OwnerAecProcessor aec=null;AudioRecord owned=null;byte[] copy=new byte[2560];
        java.util.concurrent.ScheduledExecutorService watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try{
            if(!(Boolean)Class.forName("com.smarttoy.util.c").getMethod("nb").invoke(null))throw new IOException("DUPLEX_REFERENCE_UNAVAILABLE");
            aec=new OwnerAecProcessor(metrics);
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("DUPLEX_RECORD_FORMAT");
            owned=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(32768,min*2));recorder=owned;
            if(owned.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("DUPLEX_RECORD_INIT");check();
            final AudioRecord stopTarget=owned;lastRead=SystemClock.elapsedRealtime();
            watchdog.scheduleAtFixedRate(new Runnable(){public void run(){
                if(closed||life.cancelled()||SystemClock.elapsedRealtime()-lastRead>3000){try{stopTarget.stop();}catch(RuntimeException ignored){}}
            }},50,50,java.util.concurrent.TimeUnit.MILLISECONDS);
            owned.startRecording();if(owned.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("DUPLEX_RECORD_START");
            ByteBuffer block=ByteBuffer.allocateDirect(2560);
            OwnerAecProcessor.Sink sink=new OwnerAecProcessor.Sink(){public void accept(byte[] mono)throws Exception{input.accept(mono);}};
            while(!closed&&!life.cancelled()){
                block.clear();int n=owned.read(block,2560);check();
                if(n<0||n%4!=0)throw new IOException("DUPLEX_RECORD_READ");
                if(n==0){if(SystemClock.elapsedRealtime()-lastRead>3000)throw new IOException("DUPLEX_RECORD_STALLED");Thread.sleep(5);continue;}
                for(int i=0;i<n;i++)copy[i]=block.get(i);aec.accept(copy,n,sink);Arrays.fill(copy,0,n,(byte)0);lastRead=SystemClock.elapsedRealtime();receipt=new ReadReceipt(n,lastRead);
            }
        }catch(Throwable error){String m=error.getMessage();if(!closed)input.fail(m!=null&&m.matches("[A-Z0-9_]+")?m:"DUPLEX_CAPTURE_FAILED");}
        finally{
            watchdog.shutdownNow();recorder=null;
            boolean recordReleased=owned==null,aecReleased=aec==null;
            try{if(owned!=null){try{owned.stop();}catch(RuntimeException ignored){}owned.release();recordReleased=true;}}
            catch(Throwable e){input.fail("DUPLEX_RECORD_RELEASE_FAILED");}
            finally{try{if(aec!=null){aec.close();aecReleased=true;}}catch(Throwable e){input.fail("AEC_RELEASE_FAILED");}
                Arrays.fill(copy,(byte)0);released=recordReleased&&aecReleased&&OwnerAecProcessor.releaseConfirmed();}
        }
    }
    interface IdleOpportunity {boolean due(long waitedMs);}
    ContinuousSpeechInput.Utterance take()throws Exception{return take(null,null);}
    ContinuousSpeechInput.Utterance take(IdleOpportunity opportunity,DuplexTurnGate gate)throws Exception{
        input.waiting(true);long nextOpportunity=0;
        try{for(;;){check();ContinuousSpeechInput.Utterance value=input.pollForResponse();if(value!=null)return value;
            if(opportunity!=null&&input.idleQuietMs()>=IdleInitiative.QUIET_MS&&SystemClock.elapsedRealtime()>=nextOpportunity){nextOpportunity=SystemClock.elapsedRealtime()+100;if(opportunity.due(input.idleQuietMs())){value=input.claimInitiative(gate);if(value!=null)return value;}}
            Thread.sleep(10);}}
        finally{input.waiting(false);}
    }
    ReadReceipt awaitFreshRead()throws Exception{ReadReceipt before=receipt;long until=SystemClock.elapsedRealtime()+1000;while(receipt==before){check();if(SystemClock.elapsedRealtime()>=until)throw new IOException("DUPLEX_NO_FRESH_FRAME");Thread.sleep(5);}return receipt;}
    void playback(boolean active){input.playback(active);}
    void finishResponse(long utterance){input.finishResponse(utterance);}
    boolean provisionalInterruption(){return input.provisionalInterruption();}
    boolean confirmationActive(){return input.confirmationActive();}
    int rejectedBarges(){return input.rejectedBarges();}
    int confirmedBarges(){return input.confirmedBarges();}
    boolean protectInterruptionOpening(long id){return input.protectInterruptionOpening(id);}
    double ambient(){return input.ambient();}
    boolean heardSpeech(){return input.heardSpeech();}
    void sensitivity(int value){input.sensitivity(value);}
    void close(){
        // A stop request often interrupts the conversation thread. It must not
        // skip the bounded worker-release check or be mistaken for a leak.
        boolean interrupted=Thread.interrupted();
        try{
        if(stream!=null)stream.cancel();
        closed=true;AudioRecord target=recorder;if(target!=null){try{target.stop();}catch(RuntimeException ignored){}}
        long until=System.nanoTime()/1000000L+1500;
        if(worker!=null&&worker!=Thread.currentThread())while(worker.isAlive()){
            long left=until-System.nanoTime()/1000000L;if(left<=0)break;
            try{worker.join(left);}catch(InterruptedException e){interrupted=true;}
        }
        input.close();
        boolean asrReleased=!(stream instanceof StreamingAsrConversation);
        until=System.nanoTime()/1000000L+1500;
        while(!asrReleased){
            long left=until-System.nanoTime()/1000000L;if(left<=0)break;
            asrReleased=((StreamingAsrConversation)stream).awaitRelease(left);
            if(Thread.interrupted())interrupted=true;
        }
        if((worker!=null&&worker.isAlive())||!released||!asrReleased)throw new IllegalStateException("DUPLEX_CLOSE_UNCONFIRMED");
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    /** Keep outer admission owned until the native worker really exits. Cancellation is
     * not evidence of resource release. A failed native release quarantines this process. */
    boolean awaitRelease(){
        boolean interrupted=false;
        if(worker==Thread.currentThread())return false;
        while(worker!=null&&worker.isAlive()){try{worker.join();}catch(InterruptedException e){interrupted=true;}}
        if(stream instanceof StreamingAsrConversation){
            StreamingAsrConversation asr=(StreamingAsrConversation)stream;asr.cancel();
            // Outer admission remains owned until all provider workers and controller exit.
            while(!asr.released()){if(Thread.interrupted())interrupted=true;asr.awaitRelease(1000);}
        }
        if(interrupted)Thread.currentThread().interrupt();
        return released;
    }
}
