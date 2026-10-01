package org.sp001.core;

import java.util.Arrays;

/** Bounded 16k PCM16LE endpointing, rejecting near-pure tones and high-crossing hiss.
 * Conservative nuisance rejection is NOT a general speech recognizer, denoiser or AEC.
 * One capture owner feeds samples; no I/O, callbacks, or Android dependencies.
 */
public final class SpeechWindow {
    public static final int RATE = 16000, FRAME_SAMPLES = 320;
    public static final int MAX_BYTES = RATE * 2 * 30;
    public static final int WAIT_MS = 15000, SILENCE_MS = 800;
    // Keep 350ms before the entire 300ms confirmation window, including weak initial syllables.
    // This stores existing samples; it does not add a wait after the user finishes.
    private static final int PRE_BYTES = 20800, TRAIL_BYTES = 7680;
    private final byte[] frame = new byte[640], ring, output = new byte[MAX_BYTES];
    private int frameBytes, ringNext, ringSize, used, attack, weakOnsetEvidence, continuationEvidence, frames, lastVoiceEnd, waitingFrames;
    private boolean waitingForSpeech = true;
    private double energy, previousX, previousY, noise = 80;
    private boolean active, done, cancelled, noiseLearning = true;
    private String reason = "LISTENING";
    private double minimumEnergy,noiseFactor,noiseFloor;
    private final int silenceMs;
    private final int baselinePreBytes;
    private int activatedAtFrame,protectedOpeningMs;
    private boolean lastBargeSpeech;
    private int recognizerQuietMs;
    int analyzedFrames(){return frames;}
    boolean bargeSpeechFrame(){return lastBargeSpeech;}
    /** Keep original onset/energy thresholds; only a stable provider final may use this quiet evidence. */
    boolean completeFromRecognizer(){
        if(!active||done||cancelled||recognizerQuietMs<silenceMs||(frames-activatedAtFrame)*20<protectedOpeningMs)return false;
        complete("ASR_CONFIRMED_END");return true;
    }
    int recognizerQuietMs(){return recognizerQuietMs;}
    private boolean lowBandFrame(){
        double x0=0,lo=0,hi=0,hi2=0,h0=0,total=0,band=0;
        for(int i=0;i<frame.length;i+=2){
            int x=(short)((frame[i]&255)|(frame[i+1]<<8));
            lo=x-x0+.97*lo;hi=.89*(hi+x-x0);hi2=.89*(hi2+hi-h0);
            x0=x;h0=hi;total+=lo*lo;band+=hi2*hi2;
        }
        return total>0&&band/total<.12;
    }
    /** Guard only the opening of a confirmed interruption, not every final silence. */
    boolean protectOpening(int milliseconds){
        if(milliseconds<0||milliseconds>2000||milliseconds%20!=0)throw new IllegalArgumentException("OPENING_BOUND");
        if(!active||done||cancelled)return false;
        protectedOpeningMs=Math.max(protectedOpeningMs,milliseconds);return true;
    }
    public SpeechWindow(){this(1);}
    public SpeechWindow(int sensitivity){this(sensitivity,0);}
    public SpeechWindow(int sensitivity,double ambient){
        this(sensitivity,ambient,SILENCE_MS);
    }
    /** Explicit offline/controlled comparison; production default remains conservative until verified. */
    public SpeechWindow(int sensitivity,double ambient,int silenceMs){
        this(sensitivity,ambient,silenceMs,PRE_BYTES/32);
    }
    public SpeechWindow(int sensitivity,double ambient,int silenceMs,int preRollMs){
        this(sensitivity,ambient,silenceMs,preRollMs,preRollMs);
    }
    SpeechWindow(int sensitivity,double ambient,int silenceMs,int preRollMs,int listeningPreRollMs){
        if(sensitivity<0||sensitivity>2)throw new IllegalArgumentException("SENSITIVITY_BOUND");
        if(Double.isNaN(ambient)||Double.isInfinite(ambient)||ambient<0||ambient>32768)throw new IllegalArgumentException("NOISE_BOUND");
        if(silenceMs<400||silenceMs>1200||silenceMs%20!=0)throw new IllegalArgumentException("ENDPOINT_BOUND");
        if(preRollMs<650||preRollMs>1500||preRollMs%10!=0)throw new IllegalArgumentException("PREROLL_BOUND");
        if(listeningPreRollMs<preRollMs||listeningPreRollMs>3000||listeningPreRollMs%10!=0)throw new IllegalArgumentException("LISTENING_PREROLL_BOUND");
        baselinePreBytes=preRollMs*32;ring=new byte[listeningPreRollMs*32];
        this.silenceMs=silenceMs;
        minimumEnergy=sensitivity==0?160:sensitivity==1?90:65;
        noiseFactor=sensitivity==0?3.2:sensitivity==1?2.5:2.1;
        noiseFloor=sensitivity==0?80:35;noise=Math.max(ambient,noiseFloor);
    }

