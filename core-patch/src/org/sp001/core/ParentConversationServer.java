package org.sp001.core;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;

/** Authenticated LAN journal export and bounded parent settings. */
final class ParentConversationServer {
    private static final AtomicBoolean STARTED=new AtomicBoolean();
    private static final Charset UTF8=Charset.forName("UTF-8");
    private ParentConversationServer(){}
    static void start(final Context context){
        if(!STARTED.compareAndSet(false,true))return;
        Thread thread=new Thread(new Runnable(){public void run(){serve(context);}},"sp001-parent-readonly");
        thread.setDaemon(true);thread.start();
    }
    private static JSONObject loadConfig(Context context)throws Exception{
        File file=new File(context.getFilesDir(),"sp001-parent-link.json");if(!file.isFile())return null;
        InputStream in=new FileInputStream(file);try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] part=new byte[2048];int n;
            while((n=in.read(part))!=-1){if(out.size()+n>4096)throw new Exception("CONFIG_TOO_LARGE");out.write(part,0,n);}
            JSONObject c=new JSONObject(new String(out.toByteArray(),UTF8));
            if(!c.getString("deviceId").matches("[a-zA-Z0-9_-]{8,64}")||!c.getString("deviceKey").matches("[a-f0-9]{64}"))throw new Exception("LINK_CONFIG_INVALID");
            if(c.getInt("port")!=8789)throw new Exception("PORT_INVALID");
            if(!c.getString("storePassword").matches("[a-zA-Z0-9]{16,128}"))throw new Exception("STORE_PASSWORD_INVALID");
            return c;
        }finally{in.close();}
    }
    private static SSLServerSocket socket(Context context,JSONObject c)throws Exception{
        File file=new File(context.getFilesDir(),"sp001-parent-server.p12");
        KeyStore store=KeyStore.getInstance("PKCS12");InputStream in=new FileInputStream(file);
        try{store.load(in,c.getString("storePassword").toCharArray());}finally{in.close();}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store,c.getString("storePassword").toCharArray());
        SSLContext tls=SSLContext.getInstance("TLSv1.2");tls.init(km.getKeyManagers(),null,null);
        SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(8789,8,InetAddress.getByName("0.0.0.0"));
        server.setEnabledProtocols(new String[]{"TLSv1.2"});return server;
    }
    private static void serve(Context context){
        try{
            JSONObject config=loadConfig(context);if(config==null){STARTED.set(false);return;}
            SSLServerSocket listener=socket(context,config);
            status(context,"LISTENING","");
            try{while(true){SSLSocket client=(SSLSocket)listener.accept();
                try{client.setSoTimeout(5000);handle(client,config,context);}catch(Exception ignored){}finally{try{client.close();}catch(Exception ignored){}}
            }}finally{listener.close();}
        }catch(Throwable error){status(context,"FAILED",error.getClass().getSimpleName());STARTED.set(false);/* Voice remains available if parent endpoint fails. */}
    }
    private static void status(Context context,String stage,String reason){
        try{android.util.AtomicFile file=new android.util.AtomicFile(new File(context.getFilesDir(),"sp001-parent-server-status.json"));
            FileOutputStream out=null;try{out=file.startWrite();out.write(new JSONObject().put("stage",stage)
                .put("reason",reason).put("atMs",System.currentTimeMillis()).toString().getBytes(UTF8));file.finishWrite(out);out=null;}
            finally{if(out!=null)file.failWrite(out);}
        }catch(Exception ignored){}
    }
    private static String line(InputStream in)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r'){if(out.size()>=4096)throw new Exception("HEADER_BOUND");out.write(c);}}
        if(c==-1)throw new Exception("REQUEST_EOF");return new String(out.toByteArray(),UTF8);
    }
    private static String hmac(String key,String id,long after,long at)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(UTF8),"HmacSHA256"));
        byte[] digest=mac.doFinal(("GET\n/api/device/events\n"+id+"\n"+after+"\n"+at).getBytes(UTF8));
        char[] digits="0123456789abcdef".toCharArray(),hex=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){hex[2*i]=digits[(digest[i]>>>4)&15];hex[2*i+1]=digits[digest[i]&15];}
        return new String(hex);
    }
    static String commandHmac(String key,String id,long seq,long expires,long at,byte[] body)throws Exception{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(UTF8),"HmacSHA256"));
        mac.update(("POST\n/api/device/command\n"+id+"\n"+seq+"\n"+expires+"\n"+at+"\n").getBytes(UTF8));
        byte[] digest=mac.doFinal(body);char[] digits="0123456789abcdef".toCharArray(),hex=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){hex[2*i]=digits[(digest[i]>>>4)&15];hex[2*i+1]=digits[digest[i]&15];}
        return new String(hex);
    }
    private static void respond(OutputStream out,int status,String body)throws Exception{
        byte[] bytes=body.getBytes(UTF8);
        String head="HTTP/1.1 "+status+(status==200?" OK":" Unauthorized")+"\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));out.write(bytes);out.flush();
    }
    private static void handle(SSLSocket client,JSONObject config,Context context)throws Exception{
        client.startHandshake();InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
        String first=line(in),id="",afterText="",atText="",signature="",seqText="",expiresText="",lengthText="";int total=first.length();
        for(int i=0;i<32;i++){String header=line(in);total+=header.length();if(total>8192)throw new Exception("HEADER_BOUND");if(header.isEmpty())break;
            int colon=header.indexOf(':');if(colon<1)continue;String name=header.substring(0,colon).trim().toLowerCase(Locale.US),value=header.substring(colon+1).trim();
            if(name.equals("x-sp001-device-id"))id=value;else if(name.equals("x-sp001-after"))afterText=value;
            else if(name.equals("x-sp001-sent-at"))atText=value;else if(name.equals("x-sp001-signature"))signature=value;
            else if(name.equals("x-sp001-command-seq"))seqText=value;else if(name.equals("x-sp001-expires-at"))expiresText=value;
            else if(name.equals("content-length"))lengthText=value;
        }
        if("POST /api/device/command HTTP/1.1".equals(first)){
            if(!seqText.matches("[1-9][0-9]{0,14}")||!expiresText.matches("[0-9]{10,15}")||
                !atText.matches("[0-9]{10,15}")||!lengthText.matches("[1-9][0-9]{0,3}")){
                respond(out,401,"{\"error\":\"bad_request\"}");return;
            }
            int length=Integer.parseInt(lengthText);if(length>2048){respond(out,401,"{\"error\":\"body_bound\"}");return;}
            byte[] body=new byte[length];int offset=0;
            while(offset<length){int n=in.read(body,offset,length-offset);if(n<0)throw new Exception("BODY_EOF");offset+=n;}
            long seq=Long.parseLong(seqText),expires=Long.parseLong(expiresText),at=Long.parseLong(atText);
            if(!id.equals(config.getString("deviceId"))||Math.abs(System.currentTimeMillis()-at)>300000||!signature.matches("[a-f0-9]{64}")||
                !MessageDigest.isEqual(signature.getBytes(UTF8),commandHmac(config.getString("deviceKey"),id,seq,expires,at,body).getBytes(UTF8))){
                respond(out,401,"{\"error\":\"unauthorized\"}");return;
            }
            JSONObject command=new JSONObject(new String(body,UTF8));
            if(command.length()!=2||!(command.opt("type") instanceof String)||!(command.opt("payload") instanceof JSONObject)){
                respond(out,401,"{\"error\":\"invalid_command\"}");return;
            }
            String result=ParentUseGuard.apply(OwnerVoiceBridge.nativeContext(),seq,expires,command.getString("type"),command.getJSONObject("payload"));
            respond(out,200,new JSONObject().put("seq",seq).put("code",result).toString());return;
        }
        if(!first.matches("GET /api/device/events\\?after=[0-9]{1,15} HTTP/1\\.[01]")||!afterText.matches("[0-9]{1,15}")||!atText.matches("[0-9]{10,15}")){
            respond(out,401,"{\"error\":\"bad_request\"}");return;
        }
        long after=Long.parseLong(afterText),at=Long.parseLong(atText);
        if(!first.startsWith("GET /api/device/events?after="+afterText+" ")||!id.equals(config.getString("deviceId"))
            ||Math.abs(System.currentTimeMillis()-at)>300000||!signature.matches("[a-f0-9]{64}")){
            respond(out,401,"{\"error\":\"unauthorized\"}");return;
        }
        String expected=hmac(config.getString("deviceKey"),id,after,at);
        if(!MessageDigest.isEqual(signature.getBytes(UTF8),expected.getBytes(UTF8))){respond(out,401,"{\"error\":\"unauthorized\"}");return;}
        JSONObject response=ParentConversationSync.eventsAfter(after);
        // Read the existing sticky battery status; no receiver, timer or hardware polling.
        try{
            android.content.Intent battery=context.registerReceiver(null,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                int level=battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1);
                int scale=battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,-1);
                int status=battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1);
                if(level>=0&&scale>0&&level<=scale)response.put("battery",new JSONObject()
                    .put("percent",Math.round(level*100.0f/scale))
                    .put("charging",status==android.os.BatteryManager.BATTERY_STATUS_CHARGING||status==android.os.BatteryManager.BATTERY_STATUS_FULL));
            }
        }catch(Exception ignored){/* Missing battery information must not block journal delivery. */}
        respond(out,200,response.toString());
    }
}
