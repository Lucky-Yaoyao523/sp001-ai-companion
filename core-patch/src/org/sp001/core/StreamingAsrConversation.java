package org.sp001.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;

/** Bounded controller for single-utterance sessions. Microphone callbacks only claim
 * an already prepared session and copy PCM; they never create a thread or transport.
 * An unavailable standby fails that complete utterance: no late attachment/partial replay.
 */
public final class StreamingAsrConversation implements ContinuousSpeechInput.EndpointStream, Closeable {
    public interface SessionFactory { StreamingAsrSession create(String taskId)throws IOException; }
    public interface BudgetedFactory extends SessionFactory { boolean canCreate()throws IOException; }
    public static final int MAX_SESSIONS=2;
    public static final long LIFETIME_MS=600000;
    private final SessionFactory factory;
    private final ArrayList<StreamingAsrSession> owned=new ArrayList<StreamingAsrSession>(MAX_SESSIONS);
    private StreamingAsrSession standby;
    private Turn active;
    private boolean prepared,creating,stopping,controllerExited=true;
    private String failure,deferredStandbyFailure;
    private static final class Turn {
        final long id;final StreamingAsrSession session;String failure;
        Turn(long id,StreamingAsrSession session){this.id=id;this.session=session;}
    }
    public StreamingAsrConversation(SessionFactory factory){
        if(factory==null)throw new IllegalArgumentException("ASR_FACTORY");this.factory=factory;
    }
    private static long now(){return System.nanoTime()/1000000L;}
    public synchronized void prepare(){
        if(prepared||stopping)return;prepared=true;controllerExited=false;
        Thread controller=new Thread(new Runnable(){public void run(){control();}},"sp001-asr-controller");controller.setDaemon(true);
        try{controller.start();}catch(RuntimeException error){controllerExited=true;stop("ASR_CONTROLLER_START_FAILED");}
    }
    public synchronized void started(long id){
        if(stopping)return;
        if(id<=0){stop("ASR_UTTERANCE_ID");return;}
        if(active!=null&&id<=active.id)return;
        if(active!=null&&active.session!=null)active.session.cancel();
        StreamingAsrSession available=standby;standby=null;
        active=new Turn(id,available);
        if(available==null){active.failure=deferredStandbyFailure==null?"ASR_NOT_PREPARED":deferredStandbyFailure;if(deferredStandbyFailure!=null)stop(deferredStandbyFailure);}
        else if(available.workerExited()||available.failureCode()!=null){active.failure="ASR_STANDBY_EXPIRED";available.cancel();stop("ASR_STANDBY_EXPIRED");}
        else available.started(id);
        notifyAll();
    }
    public synchronized void audio(long id,byte[] pcm,int offset,int count){
        if(stopping||active==null||active.id!=id||active.failure!=null||active.session==null)return;
        active.session.audio(id,pcm,offset,count);notifyAll();
    }
    public synchronized boolean finalCandidateStable(long id,int holdMs){
        return !stopping&&failure==null&&active!=null&&active.id==id&&active.failure==null&&
            active.session!=null&&active.session.finalCandidateStable(id,holdMs);
    }
    public synchronized void finished(long id){
        if(stopping||active==null||active.id!=id||active.failure!=null||active.session==null)return;
        active.session.finished(id);notifyAll();
    }
    public String awaitResult(long id,long timeoutMs)throws IOException{
        if(timeoutMs<=0||timeoutMs>StreamingAsrSession.LIFETIME_MS)throw new IOException("ASR_WAIT_BOUND");
        long deadline=now()+timeoutMs;StreamingAsrSession selected;
        synchronized(this){
            for(;;){
                String error=failureCode(id);if(error!=null)throw new IOException(error);
                selected=active.session;
                if(selected.resultReady()||selected.workerExited())break;
                long left=deadline-now();if(left<=0){active.failure="ASR_RESULT_WAIT_TIMEOUT";selected.cancel();notifyAll();throw new IOException(active.failure);}
                try{wait(Math.min(left,10));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();active.failure="ASR_WAIT_INTERRUPTED";selected.cancel();throw new IOException(active.failure);}
            }
        }
        // Already has complete text or is terminal: never wait for cleanup to deliver text.
        String text;
        try{text=selected.awaitResult(id,1);}catch(IOException error){synchronized(this){if(active==null||active.id!=id||active.session!=selected)throw new IOException("ASR_TURN_SUPERSEDED");}throw error;}
        synchronized(this){String error=failureCode(id);if(error!=null)throw new IOException(error);if(active.session!=selected)throw new IOException("ASR_TURN_SUPERSEDED");return text;}
    }
    public synchronized String failureCode(){
        if(failure!=null)return failure;
        if(stopping)return "ASR_CONVERSATION_CLOSED";
        if(active==null)return null;
        return active.failure!=null?active.failure:active.session==null?"ASR_NOT_PREPARED":active.session.failureCode();
    }
    public synchronized String failureCode(long id){
        if(active==null)return failure==null?"ASR_TURN_UNKNOWN":failure;
        if(active.id!=id)return "ASR_TURN_SUPERSEDED";
        return failureCode();
    }
    public synchronized void cancel(){stop(null);}
    public void close(){cancel();}
    private synchronized void stop(String code){
        if(failure==null&&code!=null)failure=code;
        stopping=true;standby=null;
        for(StreamingAsrSession session:owned)session.cancel();
        notifyAll();
    }
    /** Does not equate cancellation requests with release. Also counts a reserved create slot. */
    public synchronized boolean released(){
        if(!stopping||!controllerExited||creating)return false;
        for(StreamingAsrSession session:owned)if(!session.workerExited())return false;
        return true;
    }
    public synchronized boolean awaitRelease(long timeoutMs){
        if(timeoutMs<0||timeoutMs>60000)throw new IllegalArgumentException("ASR_RELEASE_WAIT_BOUND");
        long deadline=now()+timeoutMs;
        while(!released()){
            long left=deadline-now();if(left<=0)return false;
            try{wait(Math.min(left,10));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        }return true;
    }
    // Package-private observation seams for deterministic offline tests, never network actions.
    synchronized boolean standbyPrepared(){return standby!=null&&!stopping;}
    synchronized int occupiedSlots(){return owned.size()+(creating?1:0);}
    private void control(){
        long beginning=now();
        try{
            for(;;){
                boolean create=false;
                synchronized(this){
                    if(stopping)break;
                    if(now()-beginning>=LIFETIME_MS){stop("ASR_CONVERSATION_DEADLINE");break;}
                    if(standby!=null&&(standby.workerExited()||standby.failureCode()!=null)){
                        // Speculation is optional. Its failure must not cancel a healthy
                        // claimed task or erase an already completed transcript.
                        // Keep the failed slot as a sentinel: no auto-reconnect and no
                        // budget refill. The next explicit onset fails through started().
                        boolean currentHealthy=active!=null&&active.failure==null&&active.session!=null&&active.session.failureCode()==null;
                        if(!currentHealthy){stop("ASR_STANDBY_EXPIRED");break;}
                    }
                    for(Iterator<StreamingAsrSession> it=owned.iterator();it.hasNext();)if(it.next().workerExited())it.remove();
                    if(standby==null&&deferredStandbyFailure==null&&!creating&&owned.size()<MAX_SESSIONS){creating=true;create=true;}
                }
                if(create){
                    StreamingAsrSession session=null;
                    try{
                        if(factory instanceof BudgetedFactory&&!((BudgetedFactory)factory).canCreate()){
                            synchronized(this){creating=false;notifyAll();}
                            try{Thread.sleep(10);}catch(InterruptedException e){stop("ASR_CONTROLLER_INTERRUPTED");break;}
                            continue;
                        }
                        session=factory.create(UUID.randomUUID().toString());if(session==null)throw new IOException("ASR_SESSION_MISSING");
                        synchronized(this){creating=false;owned.add(session);if(stopping)session.cancel();}
                        // Keep the initial low-latency preconnect. Subsequent slots prepare
                        // a worker only; their socket starts with actual speech, not during TTS.
                        // Thread creation remains outside the microphone's manager lock.
                        boolean following; synchronized(this){following=active!=null;}
                        if(!isStopping()){if(following)session.prepareOnClaim();else session.prepare();}
                        synchronized(this){if(stopping)session.cancel();else standby=session;notifyAll();}
                    }catch(Exception error){synchronized(this){
                        creating=false;if(session!=null&&!owned.contains(session))owned.add(session);if(session!=null)session.cancel();
                        boolean currentHealthy=active!=null&&active.failure==null&&active.session!=null&&active.session.failureCode()==null;
                        if(currentHealthy)deferredStandbyFailure="ASR_SESSION_CREATE_FAILED";
                        else stop("ASR_SESSION_CREATE_FAILED");notifyAll();
                    }if(isStopping())break;}
                }
                try{Thread.sleep(10);}catch(InterruptedException interrupted){stop("ASR_CONTROLLER_INTERRUPTED");break;}
            }
        }catch(RuntimeException error){stop("ASR_CONTROLLER_FAILED");}
        finally{synchronized(this){creating=false;for(StreamingAsrSession session:owned)session.cancel();controllerExited=true;notifyAll();}}
    }
    private synchronized boolean isStopping(){return stopping;}
}
