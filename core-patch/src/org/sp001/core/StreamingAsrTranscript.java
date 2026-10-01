package org.sp001.core;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** One immutable provider task. Transport owns JSON parsing, deadlines and cancellation.
 * A server sentence boundary is not the end of the user's turn.
 * Successful normal close preserves the completed result; cancellation invalidates it.
 */
public final class StreamingAsrTranscript {
    public static final int MAX_EVENTS=512, MAX_SENTENCES=32, MAX_TEXT=4096, MAX_RECEIVED_TEXT=65536;
    private final String taskId;
    private final TreeMap<Integer,Sentence> sentences=new TreeMap<Integer,Sentence>();
    private int events,retainedCharacters,receivedCharacters;
    private long contentRevision;
    private boolean inputDone,taskDone,closed,cancelled;
    private String failure;
    private static final class Sentence {
        final String text; final boolean complete;
        Sentence(String text,boolean complete){this.text=text;this.complete=complete;}
    }

    public StreamingAsrTranscript(String taskId){
        if(taskId==null||!taskId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("ASR_TASK_ID");
        this.taskId=taskId;
    }
    private boolean accepts(String id){return taskId.equals(id)&&!closed&&!cancelled&&failure==null&&!taskDone;}
    private boolean count(){if(++events>MAX_EVENTS){fail("ASR_EVENT_BOUND");return false;}return true;}
    private void fail(String code){if(failure==null)failure=code;sentences.clear();retainedCharacters=0;}

    /** Returns whether the callback was accepted, never whether the user turn is ready.
     * Repeated/updated finals replace the same sentence; a stale partial cannot undo a final.
     * Heartbeats and provider stash previews do not create sentences (do not pass stash here).
     */
    public synchronized boolean result(String id,int sentenceId,String text,boolean isFinal,boolean heartbeat){
        if(!accepts(id))return false;
        if(!count())return false;
        if(heartbeat)return true;
        if(sentenceId<1||sentenceId>MAX_SENTENCES||text==null){fail("ASR_SENTENCE_INVALID");return false;}
        if(text.length()>MAX_TEXT||receivedCharacters>MAX_RECEIVED_TEXT-text.length()){fail("ASR_TEXT_BOUND");return false;}
        receivedCharacters+=text.length();
        Sentence old=sentences.get(sentenceId);
        if(old!=null&&old.complete&&!isFinal)return false;
        int size=retainedCharacters-(old==null?0:old.text.length())+text.length();
        if(size>MAX_TEXT){fail("ASR_TEXT_BOUND");return false;}
        if(old==null||old.complete!=isFinal||!old.text.equals(text))contentRevision++;
        sentences.put(sentenceId,new Sentence(text,isFinal));retainedCharacters=size;return true;
    }
    /** Called after local endpoint and audio queue drain, immediately before finish-task enqueue. */
    public synchronized boolean inputFinished(){
        if(closed||cancelled||failure!=null||inputDone)return false;
        inputDone=true;return true;
    }
    public synchronized boolean taskFinished(String id){
        if(!accepts(id))return false;
        if(!count())return false;
        if(!inputDone){fail("ASR_FINISHED_BEFORE_INPUT_END");return false;}
        taskDone=true;
        if(!completeSentences()){fail("ASR_INCOMPLETE_TRANSCRIPT");return false;}
        return true;
    }
    /** Only code, never error_message/Throwable/headers. Unknown strings are not retained. */
    public synchronized boolean taskFailed(String id,String code){
        if(!accepts(id))return false;
        fail(safeProviderCode(code));return true;
    }
    private static String safeProviderCode(String code){
        if(code!=null&&code.matches("CLIENT_ERROR|SERVER_ERROR|TIMEOUT|INVALID_PARAMETER|INVALID_API_KEY|UNAUTHORIZED|FORBIDDEN|RATE_LIMIT_EXCEEDED|QUOTA_EXCEEDED|InvalidParameter|InvalidApiKey|InvalidRequest|Throttling|AccessDenied|InternalError|[45][0-9]{2}"))return code;
        return "ASR_TASK_FAILED";
    }
    private boolean completeSentences(){
        if(sentences.isEmpty())return false;
        int next=1;boolean nonempty=false;
        for(Map.Entry<Integer,Sentence> entry:sentences.entrySet()){
            if(entry.getKey()!=next++||!entry.getValue().complete)return false;
            if(entry.getValue().text.trim().length()>0)nonempty=true;
        }
        return nonempty;
    }
    /** Endpoint evidence only: never exposes or commits text before task-finished. */
    public synchronized boolean finalCandidate(){return !closed&&!cancelled&&failure==null&&!inputDone&&!taskDone&&completeSentences();}
    public synchronized boolean ready(){return !cancelled&&failure==null&&inputDone&&taskDone&&completeSentences();}
    public synchronized String text()throws IOException{
        if(!ready())throw new IOException(failure==null?"ASR_NOT_READY":failure);
        StringBuilder result=new StringBuilder(retainedCharacters);
        for(Sentence sentence:sentences.values())result.append(sentence.text);
        return result.toString().trim();
    }
    public synchronized String failureCode(){return failure;}
    /** Protocol repeats count toward transport limits, but are not new speech evidence. */
    synchronized long contentRevision(){return contentRevision;}
    public synchronized int eventCount(){return events;}
    public synchronized void close(){
        if(closed)return;
        if(!ready()&&!cancelled&&failure==null)fail("ASR_CLOSED_BEFORE_COMPLETE");
        closed=true;
    }
    public synchronized void cancel(){cancelled=true;closed=true;sentences.clear();retainedCharacters=0;}
}
