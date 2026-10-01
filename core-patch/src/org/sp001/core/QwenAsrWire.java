package org.sp001.core;

import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.*;

/** Optional Beijing-only ASR transport. Construction has no I/O. Never logs credentials.
 * The owning StreamingAsrSession worker calls transport methods; callbacks carry data only. */
final class QwenAsrWire implements StreamingAsrSession.ReleasingWire {
    static final String MODEL="qwen-audio-3.0-asr-flash-streaming";
    private final String key,workspace,taskId;
    private volatile boolean cancelled,transportTerminated;private OkHttpClient client,retiringClient;private WebSocket socket,retiringSocket;
    private boolean startSent,finishSent;private int events;
    QwenAsrWire(String key,String workspace,String taskId){
        if(key==null||!key.matches("[A-Za-z0-9_.-]{16,512}"))throw new IllegalArgumentException("ASR_KEY_FORMAT");
        endpoint(workspace);
        if(taskId==null||!taskId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("ASR_TASK_ID");
        this.key=key;this.workspace=workspace;this.taskId=taskId;
    }
    static String endpoint(String workspace){
        if(workspace==null||!workspace.matches("[A-Za-z0-9][A-Za-z0-9-]{0,79}"))throw new IllegalArgumentException("ASR_WORKSPACE");
        return "wss://"+workspace+".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference";
    }
    static String command(String taskId,boolean start)throws IOException{
        try{
            JSONObject payload=new JSONObject().put("input",new JSONObject());
            if(start)payload.put("task_group","audio").put("task","asr").put("function","recognition").put("model",MODEL)
                .put("parameters",new JSONObject().put("format","pcm").put("sample_rate",16000).put("language_hints",new JSONArray().put("zh"))
                    .put("semantic_punctuation_enabled",false).put("max_sentence_silence",400));
            return new JSONObject().put("header",new JSONObject().put("action",start?"run-task":"finish-task").put("task_id",taskId).put("streaming","duplex")).put("payload",payload).toString();
        }catch(JSONException e){throw new IOException("ASR_COMMAND_INVALID");}
    }
    public synchronized void open(final StreamingAsrSession.Listener listener)throws IOException{
        if(cancelled||client!=null)throw new IOException("ASR_WIRE_ALREADY_USED");
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8,TimeUnit.SECONDS).writeTimeout(8,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build();
        socket=client.newWebSocket(new Request.Builder().url(endpoint(workspace)).header("Authorization","Bearer "+key).build(),new WebSocketListener(){
            public void onOpen(WebSocket ws,Response response){if(!cancelled)listener.opened();}
            public void onMessage(WebSocket ws,String raw){if(!cancelled)receive(raw,listener);}
            public void onMessage(WebSocket ws,ByteString binary){if(!cancelled)listener.failed("ASR_UNEXPECTED_BINARY");}
            public void onFailure(WebSocket ws,Throwable error,Response response){transportTerminated=true;if(!cancelled)listener.failed(response==null?"ASR_TRANSPORT_FAILED":"ASR_HTTP_"+response.code());}
            public void onClosing(WebSocket ws,int code,String reason){ws.close(1000,"owner closing");if(!cancelled)listener.closed();}
            public void onClosed(WebSocket ws,int code,String reason){transportTerminated=true;if(!cancelled)listener.closed();}
        });
    }
    /** Parser seam used by offline fixtures; no socket or account needed. */
    void receive(String raw,StreamingAsrSession.Listener listener){
        if(cancelled)return;
        try{
            if(raw==null||raw.length()>65536||++events>512)throw new IOException("ASR_EVENT_BOUND");
            JSONObject event=new JSONObject(raw),header=event.getJSONObject("header");
            if(!taskId.equals(header.getString("task_id")))throw new IOException("ASR_TASK_ID_MISMATCH");
            String kind=header.getString("event");
            if("task-started".equals(kind))listener.taskStarted();
            else if("task-finished".equals(kind))listener.taskFinished();
            else if("task-failed".equals(kind))listener.failed("ASR_TASK_FAILED");
            else if("result-generated".equals(kind)){
                JSONObject sentence=event.getJSONObject("payload").getJSONObject("output").getJSONObject("sentence");
                if(sentence.optBoolean("heartbeat",false))listener.result(0,"",false,true);
                else listener.result(sentence.getInt("sentence_id"),sentence.getString("text"),sentence.getBoolean("sentence_end"),false);
            }else throw new IOException("ASR_UNKNOWN_EVENT");
        }catch(IOException e){listener.failed(e.getMessage());}
        catch(Exception e){listener.failed("ASR_EVENT_INVALID");}
    }
    public synchronized boolean startTask()throws IOException{
        if(cancelled||socket==null||startSent)return false;startSent=true;return socket.send(command(taskId,true));
    }
    public synchronized boolean sendAudio(byte[] pcm,int offset,int count){
        if(cancelled||socket==null||!startSent||finishSent)return false;
        if(pcm==null||offset<0||count<2||count>3200||(offset&1)!=0||(count&1)!=0||offset>pcm.length-count)throw new IllegalArgumentException("ASR_PCM_BOUND");
        return socket.send(ByteString.of(pcm,offset,count));
    }
    public synchronized boolean finishTask()throws IOException{
        if(cancelled||socket==null||!startSent||finishSent)return false;finishSent=true;return socket.send(command(taskId,false));
    }
    public synchronized long queuedBytes(){return socket==null?0:socket.queueSize();}
    public synchronized boolean releaseConfirmed(){
        return cancelled&&(retiringClient==null||(transportTerminated&&retiringClient.dispatcher().executorService().isTerminated()&&writerReleased(retiringSocket)));
    }
    // Pinned OkHttp 3.12.13 owns a separate writer executor. A terminal callback
    // precedes its final cleanup; the dispatcher alone is not release evidence.
    static boolean writerReleased(WebSocket ws){
        if(ws==null)return true;
        if(!"okhttp3.internal.ws.RealWebSocket".equals(ws.getClass().getName()))return false;
        try{
            java.lang.reflect.Field field=ws.getClass().getDeclaredField("executor");field.setAccessible(true);
            synchronized(ws){Object executor=field.get(ws);return executor==null||(executor instanceof java.util.concurrent.ExecutorService&&((java.util.concurrent.ExecutorService)executor).isTerminated());}
        }catch(Exception unavailable){return false;}
    }
    public void cancel(){
        OkHttpClient owned;WebSocket ws;
        synchronized(this){if(cancelled)return;cancelled=true;ws=socket;retiringSocket=ws;socket=null;owned=client;retiringClient=owned;client=null;}
        // In pinned3.12 a received Close ends the reader before Call.cancel can
        // deliver onFailure. Finalize our own websocket's streams/writer explicitly;
        // releaseConfirmed still waits for the real callback and terminated executors.
        if(ws!=null)try{
            if(ws instanceof okhttp3.internal.ws.RealWebSocket)((okhttp3.internal.ws.RealWebSocket)ws).failWebSocket(new IOException("ASR_OWNER_CANCELLED"),null);
        }finally{ws.cancel();}
        if(owned!=null){owned.dispatcher().cancelAll();owned.dispatcher().executorService().shutdownNow();owned.connectionPool().evictAll();}
    }
}
