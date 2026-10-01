package org.sp001.core;
import java.util.*;
/** Coalesce tiny model records for story TTS. A short teaser must not substitute for a complete chapter. */
public final class StorySpeechBuffer {
 public static final int MIN_MODEL_CHARACTERS=250;
 private final StringBuilder pending=new StringBuilder();private String emotion="neutral",lesson="";private int total,emitted;private boolean committed,finished;
 public StorySpeechBuffer(){this(true);}
 public StorySpeechBuffer(boolean hasRelevantFallback){committed=!hasRelevantFallback;}
 public List<ReplySegment> accept(ReplySegment s){
  if(finished||s==null)throw new IllegalStateException("STORY_STREAM_CLOSED");
  List<ReplySegment> out=new ArrayList<ReplySegment>();
  // Actions remain immediate and once-only, independent of the pending story's source.
  if(!s.actions.isEmpty()||!s.memoryQuote.isEmpty()||!s.followUp.isEmpty()||s.end)
   out.add(new ReplySegment("",s.emotion,s.actions,s.end,s.followUp,s.memoryQuote));
  if(!s.speech.isEmpty()){
   if(total+s.speech.length()>ChildCompanionPolicy.STORY_TEXT_LIMIT)throw new IllegalStateException("STORY_TEXT_BOUND");
   pending.append(s.speech);total+=s.speech.length();emotion=s.emotion;if(!s.lesson.isEmpty())lesson=s.lesson;
   if(!committed&&total>=MIN_MODEL_CHARACTERS)committed=true;
   if(committed&&pending.length()>=70)flush(out);
  }return out;
 }
 private void flush(List<ReplySegment> out){if(pending.length()==0)return;String text=pending.toString();pending.setLength(0);for(int at=0;at<text.length();at+=180){String piece=text.substring(at,Math.min(text.length(),at+180));out.add(new ReplySegment(piece,emotion,Collections.<String>emptyList(),false,"","",at+180>=text.length()?lesson:""));emitted+=piece.length();}lesson="";}
 public List<ReplySegment> finish(String fallback){
  if(finished)throw new IllegalStateException("STORY_STREAM_CLOSED");finished=true;
  List<ReplySegment> out=new ArrayList<ReplySegment>();if(!committed&&fallback!=null&&!fallback.isEmpty()){
  if(fallback.length()>ChildCompanionPolicy.STORY_TEXT_LIMIT)throw new IllegalStateException("STORY_TEXT_BOUND");
  pending.setLength(0);lesson="";
  StringBuilder part=new StringBuilder();for(String sentence:fallback.split("(?<=[。！？])")){if(part.length()+sentence.length()>150&&part.length()>0){out.add(ReplySegment.speech(part.toString(),"curious"));part.setLength(0);}part.append(sentence);}if(part.length()>0)out.add(ReplySegment.speech(part.toString(),"curious"));
 }else flush(out);return out;}
 public int modelCharacters(){return total;}
}
