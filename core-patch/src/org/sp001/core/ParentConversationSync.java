package org.sp001.core;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional, bounded parent telemetry. File and network work never runs on the voice thread. */
final class ParentConversationSync {
    private static final Object START_LOCK=new Object();
    private static ScheduledExecutorService worker;
    private static Context application;
    private ParentConversationSync(){}

    private static String timestamp(){
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());
    }
    private static String shortText(String value,int limit){
        if(value==null)return "";value=value.trim();return value.length()>limit?value.substring(0,limit):value;
    }
    private static void ensure(Context context){
        synchronized(START_LOCK){
            if(worker==null){
                application=context.getApplicationContext();
                worker=Executors.newSingleThreadScheduledExecutor();
                worker.scheduleWithFixedDelay(new Runnable(){public void run(){try{upload();}catch(Throwable ignored){} }},5,30,TimeUnit.SECONDS);
            }
            ParentConversationServer.start(application);
        }
    }
    /** Bring up the paired LAN endpoint when the original standby sensors arm. */
    static void startBackground(Context context){
        if(context!=null)try{ensure(context);}catch(Throwable ignored){}
    }
    static void start(Context context,String sessionId){
        event(context,"session_start",sessionId,"",null,null,null,0);
    }
    static void answer(Context context,String sessionId,long turnId,String heard,String spoken){
        event(context,"answer_complete",sessionId,String.valueOf(turnId),heard,spoken,null,0);
    }
    static void answer(Context context,String sessionId,long turnId,String heard,String spoken,String failureCode){
        String reason=failureCode!=null&&failureCode.matches("[A-Z0-9_]{1,100}")?failureCode:null;
        event(context,"answer_complete",sessionId,String.valueOf(turnId),heard,spoken,reason,0);
    }
    static void partial(Context context,String sessionId,long turnId,String heard,String confirmedSpoken){
        event(context,"answer_partial",sessionId,String.valueOf(turnId),heard,confirmedSpoken,
            confirmedSpoken.isEmpty()?"PLAYED_AUDIO_TEXT_NOT_CONFIRMED":"PARTIAL_PLAYBACK",0);
    }
    static void interrupted(Context context,String sessionId,long turnId,String spoken){
        event(context,"interrupted",sessionId,String.valueOf(turnId),null,spoken,null,0);
    }
    static void end(Context context,String sessionId,String reason,long durationMs){
        event(context,"session_end",sessionId,"",null,null,reason,durationMs);
    }
    private static void event(Context context,String kind,String sessionId,String turnId,String heard,String spoken,String reason,long duration){
        if(context==null||sessionId==null||sessionId.isEmpty())return;
        try{
            ensure(context);
            JSONObject value=new JSONObject().put("kind",kind).put("at",timestamp()).put("sessionId",sessionId);
            if(!turnId.isEmpty())value.put("turnId",turnId);
            if(heard!=null)value.put("childText",shortText(heard,1000));
            if(spoken!=null)value.put("replyText",shortText(spoken,1000));
            if(reason!=null)value.put("reason",shortText(reason,100));
            if(duration>0)value.put("durationMs",Math.min(duration,4*3600000L));
            final String serialized=value.toString();
            worker.execute(new Runnable(){public void run(){try{append(serialized);upload();}catch(Throwable ignored){} }});
        }catch(Throwable ignored){/* Telemetry must never change a turn. */}
    }
    private static AtomicFile journal(){return new AtomicFile(new File(application.getFilesDir(),"sp001-parent-journal.json"));}
    private static byte[] bounded(InputStream in,int max)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] part=new byte[4096];int n;
        while((n=in.read(part))!=-1){if(out.size()+n>max)throw new IllegalStateException("PARENT_FILE_LIMIT");out.write(part,0,n);}return out.toByteArray();
    }
    private static JSONObject readState()throws Exception{
        AtomicFile file=journal();File path=file.getBaseFile();
        if(!path.isFile()&&!new File(path.getPath()+".bak").isFile())return new JSONObject().put("nextSeq",1).put("events",new JSONArray());
        FileInputStream in=file.openRead();try{return new JSONObject(new String(bounded(in,16*1024*1024),"UTF-8"));}finally{in.close();}
    }
    static JSONObject eventsAfter(final long after)throws Exception{
        if(worker==null)throw new IllegalStateException("PARENT_JOURNAL_NOT_STARTED");
        return worker.submit(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call()throws Exception{
            JSONObject state=readState();int before=state.getJSONArray("events").length();
            JSONObject result=ParentJournalState.after(state,after);
            if(state.getJSONArray("events").length()!=before)save(state);
            return result;
        }}).get(10,TimeUnit.SECONDS);
    }
    private static void save(JSONObject state)throws Exception{
        AtomicFile file=journal();FileOutputStream out=null;
        try{out=file.startWrite();out.write(state.toString().getBytes("UTF-8"));file.finishWrite(out);out=null;}
        finally{if(out!=null)file.failWrite(out);}
    }
    private static void append(String serialized)throws Exception{
        JSONObject state=readState();ParentJournalState.append(state,serialized);save(state);
    }
    private static JSONObject config()throws Exception{
        File path=new File(application.getFilesDir(),"sp001-parent-sync.json");
        if(!path.isFile())return null;
        FileInputStream in=new FileInputStream(path);
        try{
            JSONObject c=new JSONObject(new String(bounded(in,8192),"UTF-8"));
            URL url=new URL(c.getString("url"));
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null
                ||!"/api/device/sync".equals(url.getPath()))throw new IllegalStateException("PARENT_URL_INVALID");
            if(!c.getString("deviceId").matches("[a-zA-Z0-9_-]{8,64}")||!c.getString("deviceKey").matches("[a-f0-9]{64}"))throw new IllegalStateException("PARENT_ID_INVALID");
            return c;
        }finally{in.close();}
    }
    private static javax.net.ssl.SSLSocketFactory pinnedFactory(String der)throws Exception{
        byte[] bytes=Base64.decode(der,Base64.DEFAULT);
        if(bytes.length<500||bytes.length>8192)throw new IllegalStateException("PARENT_CERT_BOUND");
        Certificate cert=CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(bytes));
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("parent",cert);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),new SecureRandom());
        return tls.getSocketFactory();
    }
    private static String sign(String key,String id,long seq,long at,byte[] body)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes("UTF-8"),"HmacSHA256"));
        mac.update((id+"\n"+seq+"\n"+at+"\n").getBytes("UTF-8"));byte[] digest=mac.doFinal(body);
        char[] digits="0123456789abcdef".toCharArray();char[] hex=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){hex[i*2]=digits[(digest[i]>>>4)&15];hex[i*2+1]=digits[digest[i]&15];}
        return new String(hex);
    }
    private static boolean send(JSONObject config,JSONObject event)throws Exception{
        String id=config.getString("deviceId"),key=config.getString("deviceKey");long seq=event.getLong("seq"),at=System.currentTimeMillis();
        byte[] body=new JSONObject().put("events",new JSONArray().put(event)).toString().getBytes("UTF-8");
        HttpsURLConnection c=(HttpsURLConnection)new URL(config.getString("url")).openConnection();
        c.setSSLSocketFactory(pinnedFactory(config.getString("certificateDerBase64")));
        // Platform default hostname verifier remains enabled, including the certificate SAN.
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setDoOutput(true);c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        c.setRequestProperty("X-SP001-Device-ID",id);c.setRequestProperty("X-SP001-Batch-Seq",String.valueOf(seq));
        c.setRequestProperty("X-SP001-Sent-At",String.valueOf(at));c.setRequestProperty("X-SP001-Signature",sign(key,id,seq,at,body));
        try{
            java.io.OutputStream out=c.getOutputStream();try{out.write(body);out.flush();}finally{out.close();}
            if(c.getResponseCode()!=200)return false;
            InputStream in=c.getInputStream();JSONObject reply;
            try{reply=new JSONObject(new String(bounded(in,4096),"UTF-8"));}finally{in.close();}
            return reply.optBoolean("ok",false)&&reply.optLong("acceptedThrough",-1)>=seq;
        }finally{c.disconnect();}
    }
    private static void upload()throws Exception{
        JSONObject c=config();if(c==null)return;
        for(int attempt=0;attempt<8;attempt++){
            JSONObject state=readState();JSONArray items=state.optJSONArray("events");
            if(items==null||items.length()==0)return;
            JSONObject first=items.getJSONObject(0);if(!send(c,first))return;
            JSONArray rest=new JSONArray();for(int i=1;i<items.length();i++)rest.put(items.get(i));
            state.put("events",rest);save(state);
        }
    }
}
