package org.sp001.core;

import java.io.IOException;
import org.json.JSONObject;

/** Bidirectional TTS turn, distinct from the provider's sentence/request completion.
 * Pure state only: the Android adapter owns JSON, transport, cancellation and playback.
 */
public final class TtsTurnState {
    public static final int MAX_EVENTS=8192,MAX_BYTES=3840000;
    private final String session;
    private String sentence="";
    private boolean open,flushed,formatSeen,requestEnded;
    private int events,starts,ends,bytes,finals;
    private String lastEvent="";
    public TtsTurnState(String session){if(session==null||session.length()==0)throw new IllegalArgumentException("SESSION_REQUIRED");this.session=session;}
    public void event(String type,String sessionId,String sentenceId)throws IOException{
        lastEvent=type==null?"":type;
        if(flushed||++events>MAX_EVENTS)throw new IOException("TTS_EVENT_BOUND");
        if(!session.equals(sessionId))throw new IOException("TTS_SESSION_MISMATCH");
        if(type==null||sentenceId==null)throw new IOException("TTS_EVENT_SCHEMA");
        if("sentence_start".equals(type)){
            if(open)throw new IOException("TTS_SENTENCE_OVERLAP");open=true;requestEnded=false;sentence=sentenceId;starts++;
        }else if("sentence_end".equals(type)){
            if(!open||!requestEnded||!sentence.equals(sentenceId))throw new IOException("TTS_SENTENCE_ORDER");open=false;ends++;
        }else if("task_flushed".equals(type)){
            if(open||starts==0||starts!=ends||bytes<3200||!formatSeen||finals==0)throw new IOException("TTS_FLUSH_INCOMPLETE");flushed=true;
        }else if(type.length()==0||"task_continued".equals(type)){
            if(sentenceId.length()>0&&(!open||!sentence.equals(sentenceId)))throw new IOException("TTS_AUDIO_SENTENCE");
        }else throw new IOException("TTS_UNEXPECTED_EVENT");
    }
    public void audio(int length)throws IOException{
        if(!open||flushed||requestEnded||length<=0||length%2!=0||length>MAX_BYTES-bytes)throw new IOException("TTS_PCM_BOUND");bytes+=length;
    }
    public void format(int rate,int channels,String kind)throws IOException{
        if(flushed||rate!=32000||channels!=1||!"pcm".equals(kind))throw new IOException("TTS_FORMAT_MISMATCH");formatSeen=true;
    }
    public void requestFinal()throws IOException{if(flushed||!open||requestEnded)throw new IOException("TTS_FINAL_ORDER");requestEnded=true;finals++;}
    public boolean flushed(){return flushed;}
    public int bytes(){return bytes;}
    /** Failure evidence for the current text only; never includes speech, audio, or credentials. */
    JSONObject diagnostic(){
        JSONObject state=new JSONObject();
        try{state.put("events",events).put("lastEvent",lastEvent).put("starts",starts).put("ends",ends)
            .put("bytes",bytes).put("finals",finals).put("formatSeen",formatSeen)
            .put("sentenceOpen",open).put("requestEnded",requestEnded).put("flushed",flushed);}
        catch(Exception ignored){}
        return state;
    }
}
