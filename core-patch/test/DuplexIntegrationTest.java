package org.sp001.core;
import java.io.*;import java.net.*;import java.security.cert.Certificate;import java.util.concurrent.*;import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
/** Shared synthetic HTTPS fixture only. No recordings, credentials, or real transport. */
public final class DuplexIntegrationTest {
 static JSONArray history(MiniMaxVoiceClient c)throws Exception{java.lang.reflect.Field f=MiniMaxVoiceClient.class.getDeclaredField("history");f.setAccessible(true);return (JSONArray)f.get(c);}
 static final OwnerStreamPlayer.Clock CLOCK=new OwnerStreamPlayer.Clock(){public long now(){return System.nanoTime()/1000000;}public void pause(long ms)throws InterruptedException{Thread.sleep(ms);}};
 static final class Connection extends HttpsURLConnection {
  String body="{\"text\":\"声音大一点\"}",contentType="application/json",blockAt="";
  volatile boolean disconnected,blockDisconnect;
  final CountDownLatch entered=new CountDownLatch(1),disconnectStarted=new CountDownLatch(1),releaseDisconnect=new CountDownLatch(1);
  final ByteArrayOutputStream uploaded=new ByteArrayOutputStream();
  Connection()throws Exception{super(new URL("https://api.minimax.io/test"));}
  void await(String stage)throws IOException{
   if(!blockAt.equals(stage))return;entered.countDown();long until=CLOCK.now()+3000;
   while(!disconnected&&CLOCK.now()<until){try{Thread.sleep(2);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("INTERRUPTED");}}
   if(!disconnected)throw new IOException("TEST_TIMEOUT");throw new IOException("DISCONNECTED");
  }
  public OutputStream getOutputStream(){return new OutputStream(){public void write(int b)throws IOException{await("upload");uploaded.write(b);}public void write(byte[] b,int off,int len)throws IOException{await("upload");uploaded.write(b,off,len);}};}
  public int getResponseCode()throws IOException{await("headers");return 200;}
  public String getContentType(){return contentType;}
  public InputStream getInputStream()throws IOException{final byte[] data=body.getBytes("UTF-8");return new ByteArrayInputStream(data){public synchronized int read(byte[] b,int off,int n){try{await("body");}catch(IOException e){throw new UncheckedIOException(e);}return super.read(b,off,n);}};}
  public void disconnect(){disconnectStarted.countDown();if(blockDisconnect){try{releaseDisconnect.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}disconnected=true;}
  public boolean usingProxy(){return false;}public void connect(){}public String getCipherSuite(){return "TEST";}
  public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
 }
}
