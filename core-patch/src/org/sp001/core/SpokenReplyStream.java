package org.sp001.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pure incremental content assembler. Reasoning must never be supplied to this class.
 * Emits complete stable sentences and retains tags/partial words until validated.
 */
public final class SpokenReplyStream {
    private final StringBuilder raw=new StringBuilder();
    private int contentStart=-1,emitted;
    private boolean finished;
    private String emotion="neutral";
    private final int speechLimit;
    public SpokenReplyStream(){this(500);}
    public SpokenReplyStream(int maximum){if(maximum!=500&&maximum!=1200)throw new IllegalArgumentException("SPEECH_LIMIT");speechLimit=maximum;}
    public String emotion(){return emotion;}
    public List<String> append(String content)throws IOException{
        if(finished)throw new IOException("CHAT_CONTENT_AFTER_FINISH");
        if(content==null)throw new IOException("CHAT_DELTA_TYPE");
        if(raw.length()+content.length()>speechLimit+50)throw new IOException("MINIMAX_SPEECH_BOUND");
        raw.append(content);String value=raw.toString();
        if(value.indexOf('<')>=0||value.indexOf('>')>=0)throw new IOException("CHAT_STREAM_MARKUP");
        if(contentStart<0){
            int at=0;while(at<value.length()&&Character.isWhitespace(value.charAt(at)))at++;
            if(at==value.length())return new ArrayList<String>();
            if(value.charAt(at)=='['){
                int end=value.indexOf(']',at);if(end<0){if(value.length()-at>18)throw new IOException("CHAT_PREFIX_BOUND");return new ArrayList<String>();}
                String mood=value.substring(at+1,end);if(!mood.matches("neutral|happy|playful|surprised|curious|caring"))throw new IOException("CHAT_PREFIX_INVALID");
                emotion=mood;at=end+1;
            }
            contentStart=at;emitted=at;
        }
        List<String> ready=new ArrayList<String>();
        // A control tag or quotation may straddle any provider delta. Hold the tail
        // from '[' until final parsing so no partial control syntax is spoken.
        int bracket=value.indexOf('[',emitted),limit=bracket<0?value.length():bracket;
        for(int i=emitted;i<limit;i++){
            char c=value.charAt(i);
            if("。！？!?；;\n".indexOf(c)>=0&&hasSpokenContent(value,emitted,i)){
                String chunk=MiniMaxCodec.speech(value.substring(emitted,i+1),speechLimit);
                ready.add(chunk);emitted=i+1;
            }
        }
        return ready;
    }
    /** A complete short answer (for example 七。 or 7。) is already useful speech.
     * Do not wait for unrelated later tokens; punctuation alone is never a sentence. */
    private static boolean hasSpokenContent(String value,int begin,int end){
        for(int i=begin;i<end;i++)if(Character.isLetterOrDigit(value.charAt(i)))return true;
        return false;
    }
    public String finish(String reason)throws IOException{
        if(finished)throw new IOException("CHAT_DUPLICATE_FINISH");
        if(!"stop".equals(reason))throw new IOException("MINIMAX_REPLY_INCOMPLETE");
        String withoutTags=raw.toString().replaceAll("\\[(?:neutral|happy|playful|surprised|curious|caring|end)\\]","");
        if(withoutTags.indexOf('[')>=0||withoutTags.indexOf(']')>=0)throw new IOException("CHAT_CONTROL_INCOMPLETE");
        finished=true;
        ReplyExpression parsed=ReplyExpression.parse(raw.toString(),speechLimit);
        String suffix=contentStart<0?parsed.text:raw.substring(emitted).trim();
        suffix=suffix.replaceAll("\\[(?:neutral|happy|playful|surprised|curious|caring|end)\\]","").trim();
        if(suffix.matches("[。.!！\\s]*"))return "";
        return MiniMaxCodec.speech(suffix,speechLimit);
    }
    public ReplyExpression result()throws IOException{
        if(!finished)throw new IOException("CHAT_STREAM_INCOMPLETE");return ReplyExpression.parse(raw.toString(),speechLimit);
    }
}
