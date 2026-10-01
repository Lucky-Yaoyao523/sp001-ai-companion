package org.sp001.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** One utterance, one provider task, one worker. Wire methods MUST be nonblocking:
 * open schedules async connection, sends only enqueue, queuedBytes reads memory.
 * JSON, immutable task-id validation, TLS and actual network belong to Wire.
 * No Wire method is called by the microphone thread or a provider callback.
 */
public final class StreamingAsrSession implements ContinuousSpeechInput.Stream, Closeable {
    public interface WireFactory { Wire create(String taskId)throws IOException; }
    /** Async transports retain their slot until callbacks and executor termination confirm release. */
    public interface ReleasingWire extends Wire { boolean releaseConfirmed(); }
    public interface Wire {
        void open(Listener listener)throws IOException;
        boolean startTask()throws IOException;
        boolean sendAudio(byte[] pcm,int offset,int count)throws IOException;
        boolean finishTask()throws IOException;
        long queuedBytes()throws IOException;
        void cancel();
    }
    public interface Listener {
        void opened(); void taskStarted();
        void result(int sentenceId,String text,boolean isFinal,boolean heartbeat);
        void taskFinished(); void failed(String code); void closed();
    }
    // The 60s budget starts at local onset, not while a standby waits through TTS.
    // Unclaimed sessions are bounded by the owning conversation's 600s lifetime.
    public static final long SETUP_TIMEOUT_MS=8000, RESULT_TIMEOUT_MS=8000, LIFETIME_MS=60000;
    public static final long MAX_WIRE_BYTES=32000;
    private final String taskId;
    private final WireFactory factory;
    private final StreamingAsrTranscript transcript;
    private StreamingPcmQueue queue;
    private long utterance,claimedAt;
    private long lastContentEventAt=-1,lastWireObservationAt=-1,observedWireBytes=Long.MAX_VALUE;
    private boolean prepared,opened,startSent,taskStarted,finishSent,terminal,cancelled,exited,connectOnClaim;
    private String failure,completed,cleanupFailure;
    private Thread worker;

