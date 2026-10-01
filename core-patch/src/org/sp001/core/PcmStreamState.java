package org.sp001.core;

import java.io.IOException;

/** Pure incremental PCM contract. Final aggregate audio is refused to prevent double playback. */
public final class PcmStreamState {
    public static final int MAX_BYTES=3840000;
    private boolean ended;private int bytes,events;
    public byte[] accept(int status,String hex)throws IOException {
        if(ended||++events>1024)throw new IOException("TTS_STREAM_SEQUENCE");
        if(status!=1&&status!=2)throw new IOException("TTS_STREAM_STATUS");
        if(status==2){if(hex!=null&&!hex.isEmpty())throw new IOException("TTS_UNEXPECTED_AGGREGATE");ended=true;return new byte[0];}
        if(hex==null||hex.isEmpty())return new byte[0];
        byte[] pcm=MiniMaxCodec.decodePcm(hex);
        if(bytes+pcm.length>MAX_BYTES)throw new IOException("TTS_STREAM_BOUND");bytes+=pcm.length;return pcm;
    }
    public int finish()throws IOException{if(!ended||bytes<3200)throw new IOException("TTS_STREAM_INCOMPLETE");return bytes;}
    public int bytes(){return bytes;}
}
