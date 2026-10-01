package org.sp001.core;
import java.io.IOException;import java.util.*;import org.json.*;
/** Bounded local facts with revision-fenced writes. Store must atomically preserve old bytes on failure. */
public final class CompanionMemory {
 public interface Store {String read()throws Exception;void write(String snapshot)throws Exception;}
 public interface VersionedStore extends Store {void writeExpected(String expected,String snapshot)throws Exception;}
 public static final int MAX_FACTS=100,MAX_BYTES=65536,MAX_PROMPT=1200;
 private final Store store;private JSONObject state;private String baseline;private String profile="家庭";
 public CompanionMemory(Store store){if(store==null)throw new IllegalArgumentException("MEMORY_STORE");this.store=store;}
 private JSONObject load()throws Exception{
  String raw=store.read();
  if(state!=null&&Objects.equals(raw,baseline))return state;
  // Invalidate before parsing: deletion, disable or corruption must never serve an old snapshot.
  state=null;baseline=raw;
  if(raw==null||raw.isEmpty()){state=new JSONObject().put("schema",1).put("revision",0L).put("enabled",true).put("facts",new JSONArray());return state;}
  if(raw.getBytes("UTF-8").length>MAX_BYTES)throw new IOException("MEMORY_BOUND");
  JSONObject obj;try{obj=new JSONObject(raw);if(obj.optInt("schema")!=1||!(obj.opt("revision") instanceof Integer)&&!(obj.opt("revision") instanceof Long)||obj.optLong("revision",-1)<0||!(obj.opt("enabled") instanceof Boolean))throw new Exception();
   JSONArray facts=obj.getJSONArray("facts");if(facts.length()>MAX_FACTS)throw new Exception();
   for(int i=0;i<facts.length();i++){JSONObject f=facts.getJSONObject(i);if(!validProfile(f.getString("profile"))||!safeText(f.getString("text"))||!f.getString("kind").matches("explicit|preference")||f.getLong("at")<0)throw new Exception();}
  }catch(Exception invalid){throw new IOException("MEMORY_CORRUPT_PRESERVED");}
  state=obj;return state;
 }
 private void persist(JSONObject next)throws Exception{String text=next.toString();if(text.getBytes("UTF-8").length>MAX_BYTES)throw new IOException("MEMORY_BOUND");if(store instanceof VersionedStore)((VersionedStore)store).writeExpected(baseline,text);else store.write(text);baseline=text;state=next;}
 private JSONObject copy()throws Exception{return new JSONObject(load().toString());}
 public synchronized long revision()throws Exception{return load().getLong("revision");}
 public synchronized boolean enabled()throws Exception{return load().getBoolean("enabled");}
 public synchronized String profile(){return profile;}
 public synchronized void select(String value)throws Exception{if(!validProfile(value))throw new IOException("MEMORY_PROFILE_INVALID");load();profile=value;}
 public static boolean validProfile(String p){return p!=null&&p.matches("[\\p{L}\\p{N}_-]{1,24}");}
 public static boolean safeText(String s){return s!=null&&s.length()>0&&s.length()<=160&&!s.matches("(?is).*(?:密码|口令是|api.?key|access.?token|secret|身份证|银行卡|家庭住址|详细地址|病历|诊断|药物|手机号|电话号码|忽略.{0,8}指令|system[：:]|assistant[：:]|<\\|).*")&&!s.matches(".*[0-9]{7,}.*")&&!s.matches("(?s).*[\\p{Cntrl}].*");}
 private void bump(JSONObject o)throws Exception{long r=o.getLong("revision");if(r==Long.MAX_VALUE)throw new IOException("MEMORY_REVISION_BOUND");o.put("revision",r+1);}
 public synchronized boolean remember(String text,String kind,long expectedRevision,long now)throws Exception{
  if(!safeText(text)||!("explicit".equals(kind)||"preference".equals(kind))||now<0)return false;
  JSONObject next=copy();
  if(!next.getBoolean("enabled")||next.getLong("revision")!=expectedRevision)return false;
  JSONArray facts=next.getJSONArray("facts");
  for(int i=0;i<facts.length();i++){JSONObject f=facts.getJSONObject(i);if(profile.equals(f.getString("profile"))&&text.equals(f.getString("text"))){
    // A no-op still needs a current snapshot before confirming it; do not rewrite or revive a deleted fact.
    if(Objects.equals(baseline,store.read()))return true;
    state=null;return false;
   }}
  if(facts.length()>=MAX_FACTS)throw new IOException("MEMORY_FULL");
  facts.put(new JSONObject().put("profile",profile).put("text",text).put("kind",kind).put("at",now));bump(next);persist(next);return true;
 }
 public synchronized boolean suggest(String quote,String user,String expectedProfile,long expectedRevision,long now)throws Exception{
  if(!profile.equals(expectedProfile)||profile.equals("家庭")||quote==null||user==null||!user.contains(quote)||!safeText(quote))return false;
  if(user.matches("(?s).*(?:不要记|别记|不保存|他说|她说|如果|假如|扮演|假装).*")||!quote.matches("我(?:最)?(?:喜欢|不喜欢|爱好是|偏好).{1,100}"))return false;
  return remember(quote,"preference",expectedRevision,now);
 }
 public synchronized int forget(String fragment,boolean all)throws Exception{
  if(!all&&(fragment==null||fragment.trim().isEmpty()||fragment.length()>160))return 0;
  JSONObject next=copy();JSONArray before=next.getJSONArray("facts"),after=new JSONArray();int removed=0;
  for(int i=0;i<before.length();i++){JSONObject f=before.getJSONObject(i);if(profile.equals(f.getString("profile"))&&(all||f.getString("text").contains(fragment)))removed++;else after.put(f);}
  next.put("facts",after);bump(next);persist(next);return removed;
 }
 public synchronized void setEnabled(boolean yes)throws Exception{JSONObject next=copy();next.put("enabled",yes);bump(next);persist(next);}
 public synchronized String list()throws Exception{
  JSONObject current=load();if(!current.getBoolean("enabled"))return "长期记忆已关闭。";JSONArray fs=current.getJSONArray("facts");StringBuilder s=new StringBuilder();int shown=0,total=0;
  for(int i=0;i<fs.length();i++){JSONObject f=fs.getJSONObject(i);if(profile.equals(f.getString("profile"))){total++;String t=f.getString("text");if(shown<5&&s.length()+t.length()<350){if(s.length()>0)s.append('；');s.append(t);shown++;}}}
  return shown==0?"这个档案还没有保存记忆。":"在"+profile+"的档案里，我记着："+s+"。"+(total>shown?"还有其他记忆，可以按内容删除。":"");
 }
 /** JSON data, not executable instructions. A named profile never retrieves another profile. */
 public synchronized String context(String query)throws Exception{
  JSONObject current=load();if(!current.getBoolean("enabled"))return "";JSONArray fs=current.getJSONArray("facts"),selected=new JSONArray();
  for(int pass=0;pass<2;pass++)for(int i=fs.length()-1;i>=0;i--){JSONObject f=fs.getJSONObject(i);if(!profile.equals(f.getString("profile")))continue;String t=f.getString("text");boolean relevant=false;
   if(query!=null)for(int k=0;k+1<query.length();k++)if(t.contains(query.substring(k,k+2))){relevant=true;break;}
   if((pass==0)!=relevant)continue;JSONObject row=new JSONObject().put("fact",t).put("recordedAt",f.getLong("at"));
   JSONArray trial=new JSONArray(selected.toString());trial.put(row);if(trial.toString().length()>MAX_PROMPT)continue;selected=trial;if(selected.length()>=8)return selected.toString();
  }return selected.length()==0?"":selected.toString();
 }
 public synchronized String command(MemoryCommand c,long now)throws Exception{
  switch(c.kind){case NONE:return null;case PROFILE:select(c.value);return "已切换到"+profile+"的记忆档案。";
   case ON:setEnabled(true);return "长期记忆已开启。";case OFF:setEnabled(false);return "长期记忆已关闭，已有记录保留，可随时删除。";
   case LIST:return list();case CLEAR:return "已清空当前档案的"+forget("",true)+"条记忆。";
   case FORGET:return "已删除"+forget(c.value,false)+"条相关记忆。";
   case REMEMBER:return remember(c.value,"explicit",revision(),now)?"记住了，已保存在"+profile+"的档案。":"这条没有保存；记忆可能已关闭，或内容不适合保存。";
   default:return null;}
 }
}
