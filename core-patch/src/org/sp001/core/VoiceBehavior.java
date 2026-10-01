package org.sp001.core;
/** Finite semantic command boundary. No I/O, generated code, hidden timers or audio access. */
public final class VoiceBehavior {
 public enum Kind { CHAT,AUDIO,MODE,STOP,END,EYE_SMILE,EYE_SURPRISE,EYE_NEUTRAL,EXPRESSIVE_ON,EXPRESSIVE_OFF,INITIATIVE_ON,INITIATIVE_OFF,TOPIC,HELP }
 public static final class Decision {
  public final Kind kind;public final int audio;public final Boolean legacy;
  Decision(Kind k,int a,Boolean l){kind=k;audio=a;legacy=l;}
  public boolean local(){return kind!=Kind.CHAT;}
 }
 private static Decision of(Kind k){return new Decision(k,0,null);}
 private VoiceBehavior(){}
 public static Decision classify(String text){
  if(text==null||text.length()>160)return of(Kind.CHAT);
  // Mentions, examples and chained commands are not an imperative to an actuator.
  if(text.matches("(?s).*(?:[\"'“”‘’《》]|他说|他說|她说|她說|如果|假如|假设|假設|例如|比如|然后|然後|删除|刪除|清除|恢复出厂|恢復出廠|或者|还是|還是).*"))return of(Kind.CHAT);
  int audio=AudioCommand.parse(text);if(audio!=AudioCommand.NONE)return new Decision(Kind.AUDIO,audio,null);
  Boolean legacy=ModeCommand.target(text);if(legacy!=null)return new Decision(Kind.MODE,0,legacy);
  if(MiniMaxCodec.stopCommand(text))return of(Kind.STOP);
  String s=text.trim().replaceAll("[\\s，。！？、,.!?]","")
   .replace('請','请').replace('俠','侠').replace('個','个').replace('驚','惊').replace('訝','讶')
   .replace('點','点').replace('話','话').replace('動','动').replace('問','问').replace('說','说')
   .replace('別','别').replace('總','总').replace('開','开').replace('啟','启').replace('閉','闭')
   .replace('關','关').replace('豐','丰').replace('復','复').replace('標','标').replace('準','准')
   .replace('嗎','吗').replace('樣','样').replace('來','来').replace('題','题').replace('戲','戏').replace('謎','谜');
  if(s.matches("(?:我困了|我累了)?(?:先不聊了|先不说了|今天先聊到这里|我要睡觉了)"))return of(Kind.END);
  s=s.replaceFirst("^(?:蜘蛛侠|小蜘蛛)","").replaceFirst("^(?:麻烦你|麻烦|请你|请|你能不能|能不能|你可以|可以|你能|能|帮我|给我)","")
   .replaceFirst("^(?:你|把)","").replaceFirst("(?:好吗|好不好|可以吗|行吗|吗|吧|啊|呀|啦)+$","");
  if(s.matches("(?:主动(?:一点点|一点|些|一些)(?:别总等我问)?|开启主动接话|开启自主开口|多主动接话)"))return of(Kind.INITIATIVE_ON);
  if(s.matches("(?:(?:不要|别)(?:主动)?(?:提问|追问|反问)(?:只回答(?:就行)?)?|只回答(?:我)?(?:就行)?|关闭主动接话|关闭自主开口|不要主动找话题|(?:不要|别)(?:再)?主动(?:说话|开口|接话))"))return of(Kind.INITIATIVE_OFF);
  if(s.matches("(?:笑(?:一个|一下)|做个笑脸|做一个笑脸|露个笑脸|笑一笑|开心一点)"))return of(Kind.EYE_SMILE);
  if(s.matches("(?:做(?:个|一个)惊讶(?:的)?表情|惊讶一下|做个惊讶脸)"))return of(Kind.EYE_SURPRISE);
  if(s.matches("(?:恢复(?:普通|正常|中性)表情|别笑了|不要笑|表情恢复正常)"))return of(Kind.EYE_NEUTRAL);
  if(s.matches("(?:表情(?:丰富|生动)(?:一点|些)?|眼睛动一动|开启表情|打开表情|恢复表情)"))return of(Kind.EXPRESSIVE_ON);
  if(s.matches("(?:关闭表情|不要做表情(?:了)?|别挤眉弄眼(?:了)?|眼睛别乱动(?:了)?)"))return of(Kind.EXPRESSIVE_OFF);
  // Games, riddles, topic requests and capability questions are conversation, not actuators.
  // Keep legacy enum values for compatibility, but never short-circuit free user input to them.
  return of(Kind.CHAT);
 }
 /** External semantic predictions never override explicit local negation/closing/allowlist policy. */
 public static boolean agreesWithLocal(String text,String proposed){
  if(proposed==null)return false;return classify(text).kind.name().equals(proposed);
 }
}