    public StreamingAsrSession(String taskId,WireFactory factory){
        if(factory==null)throw new IllegalArgumentException("ASR_FACTORY");
        this.transcript=new StreamingAsrTranscript(taskId);this.taskId=taskId;this.factory=factory;
    }
    private static long now(){return System.nanoTime()/1000000L;}
    /** Explicit setup; construction and microphone callbacks never create a transport. */
    public synchronized void prepare(){prepare(false);}
    /** Reserve the next utterance worker, but do not leave an unused socket open through playback. */
    synchronized void prepareOnClaim(){prepare(true);}
    private synchronized void prepare(boolean deferred){
        if(prepared||terminal||cancelled)return;
        connectOnClaim=deferred;prepared=true;worker=new Thread(new Runnable(){public void run(){work();}},"sp001-streaming-asr");worker.setDaemon(true);worker.start();
    }
    public synchronized void started(long id){
        if(terminal||cancelled)return;
        if(id<=0){fail("ASR_UTTERANCE_ID");return;}
        if(utterance!=0){if(id!=utterance)fail("ASR_UTTERANCE_CHANGED");return;}
        utterance=id;claimedAt=now();queue=new StreamingPcmQueue(id);notifyAll();
    }
    public synchronized void audio(long id,byte[] pcm,int offset,int count){
        if(terminal||cancelled||queue==null||id!=utterance)return;
        try{if(!queue.offer(id,pcm,offset,count))fail(queue.state()==StreamingPcmQueue.State.OVERFLOW?"ASR_INPUT_OVERFLOW":"ASR_INPUT_SEALED");}
        catch(RuntimeException invalid){fail("ASR_PCM_INVALID");}notifyAll();
    }
    public synchronized void finished(long id){
        if(terminal||cancelled||queue==null||id!=utterance)return;
        if(!queue.finish(id))fail("ASR_INPUT_SEAL_FAILED");notifyAll();
    }
    /** Caller must be the conversation consumer, never the microphone producer. */
    public synchronized String awaitResult(long id,long timeoutMs)throws IOException{
        if(id<=0||id!=utterance)throw new IOException("ASR_UTTERANCE_MISMATCH");
        if(timeoutMs<=0||timeoutMs>LIFETIME_MS)throw new IOException("ASR_WAIT_BOUND");
        long deadline=now()+timeoutMs;
        while(completed==null&&!exited&&failure==null&&!cancelled){
            long remaining=deadline-now();if(remaining<=0){fail("ASR_RESULT_WAIT_TIMEOUT");break;}
            try{wait(Math.min(remaining,25));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();cancel();throw new IOException("ASR_WAIT_INTERRUPTED");}
        }
        if(failure!=null)throw new IOException(failure);
        if(cancelled)throw new IOException("ASR_CANCELLED");
        if(completed==null)throw new IOException("ASR_NOT_COMPLETE");return completed;
    }
    /** Nonblocking read from the capture owner. Stable finals alone never close a local turn. */
    synchronized boolean finalCandidateStable(long id,int holdMs){
        long tick=now();
        return holdMs>=1200&&holdMs<=3000&&id==utterance&&active()&&taskStarted&&!finishSent&&
            lastContentEventAt>=0&&tick-lastContentEventAt>=holdMs&&transcript.finalCandidate()&&
            queue!=null&&queue.state()==StreamingPcmQueue.State.OPEN&&queue.bufferedBytes()<=3200&&
            lastWireObservationAt>=0&&tick-lastWireObservationAt<=250&&observedWireBytes<=3200;
    }
    public synchronized String failureCode(){return failure;}
    public synchronized String cleanupFailureCode(){return cleanupFailure;}
    /** Complete text can be consumed while transport cleanup still owns its resource slot. */
    public synchronized boolean resultReady(){return completed!=null&&failure==null&&!cancelled;}
    public synchronized boolean workerExited(){return exited;}
    public synchronized void cancel(){
        if(cancelled)return;cancelled=true;terminal=true;completed=null;
        if(queue!=null)queue.cancel();transcript.cancel();notifyAll();
        if(worker!=null)worker.interrupt();else exited=true;
    }
    public synchronized void close(){if(resultReady()){terminal=true;return;}cancel();}
    private synchronized void fail(String code){
        if(failure==null&&!cancelled){failure=code;terminal=true;completed=null;if(queue!=null)queue.cancel();transcript.cancel();notifyAll();}
    }
    private synchronized boolean active(){return !terminal&&!cancelled&&failure==null;}
    private synchronized void transcriptFailure(){if(transcript.failureCode()!=null)fail(transcript.failureCode());}
    private final Listener listener=new Listener(){
        public void opened(){synchronized(StreamingAsrSession.this){if(!active())return;if(opened){fail("ASR_DUPLICATE_OPEN");return;}opened=true;notifyWaiters();}}
        public void taskStarted(){synchronized(StreamingAsrSession.this){if(!active())return;if(!startSent||taskStarted){fail("ASR_TASK_START_ORDER");return;}taskStarted=true;notifyWaiters();}}
        public void result(int sentenceId,String text,boolean isFinal,boolean heartbeat){synchronized(StreamingAsrSession.this){
            if(!active())return;if(!taskStarted){fail("ASR_RESULT_BEFORE_START");return;}
            long revision=transcript.contentRevision();
            boolean accepted=transcript.result(taskId,sentenceId,text,isFinal,heartbeat);
            if(accepted&&!heartbeat&&transcript.contentRevision()!=revision)lastContentEventAt=now();
            transcriptFailure();notifyWaiters();
        }}
        public void taskFinished(){synchronized(StreamingAsrSession.this){if(!active())return;
            if(!finishSent){fail("ASR_FINISHED_BEFORE_INPUT_END");return;}
            transcript.taskFinished(taskId);transcriptFailure();notifyWaiters();
        }}
        public void failed(String code){synchronized(StreamingAsrSession.this){if(!active())return;
            if(code!=null&&code.matches("ASR_(?:HTTP_[45][0-9]{2}|UNEXPECTED_BINARY|TRANSPORT_FAILED|EVENT_BOUND|TASK_ID_MISMATCH|TASK_FAILED|UNKNOWN_EVENT|EVENT_INVALID)")){fail(code);return;}
            transcript.taskFailed(taskId,code);String safe=transcript.failureCode();fail(safe==null?"ASR_TRANSPORT_FAILED":safe);
        }}
        public void closed(){synchronized(StreamingAsrSession.this){if(!active())return;if(!transcript.ready())fail("ASR_TRANSPORT_CLOSED");notifyWaiters();}}
    };
    private synchronized void notifyWaiters(){notifyAll();}

