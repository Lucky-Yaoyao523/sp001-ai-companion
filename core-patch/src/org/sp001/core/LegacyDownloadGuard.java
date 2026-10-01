package org.sp001.core;
import android.os.SystemClock;import java.lang.reflect.*;
/** Preserve the original downloader body, but missing metadata cannot hot-loop the sleep queue. */
public final class LegacyDownloadGuard {
 public interface Attempt {boolean run()throws Exception;}
 public static final class Backoff {
  private long until;private boolean active;
  public synchronized boolean begin(long now){if(active||now<0||now<until)return false;active=true;return true;}
  public synchronized void end(boolean ok,long now){active=false;until=ok?0:Math.max(0,now)+300000L;}
 }
 private static final Backoff policy=new Backoff();
 private static volatile String last="NOT_ATTEMPTED";
 private LegacyDownloadGuard(){}
 public static String status(){return last;}
 public static Boolean run(final Object task){
  if(task==null)return Boolean.FALSE;
  try{
   // Disable only immediate scheduler requeue of THIS task; future normal scheduling remains valid.
   task.getClass().getMethod("k",Boolean.TYPE).invoke(task,Boolean.FALSE);
   if(!policy.begin(SystemClock.elapsedRealtime())){last="COOLDOWN";return Boolean.FALSE;}
   boolean ok=false;try{
    Object value=task.getClass().getMethod("ownerOriginalDownload").invoke(task);ok=Boolean.TRUE.equals(value);last=ok?"COMPLETE":"METADATA_OR_DOWNLOAD_UNAVAILABLE";return Boolean.valueOf(ok);
   }catch(InvocationTargetException e){Throwable cause=e.getCause();last=cause instanceof NullPointerException?"METADATA_MISSING":"DOWNLOAD_FAILED";return Boolean.FALSE;}
   finally{policy.end(ok,SystemClock.elapsedRealtime());}
  }catch(Exception e){last="DOWNLOAD_BINDING_UNAVAILABLE";return Boolean.FALSE;}
 }
}
