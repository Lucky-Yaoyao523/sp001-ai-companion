package org.sp001.core;
/** One UI callback per live phase. Injected clock/scheduler/driver never control audio. */
public final class CompanionVisualPlayer {
 public interface Clock {long now();}
 public interface Scheduler {void later(Runnable callback,long delay);}
 public interface Driver {void show(String preset)throws Exception;}
 public interface Lease {boolean valid();}
 private final Clock clock;private final Scheduler scheduler;private final Driver driver;
 private long revision;private int submitted,failed;private String lastPreset="",lastError="";
 public CompanionVisualPlayer(Clock c,Scheduler s,Driver d){if(c==null||s==null||d==null)throw new IllegalArgumentException("VISUAL_PORTS");clock=c;scheduler=s;driver=d;}
 public synchronized void invalidate(){revision++;}
 public synchronized int submitted(){return submitted;}
 public synchronized int failed(){return failed;}
 public synchronized String lastPreset(){return lastPreset;}
 public synchronized String lastError(){return lastError;}
 private synchronized boolean current(long id){return revision==id;}
 private synchronized void failed(Exception e){failed++;lastError=e.getClass().getSimpleName();}
 private boolean permitted(long id,Lease lease){try{return current(id)&&lease.valid();}catch(RuntimeException e){failed(e);return false;}}
 private void enqueue(Runnable work,long delay){try{scheduler.later(work,delay);}catch(RuntimeException e){failed(e);}}
 public void phase(final String phase,final String emotion,final Lease lease){
  if(lease==null)throw new IllegalArgumentException("VISUAL_LEASE");
  final long id; synchronized(this){id=++revision;}
  final long began;try{began=clock.now();}catch(RuntimeException e){failed(e);return;}
  enqueue(new Runnable(){String previous;int consecutiveFailures;
   public void run(){
    if(!permitted(id,lease))return;
    long age;try{age=clock.now()-began;}catch(RuntimeException e){failed(e);return;}
    if(age<0||age>CompanionVisualPlan.MAX_PHASE_MS)return;
    String preset=CompanionVisualPlan.eye(phase,emotion,age);
    if(!preset.equals(previous)){
     try{
      if(!permitted(id,lease))return;driver.show(preset);
      synchronized(CompanionVisualPlayer.this){submitted++;lastPreset=preset;lastError="";}
      previous=preset;consecutiveFailures=0;
     }catch(Exception error){failed(error);if(++consecutiveFailures>=2)return;}
    }
    long delay=CompanionVisualPlan.nextDelay(phase,age);
    if(delay>0&&permitted(id,lease))enqueue(this,delay);
   }
  },0);
 }
}
