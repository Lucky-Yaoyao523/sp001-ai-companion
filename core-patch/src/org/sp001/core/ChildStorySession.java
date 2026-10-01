package org.sp001.core;
import org.json.*;
/** Curated curriculum checkpoint contains no raw transcript or recordings. */
public final class ChildStorySession {
 public interface Store {String read()throws Exception;void write(String text)throws Exception;}
 private final Store store;private final int[] next=new int[3];private String active="journey";private HeroStoryLibrary.Episode planned;private int plannedIndex,plannedNext;private boolean committed,story,quiet,englishAllowed=true,loaded,hasStory,continuationEligible;private String status="SESSION_ONLY";
 public ChildStorySession(){this(null);}public ChildStorySession(Store s){store=s;}
 private static int slot(String s){return s.equals("journey")?0:s.equals("kingdoms")?1:2;}
 private void load(){if(loaded)return;loaded=true;if(store==null)return;try{String raw=store.read();if(raw==null||raw.isEmpty()){status="READY";return;}if(raw.length()>2048)throw new Exception();JSONObject j=new JSONObject(raw);if(j.optInt("schema")!=1)throw new Exception();String a=j.getString("active");if(!a.equals("open")&&HeroStoryLibrary.count(a)==0)throw new Exception();JSONArray ns=j.getJSONArray("next");if(ns.length()!=3)throw new Exception();int[] valid=new int[3];for(int i=0;i<3;i++){Object v=ns.get(i);if(!(v instanceof Integer)||(Integer)v<0||(Integer)v>1000000)throw new Exception();valid[i]=(Integer)v;}System.arraycopy(valid,0,next,0,3);active=a;hasStory=a.equals("open")||next[0]+next[1]+next[2]>0;continuationEligible=hasStory;status="READY";}catch(Exception bad){status="UNAVAILABLE_PRESERVED";}}
 public boolean wouldTellStory(String user){load();return ChildCompanionPolicy.storyRequest(user)||(ChildCompanionPolicy.continuing(user)&&continuationEligible);}
 public void prepare(String user){load();boolean continuation=ChildCompanionPolicy.continuing(user)&&continuationEligible;story=wouldTellStory(user);continuationEligible=story;quiet=ChildCompanionPolicy.bedtime(user);englishAllowed=!ChildCompanionPolicy.noEnglish(user)&&!quiet;planned=null;committed=false;if(!story)return;
  HeroStoryLibrary.Episode exact=HeroStoryLibrary.exactTitle(user);
  if(exact!=null){active=exact.series;planned=exact;plannedIndex=next[slot(active)];int index=Integer.parseInt(exact.id.substring(exact.id.lastIndexOf('-')+1)),count=HeroStoryLibrary.count(active);plannedNext=plannedIndex+(index-plannedIndex%count+count)%count+1;}
  else if((continuation&&!active.equals("open"))||(!continuation&&HeroStoryLibrary.genericRequest(user))){
   String chosen=HeroStoryLibrary.series(user);if(chosen.isEmpty())chosen=active.equals("open")?"journey":active;String s=HeroStoryLibrary.addressed(user);
   if(s.contains("换一个")||s.contains("换个故事"))if(chosen.equals(active))chosen=active.equals("journey")?"kingdoms":active.equals("kingdoms")?"heroes":"journey";
   active=chosen;plannedIndex=next[slot(active)];plannedNext=plannedIndex+1;planned=HeroStoryLibrary.get(active,plannedIndex);
  }else active="open";
  hasStory=true;
 }
 public boolean isStory(){return story;}public boolean englishAllowed(){return englishAllowed;}
 public String hint(){if(!isStory())return "本轮是普通交流，直接理解当前问题，不强行讲故事或测试。";
  return (planned==null?"这是用户指定的故事、题材或续讲请求。严格按最新user消息和真实上文讲，不套用默认西游、三国或灯塔故事。不知道的专有作品不要冒充原作；只有确实无法确定用户所指时才简短澄清。若只有跨会话续讲而无上文，坦诚请他提示上回讲到哪里。":planned.prompt()+" 这些资料仅适用于当前请求匹配的内容；用户最新指定或纠正优先，不能因为有这份资料就改讲其他作品。")+"\n故事模式讲完整小章，不套普通答复篇幅。"+(quiet?"睡前本章250—350汉字，柔和、少刺激，不插英语练习。":"本章450—650汉字，8—14个连续的JSON语音片段，每段30—100字，有起因、具体尝试、转折和解决。")+
   "首段直接开讲，不先列菜单或问选哪个。讲完完整小章再完成本轮回答并回到聆听；章末不是退出整段会话，end仍按孩子是否想离开来判断。不每两句问还要不要听，不让孩子答对或开口才解锁后文。可以在叙事中示范一句想办法，但不模拟孩子真的回答。保持趣味、声音节奏与少量幽默，不用最后一大段说教。完整讲述不频繁停顿考他，情节通过人物的选择、线索和后果呈现；不要把成长主题讲成大道理。只有本轮语境与英语节奏允许时，才可在完整小章后自然提出一次小任务，不能强行追加或伪造孩子已回答。平时不要反复声明在故事里、想象世界；真实危险或真假追问除外。孩子新问题、换故事或停下优先。";
 }
 public String fallback(){return planned==null?"":HeroStoryNarratives.text(planned.id);}
 public String series(){return active;}public String episodeId(){return planned==null?"":planned.id;}
 public int completed(){load();return next[0]+next[1]+next[2];}
 public void commit(){if(!isStory()||committed)return;int at=active.equals("open")?-1:slot(active);if(at>=0&&next[at]!=plannedIndex)return;if(at>=0)next[at]=Math.min(1000000,plannedNext);
  if(store!=null&&!status.equals("UNAVAILABLE_PRESERVED"))try{JSONArray ns=new JSONArray();for(int n:next)ns.put(n);store.write(new JSONObject().put("schema",1).put("active",active).put("next",ns).toString());status=at<0?"OPEN_TOPIC_NOT_STORED":"SAVED";}catch(Exception bad){if(at>=0)next[at]=plannedIndex;status="SAVE_NOT_CONFIRMED";return;}committed=true;
 }
 public String status(){return status;}public void endTurn(){story=false;planned=null;}
}
