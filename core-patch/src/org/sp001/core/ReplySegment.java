package org.sp001.core;
import java.util.*;
/** Immutable data for one spoken sentence and its optional presentation metadata. */
public final class ReplySegment {
 public final String speech,emotion,followUp,memoryQuote,lesson;
 public final List<String> actions;
 public final boolean end;
 public ReplySegment(String text,String mood,List<String> commands,boolean closing){this(text,mood,commands,closing,"","");}
 public ReplySegment(String text,String mood,List<String> commands,boolean closing,String follow,String fact){this(text,mood,commands,closing,follow,fact,"");}
 public ReplySegment(String text,String mood,List<String> commands,boolean closing,String follow,String fact,String lessonState){
  lesson=!closing&&EnglishAdventure.validState(lessonState)?lessonState:"";
  followUp=closing||follow==null?"":follow;memoryQuote=fact==null?"":fact;
  speech=text==null?"":text;
  emotion=mood!=null&&mood.matches("neutral|happy|playful|surprised|curious|caring")?mood:"neutral";
  actions=Collections.unmodifiableList(new ArrayList<String>(commands));
  end=closing;
 }
 /** A mixed record may speak its answer only after all actions were confirmed. */
 public String afterActions(String acknowledgement,boolean confirmed){
  String actual=acknowledgement==null?"":acknowledgement;
  return confirmed?actual+speech:actual;
 }
 public static ReplySegment speech(String text,String mood){
  return new ReplySegment(text,mood,Collections.<String>emptyList(),false);
 }
}
