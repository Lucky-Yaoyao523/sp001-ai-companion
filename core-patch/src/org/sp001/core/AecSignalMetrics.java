package org.sp001.core;

/** Capture-thread-only, fixed 300ms numeric history. No retained audio or I/O.
 * Zero-lag similarity is evidence about signal coupling, not a speech classifier. */
final class AecSignalMetrics {
    private static final int METRICS=11;
    private final double[][] ring=new double[METRICS][30];
    private int next,count;private long frames;private double previousX,previousY;
    void record(short[] near,short[] reference,short[] clean){
        for(int j=0;j<METRICS;j++)ring[j][next]=0;
        for(int i=0;i<160;i++){
            double n=near[i],r=reference[i],c=clean[i];
            double hp=c-previousX+.97*previousY;previousX=c;previousY=hp;
            ring[0][next]+=n*n;ring[1][next]+=r*r;ring[2][next]+=c*c;
            ring[3][next]+=hp*hp;ring[4][next]+=n*r;
            ring[8][next]+=n;ring[9][next]+=r;ring[10][next]+=c;
            if(Math.abs(n)>=32760)ring[5][next]++;
            if(Math.abs(r)>=32760)ring[6][next]++;
            if(Math.abs(c)>=32760)ring[7][next]++;
        }
        next=(next+1)%30;count=Math.min(30,count+1);frames++;
    }
    Snapshot snapshot(){
        double[] sums=new double[METRICS];for(int j=0;j<METRICS;j++)for(int i=0;i<count;i++)sums[j]+=ring[j][i];
        return new Snapshot(frames,count,sums);
    }
    static final class Snapshot {
        final long frame;final int windowMs;final double nearRms,referenceRms,cleanRms,cleanHighPassRms,zeroLagSimilarity;
        final int nearClipped,referenceClipped,cleanClipped;final boolean similarityAvailable;
        final double nearAcRms,referenceAcRms,cleanAcRms,acSimilarity;
        final boolean acSimilarityAvailable;
        Snapshot(long frames,int count,double[] s){
            frame=frames;windowMs=count*10;double samples=Math.max(1,count*160);
            nearRms=Math.sqrt(s[0]/samples);referenceRms=Math.sqrt(s[1]/samples);cleanRms=Math.sqrt(s[2]/samples);cleanHighPassRms=Math.sqrt(s[3]/samples);
            similarityAvailable=s[0]>0&&s[1]>0;
            zeroLagSimilarity=similarityAvailable?Math.max(-1,Math.min(1,s[4]/Math.sqrt(s[0]*s[1]))):0;
            nearClipped=(int)s[5];referenceClipped=(int)s[6];cleanClipped=(int)s[7];
            // ADC bias can dominate raw cosine similarity even with no speaker reference.
            // Centered statistics are diagnostics, not a barge-in classifier.
            double n=Math.max(0,s[0]-s[8]*s[8]/samples),r=Math.max(0,s[1]-s[9]*s[9]/samples);
            double c=Math.max(0,s[2]-s[10]*s[10]/samples);
            nearAcRms=Math.sqrt(n/samples);referenceAcRms=Math.sqrt(r/samples);cleanAcRms=Math.sqrt(c/samples);
            acSimilarityAvailable=n>0&&r>0;
            acSimilarity=acSimilarityAvailable?Math.max(-1,Math.min(1,(s[4]-s[8]*s[9]/samples)/Math.sqrt(n*r))):0;
        }
    }
}
