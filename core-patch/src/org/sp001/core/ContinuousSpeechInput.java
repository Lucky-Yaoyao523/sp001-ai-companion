package org.sp001.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayDeque;

/** One continuous producer, one conversation consumer. Receives AEC-clean 10ms frames.
 * No hardware/provider I/O. The onset callback must only update the generation gate.
 */
final class ContinuousSpeechInput {
    // Ending a user utterance is not the same decision as interrupting old playback.
    // Keep the existing fast onset, but tolerate common sentence-internal pauses.
    // This adds at most 400ms versus the old 800ms endpoint; it is not a latency claim.
    static final int UTTERANCE_SILENCE_MS = 1200;
    static final int INTERRUPTION_OPENING_MS = 1800;

    interface Onset {void started(long utterance);}
    /** Optional candidate ingress. Every method must only do bounded in-memory work.
     * Socket I/O, waits, JSON and provider calls belong to a separate worker. */
    interface Stream {
        void started(long utterance);
        void audio(long utterance,byte[] borrowed,int offset,int count);
        void finished(long utterance);
        void cancel();
    }
    interface EndpointStream extends Stream {boolean finalCandidateStable(long utterance,int holdMs);}
    static final class Utterance {
        final long id;final byte[] pcm;final long initiativeToken;
        Utterance(long id,byte[] pcm){this(id,pcm,0);}
        Utterance(long id,byte[] pcm,long token){this.id=id;this.pcm=pcm;initiativeToken=token;}
    }
    private int sensitivity;private final Onset onset;
    private final ArrayDeque<Utterance> pending=new ArrayDeque<Utterance>();
    private final byte[] room=new byte[10240];private int roomBytes;
    private String calibration="PENDING";
    private SpeechWindow window;private double ambient;private long sequence,active;
    private boolean waiting,closed,calibrated;private String failure;
    private boolean playbackActive,followupOpening;private long responseOwner;
    private final BargeInConfirmation barge=new BargeInConfirmation();
    private int rejectedBargeCandidates,confirmedBarges,endpointLimits;
    private int initiativeQuietMs;
    synchronized int idleQuietMs(){return initiativeQuietMs;}
    synchronized boolean confirmationActive(){return playbackActive||responseOwner>0;}
    private void confirmationChanged(boolean wasActive){
        boolean guarded=confirmationActive();if(wasActive==guarded)return;
        if(!guarded&&active==0&&window.speechStarted()&&!barge.currentlySpeaking()){
            window.cancel();reset();
        }
        barge.reset();
    }
    synchronized void playback(boolean activePlayback){
        boolean wasActive=confirmationActive();playbackActive=activePlayback;confirmationChanged(wasActive);
    }
    /** Atomically arm before returning a completed input to recognition/model/TTS. */
    synchronized Utterance pollForResponse()throws IOException{
        Utterance value=poll();
        if(value!=null&&value.pcm.length>0){boolean wasActive=confirmationActive();responseOwner=value.id;confirmationChanged(wasActive);}
        return value;
    }
    /** Only the matching old response may release its guard; no audio/network work. */
    synchronized void finishResponse(long utterance){
        if(closed||utterance<=0||responseOwner!=utterance)return;
        boolean wasActive=confirmationActive();responseOwner=0;followupOpening=true;confirmationChanged(wasActive);
    }
    synchronized boolean provisionalInterruption(){return !closed&&playbackActive&&barge.provisional();}
    synchronized int rejectedBarges(){return rejectedBargeCandidates;}
    synchronized int confirmedBarges(){return confirmedBarges;}
    private Stream stream;private String streamFailure;private int streamedBytes;
    private final SpeechWindow.PcmSink streamSink=new SpeechWindow.PcmSink(){public void accept(byte[] pcm,int offset,int count){stream.audio(active,pcm,offset,count);}};
    ContinuousSpeechInput(int sensitivity,Onset onset){this(sensitivity,onset,null);}
    ContinuousSpeechInput(int sensitivity,Onset onset,Stream stream){if(sensitivity<0||sensitivity>2||onset==null)throw new IllegalArgumentException("INPUT_CONFIG");this.sensitivity=sensitivity;this.onset=onset;this.stream=stream;
        // Shipped AEC's recorded quiet high-pass RMS was about 60. Use that bootstrap
        // prior when a user starts immediately, instead of learning their voice as noise.
        window=new SpeechWindow(sensitivity,60,UTTERANCE_SILENCE_MS,1000,3000);
        window.waitingForSpeech(false);
    }
    synchronized void accept(byte[] frame)throws IOException{
        check();if(frame==null||frame.length!=320)throw new IOException("DUPLEX_FRAME_BOUND");
        if(!calibrated){
            System.arraycopy(frame,0,room,roomBytes,320);roomBytes+=320;
            if(roomBytes<room.length)return;
            // Do not commit an onset against a guessed floor and then refuse the
            // real calibration because that guessed onset already owns the turn.
            double measured=SpeechWindow.ambientLevel(room);
            boolean background=measured<=window.startThreshold()||lowBandBackground(room);
            ambient=background?measured:60;
            calibration=background?"BUFFERED_ROOM":"EARLY_SPEECH_PRESERVED";
            window.cancel();window=new SpeechWindow(sensitivity,ambient,UTTERANCE_SILENCE_MS,1000,3000);
            ambient=window.noiseLevel();window.learnNoise(false);window.waitingForSpeech(waiting);
            calibrated=true;
            // Replay every original sample once, in order. Calibration is not
            // discarded audio or an extra per-utterance waiting period.
            byte[] saved=new byte[320];
            try{for(int at=0;at<room.length;at+=320){System.arraycopy(room,at,saved,0,320);acceptCalibrated(saved);}}
            finally{Arrays.fill(saved,(byte)0);Arrays.fill(room,(byte)0);}
            return;
        }
        acceptCalibrated(frame);
    }
    private void acceptCalibrated(byte[] frame)throws IOException{
        final boolean guarded=confirmationActive();
        int before=window.analyzedFrames();window.accept(frame);
        if(window.analyzedFrames()!=before){if(waiting&&!guarded&&window.quietForInitiative())initiativeQuietMs=Math.min(15000,initiativeQuietMs+20);else initiativeQuietMs=0;}
        if(guarded&&active==0)window.trimUnconfirmedPrefix(3000);
        if(guarded&&active==0&&window.analyzedFrames()!=before)barge.frame(window.bargeSpeechFrame(),playbackActive);
        if(guarded&&active==0&&window.speechStarted()&&barge.elapsedMs()==0){
            rejectedBargeCandidates++;window.cancel();reset();return;
        }
        if(active==0&&window.speechStarted()&&(!guarded||barge.confirmed())){
            if(guarded)confirmedBarges++;
            // A short introductory word immediately after our reply may precede the real question.
            // Protect this opening only; keep1200ms final silence and fast ordinary onset.
            if(!guarded&&followupOpening)window.protectOpening(INTERRUPTION_OPENING_MS);
            followupOpening=false;active=++sequence;onset.started(active);
            if(stream!=null)try{stream.started(active);}catch(RuntimeException e){disableStream();}
        }
        if(active!=0&&stream!=null)try{streamedBytes=window.streamSince(streamedBytes,streamSink);}catch(RuntimeException e){disableStream();}
        if(active!=0&&!window.completed()&&window.recognizerQuietMs()>=UTTERANCE_SILENCE_MS&&stream instanceof EndpointStream){
            if(((EndpointStream)stream).finalCandidateStable(active,UTTERANCE_SILENCE_MS))window.completeFromRecognizer();
        }
        if(window.completed()){
            if(active==0&&window.speechStarted()){
                rejectedBargeCandidates++;window.cancel();reset();return;
            }
            // Preserve the first bounded recording for recognition instead of
            // dropping the whole conversation. A second cap in this session is
            // still terminal: sustained noise cannot create an endless loop.
            if("UTTERANCE_LIMIT".equals(window.reason())&&++endpointLimits>1){fail("INPUT_ENDPOINT_LIMIT");check();}
            if(active!=0&&stream!=null)try{stream.finished(active);}catch(RuntimeException e){disableStream();}
            byte[] pcm=window.finish();
            if(pcm.length>0||waiting){
                if(pending.size()>=2){Arrays.fill(pcm,(byte)0);fail("DUPLEX_INPUT_BACKLOG");check();}
                pending.addLast(new Utterance(pcm.length==0?sequence:active,pcm));
            }else Arrays.fill(pcm,(byte)0);
            reset();
        }
    }
    synchronized boolean protectInterruptionOpening(long id){
        if(closed||failure!=null||id<=0||active!=id)return false;
        return window.protectOpening(INTERRUPTION_OPENING_MS);
    }
    /** Detect low-band-dominated startup interference, not voice by loudness alone.
     * Analysis only: the uploaded/retained PCM is never high-pass filtered here.
     * A broadband early voice keeps the original speech prior instead of training
     * itself into the room floor. This is not a general learned VAD classifier. */
    private static boolean lowBandBackground(byte[] pcm){
        double prior=0,low=0,high=0,high2=0,previousHigh=0,total=0,band=0;
        for(int i=0;i<pcm.length;i+=2){
            int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
            low=sample-prior+.97*low;
            high=.89*(high+sample-prior);high2=.89*(high2+high-previousHigh);
            prior=sample;previousHigh=high;total+=low*low;band+=high2*high2;
        }
        return total>0&&band/total<.12;
    }
    private void reset(){barge.reset();window=new SpeechWindow(sensitivity,ambient,UTTERANCE_SILENCE_MS,1000,3000);window.learnNoise(false);window.waitingForSpeech(waiting);active=0;streamedBytes=0;}
    private void disableStream(){Stream owned=stream;stream=null;streamFailure="STREAM_INGRESS_FAILED";if(owned!=null)try{owned.cancel();}catch(RuntimeException ignored){}}
    synchronized String streamFailure(){return streamFailure;}
    synchronized int endpointLimits(){return endpointLimits;}
    synchronized String calibrationStatus(){return calibration;}
    synchronized boolean ready(){return calibrated&&!closed&&failure==null;}
    synchronized double ambient(){return ambient;}
    synchronized double noiseLevel(){return window.noiseLevel();}
    synchronized double startThreshold(){return window.startThreshold();}
    synchronized boolean heardSpeech(){return sequence>0;}
    synchronized void sensitivity(int value){window.sensitivity(value);sensitivity=value;}
    synchronized void waiting(boolean value){
        if(value&&!waiting){
            // Silence receipts belong to their previous wait, even when no newer
            // speech id exists. Keep every real utterance buffered during playback.
            java.util.Iterator<Utterance> it=pending.iterator();
            while(it.hasNext())if(it.next().pcm.length==0)it.remove();
        }
        if(waiting!=value)initiativeQuietMs=0;waiting=value;window.waitingForSpeech(value);
    }
    /** Same lock order as real onset: input then gate; emerging words win admission. */
    synchronized Utterance claimInitiative(DuplexTurnGate gate)throws IOException{
        check();if(gate==null||!calibrated||!waiting||sequence<1||active!=0||!pending.isEmpty()||confirmationActive()||!window.quietForInitiative())return null;
        long token=gate.beginInitiative(sequence);if(token==0)return null;
        boolean was=confirmationActive();responseOwner=sequence;confirmationChanged(was);
        return new Utterance(sequence,new byte[0],token);
    }
    synchronized Utterance poll()throws IOException{check();while(!pending.isEmpty()){
        Utterance value=pending.removeFirst();
        if(value.pcm.length==0&&value.id<sequence)continue;
        return value;
    }return null;}
    synchronized void check()throws IOException{if(failure!=null)throw new IOException(failure);if(closed)throw new IOException("DUPLEX_INPUT_CLOSED");}
    synchronized void fail(String code){if(failure==null)failure=code;wipe();}
    synchronized void close(){closed=true;wipe();}
    private void wipe(){Stream owned=stream;stream=null;if(owned!=null)try{owned.cancel();}catch(RuntimeException ignored){}Arrays.fill(room,(byte)0);if(window!=null)window.cancel();while(!pending.isEmpty())Arrays.fill(pending.removeFirst().pcm,(byte)0);}
}
