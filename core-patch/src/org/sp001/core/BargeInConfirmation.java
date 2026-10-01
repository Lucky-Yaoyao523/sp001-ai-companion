package org.sp001.core;
/** Confirmation from recent frames, never lifetime noise totals.
 * Twenty-ms frames; short articulation gaps are allowed, separated impulses cannot accumulate. */
public final class BargeInConfirmation {
 public static final int REQUIRED_MS=2000,MIN_VOICED_MS=1500,RESET_GAP_MS=400;
 public static final int PLAYBACK_REQUIRED_MS=800,PLAYBACK_MIN_VOICED_MS=500;
 private final boolean[] recent=new boolean[REQUIRED_MS/20];
 private int elapsed,voiced,gap,next,filled;private boolean confirmed;
 public boolean frame(boolean speech){return frame(speech,false);}
 public boolean frame(boolean speech,boolean playback){
  if(confirmed)return true;
  if(!speech&&elapsed==0)return false;
  if(!speech){gap+=20;if(gap>=RESET_GAP_MS){reset();return false;}}else gap=0;
  if(filled==recent.length){if(recent[next])voiced-=20;}else filled++;
  recent[next]=speech;next=(next+1)%recent.length;if(speech)voiced+=20;
  elapsed=Math.min(REQUIRED_MS,elapsed+20);
  int required=playback?PLAYBACK_REQUIRED_MS:REQUIRED_MS;
  int minimum=playback?PLAYBACK_MIN_VOICED_MS:MIN_VOICED_MS;
  confirmed=speech&&filled>=required/20&&voicedInLast(required/20)>=minimum&&currentlySpeaking();
  return confirmed;
 }
 private int voicedInLast(int frames){
  int count=0;for(int n=1;n<=frames;n++)if(recent[(next-n+recent.length)%recent.length])count++;
  return count*20;
 }
 /** Only lowers our current playback volume; never admits/cancels a turn. */
 public boolean provisional(){
  if(confirmed)return true;if(gap!=0||filled<20)return false;
  int count=0;for(int n=1;n<=20;n++)if(recent[(next-n+recent.length)%recent.length])count++;
  return count>=12;
 }
 public boolean confirmed(){return confirmed;}
 public int voicedMs(){return voiced;}
 public int elapsedMs(){return elapsed;}
 public boolean currentlySpeaking(){
  // Natural playback completion may promote current human speech, not old sparse evidence.
  if(gap!=0||filled<20)return false;
  int count=0;for(int n=1;n<=20;n++)if(recent[(next-n+recent.length)%recent.length])count++;
  return count>=15;
 }
 public void reset(){java.util.Arrays.fill(recent,false);elapsed=voiced=gap=next=filled=0;confirmed=false;}
}
