package org.sp001.core;
/** Explicit short practice with transcript-only feedback. Never claims acoustic assessment. */
public final class EnglishAdventure {
 // Only played conversational state and spacing; meaning is never decided by keyword routing.
 private String lesson="none",pendingLesson="none";
 private boolean disabled,pendingDisabled,userSuppressed;
 private int quietTurns=3,pendingQuietTurns=3;
 public static final String CAPABILITY="transcript-practice-not-phoneme-assessment";
 public static boolean validState(String value){return value!=null&&value.matches("none|meaning|repeat|complete|off");}
 public boolean awaiting(){return lesson.equals("meaning")||lesson.equals("repeat");}
 public boolean disabled(){return disabled;}
 public void cancel(){lesson=pendingLesson="none";userSuppressed=false;}
 public void begin(){pendingLesson="none";pendingDisabled=disabled;userSuppressed=false;pendingQuietTurns=Math.min(4,quietTurns+1);}
 /** Always let the existing LLM interpret the actual utterance, including refusal and translation. */
 public String respond(String raw){begin();if(ChildCompanionPolicy.noEnglish(raw)){userSuppressed=true;pendingDisabled=true;}return null;}
 public boolean suppressedNow(){return userSuppressed;}
 public String hint(){
  return userSuppressed?"当前明确拒绝英语：本轮只用中文回应现在的分享或问题，不翻译新话题，不教单词、不跟读、不猜义。已有英语任务已取消。":pendingDisabled?"此前已拒绝英语，除非这次明确重新请求，否则不插入英语。":awaiting()?"前文邀请过练习，但若最新话题改变就立即放下旧练习，不能把换题当答错。":canIntroduce()?"可在真正合适时偶尔引导英语，翻译和分享成功后不额外出题。":"现在不主动新增英语任务，仍正常响应用户主动追问。";
 }
 public void observe(String state,String spoken){
  if(!validState(state)||spoken==null||spoken.trim().isEmpty())return;
  if(state.equals("off")){pendingDisabled=true;pendingLesson="none";return;}
  if(state.equals("meaning")||state.equals("repeat")){
   if(userSuppressed||!spoken.matches("(?s).*[A-Za-z].*")||!spoken.matches("(?s).*(?:你.{0,5}(?:猜|说|试)|轮到你|跟我|跟读|试着说|请说|猜猜).*"))return;
   pendingLesson=state;pendingQuietTurns=0;pendingDisabled=false;
  }else if(state.equals("complete")){pendingLesson="none";pendingQuietTurns=0;}
  else pendingLesson="none";
 }
 public boolean canIntroduce(){return !pendingDisabled&&!awaiting()&&pendingQuietTurns>=3;}
 public void commit(){lesson=pendingLesson;disabled=pendingDisabled;quietTurns=pendingQuietTurns;}
 public void rollback(){pendingLesson=lesson;pendingDisabled=disabled;pendingQuietTurns=quietTurns;userSuppressed=false;}
}
