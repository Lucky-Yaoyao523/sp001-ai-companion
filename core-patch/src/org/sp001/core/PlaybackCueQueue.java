package org.sp001.core;
import java.util.*;
/** Presentation cues keyed to accepted PCM frames, not network or synthesis time. */
public final class PlaybackCueQueue {
 private static final class Cue {final long frame;final Runnable task;Cue(long f,Runnable r){frame=f;task=r;}}
 private final ArrayDeque<Cue> cues=new ArrayDeque<Cue>();
 private boolean closed;private long lastFrame=-1;private int submitted,dispatched,failed;
 public synchronized void add(long frame,Runnable task){
  if(closed||task==null||frame<0||frame<lastFrame||submitted>=32)throw new IllegalStateException("PLAYBACK_CUE_BOUND");
  cues.add(new Cue(frame,task));lastFrame=frame;submitted++;
 }
 public synchronized List<Runnable> ready(long played,long written){
  List<Runnable> out=new ArrayList<Runnable>();if(closed||played<0||written<0)return out;
  while(!cues.isEmpty()&&cues.peek().frame<=played&&cues.peek().frame<written){out.add(cues.remove().task);dispatched++;}
  return out;
 }
 public synchronized void failed(){failed++;}
 public synchronized int submitted(){return submitted;}
 public synchronized int dispatched(){return dispatched;}
 public synchronized int failures(){return failed;}
 public synchronized void close(){closed=true;cues.clear();}
}
