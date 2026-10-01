package org.sp001.core;

import java.io.IOException;

/** One presentation pipeline for every answer. Model records are not TTS packet sizes.
 * Validate and split complete text before speaking; preserve cancellation and byte accounting. */
public final class SentencePlayback {
    public interface TextSink {void accept(String sentence)throws Exception;}
    public interface AudioSink {void accept(byte[] pcm)throws Exception;}
    public interface Ports {
        void check()throws Exception;void sentences(TextSink sink)throws Exception;
        int synthesize(String sentence,AudioSink sink)throws Exception;
        void write(byte[] pcm)throws Exception;int finish()throws Exception;void close();
    }
    public static final class Result {public int acceptedBytes,sentences;public boolean drained;}
    private SentencePlayback(){}
    private static final int TTS_CHUNK_CHARS=240,MAX_RECORD_CHARS=1250;
    /** Transport segmentation only: no topic, language intent or conversation state. */
    static java.util.List<String> chunks(String text)throws IOException{
        if(text==null||text.trim().isEmpty()||text.length()>MAX_RECORD_CHARS)throw new IOException("PIPELINE_TEXT_BOUND");
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IOException("PIPELINE_TEXT_ENCODING");
            }else if(Character.isLowSurrogate(c))throw new IOException("PIPELINE_TEXT_ENCODING");
        }
        java.util.List<String> out=new java.util.ArrayList<String>();
        for(int at=0;at<text.length();){
            int end=Math.min(text.length(),at+TTS_CHUNK_CHARS);
            if(end<text.length()){
                if(Character.isHighSurrogate(text.charAt(end-1)))end--;
                for(int i=end;i>at+TTS_CHUNK_CHARS/2;i--){
                    char c=text.charAt(i-1);
                    if("。！？；.!?;\n".indexOf(c)>=0||Character.isWhitespace(c)){end=i;break;}
                }
            }
            String part=text.substring(at,end);if(!part.trim().isEmpty())out.add(part);at=end;
        }
        return out;
    }

    public static void run(final Ports p,final Result result)throws Exception {run(p,result,PcmStreamState.MAX_BYTES);}
    public static void run(final Ports p,final Result result,final int maximumBytes)throws Exception {
        if(maximumBytes<PcmStreamState.MAX_BYTES||maximumBytes>12800000)throw new IllegalArgumentException("PIPELINE_OUTPUT_BUDGET");
        if(p==null||result==null)throw new IllegalArgumentException("PIPELINE_PORTS");
        final Thread owner=Thread.currentThread();final boolean[] accepting={true};
        try{
            p.check();p.sentences(new TextSink(){public void accept(String sentence)throws Exception{
                if(!accepting[0]||Thread.currentThread()!=owner)throw new IOException("PIPELINE_LATE_CALLBACK");
                p.check();java.util.List<String> parts=chunks(sentence);
                if(parts.size()>(maximumBytes>PcmStreamState.MAX_BYTES?96:32)-result.sentences)throw new IOException("PIPELINE_SENTENCE_BOUND");
                for(String part:parts){
                p.check();result.sentences++;
                final int[] written={0};final boolean[] writing={true};int declared;
                try{declared=p.synthesize(part,new AudioSink(){public void accept(byte[] pcm)throws Exception{
                    if(!writing[0]||Thread.currentThread()!=owner)throw new IOException("PIPELINE_LATE_CALLBACK");
                    p.check();if(pcm==null||pcm.length%2!=0||pcm.length>maximumBytes-result.acceptedBytes)throw new IOException("STREAM_PCM_BOUND");
                    p.write(pcm);written[0]+=pcm.length;result.acceptedBytes+=pcm.length;p.check();
                }});}finally{writing[0]=false;}
                p.check();if(declared!=written[0]||declared<2)throw new IOException("TTS_STREAM_BYTE_MISMATCH");
                }
            }});
            accepting[0]=false;p.check();int played=p.finish();p.check();
            if(played!=result.acceptedBytes||played<2)throw new IOException("TTS_STREAM_BYTE_MISMATCH");
            result.drained=true;
        }finally{
            accepting[0]=false;
            try{p.close();}catch(RuntimeException cleanup){
                // Preserve an in-flight failure; cleanup failure after success is not success.
                if(result.drained){result.drained=false;throw cleanup;}
            }
        }
    }
}
