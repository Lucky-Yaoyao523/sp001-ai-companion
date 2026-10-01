package org.sp001.core;

/** Capture-thread-owned causal reference subtraction before stock AEC.
 * Fixed 128-tap NLMS; no gating, playback pause, network, or retained recording.
 * The reference and microphone are never independently amplified before AEC.
 */
final class ReferenceEchoFilter {
 private static final int TAPS=128;
 private final double[] weights=new double[TAPS],delay=new double[TAPS*2];
 private int next;private double previousNear,previousReference,nearHigh,referenceHigh,power;
 private boolean closed;
 void process(short[] near,short[] reference,short[] residual){
  if(closed)throw new IllegalStateException("REFERENCE_FILTER_CLOSED");
  if(near==null||reference==null||residual==null||near.length!=160||reference.length!=160||residual.length!=160||residual==reference||residual==near)throw new IllegalArgumentException("REFERENCE_FILTER_FRAME");
  for(int i=0;i<160;i++){
   double n=near[i]-previousNear+.97*nearHigh,r=reference[i]-previousReference+.97*referenceHigh;
   previousNear=near[i];previousReference=reference[i];nearHigh=n;referenceHigh=r;
   next=next==0?TAPS-1:next-1;power+=r*r-delay[next]*delay[next];if(power<0)power=0;
   delay[next]=delay[next+TAPS]=r;
   double predicted=0;for(int j=0;j<TAPS;j++)predicted+=weights[j]*delay[next+j];
   double error=n-predicted;
   if(power>TAPS*100.0){
    double step=.15*error/(power+TAPS*400.0);
    for(int j=0;j<TAPS;j++)weights[j]=Math.max(-4,Math.min(4,weights[j]+step*delay[next+j]));
   }
   int sample=(int)Math.round(near[i]-predicted);
   residual[i]=(short)Math.max(-32768,Math.min(32767,sample));
  }
 }
 void close(){closed=true;java.util.Arrays.fill(weights,0);java.util.Arrays.fill(delay,0);previousNear=previousReference=nearHigh=referenceHigh=power=0;next=0;}
}
