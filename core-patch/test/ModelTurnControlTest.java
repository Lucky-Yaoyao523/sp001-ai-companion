package org.sp001.core;

import java.io.*;
import java.net.URL;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Actual client plus actual session controller. Synthetic transport and audio only. */
public final class ModelTurnControlTest {
    static int checks;
    static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static JSONObject config()throws Exception{return TtsSocketAdapterTest.config().put("enabled",true).put("cloudConsent",true).put("chatModel","MiniMax-M3");}
    static JSONObject result(boolean closing,String action)throws Exception{return result(closing,action,"");}
    static JSONObject result(boolean closing,String action,String user)throws Exception{
        if(closing)return ApiOwnedDialogueTest.response(DeviceToolExecutionTest.endCall("end_current_turn","好，先聊到这里。"));
        JSONObject s=new JSONObject().put("continue_listening",true).put("speech",closing?"好，先聊到这里。":"我明白你的意思了。")
            .put("emotion","caring").put("actions",new JSONArray()).put("end",closing)
            .put("follow_up","要不要再来一道题？");
        if(action!=null){s.getJSONArray("actions").put(action);s.put("action_request",user);}
        return new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",s.toString())))));
    }
    static final class Wire implements MiniMaxVoiceClient.ConnectionFactory {
        final JSONObject answer;int requests;DuplexIntegrationTest.Connection connection;
        Wire(JSONObject answer){this.answer=answer;}
        public HttpsURLConnection open(URL url)throws IOException{
            try{yes(url.getPath().equals("/v1/responses"),"native route");yes(++requests==1,"one model request, no classifier");
                connection=new DuplexIntegrationTest.Connection();connection.contentType="text/event-stream";
                connection.body="data: "+new JSONObject().put("type","response.completed").put("response",answer)+"\n\n";return connection;
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
        }
    }
    static final class Ports implements ConversationSession.Ports,ConversationSession.PipelinedPorts,
            ConversationSession.EndingReply,ConversationSession.ModelDirectedReply,ConversationSession.Interruptible {
        final String heard;final MiniMaxVoiceClient client;int captures,endings,pipelines;boolean closed;
        final List<ReplySegment> records=new ArrayList<ReplySegment>();
        Ports(String input,Wire wire)throws Exception{heard=input;client=new MiniMaxVoiceClient(config(),()->false,null,null,wire);}
        public long nowMs(){return System.nanoTime()/1000000L;}
        public boolean cancelled(){return false;}
        public byte[] capture()throws Exception{if(++captures>1)return new byte[0];client.beginTurn(()->false,r->{r.run();return true;});byte[] b=new byte[6400];for(int i=0;i<b.length;i+=2){short v=(short)(3000*Math.sin(i*.05));b[i]=(byte)v;b[i+1]=(byte)(v>>8);}return b;}
        public String transcribe(byte[] b){return heard;}
        public String reply(String s){throw new AssertionError("non-native reply");}
        public byte[] synthesize(String s){throw new AssertionError("unexpected synth");}
        public void play(byte[] b){throw new AssertionError("unexpected playback");}
        public void state(String s){}
        public void close(){closed=true;client.close();}
        public boolean modelOwnsDialogue(){return client.modelOwnsDialogue();}
        public boolean endAfterReply(){return client.endAfterReply();}
        public boolean pipelineEnabled(){return true;}
        public int replyAndSpeak(String s)throws Exception{pipelines++;client.replySegments(s,p->{records.add(p);return p.speech;});client.commitReply();return 640;}
        public boolean interrupted(){return false;}
        public void checkTurn(){}
        public void endResponse(){endings++;}
        public void finishTurn(){client.finishTurn();}
    }
    /** Public test live entry is disabled; offline checks only. */
    static void live(String ignored)throws Exception{throw new IOException("PUBLIC_TESTS_OFFLINE_ONLY");}
    static final class SequenceWire implements MiniMaxVoiceClient.ConnectionFactory {
        final JSONObject[] responses;int used;
        SequenceWire(JSONObject... values){responses=values;}
        public HttpsURLConnection open(URL url)throws IOException{
            if(used>=responses.length)throw new IOException("UNEXPECTED_MODEL_RETRY");
            try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";
                c.body="data: "+new JSONObject().put("type","response.completed").put("response",responses[used++])+"\n\n";return c;
            }catch(Exception e){throw new IOException(e);}
        }
    }
    static void terminalBoundaries()throws Exception{
        for(boolean story:new boolean[]{false,true}){
            JSONObject response=result(true,null,"今天先到这，故事以后接着听。");
            response.getJSONArray("output").put(ApiOwnedDialogueTest.message("不该继续说的话。"));
            SequenceWire wire=new SequenceWire(response);final List<ReplySegment> seen=new ArrayList<ReplySegment>();
            NativeDialogueEngine e=new NativeDialogueEngine(config(),wire,(city,date,now,token)->{throw new AssertionError("no tool expected");});
            e.reply(new JSONArray().put(new JSONObject().put("role","user").put("content","今天先到这，故事以后接着听。")),"",story,new NativeDialogueEngine.Output(){
                public void speak(ReplySegment s){seen.add(s);}public void status(JSONObject s){}
            },()->false);
            yes(wire.used==1&&seen.size()==1&&seen.get(0).end,"short closing survives story limit; nothing after end");
            yes(seen.get(0).followUp.isEmpty()&&e.status().getBoolean("endRequested"),"ending receipt no invitation");
        }
        String day=new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        JSONObject call=new JSONObject().put("type","function_call").put("call_id","closing_weather").put("name","get_weather")
            .put("arguments",new JSONObject().put("city","北京").put("date",day).toString());
        JSONObject toolResponse=ApiOwnedDialogueTest.response(call,DeviceToolExecutionTest.endCall("end_after_weather","那我们先休息吧。"));
        SequenceWire wire=new SequenceWire(toolResponse);final List<ReplySegment> seen=new ArrayList<ReplySegment>();
        NativeDialogueEngine e=new NativeDialogueEngine(config(),wire,(city,date,now,token)->new JSONObject().put("kind","verified_weather").put("city",city).put("timezone","Asia/Shanghai")
            .put("current",new JSONObject().put("weatherCode",2).put("temperatureC",21).put("feelsLikeC",22))
            .put("forecast",new JSONObject().put("date",date).put("weatherCode",2).put("lowC",17).put("highC",26).put("rainProbabilityPercent",30)));
        e.reply(new JSONArray().put(new JSONObject().put("role","user").put("content","告诉我北京天气，说完咱们就先休息吧。")),"",false,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){seen.add(s);}public void status(JSONObject s){}
        },()->false);
        yes(wire.used==1&&seen.get(seen.size()-1).end,"weather tool and explicit end share one ordered execution plan");
        yes(e.status().getBoolean("endRequested")&&seen.get(seen.size()-1).speech.contains("北京"),"weather fact and close coexist");
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){if(args.length!=2||!"--live".equals(args[0]))throw new IllegalArgumentException("EXPLICIT_LIVE_REQUIRED");live(args[1]);return;}
        terminalBoundaries();
        for(String input:new String[]{"我要去找妈妈啦，咱们待会儿再说。","谢谢你陪我，现在让耳朵歇一会儿。","今天的任务收工，我们下次接着来。","拜拜","请结束自动聊天模式"}){
            Wire w=new Wire(result(true,null,input));Ports p=new Ports(input,w);ConversationSession.Result r=new ConversationSession(p,3,10000).run();
            yes(r.code.equals("SESSION_ENDED_BY_REPLY"),"model stop accepted: "+input);yes(p.captures==1&&p.pipelines==1&&p.endings==1&&p.closed,"no next recording after close");
            for(ReplySegment s:p.records)yes(s.followUp.isEmpty()&&s.lesson.isEmpty(),"closing cannot leave queued lesson/follow-up");
            JSONObject sent=new JSONObject(new String(w.connection.uploaded.toByteArray(),"UTF-8"));JSONArray turns=sent.getJSONArray("input");yes(turns.getJSONObject(turns.length()-1).getString("content").equals(input),"unaltered input");
        }
        for(String input:new String[]{"拜拜","再见是什么意思？","我不是要走，帮我翻译一下再见。"}){
            Wire w=new Wire(result(false,null));Ports p=new Ports(input,w);ConversationSession.Result r=new ConversationSession(p,3,10000).run();
            yes(w.requests==1&&p.pipelines==1,"no keyword preemption, even exact word");yes(r.code.equals("SESSION_SILENCE")&&p.captures==2&&p.endings==0,"model keep-open is honored");
        }
        // Device actions now require an actual function-call/result round trip.
        DeviceToolExecutionTest.directActionRequiresTool();
        for(String reason:new String[]{"SESSION_ENDED","SESSION_ENDED_BY_REPLY"}){yes(PassiveWakeGate.explicitEnd(reason,false),"model end has passive cooldown");PassiveWakeGate g=new PassiveWakeGate();g.ended(100,PassiveWakeGate.explicitEnd(reason,false));yes(g.offer("ir",1,1,110,12,true).equals("COOLDOWN"),"no immediate walk-away restart");}
        yes(!PassiveWakeGate.explicitEnd("SESSION_SILENCE",false),"ordinary idle not permanent mute");yes(PassiveWakeGate.explicitEnd("ANY",true),"physical cancel protected");
        IdleInitiative i=new IdleInitiative();i.user();yes(!i.prepared(),"no invented proactive text");i.prepare("刚才那个线索真有意思。");yes(i.prepared(),"model supplied follow-up can be queued");i.discard();yes(!i.prepared(),"closed turn removes pending work");
        System.out.println("MODEL_TURN_CONTROL "+checks+" checks passed; actual client/controller; synthetic HTTP/audio; no device/network");
    }
}
