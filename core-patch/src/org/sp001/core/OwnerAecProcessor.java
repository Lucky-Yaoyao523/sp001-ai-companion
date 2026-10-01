package org.sp001.core;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Isolated stock AECM, exact 10ms stereo frames, never the stock singleton.
 * Called and closed by one capture owner thread. No AGC may alter the reference/near pair first.
 * Owned by the continuous capture adapter; installed-device acceptance is separate.
 */
final class OwnerAecProcessor {
    // Real stereo replay with the speaker EQ/gain requested: mode 1 produced
    // far-only onsets; mode 3 retained the external utterance without those onsets.
    // This is a candidate setting, not proof of general human double-talk accuracy.
    static final short ECHO_MODE=3;
    interface Kernel {void process(short[] near,short[] reference,short[] clean)throws Exception;void close()throws Exception;}
    /** Borrowed mono data is wiped immediately after return; an asynchronous sink MUST copy it. */
    interface Sink {void accept(byte[] mono)throws Exception;}
    private final Kernel kernel;
    private final AecSignalMetrics metrics;
    private final ReferenceEchoFilter referenceFilter;
    private final short[] referenceResidual=new short[160];
    private static volatile boolean nativeReleaseUnconfirmed;
    static boolean releaseConfirmed(){return !nativeReleaseUnconfirmed;}
    private final byte[] stereo=new byte[640],mono=new byte[320];
    private final short[] near=new short[160],reference=new short[160],clean=new short[160];
    private int used;private boolean closed,failed;
    OwnerAecProcessor()throws Exception{this(new NativeKernel(),null,true);}
    OwnerAecProcessor(AecSignalMetrics metrics)throws Exception{this(new NativeKernel(),metrics,true);}
    OwnerAecProcessor(Kernel kernel){this(kernel,null);}
    OwnerAecProcessor(Kernel kernel,AecSignalMetrics metrics){this(kernel,metrics,false);}
    OwnerAecProcessor(Kernel kernel,AecSignalMetrics metrics,boolean useReferenceFilter){if(kernel==null)throw new IllegalArgumentException("AEC_KERNEL");this.kernel=kernel;this.metrics=metrics;referenceFilter=useReferenceFilter?new ReferenceEchoFilter():null;}
    void accept(byte[] input,int count,Sink sink)throws Exception{
        if(closed)throw new IOException("AEC_CLOSED");
        if(failed)throw new IOException("AEC_FAILED");
        if(input==null||count<0||count>input.length||count%4!=0||sink==null)throw new IOException("AEC_INPUT_BOUND");
        try{for(int at=0;at<count;){
            int n=Math.min(count-at,stereo.length-used);System.arraycopy(input,at,stereo,used,n);at+=n;used+=n;
            if(used!=stereo.length)continue;
            for(int i=0;i<160;i++){int p=i*4;near[i]=(short)((stereo[p]&255)|(stereo[p+1]<<8));reference[i]=(short)((stereo[p+2]&255)|(stereo[p+3]<<8));}
            Arrays.fill(clean,(short)0);
            if(referenceFilter!=null)referenceFilter.process(near,reference,referenceResidual);
            kernel.process(referenceFilter==null?near:referenceResidual,reference,clean);
            if(metrics!=null)metrics.record(near,reference,clean);
            for(int i=0;i<160;i++){mono[i*2]=(byte)clean[i];mono[i*2+1]=(byte)(clean[i]>>8);}
            used=0;
            try{sink.accept(mono);}finally{Arrays.fill(mono,(byte)0);}
        }}catch(Exception error){failed=true;throw error;}
    }
    void close()throws Exception{
        if(closed)return;closed=true;
        try{kernel.close();}finally{if(referenceFilter!=null)referenceFilter.close();Arrays.fill(referenceResidual,(short)0);Arrays.fill(stereo,(byte)0);Arrays.fill(mono,(byte)0);Arrays.fill(near,(short)0);Arrays.fill(reference,(short)0);Arrays.fill(clean,(short)0);used=0;}
    }
    private static final class NativeKernel implements Kernel {
        private final Method far,process,free;private int handle;private boolean released;
        static Method method(Class<?> c,String name,Class<?>...types)throws Exception{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
        static void zero(Object value,String error)throws IOException{if(!(value instanceof Integer)||((Integer)value)!=0)throw new IOException(error);}
        NativeKernel()throws Exception{
            System.loadLibrary("webrtc-jni");Class<?> c=Class.forName("com.webrtc.WebRtcWrapper");
            Method create=method(c,"nativeCreateAecmInstance"),init=method(c,"nativeInitializeAecmInstance",int.class,int.class),config=method(c,"nativeSetConfig",int.class,short.class,short.class);
            free=method(c,"nativeFreeAecmInstance",int.class);far=method(c,"nativeBufferFarend",int.class,short[].class,int.class);
            process=method(c,"nativeAecmProcess",int.class,short[].class,short[].class,short[].class,short.class,short.class);
            handle=(Integer)create.invoke(null);
            if(handle==0||handle==-1){released=true;throw new IOException("AEC_CREATE_FAILED");}
            try{
                zero(init.invoke(null,handle,16000),"AEC_INIT_FAILED");
                // Original JNI order verified from the shipped ARM library: echoMode, cngMode.
                zero(config.invoke(null,handle,ECHO_MODE,(short)1),"AEC_CONFIG_FAILED");
            }catch(Exception failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        public void process(short[] near,short[] reference,short[] clean)throws Exception{
            if(released)throw new IOException("AEC_CLOSED");
            zero(far.invoke(null,handle,reference,160),"AEC_FAREND_FAILED");
            zero(process.invoke(null,handle,near,null,clean,(short)160,(short)0),"AEC_PROCESS_FAILED");
        }
        public void close()throws Exception{if(released)return;released=true;int owned=handle;handle=0;
            try{zero(free.invoke(null,owned),"AEC_FREE_FAILED");}catch(Exception failure){nativeReleaseUnconfirmed=true;throw failure;}
        }
    }
}
