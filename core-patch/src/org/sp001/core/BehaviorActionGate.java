package org.sp001.core;
/** At-most-once admission per accepted utterance. Never hold this lock over device or disk work. */
public final class BehaviorActionGate {
 private long last;private boolean closed;
 public synchronized boolean claim(long utterance,boolean current){if(closed||!current||utterance<=0||utterance<=last)return false;last=utterance;return true;}
 public synchronized void close(){closed=true;}
 public synchronized long last(){return last;}
}
