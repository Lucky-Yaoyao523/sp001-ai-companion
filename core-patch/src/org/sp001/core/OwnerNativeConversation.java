package org.sp001.core;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Exclusive platform AudioRecord uses the stock hardware format without Cobalt recognition.
 * The original English recognizer closes its recorder on an unrecognized final result;
 * it must NOT own the lifetime of a Chinese conversation. Stock listening is stopped first.
 */
final class OwnerNativeConversation implements ConversationSession.Ports,ConversationSession.ModelDirectedReply,ConversationSession.LocalReply,ConversationSession.StreamingPorts,ConversationSession.EndingReply,ConversationSession.PipelinedPorts,ConversationSession.Interruptible,ConversationSession.RecoverableRecognition,ConversationSession.IdleReply,ConversationSession.ReplyOutputBudget,ConversationSession.SessionGuard {
    private static final AtomicLong VISUAL_OWNER = new AtomicLong();
    private final long visualOwner = VISUAL_OWNER.incrementAndGet();
    private final AtomicLong visualRevision = new AtomicLong();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object connector;
    private final long deadline = SystemClock.elapsedRealtime() + ConversationSession.DEFAULT_MS;
    private final MiniMaxVoiceClient client;
    private final boolean diagnostic, saveDiagnosticAudio;
    private byte[] diagnosticEcho;
    private final String sessionId;
    private int captureCount, progressSequence;
    private final org.json.JSONArray captureSummary = new org.json.JSONArray();
    private final org.json.JSONArray timings = new org.json.JSONArray();
    private long phaseStarted = SystemClock.elapsedRealtime();
    private String previousPhase = "STARTING";
    private volatile boolean closed;
    private android.media.AudioRecord recording;
    private OwnerInputEffects inputEffects;
    private JSONObject inputEffectStatus=new JSONObject();
    private JSONObject finalInputOnset=new JSONObject();
    private CompanionVisualPlayer companionEyes;
    private volatile String visualPhase="IDLE";
    private CompanionSettings behaviorSettings;
    private final BehaviorActionGate behaviorActions=new BehaviorActionGate();
    private volatile boolean expressive=true;
    private boolean localEnd;
    private volatile String localEye;
    private volatile String playingEmotion="neutral";
    private final ReplyActionPolicy.Gate modelActions=new ReplyActionPolicy.Gate();
    private String lastBehavior="CHAT",lastBehaviorResult="NONE";
    private JSONObject lastDeviceReceipt;
    private long firstAudioMs=-1,firstAudioMonotonicMs=-1;
    private volatile long firstAnswerAudioMonotonicMs=-1;
    private java.util.concurrent.ScheduledExecutorService captureWatchdog;
    private long captureToken;
    private String settingAnswer;
    private int settingAction;
    private final DuplexTurnGate turns=new DuplexTurnGate();
    private OwnerDuplexCapture duplex;
    private long responseToken;
    private volatile long lastBargeInMs;
    private final StreamingAsrConversation streamingAsr;
    private final StreamingAsrAccess candidateBudget;
    private volatile boolean streamingAsrDisabled;
    private AsrFailover asrFailover;
    private final class AsrPorts implements AsrFailover.Ports {
        public void check()throws Exception{checkTurn();}
        public String primary(byte[] pcm)throws Exception{return streamingAsr.awaitResult(capturedUtteranceId,8000);}
        public void cancelPrimary(){disableStreamingAsr();}
        public String backup(byte[] pcm)throws Exception{return client.transcribe(pcm);}
    }
    private final java.util.concurrent.atomic.AtomicInteger streamingAsrAttempts=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger streamingAsrConnections=new java.util.concurrent.atomic.AtomicInteger();
    private long capturedUtteranceId;
    private OwnerConversationTrace testTrace;
    private CompanionMemory memories;private String memoryStatus="UNOPENED",memoryQuote="",memoryUser="",memoryProfile="",pendingFollowUp="";
    private long memoryRevision;private boolean autonomousReply;
    private final IdleInitiative initiative=new IdleInitiative();
    private int finalConfirmedBarges,finalRejectedBarges;
    public String idleReply(){return autonomousReply?settingAnswer:null;}
    private int localHour(){return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei")).get(java.util.Calendar.HOUR_OF_DAY);}
    private void commitCompanionExtras(){
        if(autonomousReply)return;
        if(endAfterReply()){initiative.discard();pendingFollowUp="";memoryQuote="";return;}
        try{if(client.childAwaitingEnglish()){initiative.discard();}else{if(!pendingFollowUp.isEmpty())initiative.prepare(pendingFollowUp);if(!client.modelOwnsDialogue())initiative.ensureCandidate(memoryUser);}
            if(memories!=null&&!memoryQuote.isEmpty()&&!interrupted()&&!cancelled())memoryStatus=memories.suggest(memoryQuote,memoryUser,memoryProfile,memoryRevision,System.currentTimeMillis())?"SAVED":"NOT_SAVED";
        }catch(Exception e){memoryStatus="SAVE_NOT_CONFIRMED";}
        finally{memoryQuote="";pendingFollowUp="";}
    }

    boolean traceTranscriptSaved(){return testTrace!=null&&testTrace.transcriptSaved();}
    boolean traceAudioSaved(){return testTrace!=null&&testTrace.audioSaved();}


    OwnerNativeConversation(final Object c, JSONObject config) throws Exception {
        this(c,config,OwnerHeadless.nativeSessionId(c));
    }
    OwnerNativeConversation(final Object c, JSONObject config,String assignedSessionId) throws Exception {
        connector = c;
        sessionId=assignedSessionId;
        OwnerHeadless.bindNativeSession(connector,sessionId);
        diagnostic = "diagnostic".equals(config.optString("mode"));
        if (diagnostic && !OwnerVoiceBridge.validDiagnostic(config)) throw new IOException("DIAGNOSTIC_CONSENT_REQUIRED");
        saveDiagnosticAudio = diagnostic && config.optBoolean("saveDiagnosticAudio", false);
        String asrProvider=config.optString("asrProvider","minimax");
        if(!"minimax".equals(asrProvider)&&!"qwen-streaming".equals(asrProvider))throw new IOException("ASR_PROVIDER_INVALID");
        if("qwen-streaming".equals(asrProvider)){
            if(diagnostic)throw new IOException("ASR_DIAGNOSTIC_NOT_ALLOWED");
            JSONObject asr=config.optJSONObject("streamingAsr");
            if(asr==null||!"cn-beijing".equals(asr.optString("region"))||!QwenAsrWire.MODEL.equals(asr.optString("model")))throw new IOException("ASR_CONFIG_INVALID");
            final String key=asr.optString("apiKey",""),workspace=asr.optString("workspaceId","");
            if(!key.matches("[A-Za-z0-9_.-]{16,512}"))throw new IOException("ASR_KEY_FORMAT");
            QwenAsrWire.endpoint(workspace);
            final android.content.SharedPreferences ledger=OwnerVoiceBridge.nativeContext().getSharedPreferences("sp001-asr-candidate-budget",android.content.Context.MODE_PRIVATE);
            candidateBudget=StreamingAsrAccess.create(config,asr,new StreamingAsrCandidateBudget.Store(){
                public String read(String id){return ledger.getString(id,null);}
                public boolean write(String id,String value){if(!ledger.contains(id)&&ledger.getAll().size()>=32)return false;return ledger.edit().putString(id,value).commit();}
            },System.currentTimeMillis(),nowMs());
            streamingAsrDisabled=!candidateBudget.valid(System.currentTimeMillis(),nowMs())||!candidateBudget.remaining();
            streamingAsr=new StreamingAsrConversation(new StreamingAsrConversation.BudgetedFactory(){
              public boolean canCreate()throws IOException{return !streamingAsrDisabled&&candidateBudget.valid(System.currentTimeMillis(),nowMs())&&candidateBudget.remaining();}
              public StreamingAsrSession create(String taskId)throws IOException{
                candidateBudget.reserve(System.currentTimeMillis(),nowMs());
                return new StreamingAsrSession(taskId,new StreamingAsrSession.WireFactory(){public StreamingAsrSession.Wire create(String id){
                    final QwenAsrWire wire=new QwenAsrWire(key,workspace,id);
                    return new StreamingAsrSession.ReleasingWire(){
                        private void allowed()throws IOException{if(streamingAsrDisabled||!candidateBudget.valid(System.currentTimeMillis(),nowMs()))throw new IOException("ASR_CANDIDATE_EXPIRED");}
                        public void open(StreamingAsrSession.Listener listener)throws IOException{allowed();streamingAsrConnections.incrementAndGet();wire.open(listener);}
                        public boolean startTask()throws IOException{allowed();streamingAsrAttempts.incrementAndGet();return wire.startTask();}
                        public boolean sendAudio(byte[] pcm,int off,int len)throws IOException{allowed();return wire.sendAudio(pcm,off,len);}
                        public boolean finishTask()throws IOException{allowed();return wire.finishTask();}
                        public long queuedBytes()throws IOException{allowed();return wire.queuedBytes();}
                        public void cancel(){wire.cancel();}
                        public boolean releaseConfirmed(){return wire.releaseConfirmed();}
                    };
                }});
            }});
        }else {streamingAsr=null;candidateBudget=null;}
        client = diagnostic ? null : new MiniMaxVoiceClient(config, new MiniMaxVoiceClient.Cancel() {
            public boolean cancelled() { return OwnerNativeConversation.this.cancelled(); }
        });
        if(streamingAsr!=null)asrFailover=new AsrFailover(new AsrPorts());
        if(!diagnostic){
            try{behaviorSettings=OwnerBehaviorSettings.load();expressive=behaviorSettings.get("expressive");client.companionInitiative(behaviorSettings.get("initiative"));}
            catch(Exception ignored){behaviorSettings=null;}
        }
        if(!diagnostic)try{memories=OwnerCompanionMemory.open();memories.enabled();memories.select(ChildCompanionPolicy.CHILD);client.recentConversation(memories.profile(),memories.enabled(),false);memoryStatus="READY";}catch(Exception ignored){memories=null;memoryStatus="UNAVAILABLE_PRESERVED";}
        if(!diagnostic)try{client.attachChildStoryStore(OwnerChildStoryProgress.open());}catch(Exception ignored){/* A progress storage failure cannot prevent a story. */}
        try{android.content.Context context=OwnerVoiceBridge.nativeContext();
            if(new java.io.File(context.getFilesDir(),OwnerConversationTrace.ARM).isFile())testTrace=OwnerConversationTrace.claim(context.getFilesDir(),config,sessionId,context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode,System.currentTimeMillis(),nowMs());
        }catch(Exception ignored){}
        OwnerHeadless.bindNativeSession(connector,sessionId);
    }
    public long nowMs() { return SystemClock.elapsedRealtime(); }
    public String stopReason(){return diagnostic?"ALLOW":ParentUseGuard.continueSession(sessionId);}
    public boolean cancelled() {
        if (closed || nowMs() >= deadline) return true;
        try { return (Boolean)connector.getClass().getMethod("isActivityAborted").invoke(connector); }
        catch (Exception e) { return true; }
    }
    private void check() throws IOException {
        if (cancelled() || Thread.currentThread().isInterrupted()) throw new IOException("TURN_CANCELLED");
        if(!diagnostic&&responseToken!=0)turns.check(responseToken);
    }
    private boolean turnCancelled(long token){if(cancelled())return true;try{turns.check(token);return false;}catch(IOException e){return true;}}
    public boolean interrupted(){return !cancelled()&&responseToken!=0&&turns.wasInterrupted(responseToken);}
    public void checkTurn()throws IOException{check();}
    public void endResponse()throws IOException{check();if(!diagnostic&&!turns.endSession(responseToken))throw new DuplexTurnGate.Interrupted();}
    public void recoverTurn(String code)throws Exception{
        check();if(diagnostic)throw new IOException("RECOVERY_NOT_SUPPORTED");
        if("ASR_BACKUP_UNAVAILABLE".equals(code)){
            // Feedback is best effort if both network paths are unavailable. A new
            // utterance, not an automatic repeated upload, is the sole recovery.
            try{speakStream("请再说一次，我在听。");}
            catch(IOException feedback){check();String codeName=feedback.getMessage();if(!AsrFailover.backupUnavailable(codeName)&&(codeName==null||!codeName.startsWith("TTS_")))throw feedback;}
            check();return;
        }
        if("ASR_INCOMPLETE_TRANSCRIPT".equals(code)){
            // Bounded input-error feedback, not an invented transcript or model answer.
            // The controller allows it once before fresh input; current cancellation still wins.
            speakStream("刚才那句话我没有听清，请再说一次。");check();return;
        }
        if(!"HTTP_TRANSFER_FAILED".equals(code)&&!"NATIVE_REPLY_ENVELOPE_REQUIRED".equals(code))throw new IOException("RECOVERY_NOT_SUPPORTED");
        // Existing finishTurn() clears uncommitted text and scoped ownership.
        // Do not discard a healthy prewarmed TTS socket or create another worker.
    }
    public void finishTurn(){
        try{if(client!=null)client.finishTurn();memoryQuote="";pendingFollowUp="";autonomousReply=false;}
        finally{if(responseToken!=0)turns.finish(responseToken);responseToken=0;if(duplex!=null)duplex.finishResponse(capturedUtteranceId);}
    }
    private byte[] captureDuplex()throws Exception{
        check();client.prepareTts();long began=nowMs();autonomousReply=false;
        if(candidateBudget!=null&&!candidateBudget.valid(System.currentTimeMillis(),nowMs()))disableStreamingAsr();
        if(duplex==null){
            Object program=Class.forName("com.smarttoy.jsactivities.a").getMethod("lc").invoke(null);
            program.getClass().getMethod("bW",String.class).invoke(program,(Object)null);check();
            duplex=new OwnerDuplexCapture(new OwnerDuplexCapture.Life(){public boolean cancelled(){return OwnerNativeConversation.this.cancelled();}},OwnerAudioSettings.sensitivity(),new ContinuousSpeechInput.Onset(){public void started(long speech){
                long cancelledToken=turns.speechStarted(speech);
                if(cancelledToken!=0){
                    lastBargeInMs=nowMs();visualRevision.incrementAndGet();
                    // Cancellation is already committed; preserve this opening only.
                    OwnerDuplexCapture owner=duplex;if(owner!=null)owner.protectInterruptionOpening(speech);
                }
                // No network, playback, disk or JNI cleanup on this continuous capture thread.
            }},streamingAsr);
            // Publish the capture owner before starting provider workers, including failure paths.
            if(streamingAsr!=null&&!streamingAsrDisabled)streamingAsr.prepare();
            // The cue must finish before the continuous microphone opens. Playing
            // it after the 320ms calibration can turn our own ACK into speech and
            // poison both endpointing and the first streaming-ASR task.
            OwnerFeedback.readyCue();check();
            duplex.start();check();
            inputEffectStatus=new JSONObject().put("continuous",true).put("aecEchoMode",OwnerAecProcessor.ECHO_MODE).put("ambientRms",duplex.ambient()).put("inputAgc",false);
        }
        OwnerDuplexCapture.ReadReceipt fresh=duplex.awaitFreshRead();writeCaptureReady(fresh.bytes,fresh.monotonicMs);OwnerFeedback.state("LISTENING");requestVisual("LISTENING",0);
        ContinuousSpeechInput.Utterance captured;
        for(;;){captured=duplex.take(new OwnerDuplexCapture.IdleOpportunity(){public boolean due(long waited){
            return initiative.due(waited,nowMs(),localHour(),behaviorSettings!=null&&behaviorSettings.get("initiative"));
        }},turns);if(captured.initiativeToken!=0||captured.pcm.length>0||turns.endIdle(captured.id))break;check();}
        OwnerHeadless.captureNotReady(connector,sessionId);
        if(captured.initiativeToken!=0){
            capturedUtteranceId=captured.id;responseToken=captured.initiativeToken;final long token=responseToken;
            client.beginTurn(new MiniMaxVoiceClient.Cancel(){public boolean cancelled(){return turnCancelled(token);}},new MiniMaxVoiceClient.Commit(){public boolean apply(Runnable r){return turns.tryCommit(token,r);}});
            settingAnswer=initiative.claim(nowMs());settingAction=0;localEye=null;localEnd=false;autonomousReply=true;
            if(settingAnswer.isEmpty())throw new IOException("INITIATIVE_CLAIM_LOST");client.stageInitiative(settingAnswer);return captured.pcm;
        }
        if(captured.pcm.length>0)initiative.discard(); // A new utterance invalidates stale follow-ups even if its ASR later fails.
        captureCount++;captureSummary.put(new JSONObject().put("capture",captureCount).put("bytes",captured.pcm.length).put("utteranceId",captured.id).put("continuous",true).put("elapsedMs",nowMs()-began).put("inputEffects",inputEffectStatus));
        if(captured.pcm.length==0)return captured.pcm;
        if(testTrace!=null)testTrace.capture(captured.id,captured.pcm,nowMs());
        try{
            capturedUtteranceId=captured.id;responseToken=turns.beginResponse(captured.id);final long token=responseToken;
            client.beginTurn(new MiniMaxVoiceClient.Cancel(){public boolean cancelled(){return turnCancelled(token);}},new MiniMaxVoiceClient.Commit(){public boolean apply(Runnable action){return turns.tryCommit(token,action);}});
            client.bindTimingTurn(captured.id);check();return captured.pcm;
        }catch(Exception error){java.util.Arrays.fill(captured.pcm,(byte)0);throw error;}
    }
    public byte[] capture() throws Exception {
        if(!diagnostic)return captureDuplex();
        check();
        if(client!=null)client.prepareTts();
        boolean stereo = (Boolean)Class.forName("com.smarttoy.util.c").getMethod("nb").invoke(null);
        try {
            Object program = Class.forName("com.smarttoy.jsactivities.a").getMethod("lc").invoke(null);
            program.getClass().getMethod("bW", String.class).invoke(program, (Object)null);
            check();
            int channels = stereo ? android.media.AudioFormat.CHANNEL_IN_STEREO : android.media.AudioFormat.CHANNEL_IN_MONO;
            int minimum = android.media.AudioRecord.getMinBufferSize(16000,channels,android.media.AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("NATIVE_RECORD_FORMAT_UNAVAILABLE");
            recording = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,channels,android.media.AudioFormat.ENCODING_PCM_16BIT,Math.max(5120,minimum*2));
            if (recording.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IOException("NATIVE_RECORD_INIT_FAILED");
            inputEffects=new OwnerInputEffects(recording.getAudioSessionId());inputEffectStatus=inputEffects.status();
            OwnerFeedback.state("PREPARING");
            final android.media.AudioRecord ownedRecorder = recording;
            final long start = nowMs();
            captureWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            captureWatchdog.scheduleAtFixedRate(new Runnable(){public void run(){
                if(cancelled() || nowMs()-start>=50000){try{ownedRecorder.stop();}catch(RuntimeException ignored){}}
            }},100,100,java.util.concurrent.TimeUnit.MILLISECONDS);
            recording.startRecording();
            if (recording.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) throw new IOException("NATIVE_RECORD_NOT_STARTED");
            byte[] room=new byte[10240];int filled=0;long calibrationUntil=nowMs()+2500;
            java.nio.ByteBuffer calibration=java.nio.ByteBuffer.allocateDirect(2560);
            try{
                while(filled<room.length){
                    check();if(nowMs()>calibrationUntil)throw new IOException("NOISE_CALIBRATION_TIMEOUT");
                    calibration.clear();int n=recording.read(calibration,2560);
                    if(n<0||n%(stereo?4:2)!=0)throw new IOException("NOISE_CALIBRATION_READ");
                    for(int at=0;at<n&&filled<room.length;at+=stereo?4:2){room[filled++]=calibration.get(at);room[filled++]=calibration.get(at+1);}
                }
                double ambient=SpeechWindow.ambientLevel(room);
                inputEffectStatus.put("ambientRms",ambient).put("calibrationMs",320);
                recording.stop();OwnerFeedback.readyCue();check();
                captureToken=OwnerCaptureTap.beginSpeech(stereo,OwnerAudioSettings.sensitivity(),ambient);
            }finally{java.util.Arrays.fill(room,(byte)0);}
            recording.startRecording();check();
            if(recording.getRecordingState()!=android.media.AudioRecord.RECORDSTATE_RECORDING)throw new IOException("NATIVE_RECORD_RESTART_FAILED");
            java.nio.ByteBuffer block = java.nio.ByteBuffer.allocateDirect(2560);
            long progressAt = start;boolean firstRead=true;
            while (!OwnerCaptureTap.speechComplete(captureToken)) {
                check();
                if (nowMs() - start >= 50000) throw new IOException("NATIVE_CAPTURE_DEADLINE");
                block.clear(); int read = recording.read(block,2560); check();
                if (read < 0 || read % (stereo ? 4 : 2) != 0) throw new IOException("NATIVE_RECORD_READ_FAILED");
                if (read > 0) {
                    OwnerCaptureTap.observe(block,read/2); progressAt=nowMs();
                    if(firstRead){
                        firstRead=false;inputEffectStatus.put("firstReadMonotonicMs",progressAt);
                        writeCaptureReady(read,progressAt);
                        OwnerFeedback.state("LISTENING");requestVisual("LISTENING",0);
                    }
                }
                else { if(nowMs()-progressAt>=6000)throw new IOException("NATIVE_CAPTURE_STALLED");Thread.sleep(10); }
            }
            check();String endpoint=OwnerCaptureTap.speechReason(captureToken);byte[] captured = OwnerCaptureTap.finish(captureToken);
            captureCount++;
            captureSummary.put(new JSONObject().put("capture",captureCount).put("bytes",captured.length)
                .put("elapsedMs",nowMs()-start).put("rms",MiniMaxCodec.rms(captured)).put("peak",MiniMaxCodec.peak(captured)).put("endpoint",endpoint).put("inputEffects",inputEffectStatus).put("sensitivity",OwnerAudioSettings.sensitivity()));
            if (saveDiagnosticAudio && captureCount <= 2 && captured.length > 0) {
                java.io.FileOutputStream out = OwnerVoiceBridge.nativeContext().openFileOutput("sp001-diagnostic-capture-"+captureCount+".wav", android.content.Context.MODE_PRIVATE);
                try { out.write(OwnerCaptureTap.wav(captured,16000)); } finally { out.close(); }
            }
            return captured;
        } finally {
            // Release exclusive microphone ownership BEFORE any ASR, TTS or speaker action.
            try { stopCapture(); } finally { OwnerCaptureTap.cancel(captureToken); captureToken = 0; }
        }
    }
    private void writeCaptureReady(int bytes,long monotonicMs) {
        OwnerHeadless.captureReady(connector,sessionId,captureCount,monotonicMs);
        java.io.File temporary=null;
        try {
            JSONObject ready=new JSONObject().put("sessionId",sessionId).put("captureIndex",captureCount)
                .put("event","CAPTURE_READY").put("firstReadBytes",bytes).put("monotonicMs",monotonicMs)
                .put("atMs",System.currentTimeMillis());
            java.io.File directory=OwnerVoiceBridge.nativeContext().getFilesDir();
            temporary=java.io.File.createTempFile(".sp001-ready-",".tmp",directory);
            java.io.FileOutputStream out=new java.io.FileOutputStream(temporary);
            try{out.write(ready.toString().getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            // Same-directory rename on the device publishes only a complete JSON file,
            // including to an external reader that does not use Android AtomicFile.
            if(!temporary.renameTo(new java.io.File(directory,"sp001-capture-ready.json")))throw new IOException("READY_PUBLISH_FAILED");
        } catch(Exception ignored) { /* Missing receipt fails measurement, never the conversation. */ }
        finally{if(temporary!=null&&temporary.exists())temporary.delete();}
    }
    private void stopCapture() {
        if(inputEffects!=null){inputEffects.close();inputEffects=null;}
        if(!closed&&recording!=null)OwnerFeedback.state("PROCESSING");
        if (captureWatchdog != null) { captureWatchdog.shutdownNow();captureWatchdog=null; }
        android.media.AudioRecord owned = recording;recording=null;
        if(owned!=null){try{if(owned.getRecordingState()==android.media.AudioRecord.RECORDSTATE_RECORDING)owned.stop();}
            catch(RuntimeException ignored){}finally{owned.release();}}
    }
    public String transcribe(byte[] pcm) throws Exception {
        check(); if (!diagnostic) {
            checkTurn();String heard;
            if(candidateBudget!=null&&!candidateBudget.valid(System.currentTimeMillis(),nowMs()))disableStreamingAsr();
            if(asrFailover!=null&&asrFailover.usingBackup())heard=asrFailover.recognize(pcm);
            else if(streamingAsr==null||streamingAsrDisabled)heard=client.transcribe(pcm);
            else {
                try{heard=asrFailover.recognize(pcm);}
                catch(IOException error){
                    checkTurn();boolean expired=!candidateBudget.valid(System.currentTimeMillis(),nowMs());
                    boolean noGrant="ASR_NOT_PREPARED".equals(error.getMessage())&&!candidateBudget.remaining();
                    if(!expired&&!noGrant)throw error;disableStreamingAsr();checkTurn();heard=client.transcribe(pcm);
                }
                // A terminal response racing grant expiry is not eligible for delivery.
                if(!streamingAsrDisabled&&!candidateBudget.valid(System.currentTimeMillis(),nowMs())){disableStreamingAsr();checkTurn();heard=client.transcribe(pcm);}
            }
            checkTurn();if(testTrace!=null)testTrace.text(capturedUtteranceId,"ASR",heard,nowMs());
            settingAction=0;settingAnswer=null;localEnd=false;localEye=null;
            if(client.modelOwnsDialogue())initiative.user();else initiative.user(heard);memoryUser=heard;memoryQuote="";pendingFollowUp="";autonomousReply=false;
            MemoryCommand memoryCommand=MemoryCommand.parse(heard);
            try{if(memories!=null){memoryProfile=memories.profile();memoryRevision=memories.revision();client.memoryContext(memories.context(heard));
                if(memoryCommand.kind!=MemoryCommand.Kind.NONE){
                    settingAnswer=memories.command(memoryCommand,System.currentTimeMillis());
                    if(memoryCommand.kind==MemoryCommand.Kind.PROFILE||memoryCommand.kind==MemoryCommand.Kind.CLEAR||memoryCommand.kind==MemoryCommand.Kind.FORGET||memoryCommand.kind==MemoryCommand.Kind.OFF||memoryCommand.kind==MemoryCommand.Kind.ON)
                        client.recentConversation(memories.profile(),memories.enabled(),true);
                }
            }else if(memoryCommand.kind!=MemoryCommand.Kind.NONE)settingAnswer="记忆存储暂时不可用，原有内容没有删除。";
            }catch(Exception memoryError){memoryStatus="OPERATION_NOT_CONFIRMED";if(memoryCommand.kind!=MemoryCommand.Kind.NONE)settingAnswer="这次记忆操作没有确认成功。";}
            if(settingAnswer!=null){client.stageMemoryReply(settingAnswer,memoryCommand.kind==MemoryCommand.Kind.PROFILE||memoryCommand.kind==MemoryCommand.Kind.FORGET||memoryCommand.kind==MemoryCommand.Kind.CLEAR||memoryCommand.kind==MemoryCommand.Kind.OFF);return heard;}

            // Native dialogue owns language/actions. Local privacy operations above stay explicit.
            if(client.modelOwnsDialogue()){OwnerAudioSettings.describeTo(client);return heard;}
            VoiceBehavior.Decision decision=VoiceBehavior.classify(heard);
            if(decision.kind==VoiceBehavior.Kind.MODE){
                if(OwnerHeadless.requestModeAfterTurn(connector,decision.legacy.booleanValue()))return "停止对话";
                settingAnswer="模式切换没有确认，当前模式保持不变。";
            }else if(decision.kind!=VoiceBehavior.Kind.CHAT&&decision.kind!=VoiceBehavior.Kind.STOP){
                executeBehavior(decision);
            }
            if(decision.kind==VoiceBehavior.Kind.CHAT&&settingAnswer==null)settingAnswer=client.prepareChildReply(heard);
            else client.discardChildExercise();
            if(settingAnswer!=null)client.stageLocalReply(heard,settingAnswer,localEye==null?"neutral":localEye.equals("SMILE")?"happy":localEye.equals("SURPRISED")?"surprised":"neutral");
            return heard;
        }
        if (diagnosticEcho != null) java.util.Arrays.fill(diagnosticEcho,(byte)0);
        diagnosticEcho = pcm.clone(); return "离线诊断占位，不是语音识别结果";
    }
    private void disableStreamingAsr(){streamingAsrDisabled=true;if(streamingAsr!=null)streamingAsr.cancel();}
    public String reply(String text) throws Exception { check(); return settingAnswer!=null?settingAnswer:diagnostic ? "离线诊断回放，不是智能回答" : client.reply(text); }
    public boolean hasLocalReply(){return settingAnswer!=null;}
    public boolean modelOwnsDialogue(){return !diagnostic&&client.modelOwnsDialogue();}
    public boolean endAfterReply(){return localEnd||(settingAnswer==null&&!diagnostic&&client.endAfterReply());}
    public boolean streamingSpeechEnabled(){return !diagnostic;}
    public boolean pipelineEnabled(){return !diagnostic;}
    public int replyOutputLimit(){return !diagnostic&&settingAnswer==null?client.childOutputLimit():PcmStreamState.MAX_BYTES;}
    public int replyAndSpeak(final String heard)throws Exception{
        check();final long started=nowMs(),token=responseToken;firstAudioMs=-1;firstAudioMonotonicMs=-1;firstAnswerAudioMonotonicMs=-1;
        final int[] feedbackSeen={0};
        final OwnerStreamPlayer player=new OwnerStreamPlayer(deadline,new OwnerStreamPlayer.DuckingLifecycle(){
            public boolean ducking(){return duplex!=null&&duplex.provisionalInterruption();}
            public boolean cancelled(){return turnCancelled(token);}
            public void firstAudio(){if(!turnCancelled(token)){if(duplex!=null)duplex.playback(true);firstAudioMonotonicMs=nowMs();firstAudioMs=firstAudioMonotonicMs-started;state("SPEAKING");}}
        },replyOutputLimit());
        final SentencePlayback.Result result=new SentencePlayback.Result();
        boolean parentReported=false;
        try{
            SentencePlayback.run(new SentencePlayback.Ports(){
                public void check()throws Exception{OwnerNativeConversation.this.check();}
                public void sentences(final SentencePlayback.TextSink sink)throws Exception{
                    java.util.List<String> direct=client.modelOwnsDialogue()?java.util.Collections.<String>emptyList():ReplyActionPolicy.explicitActions(heard);
                    final String[] acknowledgement={direct.isEmpty()?"":executeModelSegment(heard,new ReplySegment("","neutral",direct,false))};
                    client.confirmedDeviceActions(modelActions.confirmedActions(capturedUtteranceId));
                    if(client.modelOwnsDialogue())client.bindDeviceExecutor(new MiniMaxVoiceClient.MemoryExecutor(){
                        public JSONObject execute(String action)throws Exception{return executeDeviceTool(action);}
                        public JSONObject remember(String quote)throws Exception{return executeMemoryTool(quote);}
                    });
                    client.replySegments(heard,new MiniMaxVoiceClient.SegmentSink(){public String speak(final ReplySegment segment)throws Exception{
                        int count=client.knowledgeStatus().optInt("feedbackCount",0);
                        final boolean isFeedback=count>feedbackSeen[0];if(isFeedback)feedbackSeen[0]=count;
                        checkTurn();if(!segment.memoryQuote.isEmpty())memoryQuote=segment.memoryQuote;if(!segment.followUp.isEmpty())pendingFollowUp=segment.followUp;
                        String text=executeModelSegment(heard,segment);checkTurn();
                        if(!acknowledgement[0].isEmpty()){text=acknowledgement[0]+text;acknowledgement[0]="";}
                        if(testTrace!=null)testTrace.text(capturedUtteranceId,"REPLY",text,nowMs());
                        if(text.isEmpty())return text;
                        final String mood=localEye==null?segment.emotion:(localEye.equals("SMILE")?"happy":localEye.equals("SURPRISED")?"surprised":"neutral");
                        player.cue(new Runnable(){public void run(){if(!turnCancelled(token)){if(!isFeedback&&firstAnswerAudioMonotonicMs<0)firstAnswerAudioMonotonicMs=nowMs();playingEmotion=mood;requestVisual("SPEAKING",token);}}});
                        sink.accept(text);if(!isFeedback)player.markTextEnd(text);return text;
                    }});
                }
                public int synthesize(String sentence,final SentencePlayback.AudioSink sink)throws Exception{
                    if(result.acceptedBytes==0)state("SYNTHESIZING");
                    return client.synthesizeStream(sentence,new MiniMaxVoiceClient.PcmSink(){public void write(byte[] pcm)throws Exception{sink.accept(pcm);}});
                }
                public void write(byte[] pcm)throws Exception{player.write(pcm);}
                public int finish()throws Exception{return player.finish();}
                public void close(){player.close();}
            },result,replyOutputLimit());
            check();client.commitReply();check();commitCompanionExtras();
            try{JSONObject answerState=client.knowledgeStatus();
                String failureCode="ANSWER_FAILED".equals(answerState.optString("state"))?answerState.optString("error",""):"";
                ParentConversationSync.answer(OwnerVoiceBridge.nativeContext(),sessionId,capturedUtteranceId,heard,player.playedText(),failureCode);parentReported=true;}
            catch(Throwable ignored){/* Parent reporting cannot affect speech. */}
            return result.acceptedBytes;
        }finally{
            if(!parentReported&&firstAnswerAudioMonotonicMs>=0)try{
                ParentConversationSync.partial(OwnerVoiceBridge.nativeContext(),sessionId,capturedUtteranceId,heard,player.playedText());
            }catch(Throwable ignored){/* Parent reporting cannot affect speech. */}
            if(testTrace!=null)testTrace.text(capturedUtteranceId,"ACTION","playback-duck-transitions:"+player.status().optInt("duckTransitions"),nowMs());
            if(!result.drained&&!cancelled())client.retainPlayedReply(player.playedText());
            try{JSONObject event=player.status().put("firstAudioMs",firstAudioMs).put("firstAudioOrigin","reply_pipeline_started")
                .put("firstAnswerAudioMonotonicMs",firstAnswerAudioMonotonicMs).put("firstAnswerAudioMs",firstAnswerAudioMonotonicMs<0?-1:firstAnswerAudioMonotonicMs-started)
                .put("pipeline",true).put("elapsedMs",nowMs()-started).put("playbackDrained",result.drained).put("acceptedBytes",result.acceptedBytes).put("atMs",System.currentTimeMillis());
                java.io.FileOutputStream out=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-playback-quality.json",android.content.Context.MODE_PRIVATE);
                try{out.write(event.toString().getBytes("UTF-8"));}finally{out.close();}
            }catch(Exception ignored){}finally{try{player.close();}finally{if(duplex!=null)duplex.playback(false);}}
        }
    }
    public int speakStream(String text)throws Exception{
        check();final long started=nowMs(),token=responseToken;firstAudioMs=-1;firstAudioMonotonicMs=-1;
        final OwnerStreamPlayer player=new OwnerStreamPlayer(deadline,new OwnerStreamPlayer.DuckingLifecycle(){
            public boolean ducking(){return duplex!=null&&duplex.provisionalInterruption();}
            public boolean cancelled(){return turnCancelled(token);}
            public void firstAudio(){if(!turnCancelled(token)){if(duplex!=null)duplex.playback(true);playingEmotion=client.lastEmotion();firstAudioMonotonicMs=nowMs();firstAudioMs=firstAudioMonotonicMs-started;state("SPEAKING");}}
        });
        boolean drained=false;int accepted=0;
        try{
            accepted=client.synthesizeStream(text,new MiniMaxVoiceClient.PcmSink(){public void write(byte[] pcm)throws Exception{player.write(pcm);}});
            int played=player.finish();check();if(played!=accepted)throw new IOException("TTS_STREAM_BYTE_MISMATCH");drained=true;
            check();client.commitReply();check();return played;
        }finally{
            try{JSONObject event=player.status().put("firstAudioMs",firstAudioMs).put("elapsedMs",nowMs()-started).put("playbackDrained",drained).put("acceptedBytes",accepted).put("atMs",System.currentTimeMillis());
                java.io.FileOutputStream out=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-playback-quality.json",android.content.Context.MODE_PRIVATE);
                try{out.write(event.toString().getBytes("UTF-8"));}finally{out.close();}
            }catch(Exception ignored){}finally{try{player.close();}finally{if(duplex!=null)duplex.playback(false);}}
        }
    }
    public byte[] synthesize(String text) throws Exception {
        check(); if (!diagnostic) return client.synthesize(text);
        if (diagnosticEcho == null) throw new IOException("DIAGNOSTIC_AUDIO_MISSING");
        return diagnosticEcho.clone();
    }
    public void play(byte[] pcm) throws Exception {
        check(); OwnerVoiceBridge.play(pcm, diagnostic ? 16000 : InteractionPolicy.OUTPUT_RATE, connector, deadline);
        // Short settling interval after the final sample prevents playback tail being the next turn.
        Thread.sleep(350); check();
    }
    public void state(final String phase) {
        OwnerHeadless.captureNotReady(connector,sessionId);
        final long phaseToken=responseToken;
        if(phaseToken!=0&&!phase.equals("ERROR")&&!phase.equals("IDLE")&&turnCancelled(phaseToken))return;
        long now=SystemClock.elapsedRealtime();
        try { if(timings.length()<100) timings.put(new JSONObject().put("phase",previousPhase).put("ms",now-phaseStarted)); }catch(Exception ignored){}
        previousPhase=phase;phaseStarted=now;
        // Controller LISTENING precedes AudioRecord.startRecording: do not falsely light the ready lamp.
        OwnerFeedback.state(phase.equals("LISTENING") ? "PREPARING" : phase);
        // Small phase-only receipts make remote acceptance observable without saving conversations.
        try {
            JSONObject progress = new JSONObject().put("sessionId",sessionId).put("sequence",++progressSequence)
                .put("phase",phase).put("diagnosticOnly",diagnostic).put("captures",captureSummary).put("timings",timings)
                .put("emotion",diagnostic?"neutral":client.lastEmotion()).put("speakingEye",diagnostic?"NEUTRAL":client.lastEye())
                .put("sensitivity",OwnerAudioSettings.sensitivity()).put("audioSettingCommand",settingAction)
                .put("childProfile",ChildCompanionPolicy.CHILD).put("companionNickname",ChildCompanionPolicy.NICKNAME).put("childStoryMode",!diagnostic&&client.childStoryMode()).put("pronunciationCapability",EnglishAdventure.CAPABILITY).put("storyProgress",diagnostic?"DIAGNOSTIC":client.childProgressStatus())
                .put("visualPlanVersion",1).put("eyeCommandsSubmitted",companionEyes==null?0:companionEyes.submitted())
                .put("behaviorProtocol",1).put("lastBehavior",lastBehavior).put("lastBehaviorResult",lastBehaviorResult)
                .put("nextAction",phase.equals("LISTENING")?"WAIT_FOR_USER":phase.equals("SPEAKING")?(endAfterReply()?"END_AFTER_REPLY":"RETURN_TO_LISTENING_AFTER_REPLY"):phase.equals("IDLE")?"WAIT_FOR_ACTIVATION":phase.equals("ERROR")?"RECOVER_OR_REACTIVATE":"PREPARE_REPLY")
                .put("eyeCommandFailures",companionEyes==null?0:companionEyes.failed()).put("lastRequestedEye",companionEyes==null?"":companionEyes.lastPreset())
                .put("streamingTts",!diagnostic).put("firstAudioMs",firstAudioMs).put("firstAudioMonotonicMs",firstAudioMonotonicMs).put("firstAnswerAudioMonotonicMs",firstAnswerAudioMonotonicMs)
                .put("asrProvider",streamingAsr==null||streamingAsrDisabled?"minimax":"qwen-streaming").put("streamingAsrTaskAttempts",streamingAsrAttempts.get()).put("streamingAsrConnectionAttempts",streamingAsrConnections.get())
                .put("asrFallbackReason",asrFallbackReason()).put("asrBackupCalls",asrFailover==null?0:asrFailover.backupCalls())
                .put("asrBackupFailure",asrFailover==null?"":asrFailover.backupFailure()).put("bargeConfirmMs",BargeInConfirmation.REQUIRED_MS)
                .put("bargeConfirmScope","RESPONSE_PREPARATION_AND_PLAYBACK").put("bargeGuardActive",duplex!=null&&duplex.confirmationActive())
                .put("rejectedBargeCandidates",duplex==null?finalRejectedBarges:duplex.rejectedBarges()).put("confirmedBarges",duplex==null?finalConfirmedBarges:duplex.confirmedBarges())
                .put("memoryStatus",memoryStatus).put("autonomousAttempts",initiative.count()).put("initiativeCandidatePrepared",initiative.prepared()).put("legacyDownloadStatus",LegacyDownloadGuard.status()).put("wakeStrategy","original-dsp-button-ir-gsensor")
                .put("activationSource",OwnerHeadless.sessionStatus(sessionId).optString("activationSource","UNKNOWN"))
                .put("continuousInput",!diagnostic).put("responseGeneration",phaseToken).put("lastBargeInMonotonicMs",lastBargeInMs)
                .put("inputEffects",inputEffectStatus).put("lastInputOnset",duplex==null?finalInputOnset:duplex.onsetStatus())
                .put("knowledgeLookup",diagnostic?JSONObject.NULL:client.knowledgeStatus())
                .put("replyTiming",diagnostic?JSONObject.NULL:client.replyTimingStatus())
                .put("ttsTransport",diagnostic?JSONObject.NULL:client.ttsTransportStatus())
                .put("ttsRate",diagnostic?16000:InteractionPolicy.OUTPUT_RATE).put("speechSpeed",InteractionPolicy.SPEECH_SPEED).put("atMs",System.currentTimeMillis());
            android.util.AtomicFile file = new android.util.AtomicFile(new java.io.File(OwnerVoiceBridge.nativeContext().getFilesDir(),"sp001-session-progress.json"));
            java.io.FileOutputStream out = null;
            try { out=file.startWrite();out.write(progress.toString().getBytes("UTF-8"));file.finishWrite(out);out=null; }
            finally { if(out!=null)file.failWrite(out); }
        } catch(Exception ignored) { /* Observability cannot break the voice owner. */ }
        requestVisual(phase.equals("LISTENING")?"PREPARING":phase,phaseToken);
    }
    /** Registered functions run on the current turn owner, never by parsing spoken text. */
    private JSONObject executeMemoryTool(String quote)throws Exception{
        checkTurn();
        boolean saved=false;
        try{
            if(memories!=null){saved=memories.suggest(quote,memoryUser,memoryProfile,memoryRevision,System.currentTimeMillis());
                if(saved)memoryRevision=memories.revision();}
            checkTurn();memoryStatus=saved?"SAVED":"NOT_SAVED";
        }catch(Exception failure){checkTurn();memoryStatus="SAVE_NOT_CONFIRMED";}
        return new JSONObject().put("success",saved).put("saved",saved).put("answer",saved?"这项喜好已经保存到你的长期记忆里。":"这次没有确认保存；我会先按当前聊天继续说。");
    }
    /** Registered functions run on the current turn owner, never by parsing spoken text. */
    private JSONObject executeDeviceTool(String action)throws Exception{
        checkTurn();
        if("get_volume".equals(action)){JSONObject receipt=OwnerAudioSettings.readVolume();checkTurn();OwnerAudioSettings.describeTo(client);return receipt;}
        if(ReplyActionPolicy.family(action).isEmpty())throw new IOException("DEVICE_ACTION_NOT_ALLOWED");
        if(!modelActions.claim(capturedUtteranceId,action,!turnCancelled(responseToken)))
            return new JSONObject().put("success",false).put("changed",false).put("code","DEVICE_ACTION_ALREADY_ATTEMPTED").put("answer","这一轮已处理过这类操作，不会重复执行。");
        executeBehaviorBody(ReplyActionPolicy.decision(action));checkTurn();
        boolean confirmed="SETTING_VERIFIED".equals(lastBehaviorResult)||"NATIVE_REQUEST_SUBMITTED".equals(lastBehaviorResult);
        modelActions.recordResult(capturedUtteranceId,action,confirmed);
        JSONObject receipt=lastDeviceReceipt==null?new JSONObject().put("success",confirmed).put("code",lastBehaviorResult).put("answer",settingAnswer==null?"操作没有确认成功。":settingAnswer).put("renderedVerified",false):new JSONObject(lastDeviceReceipt.toString());
        receipt.put("action",action).put("utteranceId",capturedUtteranceId);
        if(testTrace!=null)testTrace.text(capturedUtteranceId,"ACTION",action+":"+receipt.optString("code"),nowMs());
        settingAnswer=null;settingAction=0;lastDeviceReceipt=null;OwnerAudioSettings.describeTo(client);
        client.confirmedDeviceActions(modelActions.confirmedActions(capturedUtteranceId));
        return receipt;
    }
    private String executeModelSegment(String heard,ReplySegment segment)throws Exception{
        if(client.modelOwnsDialogue()){
            if(!segment.actions.isEmpty())throw new IOException("NATIVE_DEVICE_TOOL_REQUIRED");
            return segment.speech; // Native functions already executed; speech is not another command or classifier input.
        }
        if(segment.actions.isEmpty())return DeviceClaimGuard.sanitize(segment.speech,modelActions.confirmedActions(capturedUtteranceId));
        if(!ReplyActionPolicy.consistent(segment.actions))return "这些操作没有执行，请说一个明确的要求。";
        for(String action:segment.actions)if(!client.modelOwnsDialogue()&&!ReplyActionPolicy.allowed(heard,action))return "这个操作没有确认，我先不改变设置。";
        StringBuilder answer=new StringBuilder();boolean allConfirmed=true;
        for(String action:segment.actions){
            checkTurn();if(!modelActions.claim(capturedUtteranceId,action,!turnCancelled(responseToken))){allConfirmed&=modelActions.wasConfirmed(capturedUtteranceId,action);continue;}
            executeBehaviorBody(ReplyActionPolicy.decision(action));
            allConfirmed&="SETTING_VERIFIED".equals(lastBehaviorResult)||"NATIVE_REQUEST_SUBMITTED".equals(lastBehaviorResult);
            modelActions.recordResult(capturedUtteranceId,action,"SETTING_VERIFIED".equals(lastBehaviorResult)||"NATIVE_REQUEST_SUBMITTED".equals(lastBehaviorResult));
            if(testTrace!=null)testTrace.text(capturedUtteranceId,"ACTION",action+":"+lastBehaviorResult,nowMs());
            if(settingAnswer!=null)answer.append(settingAnswer);
            settingAnswer=null;settingAction=0;
        }
        segment=new ReplySegment(DeviceClaimGuard.sanitize(segment.speech,modelActions.confirmedActions(capturedUtteranceId)),segment.emotion,segment.actions,segment.end,segment.followUp,segment.memoryQuote);
        return segment.afterActions(answer.toString(),allConfirmed);
    }
    private String asrFallbackReason(){
        if(asrFailover!=null&&asrFailover.usingBackup())return asrFailover.primaryFailure();
        if(streamingAsr==null)return "MINIMAX_SELECTED";
        if(!streamingAsrDisabled)return "";
        try{String reason=candidateBudget==null?"ASR_ACCESS_UNAVAILABLE":candidateBudget.unavailableReason(System.currentTimeMillis(),nowMs());return reason.isEmpty()?"STREAMING_ASR_UNAVAILABLE":reason;}
        catch(Exception ignored){return "ASR_AVAILABILITY_CHECK_FAILED";}
    }
    private void executeBehavior(VoiceBehavior.Decision decision)throws Exception{
        checkTurn();if(!behaviorActions.claim(capturedUtteranceId,!turnCancelled(responseToken)))throw new IOException("STALE_BEHAVIOR");
        executeBehaviorBody(decision);
    }
    private void executeBehaviorBody(VoiceBehavior.Decision decision)throws Exception{
        checkTurn();lastDeviceReceipt=null;lastBehavior=decision.kind.name();lastBehaviorResult="ATTEMPTED";recordBehavior();
        try{
            checkTurn();
            switch(decision.kind){
            case AUDIO:
                settingAction=decision.audio;OwnerAudioSettings.Outcome audio=OwnerAudioSettings.applyChecked(settingAction);
                if(duplex!=null)duplex.sensitivity(OwnerAudioSettings.sensitivity());
                lastDeviceReceipt=audio.receipt;settingAnswer=audio.answer;lastBehaviorResult=audio.verified?"SETTING_VERIFIED":audio.code;break;
            case EYE_SMILE:case EYE_SURPRISE:case EYE_NEUTRAL:
                localEye=decision.kind==VoiceBehavior.Kind.EYE_SMILE?"SMILE":decision.kind==VoiceBehavior.Kind.EYE_SURPRISE?"SURPRISED":"NEUTRAL";
                submitActionEye(localEye);settingAnswer=decision.kind==VoiceBehavior.Kind.EYE_SMILE?"看，我的笑眼。":decision.kind==VoiceBehavior.Kind.EYE_SURPRISE?"哇，惊讶脸来了。":"好，恢复普通表情。";
                lastBehaviorResult="NATIVE_REQUEST_SUBMITTED";break;
            case EXPRESSIVE_ON:case EXPRESSIVE_OFF:
                if(behaviorSettings==null)throw new IOException("BEHAVIOR_STORE_UNAVAILABLE");
                boolean enabled=decision.kind==VoiceBehavior.Kind.EXPRESSIVE_ON;behaviorSettings.set("expressive",enabled);expressive=enabled;
                submitActionEye("NEUTRAL");settingAnswer=enabled?"好，我会配合聊天变化表情。":"好，我先保持普通表情。";lastBehaviorResult="SETTING_VERIFIED";break;
            case INITIATIVE_ON:case INITIATIVE_OFF:
                if(behaviorSettings==null)throw new IOException("BEHAVIOR_STORE_UNAVAILABLE");
                boolean initiative=decision.kind==VoiceBehavior.Kind.INITIATIVE_ON;behaviorSettings.set("initiative",initiative);client.companionInitiative(initiative);
                settingAnswer=initiative?"好，聊天时我会偶尔接个有趣的话题。":"好，我直接回答，不主动追加提问。";lastBehaviorResult="SETTING_VERIFIED";break;
            case TOPIC:
                settingAnswer="我们猜个动物：鼻子长长，会用鼻子喷水。你猜是谁？";lastBehaviorResult="LOCAL_REPLY_PREPARED";break;
            case HELP:
                settingAnswer="我能聊天、讲故事、猜谜，还能调音量和收音、变表情。你也可以让我主动接话，或只回答。";lastBehaviorResult="LOCAL_REPLY_PREPARED";break;
            case END:
                localEnd=true;settingAnswer="好，先休息吧，下次再聊。";lastBehaviorResult="END_AFTER_REPLY";break;
            default:throw new IOException("BEHAVIOR_NOT_ALLOWLISTED");
            }
            checkTurn();
        }catch(Exception failure){
            if(cancelled()||interrupted()||Thread.currentThread().isInterrupted())throw failure;
            lastBehaviorResult="ACTION_NOT_CONFIRMED";localEye=null;localEnd=false;
            settingAnswer="这次操作没有确认成功，我不把它当作已完成。";
        }finally{recordBehavior();}
    }
    private void submitActionEye(final String preset)throws Exception{
        final long token=responseToken,revision=visualRevision.incrementAndGet();
        if(companionEyes!=null)companionEyes.invalidate();
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<Exception>();
        if(!ui.post(new Runnable(){public void run(){try{
            if(VISUAL_OWNER.get()!=visualOwner||visualRevision.get()!=revision||turnCancelled(token))throw new IOException("STALE_EYE_ACTION");
            Object eyes=Class.forName("com.smarttoy.embedded.a.b").getMethod("iq").invoke(null);
            Object accepted=eyes.getClass().getMethod("g",String.class,Integer.TYPE).invoke(eyes,preset,Integer.valueOf(1));
            // Stock g() is void and catches internal errors; submission is not a display acknowledgement.
            if(Boolean.FALSE.equals(accepted))throw new IOException("NATIVE_EYE_REJECTED");
            recordVisual(preset,true,"");
        }catch(Exception e){failure.set(e);recordVisual(preset,false,e.getClass().getSimpleName());}finally{done.countDown();}}}))throw new IOException("VISUAL_UI_CLOSED");
        long until=nowMs()+750;
        while(!done.await(20,java.util.concurrent.TimeUnit.MILLISECONDS)){checkTurn();if(nowMs()>=until){visualRevision.incrementAndGet();throw new IOException("EYE_ACTION_TIMEOUT");}}
        checkTurn();if(failure.get()!=null)throw failure.get();
    }
    private void recordBehavior(){
        try{
            JSONObject event=new JSONObject().put("sessionId",sessionId).put("utteranceId",capturedUtteranceId).put("action",lastBehavior).put("result",lastBehaviorResult)
                .put("renderedVerified",false).put("protocol",1).put("atMs",System.currentTimeMillis());
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(OwnerVoiceBridge.nativeContext().getFilesDir(),"sp001-behavior-event.json"));
            java.io.FileOutputStream stream=null;try{stream=file.startWrite();stream.write(event.toString().getBytes("UTF-8"));file.finishWrite(stream);stream=null;}
            finally{if(stream!=null)file.failWrite(stream);}
        }catch(Exception ignored){/* Evidence is bounded and contains no transcript. */}
    }
    private synchronized void requestVisual(final String phase,final long phaseToken){
        final long revision=visualRevision.incrementAndGet();visualPhase=phase;
        try{
            if(companionEyes==null)companionEyes=new CompanionVisualPlayer(
                new CompanionVisualPlayer.Clock(){public long now(){return SystemClock.elapsedRealtime();}},
                new CompanionVisualPlayer.Scheduler(){public void later(Runnable r,long delay){if(!ui.postDelayed(r,delay))throw new IllegalStateException("VISUAL_UI_CLOSED");}},
                new CompanionVisualPlayer.Driver(){public void show(String eye)throws Exception{
                    try{
                        Object eyes=Class.forName("com.smarttoy.embedded.a.b").getMethod("iq").invoke(null);
                        Object accepted=eyes.getClass().getMethod("g",String.class,Integer.TYPE).invoke(eyes,eye,Integer.valueOf(1));
                        if(Boolean.FALSE.equals(accepted))throw new IOException("NATIVE_EYE_REJECTED");
                        recordVisual(eye,true,"");
                    }catch(Exception failure){recordVisual(eye,false,failure.getClass().getSimpleName());throw failure;}
                }});
            companionEyes.phase(CompanionVisualPlan.effectivePhase(phase,expressive,localEye!=null&&phaseToken!=0),diagnostic?"neutral":phase.equals("SPEAKING")?playingEmotion:client.lastEmotion(),new CompanionVisualPlayer.Lease(){public boolean valid(){
                if(VISUAL_OWNER.get()!=visualOwner||visualRevision.get()!=revision)return false;
                if(!phase.equals("IDLE")&&cancelled())return false;
                return phaseToken==0||phase.equals("IDLE")||phase.equals("ERROR")||!turnCancelled(phaseToken);
            }});
        }catch(RuntimeException failure){recordVisual("",false,failure.getClass().getSimpleName());}
    }
    private void recordVisual(String eye,boolean submitted,String errorType){
        try{
            JSONObject event=new JSONObject().put("sessionId",sessionId).put("phase",visualPhase).put("eye",eye)
                .put("commandSubmitted",submitted).put("renderedVerified",false).put("errorType",errorType)
                .put("visualPlanVersion",1).put("atMs",System.currentTimeMillis());
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(OwnerVoiceBridge.nativeContext().getFilesDir(),"sp001-eye-event.json"));
            java.io.FileOutputStream stream=null;
            try{stream=file.startWrite();stream.write(event.toString().getBytes("UTF-8"));file.finishWrite(stream);stream=null;}
            finally{if(stream!=null)file.failWrite(stream);}
        }catch(Exception ignored){/* Optional eye evidence never interrupts audio. */}
    }
    int[] actualApiCounts(){return client==null?new int[]{0,0,0}:new int[]{client.asrCalls+streamingAsrConnections.get(),client.llmCalls,client.ttsCalls};}
    OwnerDuplexCapture captureOwner(){return duplex;}
    public void close() {
        OwnerHeadless.captureNotReady(connector,sessionId);
        closed = true;
        if(testTrace!=null)testTrace.close();
        turns.close();
        behaviorActions.close();modelActions.close();if(companionEyes!=null)companionEyes.invalidate();
        if(streamingAsr!=null)streamingAsr.cancel();
        try{if(duplex!=null){
            try{duplex.close();}
            finally{try{finalInputOnset=duplex.onsetStatus();finalConfirmedBarges=duplex.confirmedBarges();finalRejectedBarges=duplex.rejectedBarges();}catch(Exception ignored){}}
            duplex=null;
        }}
        finally{
            try{stopCapture();}finally{
                OwnerCaptureTap.cancel(captureToken);captureToken=0;
                try{if(client!=null)client.close();}finally{if(diagnosticEcho!=null){java.util.Arrays.fill(diagnosticEcho,(byte)0);diagnosticEcho=null;}}
            }
        }
    }
}
