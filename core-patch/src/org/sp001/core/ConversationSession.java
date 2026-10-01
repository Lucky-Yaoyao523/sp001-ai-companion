package org.sp001.core;

import java.io.IOException;
import java.util.Arrays;

/** The SAME deterministic control flow is used by the Android adapter and offline fake ports.
 * No provider, file, or hardware access without explicitly supplied ports and run().
 */
public final class ConversationSession {
    public interface Ports {
        long nowMs();
        boolean cancelled();
        byte[] capture() throws Exception;
        String transcribe(byte[] pcm) throws Exception;
        String reply(String user) throws Exception;
        byte[] synthesize(String text) throws Exception;
        void play(byte[] pcm) throws Exception;
        void state(String state);
        void close();
    }
    public interface RecoverableTurns {void recoverTurn(String code)throws Exception;}
    public interface RecoverableRecognition extends RecoverableTurns {}
    public interface SessionGuard {String stopReason();}
    public interface LocalReply {boolean hasLocalReply();}
    public interface IdleReply {String idleReply();}
    public interface EndingReply {boolean endAfterReply();}
    /** Native model paths own language intent; the controller only executes their result. */
    public interface ModelDirectedReply {boolean modelOwnsDialogue();}
    public interface ReplyOutputBudget {int replyOutputLimit();}
    public interface PipelinedPorts {boolean pipelineEnabled();int replyAndSpeak(String heard)throws Exception;}
    public interface StreamingPorts {boolean streamingSpeechEnabled();int speakStream(String text)throws Exception;}
    public interface Interruptible {boolean interrupted();void checkTurn()throws IOException;void endResponse()throws IOException;void finishTurn();}
    public static final int DEFAULT_TURNS = 60;
    public static final long DEFAULT_MS = 600000;
    public static final class Result {
        public String code = "NOT_STARTED";
        public int completedTurns, interruptedTurns, autonomousTurns, autonomousInterruptedTurns, capturedBytes, outputBytes, asrCalls, llmCalls, ttsCalls;
        public boolean playbackDrained;
        public int asrRecoveries;
        public int transportRecoveries;
        public int replyRecoveries;
        public String lastTurnError="";
        public String resultBeforeClose="",closeError="";
    }
    private final Ports ports;
    private final int maxTurns;
    private final long duration;
    private long deadline;
    private boolean started;
    public ConversationSession(Ports p, int turns, long milliseconds) {
        if (p == null || turns < 1 || turns > DEFAULT_TURNS || milliseconds < 1000 || milliseconds > DEFAULT_MS)
            throw new IllegalArgumentException("SESSION_BOUNDS");
        ports = p; maxTurns = turns; duration = milliseconds;
    }
    private void check() throws IOException {
        if (ports.cancelled() || Thread.currentThread().isInterrupted()) throw new IOException("TURN_CANCELLED");
        if (ports.nowMs() >= deadline) throw new IOException("SESSION_TIME_LIMIT");
        if(ports instanceof Interruptible)((Interruptible)ports).checkTurn();
    }
    private void endResponse()throws IOException{if(ports instanceof Interruptible)((Interruptible)ports).endResponse();}
    private void state(String value) { try { ports.state(value); } catch (RuntimeException ignored) { /* Presentation is optional. */ } }
    public Result run() {
        if (started) throw new IllegalStateException("SESSION_ALREADY_RUN");
        started = true; deadline = ports.nowMs() + duration;
        Result r = new Result(); byte[] raw = null, input = null, speech = null;
        int consecutiveUnclearInputs=0;boolean previousTransportFailure=false;
        try {
            for (int turn = 0; turn < maxTurns; turn++) {
              boolean replyAttempted=false,asrAttempted=false,proactive=false;
              try {
                check();
                if(ports instanceof SessionGuard){
                    String reason=((SessionGuard)ports).stopReason();
                    if(reason!=null&&!"ALLOW".equals(reason)){
                        if(!reason.matches("[A-Z_]{1,40}"))throw new IOException("PARENT_GUARD_INVALID");
                        r.code="PARENT_"+reason;break;
                    }
                }
                state("LISTENING"); check();
                raw = ports.capture(); check();
                if (raw == null) throw new IOException("CAPTURE_MISSING");
                if(raw.length==0){
                    String idle=ports instanceof IdleReply?((IdleReply)ports).idleReply():null;
                    if(idle==null||idle.isEmpty()){r.code="SESSION_SILENCE";break;}
                    proactive=true;
                    if(!(ports instanceof StreamingPorts))throw new IOException("INITIATIVE_STREAM_REQUIRED");
                    check();state("SYNTHESIZING");r.ttsCalls++;r.playbackDrained=false;
                    int written=((StreamingPorts)ports).speakStream(idle);check();
                    if(written<2||written>3840000||(written&1)!=0)throw new IOException("OUTPUT_PCM_BOUND");
                    r.playbackDrained=true;r.outputBytes+=written;r.autonomousTurns++;r.code="SESSION_TURN_LIMIT";continue;
                }
                r.capturedBytes += raw.length;
                input = MiniMaxCodec.prepareInput(raw);
                state("TRANSCRIBING"); check(); r.asrCalls++;asrAttempted=true;
                String heard;
                try { heard = ports.transcribe(input); }
                catch (IOException unclear) {
                    // Only this ASR stage may recover. Never reuse a partial transcript,
                    // repeat the same upload, or turn authorization/transport errors into speech.
                    // A second consecutive incomplete utterance remains an explicit failure.
                    check();
                    boolean unavailable="ASR_BACKUP_UNAVAILABLE".equals(unclear.getMessage())&&ports instanceof RecoverableRecognition;
                    if (("ASR_INCOMPLETE_TRANSCRIPT".equals(unclear.getMessage())||unavailable) && consecutiveUnclearInputs++ == 0) {
                        r.asrRecoveries++; r.code=unclear.getMessage(); state("MISHEARD");
                        r.lastTurnError=unclear.getMessage();
                        if(ports instanceof RecoverableRecognition){
                            ((RecoverableRecognition)ports).recoverTurn(unclear.getMessage());check();
                        }
                        continue; // finally wipes PCM and releases this response generation.
                    }
                    throw unclear;
                }
                check(); consecutiveUnclearInputs=0;asrAttempted=false;
                if (heard == null || heard.length() > 1000) throw new IOException("ASR_TEXT_INVALID");
                if (heard.trim().length() == 0) { r.code = "SESSION_SILENCE"; break; }
                boolean modelDirected=ports instanceof ModelDirectedReply&&((ModelDirectedReply)ports).modelOwnsDialogue();
                if (!modelDirected&&MiniMaxCodec.stopCommand(heard)) { endResponse();r.code = "SESSION_ENDED"; break; }
                boolean departing=!modelDirected&&ClosingIntent.afterReply(heard);
                state("THINKING"); check();replyAttempted=true;
                if(ports instanceof PipelinedPorts&&((PipelinedPorts)ports).pipelineEnabled()&&
                        (!(ports instanceof LocalReply)||!((LocalReply)ports).hasLocalReply())){
                    r.llmCalls++;r.ttsCalls++;r.playbackDrained=false;
                    int maximum=ports instanceof ReplyOutputBudget?((ReplyOutputBudget)ports).replyOutputLimit():3840000;
                    if(maximum<3840000||maximum>12800000)throw new IOException("OUTPUT_BUDGET_BOUND");
                    int written=((PipelinedPorts)ports).replyAndSpeak(heard);check();
                    if(written<2||written>maximum||written%2!=0)throw new IOException("OUTPUT_PCM_BOUND");
                    r.playbackDrained=true;r.outputBytes+=written;r.completedTurns++;previousTransportFailure=false;
                    Arrays.fill(raw,(byte)0);raw=null;Arrays.fill(input,(byte)0);input=null;
                    if(departing||(ports instanceof EndingReply&&((EndingReply)ports).endAfterReply())){endResponse();r.code="SESSION_ENDED_BY_REPLY";break;}
                    r.code="SESSION_TURN_LIMIT";continue;
                }
                if(!(ports instanceof LocalReply)||!((LocalReply)ports).hasLocalReply())r.llmCalls++;
                String answer = MiniMaxCodec.speech(ports.reply(heard)); check();
                state("SYNTHESIZING"); check(); r.ttsCalls++;
                if(ports instanceof StreamingPorts&&((StreamingPorts)ports).streamingSpeechEnabled()){
                    r.playbackDrained=false;int written=((StreamingPorts)ports).speakStream(answer);check();
                    if(written<2||written>3840000||written%2!=0)throw new IOException("OUTPUT_PCM_BOUND");
                    r.playbackDrained=true;r.outputBytes+=written;r.completedTurns++;previousTransportFailure=false;
                    Arrays.fill(raw,(byte)0);raw=null;Arrays.fill(input,(byte)0);input=null;
                    if(departing||(ports instanceof EndingReply&&((EndingReply)ports).endAfterReply())){endResponse();r.code="SESSION_ENDED_BY_REPLY";break;}
                    r.code="SESSION_TURN_LIMIT";continue;
                }
                speech = ports.synthesize(answer); check();
                if (speech == null || speech.length < 2 || speech.length > 1920000 || speech.length % 2 != 0)
                    throw new IOException("OUTPUT_PCM_BOUND");
                r.playbackDrained = false; state("SPEAKING"); check();
                ports.play(speech); check();
                r.playbackDrained = true; r.outputBytes += speech.length; r.completedTurns++;previousTransportFailure=false;
                Arrays.fill(raw, (byte)0); raw = null;
                Arrays.fill(input, (byte)0); input = null;
                Arrays.fill(speech, (byte)0); speech = null;
                if(departing||(ports instanceof EndingReply&&((EndingReply)ports).endAfterReply())){endResponse();r.code="SESSION_ENDED_BY_REPLY";break;}
                r.code = "SESSION_TURN_LIMIT";
              } catch(Exception failure) {
                if(ports instanceof Interruptible&&!ports.cancelled()&&!Thread.currentThread().isInterrupted()&&((Interruptible)ports).interrupted()){
                    if(proactive)r.autonomousInterruptedTurns++;else r.interruptedTurns++;
                    r.playbackDrained=false;r.code="SESSION_TURN_LIMIT";
                }else if((replyAttempted||(asrAttempted&&ports instanceof RecoverableRecognition))&&failure instanceof IOException&&failure.getSuppressed().length==0&&"HTTP_TRANSFER_FAILED".equals(failure.getMessage())&&
                         ports instanceof RecoverableTurns&&!ports.cancelled()&&!Thread.currentThread().isInterrupted()&&
                         ports.nowMs()<deadline&&r.transportRecoveries<2&&!previousTransportFailure){
                    check();((RecoverableTurns)ports).recoverTurn("HTTP_TRANSFER_FAILED");check();
                    r.transportRecoveries++;previousTransportFailure=true;r.lastTurnError="HTTP_TRANSFER_FAILED";
                    r.playbackDrained=false;r.code="HTTP_TRANSFER_FAILED";state("TURN_FAILED");
                    // No retransmission: finally cleans this generation, then NEW input.
                }else if(replyAttempted&&failure instanceof IOException&&failure.getSuppressed().length==0&&
                         "NATIVE_REPLY_ENVELOPE_REQUIRED".equals(failure.getMessage())&&
                         ports instanceof RecoverableTurns&&!ports.cancelled()&&!Thread.currentThread().isInterrupted()&&
                         ports.nowMs()<deadline&&r.replyRecoveries==0){
                    // The already-played prefix is not a completed answer. Keep the
                    // conversation open for one fresh utterance, without replaying it.
                    check();((RecoverableTurns)ports).recoverTurn("NATIVE_REPLY_ENVELOPE_REQUIRED");check();
                    r.replyRecoveries++;r.lastTurnError="NATIVE_REPLY_ENVELOPE_REQUIRED";
                    r.playbackDrained=false;r.code="NATIVE_REPLY_ENVELOPE_REQUIRED";state("TURN_FAILED");
                }else throw failure;
              } finally {
                if(raw!=null){Arrays.fill(raw,(byte)0);raw=null;}if(input!=null){Arrays.fill(input,(byte)0);input=null;}if(speech!=null){Arrays.fill(speech,(byte)0);speech=null;}
                if(ports instanceof Interruptible)((Interruptible)ports).finishTurn();
              }
            }
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            String m = cause.getMessage();
            String failureType=cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]","").toUpperCase(java.util.Locale.ROOT);
            r.lastTurnError=m!=null&&m.matches("[A-Z0-9_]{1,80}")?m:"RUNTIME_"+failureType.substring(0,Math.min(64,failureType.length()));
            r.code = ports.cancelled() || Thread.currentThread().isInterrupted() ? "TURN_CANCELLED" :
                    m != null && m.matches("[A-Z0-9_]{1,80}") ? m : "SESSION_FAILED";
            state("ERROR");
        } finally {
            if (raw != null) Arrays.fill(raw, (byte)0);
            if (input != null) Arrays.fill(input, (byte)0);
            if (speech != null) Arrays.fill(speech, (byte)0);
            boolean released=true;
            r.resultBeforeClose=r.code;
            try { ports.close(); } catch (RuntimeException failure) {
                released=false;r.code="SESSION_CLOSE_FAILED";
                String message=failure.getMessage();
                r.closeError=message!=null&&message.matches("[A-Z0-9_]{1,80}")?message:"CLOSE_RUNTIME_ERROR";
            }
            state(released?"IDLE":"ERROR");
        }
        return r;
    }
}
