package org.sp001.core;

import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.HttpsURLConnection;

/** One combined resource-pressure batch. Real transport, injected blocking platform I/O. */
public final class BoundedHttpTest {
    static int checks;
    static void yes(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    static void zeroSlots()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(BoundedHttp.occupiedSlots()!=0&&System.nanoTime()<until)Thread.sleep(5);yes(BoundedHttp.occupiedSlots()==0,"all transport and cleanup leases returned");}
    static final class Connection extends HttpsURLConnection {
        final String blocked;final byte[] response;
        final CountDownLatch entered=new CountDownLatch(1),allowIo=new CountDownLatch(1),disconnectEntered=new CountDownLatch(1),allowDisconnect=new CountDownLatch(1);
        final AtomicInteger readBytes=new AtomicInteger();volatile byte[] sent;
        Connection(String blocked,byte[] response)throws Exception{super(new URL("https://api.minimax.io/test"));this.blocked=blocked;this.response=response;}
        void gate(String stage)throws IOException{if(!stage.equals(blocked))return;entered.countDown();try{if(!allowIo.await(5,TimeUnit.SECONDS))throw new IOException("TEST_HOLD_TIMEOUT");}catch(InterruptedException e){throw new IOException(e);}}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream(){public synchronized void write(byte[] b,int off,int len){try{gate("upload");}catch(IOException e){throw new UncheckedIOException(e);}sent=Arrays.copyOfRange(b,off,off+len);}public void close()throws IOException{gate("uploadClose");}};}
        public int getResponseCode()throws IOException{gate("headers");return 200;}
        public String getContentType(){return "application/json";}
        public InputStream getInputStream(){return new ByteArrayInputStream(response){public synchronized int read(byte[] b,int off,int len){try{gate("body");}catch(IOException e){throw new UncheckedIOException(e);}int n=super.read(b,off,len);if(n>0)readBytes.addAndGet(n);return n;}public void close()throws IOException{gate("bodyClose");}};}
        public void disconnect(){disconnectEntered.countDown();try{if(!allowDisconnect.await(5,TimeUnit.SECONDS))throw new IllegalStateException("TEST_CLEANUP_TIMEOUT");}catch(InterruptedException e){throw new IllegalStateException(e);}}
        public boolean usingProxy(){return false;}public void connect(){}public String getCipherSuite(){return "TEST";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
    }
    static BoundedHttp open(final Connection connection,byte[] body,final AtomicBoolean cancel)throws Exception{
        return open(connection,body,cancel,null);
    }
    static BoundedHttp open(final Connection connection,byte[] body,final AtomicBoolean cancel,BoundedHttp.Timing timing)throws Exception{
        return BoundedHttp.open(new MiniMaxVoiceClient.ConnectionFactory(){public HttpsURLConnection open(URL u){return connection;}},connection.getURL(),"application/json","application/json","test-placeholder",body,1000,new BoundedHttp.Guard(){public void check()throws IOException{if(cancel.get())throw new IOException("TURN_CANCELLED");}},timing);
    }
    /** A network error follows already-received data; cancellation discards it. */
    static void queuedTailAfterFailure()throws Exception{
        final byte[] payload="data: completed response\n\n".getBytes("UTF-8");
        for(final int scenario:new int[]{0,1,2}){
            final CountDownLatch mayFail=new CountDownLatch(1),disconnected=new CountDownLatch(1);
            final AtomicBoolean cancel=new AtomicBoolean();
            HttpsURLConnection con=new HttpsURLConnection(new URL("https://example.invalid/fault")){
                public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
                public int getResponseCode(){return 200;}public String getContentType(){return "text/event-stream";}
                public InputStream getInputStream(){return new InputStream(){boolean sent;
                    public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
                    public int read(byte[] b,int at,int n)throws IOException{
                        if(!sent){sent=true;System.arraycopy(payload,0,b,at,payload.length);return payload.length;}
                        try{if(!mayFail.await(2,TimeUnit.SECONDS))throw new IOException("TEST_GATE_TIMEOUT");}catch(InterruptedException e){throw new IOException(e);}
                        throw new SocketTimeoutException("injected AFTER final bytes");
                    }
                };}
                public void disconnect(){disconnected.countDown();}public boolean usingProxy(){return false;}public void connect(){}
                public String getCipherSuite(){return "TEST";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
            };
            BoundedHttp response=BoundedHttp.open(u->con,con.getURL(),"a","b","unused",new byte[]{1},1000,()->{if(cancel.get())throw new IOException("TURN_CANCELLED");});
            ByteArrayOutputStream got=new ByteArrayOutputStream();
            try{
                if(scenario==1)got.write(response.input().read());
                mayFail.countDown();yes(disconnected.await(2,TimeUnit.SECONDS),"read fault reached actual cleanup");
                if(scenario==2)cancel.set(true);
                String code="";Throwable cause=null;byte[] chunk=new byte[7];
                try{int n;while((n=response.input().read(chunk))!=-1)got.write(chunk,0,n);}catch(IOException error){code=error.getMessage();cause=error.getCause();}
                if(scenario==2)yes(got.size()==0&&"TURN_CANCELLED".equals(code),"cancellation still discards all queued data");
                else{
                    yes(Arrays.equals(payload,got.toByteArray()),"queued and partly-consumed bytes survive a later network read error");
                    yes("HTTP_TRANSFER_FAILED".equals(code)&&cause instanceof SocketTimeoutException,"original read failure is propagated after bytes, never relabeled EOF");
                    yes(BoundedHttp.occupiedSlots()==1,"failed native read retains response lease until consumer closes");
                }
            }finally{mayFail.countDown();response.close();}zeroSlots();
        }
    }
    public static void main(String[] args)throws Exception{
        ExecutorService callers=Executors.newFixedThreadPool(4);List<Connection> held=new ArrayList<Connection>();
        try{
            zeroSlots();queuedTailAfterFailure();final List<AtomicBoolean> cancels=new ArrayList<AtomicBoolean>();List<Future<Boolean>> requests=new ArrayList<Future<Boolean>>();
            for(String phase:new String[]{"upload","uploadClose","body","bodyClose"}){
                final Connection con=new Connection(phase,"{\"text\":\"ok\"}".getBytes("UTF-8"));held.add(con);final AtomicBoolean cancel=new AtomicBoolean();cancels.add(cancel);final byte[] upload=new byte[]{10,20,30};
                requests.add(callers.submit(new Callable<Boolean>(){public Boolean call()throws Exception{BoundedHttp response=null;try{response=open(con,upload,cancel);byte[] data=new byte[64];while(response.input().read(data)!=-1){}return true;}catch(IOException expected){return false;}finally{if(response!=null)response.close();}}}));
                yes(con.entered.await(1,TimeUnit.SECONDS),"platform blocked at "+phase);Arrays.fill(upload,(byte)0);
            }
            long began=System.nanoTime();for(AtomicBoolean c:cancels)c.set(true);for(Future<Boolean> f:requests)f.get(500,TimeUnit.MILLISECONDS);
            yes(System.nanoTime()-began<TimeUnit.MILLISECONDS.toNanos(500),"all conversation callers return while platform work remains blocked");
            for(Connection c:held)yes(c.disconnectEntered.await(1,TimeUnit.SECONDS),"cleanup scheduled independently");
            yes(BoundedHttp.occupiedSlots()==4,"four blocked requests retain exactly four leases");
            final AtomicInteger unexpectedOpen=new AtomicInteger();try{BoundedHttp.open(new MiniMaxVoiceClient.ConnectionFactory(){public HttpsURLConnection open(URL u){unexpectedOpen.incrementAndGet();return null;}},new URL("https://api.minimax.io/test"),"a","b","test",new byte[]{1},1000,new BoundedHttp.Guard(){public void check(){}});throw new AssertionError("capacity bypass");}catch(IOException expected){yes("HTTP_CAPACITY_EXHAUSTED".equals(expected.getMessage())&&unexpectedOpen.get()==0,"capacity failure precedes platform allocation");}
            for(Connection c:held)c.allowIo.countDown();Thread.sleep(50);yes(BoundedHttp.occupiedSlots()==4,"I/O exit alone does not return leases while disconnect blocks");
            yes(Arrays.equals(held.get(0).sent,new byte[]{10,20,30}),"blocked upload owns private copy despite caller wipe");
            for(Connection c:held)c.allowDisconnect.countDown();zeroSlots();
            // A slow consumer gets a byte-bounded queue plus one producer chunk; EOF cannot bypass queued data.
            final byte[] payload=new byte[100000];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i%127);
            Connection flow=new Connection("",payload);held.add(flow);flow.allowDisconnect.countDown();BoundedHttp.Timing timing=new BoundedHttp.Timing(android.os.SystemClock.elapsedRealtime());BoundedHttp response=open(flow,new byte[]{1},new AtomicBoolean(),timing);
            long[] observed=timing.snapshot();yes(observed[0]<=observed[1]&&observed[1]<=observed[2],"request/output-ready/headers timestamps monotonic");observed[1]=-99;yes(timing.snapshot()[1]>=0,"caller snapshot cannot mutate request timing");
            try{Thread.sleep(50);yes(flow.readBytes.get()<=20480,"16KiB queue plus at most one 4KiB pending producer read");ByteArrayOutputStream received=new ByteArrayOutputStream();byte[] chunk=new byte[713];int n;while((n=response.input().read(chunk))!=-1)received.write(chunk,0,n);yes(Arrays.equals(payload,received.toByteArray()),"all bytes precede EOF with arbitrary consumer fragmentation");yes(BoundedHttp.occupiedSlots()==1,"completed I/O retains response lease until consumer closes");}finally{response.close();}zeroSlots();
            System.out.println("BOUNDED_HTTP_BATCH checks="+checks+" realTransport=true platformIo=injected deviceIo=false cloudCalls=0");
        }finally{for(Connection c:held){c.allowIo.countDown();c.allowDisconnect.countDown();}callers.shutdownNow();callers.awaitTermination(2,TimeUnit.SECONDS);}
    }
}
