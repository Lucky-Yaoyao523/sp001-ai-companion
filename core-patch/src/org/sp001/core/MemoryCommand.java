package org.sp001.core;
import java.util.regex.*;
/** Explicit local memory commands. No speaker inference, file, microphone, or network I/O. */
public final class MemoryCommand {
 public enum Kind {NONE,REMEMBER,LIST,FORGET,CLEAR,PROFILE,OFF,ON}
 public final Kind kind;public final String value;
 private MemoryCommand(Kind k,String v){kind=k;value=v;}
 public static MemoryCommand parse(String raw){
  if(raw==null||raw.length()>1000)return new MemoryCommand(Kind.NONE,"");
  String s=raw.trim().replaceAll("[。！？!?]+$","").trim();
  if(s.matches("(?:请)?(?:关闭|停用)长期记忆|不要再记住我的信息"))return new MemoryCommand(Kind.OFF,"");
  if(s.matches("(?:请)?(?:开启|启用)长期记忆"))return new MemoryCommand(Kind.ON,"");
  if(s.matches("(?:请)?(?:清空|删除)当前(?:档案的)?全部记忆"))return new MemoryCommand(Kind.CLEAR,"");
  if(s.matches("你(?:还)?记得(?:我)?什么|查看(?:我的)?记忆|列出(?:我的)?记忆"))return new MemoryCommand(Kind.LIST,"");
  Matcher p=Pattern.compile("(?:请)?(?:切换|使用)记忆档案[：:，, ]*([\\p{L}\\p{N}_-]{1,24})").matcher(s);
  if(p.matches())return new MemoryCommand(Kind.PROFILE,p.group(1));
  p=Pattern.compile("(?:请)?(?:忘记|删除这条记忆)[：:，, ]*(.{1,160})").matcher(s);
  if(p.matches())return new MemoryCommand(Kind.FORGET,p.group(1));
  // Quoted instructions, questions and negation cannot silently become a memory command.
  p=Pattern.compile("(?:以后|今后)?(?:请)?(?:你(?:要|得)?)?(?:帮我)?记住[：:，, ]*(.{1,160})").matcher(s);
  if(p.matches()&&!s.matches(".*(?:不要|别记|不必记|他说|她说|如果|为什么|吗[？?]?).*"))return new MemoryCommand(Kind.REMEMBER,p.group(1));
  return new MemoryCommand(Kind.NONE,"");
 }
}
