package org.sp001.core;
import java.io.Reader;
import java.io.IOException;

/** Bounded SSE framing, independent of HTTP and JSON. Never treats a partial event as complete. */
public final class SseEventReader {
    private final Reader reader;private final int eventLimit,totalLimit;private int total;
    public static Reader utf8(java.io.InputStream input){
        return new java.io.BufferedReader(new java.io.InputStreamReader(input,
            java.nio.charset.Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)));
    }
    public SseEventReader(Reader reader,int eventLimit,int totalLimit){
        if(reader==null||eventLimit<16||totalLimit<eventLimit)throw new IllegalArgumentException("SSE_LIMITS");
        this.reader=reader;this.eventLimit=eventLimit;this.totalLimit=totalLimit;
    }
    public String next()throws IOException{
        StringBuilder data=new StringBuilder(),line=new StringBuilder();boolean hasData=false;
        while(true){
            int c=reader.read();if(c<0){if(line.length()>0||hasData)throw new IOException("SSE_TRUNCATED_EVENT");return null;}
            if(++total>totalLimit)throw new IOException("SSE_TOTAL_BOUND");
            if(c!='\n'){line.append((char)c);if(line.length()>eventLimit)throw new IOException("SSE_LINE_BOUND");continue;}
            String value=line.toString();line.setLength(0);if(value.endsWith("\r"))value=value.substring(0,value.length()-1);
            if(value.length()==0){if(hasData)return data.toString();continue;}
            if(value.startsWith("data:")){
                String part=value.substring(5);if(part.startsWith(" "))part=part.substring(1);
                if(data.length()+part.length()+1>eventLimit)throw new IOException("SSE_EVENT_BOUND");
                if(hasData)data.append('\n');data.append(part);hasData=true;
            }
        }
    }
}
