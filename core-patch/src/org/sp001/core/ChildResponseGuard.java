package org.sp001.core;
/** Last-mile guard for narrowly identifiable harmful coaching or invented pronunciation scoring. */
public final class ChildResponseGuard {
 private ChildResponseGuard(){}
 /** This removes only a denied lesson, never fabricates the answer to the new topic. */
 public static String respectEnglishOptOut(String text,boolean suppressed){
  if(!suppressed||text==null)return text;StringBuilder out=new StringBuilder();
  for(String s:text.split("(?<=[。！？!?])"))if(!(s.matches("(?s).*[A-Za-z].*")&&s.matches("(?s).*(?:英文|英语|发音|跟读|跟我说|词语|单词|意思是|可以说).*")))out.append(s);
  return out.toString();
 }
 public static String sanitize(String text){
  if(text==null||text.isEmpty())return text;
   // Preserve the correct translated word; remove only surrogate Chinese pronunciation spellings.
   text=text.replaceAll("[，,](?:(?:发音|读音)(?:听起来|读起来)?|(?:听|读|念)起来)(?:有点|有一点|大约|大概)?(?:类似|像|接近)[^。！？!?,，]{1,28}","");
   // Original Android 5.1 ICU and the JVM both support script=Han; the IsHan alias crashes Android.
   text=text.replaceAll("[，,](?:可以)?(?:念成|读作)[‘’“”'\\\"]?[\\p{script=Han}]{1,8}[‘’“”'\\\"]?","");
   text=text.replace("小蛛","蜘蛛侠");StringBuilder result=new StringBuilder();
  for(String s:text.split("(?<=[。！？!?])")){
   if(s.matches("(?is).*(?:你(?:真|就是|太|这么)(?:懒|笨|胆小)|不(?:读|说|练).{0,8}(?:就不|不给).{0,8}(?:故事|喜欢你)|只有.{0,8}(?:读对|说对).{0,8}(?:才能|才给)).*")){result.append("可以先试一小步，需要时蜘蛛侠会帮忙。");continue;}
   if(s.matches("(?is).*(?:(?:你|刚才).{0,10}(?:发音|读音).{0,10}(?:标准|完美|错了|不对|正确|[0-9]+分)|你的.{0,4}(?:r|l|th|s).{0,4}(?:发音|音).{0,4}(?:错|不对)).*")){result.append("先听蜘蛛侠示范，我们按你的节奏来。");continue;}
   result.append(s);
  }return result.toString();
 }
}
