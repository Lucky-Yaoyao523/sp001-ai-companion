package org.sp001.core;

/** Causal after-AEC ASR-only level conditioning. Never changes VAD, reference or playback.
 * A task owns this state; byte count/order and chunk-independent processing are preserved.
 * Same high-pass/10x ceiling/28000 peak bound as batch prepareInput, without waiting for EOF.
 */
final class StreamingAsrLevel {
 private double previous,high,rmsPower,peak,gain=10;
 private boolean closed;
 byte[] process(byte[] pcm,int offset,int count){
  if(closed)throw new IllegalStateException("ASR_LEVEL_CLOSED");
  if(pcm==null||offset<0||count<2||count>3200||(offset&1)!=0||(count&1)!=0||offset>pcm.length-count)throw new IllegalArgumentException("ASR_LEVEL_BOUND");
  byte[] out=new byte[count];
  for(int i=0;i<count;i+=2){
   int x=(short)((pcm[offset+i]&255)|((pcm[offset+i+1]&255)<<8));
   double y=x-previous+.97*high;previous=x;high=y;
   rmsPower=.9995*rmsPower+.0005*y*y;peak=Math.max(Math.abs(y),peak*.9995);
   double target=Math.min(10,Math.min(4200/Math.sqrt(Math.max(1,rmsPower)),28000/Math.max(1,peak)));
   // Instant attenuation prevents clipping; slow release avoids pumping on syllabic gaps.
   gain=target<gain?target:gain+.00025*(target-gain);
   int v=(int)Math.round(Math.max(-28000,Math.min(28000,y*gain)));
   out[i]=(byte)v;out[i+1]=(byte)(v>>8);
  }
  return out;
 }
 void close(){closed=true;previous=high=rmsPower=peak=0;gain=1;}
}
