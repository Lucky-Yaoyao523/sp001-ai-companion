package org.sp001.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Incremental presentation decoder: read only top-level speech strings and emit
 * complete phrases while the provider is still writing the same response.
 * No topic classifier, content rewriting, tool execution or early memory commit. */
final class NativeReplyStream {
    private int events, textCharacters;
    private long sequence = -1;
    private final String user;
    private final StringBuilder wire=new StringBuilder(), priorMessages=new StringBuilder();
    private final StringBuilder delivered = new StringBuilder();
    private String messageId = "";
    private boolean toolSeen, buffered, clientToolPending, newSearchAnswer;
    private final java.util.Set<String> pendingSearches=new java.util.HashSet<String>();
    private String emotion="neutral";
    private int deliveredRecords;
    NativeReplyStream(){user=null;}
    NativeReplyStream(String currentUser){user=currentUser;}
    int deliveredRecords(){return deliveredRecords;}
    List<ReplySegment> accept(JSONObject event) throws IOException {
        List<ReplySegment> ready=new ArrayList<ReplySegment>();
        if (event == null) throw new IOException("NATIVE_EVENT_MISSING");
        if (++events > 16000) throw new IOException("NATIVE_EVENT_BOUND");
        if (event.has("sequence_number")) {
            Object value = event.opt("sequence_number");
            if (!(value instanceof Integer) && !(value instanceof Long))
                throw new IOException("NATIVE_EVENT_SEQUENCE");
            long next = ((Number)value).longValue();
            if (next < 0 || next <= sequence) throw new IOException("NATIVE_EVENT_SEQUENCE");
            sequence = next;
        }
        String type = event.optString("type");
        JSONObject item=event.optJSONObject("item");
        if(type.startsWith("response.web_search_call.")){
            if("response.web_search_call.completed".equals(type))completeSearch(event.optString("item_id"));
            else{pendingSearches.add(event.optString("item_id"));toolSeen=true;}
        }
        if("response.output_item.done".equals(type)&&item!=null&&"web_search_call".equals(item.optString("type"))&&"completed".equals(item.optString("status")))completeSearch(item.optString("id"));
        if("response.output_item.added".equals(type)&&item!=null){
            String kind=item.optString("type");
            if("function_call".equals(kind)){clientToolPending=true;toolSeen=true;}
            if("web_search_call".equals(kind)){pendingSearches.add(item.optString("id"));toolSeen=true;}
            if(user!=null&&!toolSeen&&"message".equals(kind)&&"assistant".equals(item.optString("role"))){
                if(newSearchAnswer){
                    // A server search already ran remotely. Its new answer has its own
                    // prefix; pre-search speech must neither be replayed nor compared to it.
                    wire.setLength(0);priorMessages.setLength(0);delivered.setLength(0);newSearchAnswer=false;
                }else if(wire.length()>0){priorMessages.append(prefix().text);wire.setLength(0);}
                messageId=item.optString("id");buffered=false;
            }
        }
        if(pendingSearches.size()>64)throw new IOException("NATIVE_OUTPUT_BOUND");
        if ("response.output_text.delta".equals(type)) {
            Object delta = event.opt("delta");
            if (!(delta instanceof String)) throw new IOException("NATIVE_DELTA_TYPE");
            textCharacters += ((String)delta).length();
            if (textCharacters > 60000) throw new IOException("NATIVE_TEXT_BOUND");
            if(user!=null&&!toolSeen&&!buffered&&!messageId.isEmpty()&&messageId.equals(event.optString("item_id"))){
                wire.append((String)delta);
                if(wire.length()>24576)throw new IOException("NATIVE_TEXT_BOUND");
                Prefix decoded=prefix();String text=priorMessages.toString()+decoded.text;
                if(text.length()>ChildCompanionPolicy.STORY_TEXT_LIMIT)throw new IOException("NATIVE_REPLY_TEXT_LIMIT");
                if(!text.startsWith(delivered.toString())){buffered=true;return ready;}
                int start=delivered.length();
                while(start<text.length()){
                    int cut=boundary(text,start,decoded.closed);if(cut<=start)break;
                    String part=text.substring(start,cut);
                    if(hasSpokenContent(part)){
                        // Whitespace belongs to the same decoded prefix; normalization is
                        // the existing TTS codec, not a second semantic content filter.
                        ready.add(ReplySegment.speech(part,emotion));deliveredRecords++;
                    }
                    delivered.append(part);start=cut;
                }
            }
        }
        return ready;
    }
    private void completeSearch(String id){
        if(!id.isEmpty()&&pendingSearches.remove(id)){
            toolSeen=clientToolPending||!pendingSearches.isEmpty();
            if(!toolSeen)newSearchAnswer=true;
        }
    }
    private static final class Prefix {final String text;final boolean closed;Prefix(String t,boolean c){text=t;closed=c;}}
    private static final class Quoted {final String text;final int end;final boolean closed;Quoted(String t,int e,boolean c){text=t;end=e;closed=c;}}
    /** A partial JSON string may end between backslash/u/hex digits or surrogate halves. */
    private static Quoted quoted(String source,int begin)throws IOException{
        StringBuilder value=new StringBuilder();int i=begin+1;
        for(;i<source.length();i++){
            char c=source.charAt(i);
            if(c=='"')return new Quoted(value.toString(),i+1,true);
            if(c<' ')throw new IOException("NATIVE_REPLY_FORMAT");
            if(c=='\\'){
                if(++i>=source.length())break;c=source.charAt(i);
                switch(c){
                    case '"':case '\\':case '/':break;
                    case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                    case 'b':c='\b';break;case 'f':c='\f';break;
                    case 'u':
                        if(i+4>=source.length())return stable(value,source.length());
                        int code=0;for(int n=1;n<=4;n++){int h=Character.digit(source.charAt(i+n),16);if(h<0)throw new IOException("NATIVE_REPLY_FORMAT");code=code*16+h;}c=(char)code;i+=4;break;
                    default:throw new IOException("NATIVE_REPLY_FORMAT");
                }
            }
            value.append(c);
        }
        return stable(value,source.length());
    }
    private static Quoted stable(StringBuilder value,int end){
        if(value.length()>0&&Character.isHighSurrogate(value.charAt(value.length()-1)))value.setLength(value.length()-1);
        return new Quoted(value.toString(),end,false);
    }
    private static int spaces(String s,int i){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;return i;}
    private Prefix prefix()throws IOException{
        String source=wire.toString().trim();if(source.startsWith("```")){int n=source.indexOf('\n');if(n<0)return new Prefix("",false);source=source.substring(n+1).trim();}
        if(source.isEmpty())return new Prefix("",false);
        if(source.charAt(0)!='{'){
            // No lifecycle envelope: keep the whole unplayed draft available to the
            // completed-response validator. A later native tool still works; an
            // unframed final answer receives one bounded format repair, not playback.
            return new Prefix("",false);
        }
        StringBuilder text=new StringBuilder();int depth=0;boolean closed=false,lifecycle=false;
        for(int i=0;i<source.length();){
            char c=source.charAt(i);
            if(c=='{'||c=='['){depth++;i++;continue;}
            if(c=='}'||c==']'){depth--;i++;continue;}
            if(c!='"'){i++;continue;}
            Quoted key=quoted(source,i);if(!key.closed)break;i=key.end;
            int colon=spaces(source,i);if(depth!=1||colon>=source.length()||source.charAt(colon)!=':')continue;
            int start=spaces(source,colon+1);if(start>=source.length())break;
            if("continue_listening".equals(key.text)){
                int end=start;while(end<source.length()&&Character.isLetter(source.charAt(end)))end++;
                String flag=source.substring(start,end);
                lifecycle=("true".equals(flag)||"false".equals(flag))&&end<source.length()&&(",}".indexOf(source.charAt(end))>=0||Character.isWhitespace(source.charAt(end)));
            }
            if(source.charAt(start)!='"'){i=start;continue;}
            Quoted value=quoted(source,start);i=value.end;
            if("speech".equals(key.text)){
                String part=value.text.replaceAll("[*#`]+","").trim();
                text.append(part);closed=value.closed;
            }else if("emotion".equals(key.text)&&value.closed&&value.text.matches("neutral|happy|playful|surprised|curious|caring"))emotion=value.text;
            if(!value.closed)break;
        }
        // Missing lifecycle metadata must remain unplayed so the bounded model
        // repair can decide it. A normal header-first answer still streams early.
        return lifecycle?new Prefix(text.toString(),closed):new Prefix("",false);
    }
    private static int boundary(String text,int start,boolean complete){
        for(int i=start;i<text.length();i++){
            char c=text.charAt(i);boolean stop="。！？!?；;\n".indexOf(c)>=0;
            stop|=c=='.'&&(i+1==text.length()?complete:Character.isWhitespace(text.charAt(i+1)));
            stop|=i-start>=55&&"，,：:".indexOf(c)>=0;
            if(stop){
                int end=i+1;while(end<text.length()&&"\"'”’）)".indexOf(text.charAt(end))>=0)end++;
                if(end==text.length()&&!complete)return -1;
                return end;
            }
        }
        if(complete&&start<text.length())return text.length();
        return -1;
    }
    /** A boundary may isolate a second punctuation mark; it has no TTS audio. */
    private static boolean hasSpokenContent(String text){
        for(int i=0;i<text.length();i++)if(Character.isLetterOrDigit(text.charAt(i)))return true;
        return false;
    }
    /** Flush a completed assistant preamble before a terminal tool without replay.
     * The provider owns the message; only the already-delivered character offset is local. */
    String unspokenPrefix()throws IOException{
        String text=priorMessages.toString()+prefix().text;
        if(!text.startsWith(delivered.toString()))throw new IOException("NATIVE_STREAM_FINAL_MISMATCH");
        return text.substring(delivered.length());
    }
    /** Remove exactly the prefix already sent to TTS; retain final-only memory/follow-up metadata. */
    List<ReplySegment> remaining(List<ReplySegment> complete)throws IOException{
        if(delivered.length()==0)return complete;
        StringBuilder finalText=new StringBuilder();for(ReplySegment s:complete)finalText.append(s.speech);
        if(!finalText.toString().startsWith(delivered.toString()))throw new IOException("NATIVE_STREAM_FINAL_MISMATCH");
        int skip=delivered.length();List<ReplySegment> rest=new ArrayList<ReplySegment>();
        for(ReplySegment s:complete){
            int take=Math.min(skip,s.speech.length());skip-=take;String tail=s.speech.substring(take);
            if(!tail.isEmpty()||s.end||!s.followUp.isEmpty()||!s.memoryQuote.isEmpty())
                rest.add(new ReplySegment(tail,s.emotion,Collections.<String>emptyList(),s.end,s.followUp,s.memoryQuote,s.lesson));
        }
        return rest;
    }
}
