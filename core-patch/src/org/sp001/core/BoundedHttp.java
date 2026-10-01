package org.sp001.core;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Blocking platform HTTPS stays off the conversation worker. Four process-wide leases
 * bound stuck requests, upload copies, read queues and cleanup workers across sessions.
 * A lease is never returned merely because cancellation or a timeout was requested. */
final class BoundedHttp implements Closeable {
    interface Guard {void check()throws IOException;}
    /** Optional progress on the consumer thread only; never on the watchdog or I/O worker. */
    interface WaitFeedback {void waiting()throws IOException;}
    /** Per-request scalar observations; never shared with a subsequent request. */
    static final class Timing {
        final long requestStartedMs;
        private long outputStreamReadyMs=-1,headersMs=-1;
        Timing(long started){requestStartedMs=started;}
        synchronized void outputReady(){if(outputStreamReadyMs<0)outputStreamReadyMs=android.os.SystemClock.elapsedRealtime();}
        synchronized void headersReady(){if(headersMs<0)headersMs=android.os.SystemClock.elapsedRealtime();}
        synchronized long[] snapshot(){return new long[]{requestStartedMs,outputStreamReadyMs,headersMs};}
    }
    private static final int LIMIT=4,CHUNK=4096,QUEUE_BYTES=16384;
    private static final Semaphore SLOTS=new Semaphore(LIMIT);
    private static final ThreadFactory THREADS=new ThreadFactory(){public Thread newThread(Runnable work){Thread t=new Thread(work,"SP001-HttpBounded");t.setDaemon(true);return t;}};
    private static ThreadPoolExecutor pool(){ThreadPoolExecutor p=new ThreadPoolExecutor(LIMIT,LIMIT,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(LIMIT),THREADS);p.allowCoreThreadTimeOut(true);return p;}
    private static final ThreadPoolExecutor IO=pool(),CLEANUP=pool();
    private static final ScheduledThreadPoolExecutor WATCH=new ScheduledThreadPoolExecutor(1,THREADS);
    static {WATCH.setKeepAliveTime(10,TimeUnit.SECONDS);WATCH.allowCoreThreadTimeOut(true);}
    private final Object lock=new Object();private final ArrayDeque<byte[]> chunks=new ArrayDeque<byte[]>();
    private final Guard guard;private final WaitFeedback waitFeedback;private byte[] upload;
    private final Timing timing;
    private HttpsURLConnection connection;private ScheduledFuture<?> watch;
    private boolean headers,eof,closed,ioDone,cleanupDone,cleanupStarted,returned;
    private int status,queued;private String contentType;private IOException failure,transferFailure;
    private final InputStream input=new InputStream(){
        private byte[] current;private int position;
        public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        public int read(byte[] target,int offset,int length)throws IOException{
            if(target==null)throw new NullPointerException();if(offset<0||length<0||length>target.length-offset)throw new IndexOutOfBoundsException();if(length==0)return 0;
            checkConsumer();
            if(current==null){current=next();position=0;if(current==null)return -1;}
            try{checkConsumer();int n=Math.min(length,current.length-position);System.arraycopy(current,position,target,offset,n);position+=n;
                if(position==current.length){Arrays.fill(current,(byte)0);current=null;}return n;
            }catch(IOException error){if(current!=null)Arrays.fill(current,(byte)0);current=null;throw error;}
        }
        public void close(){if(current!=null)Arrays.fill(current,(byte)0);current=null;BoundedHttp.this.close();}
    };
    private BoundedHttp(Guard guard,byte[] body,Timing timing,WaitFeedback feedback){this.guard=guard;this.timing=timing;this.waitFeedback=feedback;upload=body.clone();}
    static BoundedHttp open(final MiniMaxVoiceClient.ConnectionFactory factory,final URL url,final String type,final String accept,final String authorization,byte[] body,final int readTimeout,Guard guard)throws IOException{
        return open(factory,url,type,accept,authorization,body,readTimeout,guard,null);
    }
    static BoundedHttp open(final MiniMaxVoiceClient.ConnectionFactory factory,final URL url,final String type,final String accept,final String authorization,byte[] body,final int readTimeout,Guard guard,Timing timing)throws IOException{
        return open(factory,url,type,accept,authorization,body,readTimeout,guard,timing,null);
    }
    static BoundedHttp open(final MiniMaxVoiceClient.ConnectionFactory factory,final URL url,final String type,final String accept,final String authorization,byte[] body,final int readTimeout,Guard guard,Timing timing,WaitFeedback feedback)throws IOException{
        if(factory==null||guard==null||body==null||body.length>2000000)throw new IOException("HTTP_CONFIG_BOUND");
        guard.check();if(!SLOTS.tryAcquire())throw new IOException("HTTP_CAPACITY_EXHAUSTED");
        BoundedHttp request;
        try{request=new BoundedHttp(guard,body,timing,feedback);}catch(Throwable e){SLOTS.release();throw e;}
        final BoundedHttp owned=request;
        try{
            ScheduledFuture<?> scheduled=WATCH.scheduleAtFixedRate(new Runnable(){public void run(){try{owned.guard.check();}catch(IOException error){owned.invalidate(error);}}},25,25,TimeUnit.MILLISECONDS);
            synchronized(request.lock){request.watch=scheduled;if(request.closed||request.failure!=null)scheduled.cancel(false);}
            IO.execute(new Runnable(){public void run(){owned.transfer(factory,url,type,accept,authorization,readTimeout);}});
        }catch(RuntimeException e){synchronized(request.lock){Arrays.fill(request.upload,(byte)0);request.upload=null;request.ioDone=true;}request.invalidate(new IOException("HTTP_WORKER_UNAVAILABLE"));throw new IOException("HTTP_WORKER_UNAVAILABLE",e);}
        try{request.awaitHeaders();return request;}catch(IOException error){request.invalidate(error);throw error;}
    }
    private void checkConsumer()throws IOException{
        if(Thread.currentThread().isInterrupted()){IOException error=new IOException("TURN_CANCELLED");invalidate(error);throw error;}
        try{guard.check();}catch(IOException error){invalidate(error);throw error;}
        synchronized(lock){if(failure!=null)throw failure;if(closed)throw new IOException("HTTP_CLOSED");}
    }
    private boolean cancelled(){synchronized(lock){return closed||failure!=null;}}
    private void awaitHeaders()throws IOException{for(;;){checkConsumer();synchronized(lock){if(headers)return;if(transferFailure!=null)throw transferFailure;waitForChange();}notifyWait();}}
    private void notifyWait()throws IOException{
        if(waitFeedback==null)return;
        checkConsumer();waitFeedback.waiting();checkConsumer();
    }
    private void waitForChange()throws IOException{try{lock.wait(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("TURN_CANCELLED",e);}}
    private byte[] next()throws IOException{for(;;){checkConsumer();synchronized(lock){
        if(failure!=null)throw failure;if(closed)throw new IOException("HTTP_CLOSED");
        if(!chunks.isEmpty()){byte[] b=chunks.removeFirst();queued-=b.length;lock.notifyAll();return b;}
        // Bytes already received precede an I/O error, just as with an ordinary
        // InputStream. Cancellation still invalidates and clears them immediately.
        if(transferFailure!=null)throw transferFailure;
        if(eof)return null;waitForChange();
    }notifyWait();}}
    private boolean offer(byte[] bytes)throws InterruptedException{
        synchronized(lock){while(!closed&&failure==null&&queued+bytes.length>QUEUE_BYTES)lock.wait(25);
            if(closed||failure!=null){Arrays.fill(bytes,(byte)0);return false;}chunks.addLast(bytes);queued+=bytes.length;lock.notifyAll();return true;}
    }
    private void transfer(MiniMaxVoiceClient.ConnectionFactory factory,URL url,String type,String accept,String authorization,int readTimeout){
        InputStream incoming=null;OutputStream outgoing=null;byte[] buffer=new byte[CHUNK];
        try{
            if(cancelled())return;
            HttpsURLConnection con=factory.open(url);
            synchronized(lock){connection=con;lock.notifyAll();}
            if(cancelled())return;
            con.setInstanceFollowRedirects(false);con.setConnectTimeout(8000);con.setReadTimeout(readTimeout);con.setRequestMethod("POST");con.setDoOutput(true);
            con.setRequestProperty("Content-Type",type);con.setRequestProperty("Accept",accept);con.setRequestProperty("Authorization",authorization);con.setFixedLengthStreamingMode(upload.length);
            if(cancelled())return;
            outgoing=con.getOutputStream();if(cancelled())return;if(timing!=null)timing.outputReady();outgoing.write(upload);outgoing.close();outgoing=null;
            Arrays.fill(upload,(byte)0);upload=null;if(cancelled())return;
            int http=con.getResponseCode();String ct=con.getContentType();
            synchronized(lock){if(closed||failure!=null)return;if(timing!=null)timing.headersReady();status=http;contentType=ct;headers=true;lock.notifyAll();}
            if(http!=200)return;
            incoming=con.getInputStream();int n;
            while(!cancelled()&&(n=incoming.read(buffer))!=-1){if(n==0)continue;if(!offer(Arrays.copyOf(buffer,n)))return;}
            synchronized(lock){if(!closed&&failure==null){eof=true;lock.notifyAll();}}
        }catch(Throwable error){
            // Do not erase a queued final sentence or response.completed merely
            // because the following native read failed. Only the consumer closes
            // the lease after draining, or the cancellation guard invalidates it.
            synchronized(lock){if(!closed&&failure==null)transferFailure=new IOException("HTTP_TRANSFER_FAILED",error);lock.notifyAll();}
        }
        finally{
            requestCleanup();
            try{if(outgoing!=null)outgoing.close();}catch(Throwable ignored){}
            try{if(incoming!=null)incoming.close();}catch(Throwable ignored){}
            if(upload!=null){Arrays.fill(upload,(byte)0);upload=null;}Arrays.fill(buffer,(byte)0);
            synchronized(lock){ioDone=true;lock.notifyAll();releaseIfDone();}
        }
    }
    int status(){synchronized(lock){return status;}}
    String contentType(){synchronized(lock){return contentType;}}
    InputStream input(){return input;}
    private void invalidate(IOException error){synchronized(lock){if(failure==null)failure=error;clearQueue();stopWatch();lock.notifyAll();releaseIfDone();}requestCleanup();}
    public void close(){synchronized(lock){closed=true;clearQueue();stopWatch();lock.notifyAll();releaseIfDone();}requestCleanup();}
    private void stopWatch(){if(watch!=null)watch.cancel(false);}
    private void clearQueue(){while(!chunks.isEmpty())Arrays.fill(chunks.removeFirst(),(byte)0);queued=0;}
    private void requestCleanup(){
        synchronized(lock){if(cleanupStarted)return;cleanupStarted=true;}
        try{CLEANUP.execute(new Runnable(){public void run(){
            HttpsURLConnection owned=null;boolean confirmed=false;
            try{synchronized(lock){while(connection==null&&!ioDone)lock.wait(25);owned=connection;}if(owned!=null)owned.disconnect();confirmed=true;}
            catch(Throwable ignored){}
            finally{synchronized(lock){cleanupDone=confirmed;releaseIfDone();}}
        }});}catch(RuntimeException rejected){/* Preserve the lease: no proof native cleanup ran. */}
    }
    private void releaseIfDone(){if((closed||failure!=null)&&ioDone&&cleanupDone&&!returned){returned=true;stopWatch();SLOTS.release();}}
    static int occupiedSlots(){return LIMIT-SLOTS.availablePermits();}
}