    private void work(){
        Wire wire=null;long begun=now(),runAt=-1,finishAt=-1;
        try{
            synchronized(this){while(active()&&connectOnClaim&&utterance==0)wait(25);}
            // PCM is queued from the original onset while this existing worker connects.
            // Setup time starts now, not at reservation before a long reply.
            begun=now();
            if(!active())return;wire=factory.create(taskId);if(wire==null)throw new IOException("ASR_WIRE_MISSING");
            if(!active())return;wire.open(listener);
            while(active()){
                long tick=now();
                boolean shouldStart=false,canSend=false;StreamingPcmQueue input=null;
                synchronized(this){
                    if(utterance!=0&&tick-claimedAt>=LIFETIME_MS){fail("ASR_UTTERANCE_TIMEOUT");break;}
                    if(!opened&&tick-begun>=SETUP_TIMEOUT_MS){fail("ASR_OPEN_TIMEOUT");break;}
                    if(opened&&utterance!=0&&!startSent){startSent=true;shouldStart=true;runAt=tick;}
                    if(startSent&&!taskStarted&&runAt>=0&&tick-runAt>=SETUP_TIMEOUT_MS){fail("ASR_TASK_START_TIMEOUT");break;}
                    if(finishSent&&finishAt>=0&&tick-finishAt>=RESULT_TIMEOUT_MS&&!transcript.ready()){fail("ASR_PROVIDER_RESULT_TIMEOUT");break;}
                    if(transcript.ready()){completed=transcript.text();terminal=true;notifyAll();break;}
                    canSend=taskStarted&&!finishSent;input=queue;
                }
                if(shouldStart){if(!active())break;if(!wire.startTask()){fail("ASR_RUN_SEND_FAILED");break;}}
                if(!active())break;
                long queued=wire.queuedBytes();if(queued<0||queued>MAX_WIRE_BYTES){fail("ASR_WIRE_QUEUE_BOUND");break;}
                synchronized(this){lastWireObservationAt=tick;observedWireBytes=queued;}
                if(canSend&&input!=null){
                    // Reserve room before removing an owned block from the input queue.
                    if(queued<=MAX_WIRE_BYTES-StreamingPcmQueue.MAX_POLL_BYTES){
                        StreamingPcmQueue.Poll item=input.poll(StreamingPcmQueue.MAX_POLL_BYTES);
                        if(item.state==StreamingPcmQueue.State.OVERFLOW){fail("ASR_INPUT_OVERFLOW");break;}
                        if(item.state==StreamingPcmQueue.State.CANCELLED)break;
                        if(item.pcm.length>0){
                            try{if(!active())break;if(!wire.sendAudio(item.pcm,0,item.pcm.length)){fail("ASR_AUDIO_SEND_FAILED");break;}}
                            finally{Arrays.fill(item.pcm,(byte)0);}
                        }else if(item.state==StreamingPcmQueue.State.FINISHED&&queued==0){
                            synchronized(this){if(!active())break;if(!transcript.inputFinished()){fail("ASR_LOCAL_FINISH_ORDER");break;}finishSent=true;finishAt=now();}
                            if(!active())break;if(!wire.finishTask()){fail("ASR_FINISH_SEND_FAILED");break;}
                        }
                    }
                }
                try{Thread.sleep(10);}catch(InterruptedException interrupted){if(active())fail("ASR_WORKER_INTERRUPTED");break;}
            }
        }catch(Exception error){fail("ASR_WORKER_FAILED");}
        finally{
            boolean cleanupThrew=false;
            if(wire!=null)try{wire.cancel();}catch(RuntimeException error){cleanupThrew=true;synchronized(this){cleanupFailure="ASR_CLEANUP_FAILED";notifyAll();}}
            // Result delivery is independent of this quarantine. Never turn an async
            // cancellation request or an interrupted wait into a resource-release claim.
            if(wire instanceof ReleasingWire||cleanupThrew){
                for(;;){
                    boolean confirmed=false;
                    if(wire instanceof ReleasingWire)try{confirmed=((ReleasingWire)wire).releaseConfirmed();}
                    catch(RuntimeException error){synchronized(this){cleanupFailure="ASR_RELEASE_CHECK_FAILED";notifyAll();}}
                    if(confirmed)break;
                    try{Thread.sleep(10);}catch(InterruptedException ignored){/* retain slot until actual confirmation */}
                }
            }
            synchronized(this){if(queue!=null)queue.cancel();transcript.close();terminal=true;exited=true;notifyAll();}
        }
    }
}
