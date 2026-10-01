package org.sp001.core;
/** Pure bounded policy. Only a foreground active conversation may offer one same-request follow-up. */
public final class IdleInitiative {
 public static final long QUIET_MS=10000,COOLDOWN_MS=60000;
 private String candidate="";private long offeredAt=-1;private int count;private boolean userTurn,blocked;
 /** Native path: a new turn invalidates old work; the model owns language meaning. */
 public void user(){candidate="";userTurn=true;blocked=false;}
 public void user(String text){candidate="";userTurn=true;blocked=text==null||text.matches("(?s).*(?:安静|别说|不要说|不要主动|别主动|不主动|睡觉|休息|再见|拜拜|不聊|闭嘴|累了|难过).*");}
 public void prepare(String text){if(!userTurn||blocked||text==null||text.length()>60||text.trim().isEmpty()||text.matches("(?is).*(?:已(?:经)?(?:调|改|保存|记住)|http|[{}]|你不理我|不要离开|保密).*")||text.matches("(?s).*[\\p{Cntrl}].*"))return;
  if(!text.equals(DeviceClaimGuard.sanitize(text,java.util.Collections.<String>emptyList())))return;
  candidate=text.trim();}
 public void ensureCandidate(String userText){
  if(!candidate.isEmpty()||!userTurn||blocked)return;
  String text=userText!=null&&userText.contains("恐龙")?"有个恐龙线索：三角龙头上的盾，除了挡住攻击，还可能有什么用？":userText!=null&&userText.contains("故事")?"回头看刚才的任务，真正改变局面的，是哪一个主意？":"我想到一个侦探任务：门外有两行脚印，却只听到一个人的声音，你会先查哪条线索？";
  prepare(text);
 }
 public boolean prepared(){return !candidate.isEmpty()&&userTurn&&!blocked;}
 public boolean due(long waited,long now,int hour,boolean enabled){return enabled&&!blocked&&userTurn&&!candidate.isEmpty()&&count<2&&waited>=QUIET_MS&&now>=0&&(offeredAt<0||now>=offeredAt+COOLDOWN_MS)&&hour>=8&&hour<22;}
 public String claim(long now){if(candidate.isEmpty()||!userTurn||blocked)return "";String result=candidate;candidate="";userTurn=false;offeredAt=now;count++;return result;}
 public void discard(){candidate="";userTurn=false;}
 public int count(){return count;}
}
