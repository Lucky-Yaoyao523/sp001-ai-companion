package org.sp001.core;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
/** Request-only interaction hint: no extra request, fabricated user turn or stored hint. */
public final class CompanionPrompt {
 private CompanionPrompt(){}
 public static boolean allowOffer(int completed,String user){
  if(completed<0||completed>20)throw new IllegalArgumentException("COMPANION_TURN_BOUND");
  if(completed%3!=0||user==null)return false;
  if(ClosingIntent.afterReply(user)||MiniMaxCodec.stopCommand(user))return false;
  return !user.matches("(?s).*(?:只要答案|直接回答|别问|別問|不要问|不要問|不要反问|不要反問|不用推荐|不用推薦|我很累|我困了|我很难过|我很難過|我害怕).*" );
 }
 public static JSONArray requestMessages(JSONArray history)throws Exception{
  return requestMessages(history,true);
 }
 public static JSONArray requestMessages(JSONArray history,boolean initiative)throws Exception{
  if(history==null||history.length()==0||history.length()>41)throw new IOException("COMPANION_HISTORY_BOUND");
  int completed=0;String user=null;
  for(int i=0;i<history.length();i++){
   JSONObject item=history.getJSONObject(i);String role=item.getString("role");
   if("assistant".equals(role))completed++;
   if("user".equals(role))user=item.getString("content");
  }
  JSONObject first=history.getJSONObject(0);if(!"system".equals(first.optString("role")))throw new IOException("COMPANION_SYSTEM_REQUIRED");
  boolean narrative=ChildCompanionPolicy.storyRequest(user);
  boolean offer=!narrative&&initiative&&allowOffer(completed,user);
  String rhythm=offer?
   "本轮允许一次轻量延伸：先完整直接回答；确有自然关联时，再用一句简短有趣的补充、共同小玩法或具体接话邀请，最多一个问题，不必强行追问。":
   "本轮只自然接住并完整回答当前话题，不在末尾例行追加反问、选项或‘还有什么问题’。";
  if(narrative)rhythm="故事请求按专属故事计划讲完整小章，不受普通简答长度限制，不在每小段后反问，不把互动答题当作继续条件。";
  String hint="\n互动节奏：你是有反应的蜘蛛侠伙伴，不是只播答案的客服。"+rhythm+
   "延伸不得喧宾夺主。普通问题需要解释或例子时充分展开，不限成一句口号；不要每轮问‘想不想’或‘还要吗’。不把用户提问当成用户已经给出答案。"+
   "用户告别、要求安静、疲倦或难过时，不催答、不挽留、不主动加游戏。情绪标签贴合语境，不一律笑脸。"+
   "只有这次输入和历史文本可用；没有沉默时长、摄像头或新传感器证据，不声称观察到了用户动作、沉默、表情或已经执行了未提供的功能。";
  JSONArray request=new JSONArray();
  for(int i=0;i<history.length();i++){
   JSONObject copy=new JSONObject(history.getJSONObject(i).toString());
   if(i==0)copy.put("content",copy.getString("content")+hint);
   // Keep exact played text, but make historical assistant format consistent with the output contract.
   if("assistant".equals(copy.optString("role")))copy.put("content",new JSONObject().put("speech",copy.getString("content")).put("emotion","neutral").put("actions",new JSONArray()).put("end",false).toString());
   request.put(copy);
  }
  return request;
 }
}
