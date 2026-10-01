package org.sp001.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.json.JSONObject;

/** Pending audio is stored as PCM, not four-times-larger Java hex characters.
 * Transport has already assembled the text frame; these are application bounds.
 * No blocking network callback, retry, or change to playback order. */
public final class TtsEventBuffer {
    public static final int MAX_MESSAGE_CHARS=3840000+65536,MAX_QUEUED_BYTES=TtsTurnState.MAX_BYTES,
        MAX_METADATA_CHARS=65536,MAX_QUEUED_CHARS=2097152,MAX_MESSAGES=TtsTurnState.MAX_EVENTS;
    public static final class Frame {
        public final JSONObject event;
        public final byte[] pcm;
        private final int metadataChars;
        private Frame(JSONObject event,byte[] pcm,int size){this.event=event;this.pcm=pcm;metadataChars=size;}
        public void release(){if(pcm!=null)Arrays.fill(pcm,(byte)0);}
    }
    private final ArrayDeque<Frame> queue=new ArrayDeque<Frame>();
    private int chars,bytes,peakChars,peakBytes,peakMessages,maxMessageChars;
    private String failure;private boolean closed;
    public boolean offer(String text){
        synchronized(this){if(closed||failure!=null)return false;
            maxMessageChars=Math.max(maxMessageChars,text==null?0:text.length());}
        if(text==null||text.length()>MAX_MESSAGE_CHARS){fail("TTS_MESSAGE_BOUND");return false;}
        byte[] pcm=null;
        try{
            JSONObject event=new JSONObject(text),data=event.optJSONObject("data");
            if(data!=null&&data.has("audio")){
                Object audio=data.remove("audio");
                if(!(audio instanceof String))throw new IOException("TTS_AUDIO_TYPE");
                if(((String)audio).length()>0)pcm=MiniMaxCodec.decodePcm((String)audio);
            }
            int size=event.toString().length();
            Frame frame=new Frame(event,pcm,size);
            synchronized(this){
                if(closed||failure!=null){frame.release();return false;}
                String bound=size>MAX_METADATA_CHARS?"TTS_METADATA_MESSAGE_BOUND":
                    size>MAX_QUEUED_CHARS-chars?"TTS_METADATA_QUEUE_BOUND":
                    pcm!=null&&pcm.length>MAX_QUEUED_BYTES-bytes?"TTS_PCM_QUEUE_BOUND":
                    queue.size()>=MAX_MESSAGES?"TTS_MESSAGE_COUNT_BOUND":null;
                if(bound!=null){frame.release();fail(bound);return false;}
                queue.add(frame);chars+=size;bytes+=pcm==null?0:pcm.length;
                peakChars=Math.max(peakChars,chars);peakBytes=Math.max(peakBytes,bytes);peakMessages=Math.max(peakMessages,queue.size());
                return true;
            }
        }catch(Exception error){
            if(pcm!=null)Arrays.fill(pcm,(byte)0);
            String code=error.getMessage();fail(code!=null&&code.matches("[A-Z0-9_]+")?code:"TTS_EVENT_JSON");return false;
        }
    }
    public synchronized Frame poll()throws IOException{
        if(failure!=null)throw new IOException(failure);
        Frame frame=queue.poll();if(frame!=null){chars-=frame.metadataChars;bytes-=frame.pcm==null?0:frame.pcm.length;return frame;}
        if(closed)throw new IOException("TTS_TRANSPORT_CLOSED");return null;
    }
    private void clear(){for(Frame frame:queue)frame.release();queue.clear();chars=0;bytes=0;}
    public synchronized void fail(String code){if(failure==null)failure=code;clear();closed=true;}
    public synchronized void close(){closed=true;clear();}
    public synchronized boolean empty(){return queue.isEmpty();}
    public synchronized int queuedChars(){return chars;}
    public synchronized int queuedBytes(){return bytes;}
    public synchronized String failure(){return failure;}
    public synchronized JSONObject diagnostic(){JSONObject state=new JSONObject();try{
        state.put("failure",failure==null?JSONObject.NULL:failure).put("queuedPcmBytes",bytes)
            .put("peakPcmBytes",peakBytes).put("peakMetadataChars",peakChars)
            .put("peakMessages",peakMessages).put("maxMessageChars",maxMessageChars);
        }catch(Exception ignored){}return state;}
}
