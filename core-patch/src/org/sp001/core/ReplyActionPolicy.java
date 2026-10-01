package org.sp001.core;
import java.util.*;
/** Maps a bounded model proposal to a known local action, grounded in current user text. */
public final class ReplyActionPolicy {
 private ReplyActionPolicy(){}
 private static String normal(String v){return v==null?"":v.replace('聲','声').replace('調','调').replace('點','点').replace('聽','听').replace('開','开').replace('關','关').replace('閉','闭').replace('復','复').replace('標','标').replace('準','准').replace('動','动').replace('問','问').replace('說','说').replace('別','别').replace('靈','灵').replace('驚','惊').replace('訝','讶').replace('豐','丰').replace('請','请').replace('個','个');}
 public static String family(String action){
  if(action==null)return "";
  if(action.equals("volume_up")||action.equals("volume_down"))return "volume";
  if(action.equals("get_volume"))return "volume_read";
  if(action.equals("mic_more")||action.equals("mic_less")||action.equals("mic_default"))return "mic";
  if(action.equals("eye_smile")||action.equals("eye_surprise")||action.equals("eye_neutral"))return "eye";
  if(action.equals("expressive_on")||action.equals("expressive_off"))return "expressive";
  if(action.equals("initiative_on")||action.equals("initiative_off"))return "initiative";
  return "";
 }
 public static boolean consistent(List<String> actions){
  Map<String,String> families=new HashMap<String,String>();
  for(String a:actions){String f=family(a);if(f.isEmpty())return false;String old=families.put(f,a);if(old!=null&&!old.equals(a))return false;}
  return actions.size()<=3;
 }
 public static boolean allowed(String user,String action){
  if(user==null||user.length()>1000||family(action).isEmpty())return false;
  String s=normal(user);
  if(s.matches("(?s)^\\s*只(?:翻译|解釋|解释).*" )||s.matches("(?s).*(?:别|不要|不用|不必).{0,8}(?:改变|調整|调整|改动).{0,6}(?:设置|設置|音量|声音).*"))return false;
  if(s.matches("(?s).*(?:[\"'“”‘’《》]|他说|她说|它说|如果|假如|假设|例如|比如|删除|清除|出厂).*"))return false;
  // A later explicit negation in the same utterance vetoes an earlier candidate.
  if(action.equals("volume_up")&&s.matches("(?s).*(?:不要|不用|别).{0,8}(?:调大|提高|调高|大声).*"))return false;
  if(action.equals("volume_down")&&s.matches("(?s).*(?:不要|不用|别).{0,8}(?:调小|降低|调低|小声).*"))return false;
  if(action.equals("eye_smile")&&s.matches("(?s).*(?:不要|不用|别).{0,3}笑.*"))return false;
   String[] clauses=s.split("[，,。；;！？!?\\n]|然后|然後|接着|接著|并且|並且|再把");
   String operation="";
   if(action.equals("mic_more"))operation="(?:提高|调高|增大|增加).{0,5}(?:收音|灵敏)|(?:收音|灵敏).{0,5}(?:高|灵敏|增加)";
   if(action.equals("mic_less"))operation="(?:降低|调低|减少).{0,5}(?:收音|灵敏)";
   if(action.equals("mic_default"))operation="(?:恢复|还原).{0,6}(?:收音|标准|默认)";
   if(action.equals("eye_surprise"))operation="(?:惊讶|惊奇)";
   if(action.equals("eye_neutral"))operation="(?:恢复|还原).{0,6}(?:表情|普通|正常)";
   if(action.equals("expressive_on"))operation="(?:开启|打开|恢复).{0,5}表情";
   if(action.equals("expressive_off"))operation="(?:关闭|关掉).{0,5}表情";
   if(action.equals("initiative_on"))operation="(?:开启|打开).{0,5}(?:主动|接话)";
   if(action.equals("initiative_off"))operation="(?:关闭|关掉).{0,5}(?:主动|接话)";
   if(!operation.isEmpty())for(String clause:clauses)
    if(clause.matches("(?s).*(?:不要|不用|别|不必|无需|不需要).{0,10}(?:"+operation+").*"))return false;
   VoiceBehavior.Decision wanted=decision(action);
   for(String clause:clauses){
    if(clause.matches("(?s).*(?:为什么|為什麼|为何|為何|为啥|原因|原理|有什么区别|是不是|是否|会不会|會不會).*"))continue;
    if(family(action).equals("volume")&&clause.matches("(?s).*(?:电视|電視|手机|手機|电脑|電腦|收音机|视频|視頻|电影|電影|音乐|音樂).{0,6}(?:声音|音量|太吵|太响).*"))continue;
   VoiceBehavior.Decision d=VoiceBehavior.classify(clause);
   if(d.local()){
    if(d.kind==wanted.kind&&d.audio==wanted.audio)return true;
    continue;
   }
   if(clause.matches("(?s).*(?:不要|不想|不用|不能|别|不必|无需|不是|并不|没有|不需要).*"))continue;
   if(action.equals("volume_up")&&clause.matches("(?s).*(?:听不清|声音.{0,5}(?:太小|有点小)|音量.{0,5}(?:太低|有点低)|(?:大声|响)(?:一点|一些)|(?:音量|声音).{0,6}(?:提高|调高|调大)).*"))return true;
   if(action.equals("volume_down")&&clause.matches("(?s).*(?:太吵|太响|声音.{0,5}(?:太大|有点大)|音量.{0,5}(?:太高|有点高)|(?:轻声|小声)(?:一点|一些)|(?:音量|声音).{0,6}(?:降低|调低|调小)).*"))return true;
   if(action.equals("eye_smile")&&clause.matches("(?s).*(?:你|蜘蛛侠).*(?:开心|笑一).*"))return true;
   if(action.equals("eye_surprise")&&clause.matches("(?s).*(?:你|表情).*(?:惊讶).*"))return true;
  }
  return false;
 }
 public static VoiceBehavior.Decision decision(String action){
  if("volume_up".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.AUDIO,AudioCommand.LOUDER,null);
  if("volume_down".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.AUDIO,AudioCommand.QUIETER,null);
  if("mic_more".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.AUDIO,AudioCommand.MIC_MORE,null);
  if("mic_less".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.AUDIO,AudioCommand.MIC_LESS,null);
  if("mic_default".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.AUDIO,AudioCommand.MIC_NORMAL,null);
  if("eye_smile".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.EYE_SMILE,0,null);
  if("eye_surprise".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.EYE_SURPRISE,0,null);
  if("eye_neutral".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.EYE_NEUTRAL,0,null);
  if("expressive_on".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.EXPRESSIVE_ON,0,null);
  if("expressive_off".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.EXPRESSIVE_OFF,0,null);
  if("initiative_on".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.INITIATIVE_ON,0,null);
  if("initiative_off".equals(action))return new VoiceBehavior.Decision(VoiceBehavior.Kind.INITIATIVE_OFF,0,null);
  return new VoiceBehavior.Decision(VoiceBehavior.Kind.CHAT,0,null);
 }
 /** Exact imperative clauses only; whole-utterance negation and other-device guards remain. */
 public static List<String> explicitActions(String user){
  if(user==null||user.length()>1000)return Collections.emptyList();
  String[] actions={"volume_up","volume_down","mic_more","mic_less","mic_default","eye_smile","eye_surprise","eye_neutral","expressive_on","expressive_off","initiative_on","initiative_off"};
  LinkedHashSet<String> requested=new LinkedHashSet<String>();
  for(String clause:normal(user).split("[，,。；;！？!?\\n]|然后|然後|接着|接著|并且|並且|再把")){
   VoiceBehavior.Decision actual=VoiceBehavior.classify(clause);if(!actual.local())continue;
   for(String action:actions){VoiceBehavior.Decision d=decision(action);if(actual.kind==d.kind&&actual.audio==d.audio&&allowed(user,action))requested.add(action);}
  }
  List<String> result=new ArrayList<String>(requested);return consistent(result)?result:Collections.<String>emptyList();
 }
 public static final class Gate {
  private long turn;private boolean closed;private final Set<String> used=new HashSet<String>();
  private final Map<String,String> claimed=new HashMap<String,String>(),verified=new HashMap<String,String>();
  public synchronized boolean claim(long id,String action,boolean current){
   if(closed||!current||id<1||id<turn||family(action).isEmpty())return false;
   if(id>turn){turn=id;used.clear();claimed.clear();verified.clear();}
   if(used.size()>=3||!used.add(family(action)))return false;claimed.put(family(action),action);return true;
  }
  public synchronized void recordResult(long id,String action,boolean success){if(!closed&&id==turn&&action!=null&&action.equals(claimed.get(family(action)))&&success)verified.put(family(action),action);}
  public synchronized boolean wasConfirmed(long id,String action){return !closed&&id==turn&&action!=null&&action.equals(verified.get(family(action)));}
  public synchronized List<String> confirmedActions(long id){return !closed&&id==turn?new ArrayList<String>(verified.values()):Collections.<String>emptyList();}
  public synchronized void close(){closed=true;used.clear();claimed.clear();verified.clear();}
 }
}