    /** Calibrate from buffered microphone samples before admitting the first onset.
     * Calibration and frame decisions use the same .94 analysis high-pass.
     * Retained and streamed PCM are unchanged; this is not an audio filter. */
    public static double ambientLevel(byte[] pcm){
        if(pcm==null||pcm.length<640||pcm.length%640!=0)throw new IllegalArgumentException("CALIBRATION_PCM");
        double[] levels=new double[pcm.length/640];double x0=0,y0=0;
        for(int block=0;block<levels.length;block++){
            double sum=0;for(int j=0;j<320;j++){
                int at=block*640+j*2;int x=(short)((pcm[at]&255)|((pcm[at+1]&255)<<8));
                double y=x-x0+.94*y0;x0=x;y0=y;sum+=y*y;
            }levels[block]=Math.sqrt(sum/320);
        }
        Arrays.sort(levels);return levels[levels.length/2];
    }
    /** Before interruption admission only, retain bounded recent PCM: two-second
     * confirmation plus one-second opening. Never called after an ASR turn is claimed. */
    void trimUnconfirmedPrefix(int maximumMs){
        if(maximumMs<2000||maximumMs>3000||maximumMs%20!=0)throw new IllegalArgumentException("PROVISIONAL_PREFIX_BOUND");
        if(!active||done||cancelled)return;
        int keep=maximumMs*32,drop=used-keep;if(drop<=0)return;
        System.arraycopy(output,drop,output,0,keep);Arrays.fill(output,keep,used,(byte)0);
        used=keep;lastVoiceEnd=Math.max(0,lastVoiceEnd-drop);
    }
    public double noiseLevel(){return noise;}
    public double startThreshold(){return Math.max(minimumEnergy,noise*noiseFactor);}
    interface PcmSink {void accept(byte[] borrowed,int offset,int count);}
    /** Nonblocking borrowed view. The sink copies before returning. At endpoint,
     * finish() trims trailing silence already streamed; never resend that prefix. */
    int streamSince(int sent,PcmSink sink){
        if(sent<0||sink==null)throw new IllegalArgumentException("STREAM_OFFSET");
        if(used>sent){sink.accept(output,sent,used-sent);return used;}return sent;
    }
    /** A continuous duplex owner disables room calibration while far-end speech is present. */
    public void learnNoise(boolean enabled){noiseLearning=enabled;}
    /** Count idle time only while the conversation is actually waiting for its user.
     * Changing this budget must not reset onset evidence, pre-roll or an active utterance. */
    public void waitingForSpeech(boolean enabled){
        if(enabled&&!waitingForSpeech)waitingFrames=0;
        waitingForSpeech=enabled;
    }
    /** Preserve the active utterance and pre-roll when a local setting changes. */
    public void sensitivity(int value){
        if(value<0||value>2)throw new IllegalArgumentException("SENSITIVITY_BOUND");
        minimumEnergy=value==0?160:value==1?90:65;noiseFactor=value==0?3.2:value==1?2.5:2.1;noiseFloor=value==0?80:35;noise=Math.max(noise,noiseFloor);
    }
    /** Preserve already buffered samples. Never train on a possible/confirmed utterance. */
    public boolean calibrateIfQuiet(double ambient){
        if(Double.isNaN(ambient)||Double.isInfinite(ambient)||ambient<0||ambient>32768)throw new IllegalArgumentException("NOISE_BOUND");
        // Provisional startup candidates can be ordinary room noise above the initial prior.
        // Accept that calibration only below the prior's speech threshold; never raise the
        // floor to a stronger candidate utterance, and never after confirmed speech starts.
        if(active||done||(attack!=0&&ambient>startThreshold()))return false;
        noise=Math.max(ambient,noiseFloor);return true;
    }

