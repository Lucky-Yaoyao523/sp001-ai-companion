package org.sp001.core;
/** Strip a device-completion assertion unless this turn has the corresponding verified action. */
public final class DeviceClaimGuard {
 private DeviceClaimGuard(){}
 public static String sanitize(String text,java.util.List<String> confirmed){
  if(text==null||text.isEmpty())return text;
  if(confirmed==null)confirmed=java.util.Collections.<String>emptyList();
  StringBuilder out=new StringBuilder();
  for(String s:text.split("(?<=[。！？!?；;])")){
   if(s.matches("(?s)^(?:电视|手机|电脑|他说|她说|故事里|小明|你的).*")&&!s.contains("我的")){out.append(s);continue;}
   boolean completion=s.matches("(?s).*(?:已经|已为你|我已|已|调好了|恢复了|恢复啦|记住了|保存了|大声点儿|小声点儿).*");
   String family=s.matches("(?s).*(?:音量|大声|小声|声音).*")?"volume":s.matches("(?s).*(?:表情|笑眼|笑脸).*")?"eye":s.matches("(?s).*(?:收音|灵敏度).*")?"mic":s.matches("(?s).*(?:主动接话|主动提问|主动性).*")?"initiative":"";
   if(completion&&!family.isEmpty()){boolean proof=false;String required="";
    if(family.equals("volume")){if(s.matches("(?s).*(?:调大|提高|大声).*")&&!s.matches("(?s).*(?:调小|降低|小声).*"))required="volume_up";else if(s.matches("(?s).*(?:调小|降低|小声).*")&&!s.matches("(?s).*(?:调大|提高|大声).*"))required="volume_down";}
    if(family.equals("eye")){
     if(s.matches("(?s).*(?:关闭|关掉|停用).{0,6}表情.*"))required="expressive_off";
     else if(s.matches("(?s).*(?:开启|打开|启用).{0,6}表情.*"))required="expressive_on";
     else if(s.matches("(?s).*(?:恢复|普通|正常|中性).*"))required="eye_neutral";
     else if(s.contains("惊讶"))required="eye_surprise";
     else if(s.matches("(?s).*(?:笑眼|笑脸|笑起来).*"))required="eye_smile";
    }
    if(family.equals("mic")){
     if(s.matches("(?s).*(?:恢复|标准|默认).*"))required="mic_default";
     else if(s.matches("(?s).*(?:降低|调低|减弱|不那么灵敏).*"))required="mic_less";
     else if(s.matches("(?s).*(?:提高|调高|更灵敏|加强).*"))required="mic_more";
    }
    if(family.equals("initiative")){
     if(s.matches("(?s).*(?:关闭|关掉|停止|停用|不再).*"))required="initiative_off";
     else if(s.matches("(?s).*(?:开启|打开|启用|恢复).*"))required="initiative_on";
    }
    for(String a:confirmed)if(a!=null&&(required.isEmpty()?a.startsWith(family+"_"):a.equals(required)))proof=true;
    if(!proof){out.append("这项设置还没有确认改变。");continue;}}
   if(completion&&s.matches("(?s).*(?:记住|保存.{0,8}记忆|删除.{0,8}记忆).*")&&!confirmed.contains("memory_verified")){out.append("记忆是否保存，以本机确认结果为准。");continue;}
   out.append(s);
  }return out.toString();
 }
}
