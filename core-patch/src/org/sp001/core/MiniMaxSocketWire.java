package org.sp001.core;

import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Android-compatible WebSocket transport. Platform TLS/hostname validation stays enabled. */
final class MiniMaxSocketWire implements MiniMaxTtsSocket.Wire {
    private boolean closed;private OkHttpClient client;private WebSocket socket;
    public synchronized void open(String origin,String key,final MiniMaxTtsSocket.Events events)throws Exception{
        if(closed||client!=null)throw new IOException("TTS_WIRE_ALREADY_USED");
        if(!"https://api.minimax.io".equals(origin)&&!"https://api.minimaxi.com".equals(origin))throw new IOException("TTS_ORIGIN");
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(8,TimeUnit.SECONDS)
            .writeTimeout(12,TimeUnit.SECONDS).pingInterval(30,TimeUnit.SECONDS).build();
        socket=client.newWebSocket(new Request.Builder().url(origin.replace("https://","wss://")+"/ws/v1/t2a_v2_bidi")
            .header("Authorization","Bearer "+key).build(),new WebSocketListener(){
                public void onMessage(WebSocket ws,String value){events.message(value);}
                public void onMessage(WebSocket ws,okio.ByteString value){events.failure("TTS_BINARY_MESSAGE");ws.cancel();}
                public void onFailure(WebSocket ws,Throwable error,Response response){events.failure(response==null?"TTS_SOCKET_FAILED":"TTS_HTTP_"+response.code());}
                public void onClosing(WebSocket ws,int code,String reason){events.failure("TTS_SOCKET_CLOSED");ws.close(code,null);}
                public void onClosed(WebSocket ws,int code,String reason){events.failure("TTS_SOCKET_CLOSED");}
            });
    }
    public synchronized boolean send(String text){return !closed&&socket!=null&&socket.send(text);}
    public void close(){
        OkHttpClient owned;WebSocket ws;
        synchronized(this){closed=true;ws=socket;socket=null;owned=client;client=null;}
        if(ws!=null)ws.cancel();
        if(owned!=null){owned.dispatcher().executorService().shutdownNow();owned.connectionPool().evictAll();}
    }
}