    public void sample(byte low, byte high) {
        if (done) return;
        frame[frameBytes++] = low; frame[frameBytes++] = high;
        int x = (short)((low & 255) | ((high & 255) << 8));
        double y = x - previousX + 0.94 * previousY;
        previousX = x; previousY = y; energy += y * y;
        if (frameBytes == frame.length) { process(); frameBytes = 0; energy = 0; }
    }
    public void accept(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0) throw new IllegalArgumentException("PCM_ALIGNMENT");
        for (int i = 0; i < pcm.length && !done; i += 2) sample(pcm[i], pcm[i + 1]);
    }
    private boolean nuisanceFrame() {
        // A single sinusoid satisfies x[n]+x[n-2]=a*x[n-1]. Multi-harmonic speech does not.
        double zz=0,xx=0,xz=0,e=0;int previous=0,previous2=0,crossings=0;
        for(int i=0;i<FRAME_SAMPLES;i++){
            int x=(short)((frame[2*i]&255)|((frame[2*i+1]&255)<<8));e+=(double)x*x;
            if(i>0&&((x>=0)!=(previous>=0)))crossings++;
            if(i>=2){double z=x+previous2;zz+=z*z;xx+=(double)previous*previous;xz+=previous*z;}
            previous2=previous;previous=x;
        }
        if(e<1)return true;
        double residual=Math.max(0,zz-xz*xz/Math.max(1,xx))/e;
        return residual<0.0003 || crossings>130;
    }
    private void process() {
        frames++;
        double level = Math.sqrt(energy / FRAME_SAMPLES);
        boolean nuisance=nuisanceFrame();
        // A quiet syllable in an established utterance must not need the same energy
        // as a new speaker onset. Keep onset/noise rejection unchanged, and require
        // continuation to remain above calibrated background (at least 1.5x).
        double threshold = active ? Math.max(minimumEnergy * 0.70,
                noise * Math.max(1.5, noiseFactor * 0.60)) : startThreshold();
        boolean voiced = !nuisance && level >= threshold;
        // Only additional evidence for playback interruption; endpoint and raw PCM stay unchanged.
        lastBargeSpeech=voiced&&!lowBandFrame();
        recognizerQuietMs=lastBargeSpeech?0:Math.min(3000,recognizerQuietMs+20);
        if (!active) {
            if(waitingForSpeech)waitingFrames++;
            for (byte v : frame) { ring[ringNext] = v; ringNext = (ringNext + 1) % ring.length; ringSize = Math.min(ring.length, ringSize + 1); }
            // Do not train the room estimate on the quieter parts of a possible utterance.
            if (noiseLearning && !voiced && !nuisance && attack == 0) noise = 0.96 * noise + 0.04 * level;
            // Keep 100ms of evidence within 300ms, tolerating consonants/gaps. A 60ms
            // click plus a separate 20ms click is insufficient. Consecutive-only lost words.
            attack = ((attack << 1) | (voiced ? 1 : 0)) & 32767;
            // A weak opening syllable can supply 80ms strong +80ms genuine weaker
            // evidence rather than a missing fifth strong frame. Do not lower the
            // start threshold, learn speech as room noise, or admit a short click.
            double weakThreshold=Math.max(minimumEnergy*.70,noise*Math.max(1.5,noiseFactor*.60));
            boolean weakVoiced=!nuisance&&level>=weakThreshold;
            weakOnsetEvidence=((weakOnsetEvidence<<1)|(weakVoiced?1:0))&32767;
            int strongFrames=Integer.bitCount(attack);
            if (strongFrames >= 5 || (strongFrames >= 4 && Integer.bitCount(weakOnsetEvidence) >= 8)) {
                active = true;activatedAtFrame=frames;
                // Preserve a soft opening already recorded while awaiting the user.
                // Do not admit weak noise or add a wait; only retain more existing PCM.
                // Outside listening, keep the exact old prefix to avoid older TTS bleed.
                int allowed=waitingForSpeech?Math.max(baselinePreBytes,Math.min(ring.length,waitingFrames*640)):baselinePreBytes;
                int retained=Math.min(ringSize,allowed);
                int first = (ringNext - retained + ring.length) % ring.length;
                for (int i = 0; i < retained; i++) output[used++] = ring[(first + i) % ring.length];
                lastVoiceEnd = used; Arrays.fill(ring, (byte)0); ringSize = ringNext = 0;
            } else if (waitingForSpeech && waitingFrames * 20 >= WAIT_MS) complete("NO_SPEECH");
        } else {
            int count = Math.min(frame.length, output.length - used);
            System.arraycopy(frame, 0, output, used, count); used += count;
            // Strong speech keeps the original immediate continuation behavior. The
            // newly accepted quieter range needs 3/5 frames (60ms in 100ms), so a
            // single weak click cannot restart the silence timer and prolong a reply.
            continuationEvidence = ((continuationEvidence << 1) | (voiced ? 1 : 0)) & 31;
            if (voiced && (level >= startThreshold() * 0.75 || Integer.bitCount(continuationEvidence) >= 3))
                lastVoiceEnd = used;
            if (used >= output.length) complete("UTTERANCE_LIMIT");
            else if ((used - lastVoiceEnd) * 1000L / (RATE * 2) >= silenceMs && (frames-activatedAtFrame)*20 >= protectedOpeningMs) {
                used = Math.min(used, lastVoiceEnd + TRAIL_BYTES);
                complete("END_OF_SPEECH");
            }
        }
    }
    private void complete(String why) { done = true; reason = why; }
    boolean quietForInitiative(){return !active&&!done&&!cancelled&&attack==0&&weakOnsetEvidence==0;}
    public boolean completed() { return done; }
    public boolean speechStarted() { return active; }
    public int samplesSeen() { return frames * FRAME_SAMPLES + frameBytes / 2; }
    public String reason() { return reason; }
    public byte[] finish() {
        if (!done || cancelled) throw new IllegalStateException("SPEECH_WINDOW_NOT_COMPLETE");
        byte[] result = Arrays.copyOf(output, used); wipe(); return result;
    }
    public void cancel() { cancelled = true; complete("CANCELLED"); wipe(); }
    private void wipe() { Arrays.fill(frame, (byte)0); Arrays.fill(ring, (byte)0); Arrays.fill(output, (byte)0); frameBytes = ringSize = ringNext = used = 0; }
}
