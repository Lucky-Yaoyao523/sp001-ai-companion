package org.sp001.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production native engine/client with synthetic SSE only; no private provider recording. */
public final class NativeReplyStreamTest {
    private static int checks;
    private interface Work { void run() throws Exception; }
    private static void yes(boolean value,String label) { checks++; if(!value)throw new AssertionError(label); }
    private static void fails(Work work,String label)throws Exception {
        try { work.run(); throw new AssertionError("expected failure: "+label); }
        catch(IOException expected) { checks++; }
    }
    private static String frame(JSONObject e) { return "data: "+e.toString()+"\n\n"; }
    private static JSONObject add(String type,String id,int index)throws Exception {
        JSONObject item=new JSONObject().put("type",type).put("id",id).put("status","in_progress");
        if("message".equals(type))item.put("role","assistant");
        return new JSONObject().put("type","response.output_item.added").put("output_index",index).put("item",item);
    }
    private static JSONObject delta(String text)throws Exception {
        return new JSONObject().put("type","response.output_text.delta").put("item_id","m1")
                .put("output_index",1).put("content_index",0).put("delta",text);
    }
    private static JSONObject record(String speech,boolean end,String action)throws Exception {
        return new JSONObject().put("continue_listening",true).put("speech",speech).put("emotion","neutral")
                .put("end",end).put("actions",action==null?new JSONArray():new JSONArray().put(action));
    }
    private static JSONObject terminal(String text)throws Exception {
        JSONObject item=new JSONObject().put("id","m1").put("type","message").put("role","assistant")
                .put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)));
        return new JSONObject().put("type","response.completed").put("response",ApiOwnedDialogueTest.response(item));
    }
    private static String wireText(String text,JSONObject completed)throws Exception {
        return frame(add("reasoning","r0",0))+frame(new JSONObject().put("type","response.reasoning_text.delta").put("delta","THIS MUST NOT BE SPOKEN"))+
                frame(add("message","m1",1))+frame(delta(text))+(completed==null?"":frame(completed));
    }
    private static final class Wire implements MiniMaxVoiceClient.ConnectionFactory {
        final String body; int requests;
        Wire(String body){this.body=body;}
        public javax.net.ssl.HttpsURLConnection open(java.net.URL url)throws IOException {
            if(++requests>1)throw new IOException("UNEXPECTED_RETRY");
            try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=body;return c;}
            catch(Exception e){throw new IOException(e);}
        }
    }
    private static NativeDialogueEngine engine(Wire w)throws Exception {
        return new NativeDialogueEngine(ApiOwnedDialogueTest.config(),w,(city,date,now,cancel)->{throw new AssertionError("unexpected weather");});
    }
    private static void parserCases()throws Exception {
        NativeReplyStream p=new NativeReplyStream();
        p.accept(add("reasoning","r0",0));
        p.accept(new JSONObject().put("type","response.reasoning_text.delta").put("delta","hidden"));
        p.accept(add("message","m1",1));
        String json=record("尚未验证的草稿。",false,null).toString();
        for(int i=0;i<json.length();i++)p.accept(delta(json.substring(i,i+1)));
        p.accept(add("function_call","f1",2));
        yes(true,"draft then tool is allowed because neither is played speculatively");
        NativeReplyStream seq=new NativeReplyStream();seq.accept(add("message","m1",1).put("sequence_number",4));
        fails(()->seq.accept(delta(json).put("sequence_number",4)),"replayed SSE sequence rejected");
        fails(()->new NativeReplyStream().accept(delta(json).put("delta",7)),"nontext delta rejected");
        fails(()->new NativeReplyStream().accept(null),"missing event rejected");
        NativeReplyStream bounded=new NativeReplyStream();
        fails(()->{for(int i=0;i<16001;i++)bounded.accept(new JSONObject().put("type","keepalive"));},"event budget retained");
    }
    private static void recordBoundaries()throws Exception {
        String json=record("诸葛亮说：\"一起想办法。\"",false,null).put("follow_up","接着聊吗？").put("memory",new JSONObject().put("kind","preference").put("quote","我喜欢三国")).toString();
        for(int width:new int[]{1,2,7,31,4096}){
            NativeReplyStream parser=new NativeReplyStream("讲故事。");parser.accept(add("message","m1",1));List<ReplySegment> records=new ArrayList<>();
            for(int at=0;at<json.length();at+=width)records.addAll(parser.accept(delta(json.substring(at,Math.min(json.length(),at+width)))));
            yes(records.size()==1&&records.get(0).speech.equals("诸葛亮说：\"一起想办法。\""),"arbitrary stream boundaries preserve exact quoted text");
            yes(records.get(0).followUp.isEmpty()&&records.get(0).memoryQuote.isEmpty()&&!records.get(0).end,"metadata cannot commit before completion");
            List<ReplySegment> tail=parser.remaining(NativeDialogueEngine.parse(json,"讲故事。",true));
            yes(tail.size()==1&&tail.get(0).speech.isEmpty()&&!tail.get(0).memoryQuote.isEmpty(),"final metadata retained without replaying speech");
        }
        NativeReplyStream tool=new NativeReplyStream("查询天气。");tool.accept(add("function_call","f0",0));tool.accept(add("message","m1",1));
        yes(tool.accept(delta(json)).isEmpty(),"tool draft waits for real execution result");
        NativeReplyStream quoted=new NativeReplyStream("翻译。");quoted.accept(add("message","m1",1));
        yes(quoted.accept(delta(record("声音大一点的英文是 speak louder。",false,null).toString())).size()==1,"quoted command is just speech");
    }
    private static void singleEnvelopeStreamsBeforeClosingQuote()throws Exception {
        NativeReplyStream p=new NativeReplyStream("完整讲。");p.accept(add("message","m1",1));
        String opening="{\"emotion\":\"happy\",\"continue_listening\":true,\"speech\":\"第一句开始了。后面";
        List<ReplySegment> early=p.accept(delta(opening));
        yes(early.size()==1&&early.get(0).speech.equals("第一句开始了。")&&early.get(0).emotion.equals("happy"),"first sentence streams before speech string or JSON ends");
        String tail="继续。\",\"memory\":{\"speech\":\"嵌套字段不得播出\"}}";
        List<ReplySegment> next=p.accept(delta(tail));StringBuilder got=new StringBuilder();for(ReplySegment s:early)got.append(s.speech);for(ReplySegment s:next)got.append(s.speech);
        yes(got.toString().equals("第一句开始了。后面继续。"),"one envelope retains all speech and excludes nested metadata");
        yes(p.remaining(NativeDialogueEngine.parse(opening+tail,"完整讲。",true)).isEmpty(),"final envelope never repeats streamed text");
        String encoded="{\"continue_listening\":true,\"speech\":\"\\u4f60\\u597d\\uff01\\uD83D\\uDE00 下一句。\"}";
        NativeReplyStream unicode=new NativeReplyStream("你好。");unicode.accept(add("message","m1",1));got.setLength(0);
        for(int i=0;i<encoded.length();i++)for(ReplySegment s:unicode.accept(delta(encoded.substring(i,i+1))))got.append(s.speech);
        yes(got.toString().equals("你好！\uD83D\uDE00 下一句。"),"escaped Unicode and surrogate pair survive every one-character packet boundary");
        NativeReplyStream punctuation=new NativeReplyStream("讲故事。");punctuation.accept(add("message","m1",1));
        String doubled="{\"speech\":\"第一句。。第二句。\",\"emotion\":\"neutral\",\"continue_listening\":true}";
        List<ReplySegment> clean=new ArrayList<>();
        for(int i=0;i<doubled.length();i++)clean.addAll(punctuation.accept(delta(doubled.substring(i,i+1))));
        yes(clean.size()==2&&clean.get(0).speech.equals("第一句。")&&clean.get(1).speech.equals("第二句。"),
            "isolated punctuation is not submitted to TTS between real sentences");
        yes(punctuation.remaining(NativeDialogueEngine.parse(doubled,"讲故事。",true)).isEmpty(),
            "skipped punctuation still aligns the final model response without replay");
        NativeReplyStream plain=new NativeReplyStream("继续。");plain.accept(add("message","m1",1));
        yes(plain.accept(delta("普通正文可以直接开始。后面继续")).isEmpty(),"unframed prose must not start playback before lifecycle validation");
    }
    private static void plainFormattingDoesNotBreakCompletion()throws Exception {
        String[] answers={"**第一句开始。**后面继续。", "# 标题\n正文继续。", "第一句。\n\n**后面**继续。"};
        for(String text:answers)for(int width:new int[]{1,7,4096}){
            NativeReplyStream p=new NativeReplyStream("继续讲。");p.accept(add("message","m1",1));
            StringBuilder spoken=new StringBuilder();
            for(int at=0;at<text.length();at+=width)for(ReplySegment part:p.accept(delta(text.substring(at,Math.min(text.length(),at+width)))))spoken.append(part.speech);
            List<ReplySegment> complete=NativeDialogueEngine.parse(text,"继续讲。",true);
            yes(complete.size()==1&&!complete.get(0).end&&complete.get(0).actions.isEmpty(),"plain text is usable only as ordinary speech");
            yes(spoken.length()==0&&p.deliveredRecords()==0,"plain formatting width="+width+" waits for the completed response");
        }
    }
    private static void searchCompletionResumesStreaming()throws Exception {
        String json="{\"emotion\":\"happy\",\"continue_listening\":true,\"speech\":\"查到的第一句。接着讲完。\"}";
        NativeReplyStream p=new NativeReplyStream("查完再告诉我。");
        p.accept(add("web_search_call","s1",0));
        p.accept(add("message","m1",1));
        yes(p.accept(delta(json)).isEmpty(),"pending server search does not present an execution-free draft");
        p.accept(new JSONObject().put("type","response.web_search_call.completed").put("item_id","s1"));
        p.accept(add("message","m1",1));
        List<ReplySegment> ready=p.accept(delta(json));
        yes(ready.size()==2&&ready.get(0).emotion.equals("happy"),"completed server search resumes phrase streaming before response.completed");
        yes(p.remaining(NativeDialogueEngine.parse(json,"查完再告诉我。",true)).isEmpty(),"post-search final does not replay streamed answer");
        NativeReplyStream local=new NativeReplyStream("调音量。");
        local.accept(add("function_call","f1",0));
        local.accept(new JSONObject().put("type","response.web_search_call.completed").put("item_id","unknown"));
        local.accept(add("message","m1",1));
        yes(local.accept(delta(json)).isEmpty(),"a search event cannot complete an unexecuted local function");
        NativeReplyStream parallel=new NativeReplyStream("查两份资料。");
        parallel.accept(add("web_search_call","s1",0));parallel.accept(add("web_search_call","s2",1));
        parallel.accept(new JSONObject().put("type","response.web_search_call.completed").put("item_id","s1"));
        parallel.accept(add("message","m1",2));yes(parallel.accept(delta(json)).isEmpty(),"all correlated pending server searches must finish");
        parallel.accept(new JSONObject().put("type","response.output_item.done").put("item",new JSONObject().put("type","web_search_call").put("id","s2").put("status","completed")));
        parallel.accept(add("message","m1",2));yes(parallel.accept(delta(json)).size()==2,"completed item also resumes the server answer");
    }
    private static void engineCases()throws Exception {
        plainFormattingDoesNotBreakCompletion();
        searchCompletionResumesStreaming();
        String a=record("第一句来了。",false,null).toString(),b=record("第二句也到了。",false,null).toString();
        String body=frame(add("message","m1",1))+frame(delta(a))+frame(delta("\n"+b))+frame(terminal(a+"\n"+b));
        Wire wire=new Wire(body);NativeDialogueEngine e=engine(wire);List<ReplySegment> got=new ArrayList<>();
        e.reply(ApiOwnedDialogueTest.history("告诉我一个小发现。"),"",false,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){yes(!e.status().has("responseCompletedMonotonicMs"),"complete sentence delivered before terminal response");got.add(s);}
            public void status(JSONObject s){}
        },()->false);
        yes(got.size()==2&&wire.requests==1,"one request, no final duplicate");
        yes(e.status().getInt("streamedSegments")==2,"two complete records streamed, no extra model request");
        yes(e.status().getLong("firstSegmentMonotonicMs")<=e.status().getLong("responseCompletedMonotonicMs"),"first record precedes completion");
        JSONObject end=DeviceToolExecutionTest.endCall("close_stream","那我们下次聊。");
        JSONObject completed=new JSONObject().put("type","response.completed").put("response",ApiOwnedDialogueTest.response(end));
        Wire ending=new Wire(frame(add("function_call","close_stream",0))+frame(completed));NativeDialogueEngine ee=engine(ending);List<ReplySegment> close=new ArrayList<>();
        ee.reply(ApiOwnedDialogueTest.history("下次再聊。"),"",false,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){if(s.end)yes(ee.status().has("responseCompletedMonotonicMs"),"end waits verified final");close.add(s);}
            public void status(JSONObject s){}
        },()->false);
        yes(close.size()==1&&close.get(0).end&&close.get(0).speech.equals("那我们下次聊。")&&ee.status().getBoolean("endRequested"),"verified end_session tool closes; uncommitted draft is never spoken");
        Wire lateTool=new Wire(wireText(a,completed));NativeDialogueEngine late=engine(lateTool);List<ReplySegment> beforeTool=new ArrayList<>();
        late.reply(ApiOwnedDialogueTest.history("今天先聊到这里。"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){beforeTool.add(s);}public void status(JSONObject s){}},()->false);
        yes(beforeTool.size()==2&&beforeTool.get(0).speech.equals("第一句来了。")&&beforeTool.get(1).speech.isEmpty()&&beforeTool.get(1).end,"speech followed by genuine end tool closes without a duplicate farewell");
        yes(late.status().optBoolean("sessionEndTool")&&late.status().optBoolean("endRequested")&&lateTool.requests==1,"model-owned farewell closes in the same response without another classifier");
        String partialJson="先说一句。余下没有句号";
        Wire trailing=new Wire(wireText(partialJson,completed));NativeDialogueEngine trailingEngine=engine(trailing);List<ReplySegment> trailingOut=new ArrayList<>();
        trailingEngine.reply(ApiOwnedDialogueTest.history("这次先这样。"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){trailingOut.add(s);}public void status(JSONObject s){}},()->false);
        yes(trailingOut.size()==1&&trailingOut.get(0).speech.equals("那我们下次聊。")&&trailingOut.get(0).end,"unframed unplayed preamble yields to the actual end tool farewell without duplicate audio");
        Wire truncated=new Wire(wireText(a,null));MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,truncated);List<ReplySegment> partial=new ArrayList<>();
        try{c.beginTurn(()->false,r->{r.run();return true;});fails(()->c.replySegments("说一个小发现。",s->{partial.add(s);return s.speech;}),"broken stream is not a successful complete answer");
            yes(partial.size()==2&&truncated.requests==1&&partial.get(0).speech.equals("第一句来了。")&&partial.get(1).speech.contains("还没说完"),"heard prefix once followed by one failure notice, no regeneration");
            java.lang.reflect.Field pending=MiniMaxVoiceClient.class.getDeclaredField("pendingAnswer");pending.setAccessible(true);yes(pending.get(c)==null,"partial response not staged as complete history");
        }finally{c.finishTurn();c.close();}
        Wire mismatch=new Wire(wireText(a,terminal(b)));NativeDialogueEngine em=engine(mismatch);List<ReplySegment> rewritten=new ArrayList<>();
        fails(()->em.reply(ApiOwnedDialogueTest.history("小发现"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){rewritten.add(s);}public void status(JSONObject s){}},()->false),"provider cannot replace a heard prefix");
        yes(rewritten.size()==1&&rewritten.get(0).speech.equals("第一句来了。")&&mismatch.requests==1,"mismatch never replays or silently overwrites heard content");
        AtomicBoolean cancelled=new AtomicBoolean();Wire cw=new Wire(body);NativeDialogueEngine ce=engine(cw);List<ReplySegment> cancelledOutput=new ArrayList<>();
        fails(()->ce.reply(ApiOwnedDialogueTest.history("讲一个发现"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){cancelledOutput.add(s);cancelled.set(true);}public void status(JSONObject s){}},cancelled::get),"cancellation checked after every sentence");
        yes(cancelledOutput.size()==1,"no second sentence after interruption");
    }
    private static void completedPostToolMessageSurvivesTrailingMetadataLoss()throws Exception{
        String text="{\"emotion\":\"neutral\",\"continue_listening\":true,\"speech\":\"音量已调好。我们继续。\"}";
        JSONObject item=terminal(text).getJSONObject("response").getJSONArray("output").getJSONObject(0).put("id","m1").put("status","completed");
        String done=frame(add("message","m1",0))+frame(delta(text))+frame(new JSONObject().put("type","response.output_item.done").put("item",item))+"event: response.completed\ndata: {\"type\":\"response.completed\",";
        JSONObject action=ApiOwnedDialogueTest.response(DeviceToolExecutionTest.call("once","volume_up"));
        final int[] requests={0},writes={0};
        NativeDialogueEngine e=new NativeDialogueEngine(ApiOwnedDialogueTest.config(),url->{try{DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=++requests[0]==1?frame(new JSONObject().put("type","response.completed").put("response",action)):done;return c;}catch(Exception x){throw new IOException(x);}},(city,date,now,t)->{throw new AssertionError("no weather");});
        StringBuilder spoken=new StringBuilder();e.reply(ApiOwnedDialogueTest.history("调大音量后继续。"),"",false,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){spoken.append(s.speech);}public void status(JSONObject s){}
            public JSONObject executeDevice(String a)throws Exception{writes[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","声音已调大。");}
        },()->false);
        yes(spoken.toString().equals("音量已调好。我们继续。")&&requests[0]==2&&writes[0]==1,"complete tools-disabled message survives truncated metadata without replay");
        yes("COMPLETED_ASSISTANT_AFTER_TRANSFER".equals(e.status().optString("completionEvidence")),"message evidence not mislabeled full terminal event");
        Wire auto=new Wire(done);NativeDialogueEngine pending=engine(auto);
        fails(()->pending.reply(ApiOwnedDialogueTest.history("告诉我。"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){}public void status(JSONObject s){}},()->false),"auto response cannot finalize before possible later tools");
        yes(auto.requests==1,"already-spoken automatic response never retried");
    }
    private static void syntheticMissingLifecycle()throws Exception {
        String text=new JSONObject().put("emotion","neutral").put("speech","这是人工构造的合成故事正文，没有真实会话。").put("end",true).toString();
        Wire w=new Wire(wireText(text,terminal(text)));NativeDialogueEngine e=engine(w);List<ReplySegment> got=new ArrayList<>();
        fails(()->e.reply(ApiOwnedDialogueTest.history("讲一个完整原创故事。"),"",true,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){got.add(s);}public void status(JSONObject s){}
        },()->false),"synthetic missing lifecycle fails without partial playback");
        yes(got.isEmpty(),"missing lifecycle never plays or closes session");
        yes(w.requests==2,"one unplayed format repair remains bounded");
    }
    /** Slow provider simulation: the real transport must deliver feedback on its consumer,
     * never on its I/O/watchdog thread, and must keep the final answer distinct. */
    private static final class SlowWire implements MiniMaxVoiceClient.ConnectionFactory {
        final String waitAt,body;final int httpStatus;int requests;
        final long delayMs;
        SlowWire(String at,String text,int status){this(at,text,status,3500);}
        SlowWire(String at,String text,int status,long delay){waitAt=at;body=text;httpStatus=status;delayMs=delay;}
        public javax.net.ssl.HttpsURLConnection open(java.net.URL url)throws IOException{
            if(++requests>1)throw new IOException("UNEXPECTED_RETRY");
            return new javax.net.ssl.HttpsURLConnection(url){
                volatile boolean disconnected;
                private void delay(String stage)throws IOException{
                    if(!waitAt.equals(stage))return;
                    long until=System.nanoTime()+delayMs*1000000L;
                    while(!disconnected&&System.nanoTime()<until)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("TURN_CANCELLED",e);}
                    if(disconnected)throw new IOException("DISCONNECTED");
                }
                public java.io.OutputStream getOutputStream(){return new java.io.ByteArrayOutputStream();}
                public int getResponseCode()throws IOException{delay("headers");return httpStatus;}
                public String getContentType(){return "text/event-stream";}
                public java.io.InputStream getInputStream()throws IOException{delay("body");return new java.io.ByteArrayInputStream(body.getBytes("UTF-8"));}
                public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}public void connect(){}
                public String getCipherSuite(){return "TEST";}public java.security.cert.Certificate[] getLocalCertificates(){return null;}public java.security.cert.Certificate[] getServerCertificates(){return null;}
            };
        }
    }
    private static void waitingCases()throws Exception{
        final Thread caller=Thread.currentThread();String answer=record("苹果的英语是 apple。",false,null).toString();
        for(String at:new String[]{"headers","body"}){
            SlowWire wire=new SlowWire(at,wireText(answer,terminal(answer)),200);
            NativeDialogueEngine engine=new NativeDialogueEngine(ApiOwnedDialogueTest.config(),wire,(city,date,now,token)->{throw new AssertionError("unexpected weather");});
            final int[] feedback={0},answers={0};final long began=System.nanoTime();
            engine.reply(ApiOwnedDialogueTest.history("苹果英语怎么说？"),"",false,new NativeDialogueEngine.Output(){
                public void feedback(ReplySegment s){yes(Thread.currentThread()==caller,"feedback owns conversation thread");yes(s.actions.isEmpty()&&!s.end&&s.followUp.isEmpty(),"feedback has no controls");yes(!engine.status().has("responseCompletedMonotonicMs"),"feedback precedes terminal");feedback[0]++;}
                public void speak(ReplySegment s){yes(!engine.status().has("responseCompletedMonotonicMs"),"complete record need not wait for terminal");yes(s.speech.contains("apple"),"only actual answer reaches substantive output");answers[0]++;}
                public void status(JSONObject value){}
            },()->false);
            yes(feedback[0]==1&&answers[0]==1&&wire.requests==1,"one cue then one answer, no classifier: "+at);
            yes(System.nanoTime()-began<6000000000L,"bounded slow fixture");
            BoundedHttpTest.zeroSlots();
        }
        String farewell=new JSONObject().put("emotion","caring").put("continue_listening",false).put("speech","好，拜拜。").toString();
        SlowWire leaving=new SlowWire("headers",wireText(farewell,terminal(farewell)),200,1850);
        NativeDialogueEngine goodbye=new NativeDialogueEngine(ApiOwnedDialogueTest.config(),leaving,(city,date,now,token)->{throw new AssertionError("unexpected weather");});
        final int[] goodbyeFeedback={0};final boolean[] goodbyeEnd={false};final StringBuilder goodbyeAnswer=new StringBuilder();
        goodbye.reply(ApiOwnedDialogueTest.history("就这样，拜拜。"),"",false,new NativeDialogueEngine.Output(){
            public void feedback(ReplySegment s){goodbyeFeedback[0]++;}
            public void speak(ReplySegment s){goodbyeAnswer.append(s.speech);goodbyeEnd[0]|=s.end;}
            public void status(JSONObject value){}
        },()->false);
        yes(goodbyeFeedback[0]==0&&goodbyeAnswer.toString().equals("好，拜拜。")&&goodbyeEnd[0]&&leaving.requests==1,"ordinary 1.85s farewell finishes before the uniform 2.5s cue");BoundedHttpTest.zeroSlots();
        for(int status:new int[]{200,503}){
            SlowWire wire=new SlowWire("headers",wireText(answer,terminal(answer)),status);
            MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);List<ReplySegment> heard=new ArrayList<>();
            try{client.beginTurn(()->false,r->{r.run();return true;});client.replySegments("苹果英语怎么说？",s->{heard.add(s);return s.speech;});client.commitReply();
                JSONArray history=DuplexIntegrationTest.history(client);String finalText=history.getJSONObject(history.length()-1).getString("content");
                yes(heard.size()==2&&heard.get(0).speech.equals("我想一想。"),"slow turn has exactly one cue");
                yes(!finalText.contains("我想一想"),"waiting feedback never committed as answer");
                yes(status==200?finalText.contains("apple"):finalText.contains("没能"),"result or explicit failure, never hanging promise");
            }finally{client.finishTurn();client.close();}BoundedHttpTest.zeroSlots();
        }
        AtomicBoolean cancel=new AtomicBoolean();SlowWire wire=new SlowWire("headers",wireText(answer,terminal(answer)),200);
        NativeDialogueEngine engine=new NativeDialogueEngine(ApiOwnedDialogueTest.config(),wire,(city,date,now,token)->{throw new AssertionError("unexpected weather");});final int[] count={0};
        fails(()->engine.reply(ApiOwnedDialogueTest.history("苹果英语怎么说？"),"",false,new NativeDialogueEngine.Output(){
            public void feedback(ReplySegment s){count[0]++;cancel.set(true);}public void speak(ReplySegment s){throw new AssertionError("speech after cancellation");}public void status(JSONObject s){}
        },cancel::get),"interrupt cancels request even while waiting cue is delivered");
        yes(count[0]==1,"no late cue or final answer after interruption");BoundedHttpTest.zeroSlots();
    }
    private static void interruptedContext()throws Exception{
        String heard="超级光波可以叫 Super beam。",unplayed="没有播放的后半段。";
        ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(heard+unplayed)),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("Super beam。")));
        AtomicBoolean stop=new AtomicBoolean();MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);
        try{
            c.beginTurn(stop::get,r->{r.run();return true;});
            fails(()->c.replySegments("教我一个英雄招式。",s->{stop.set(true);throw new IOException("BARGE_IN");}),"interrupted playback never completes response");
            c.retainPlayedReply(heard);c.retainPlayedReply(heard);
            JSONArray h=DuplexIntegrationTest.history(c);
            yes(h.length()==3&&h.getJSONObject(2).getString("content").equals(heard)&&!h.toString().contains(unplayed),"exact playback-confirmed prefix retained once, with no unspoken diagnostic annotation or generated tail");
            java.lang.reflect.Field pending=MiniMaxVoiceClient.class.getDeclaredField("pendingAnswer");pending.setAccessible(true);yes(pending.get(c)==null,"interrupted receipt is not staged as a completed answer");
            c.finishTurn();stop.set(false);c.beginTurn(stop::get,r->{r.run();return true;});c.replySegments("刚才那句只用英文再说一遍。",s->s.speech);
            JSONArray actual=wire.request(1).getJSONArray("input");
            yes(actual.length()==3&&actual.getJSONObject(1).getString("content").equals(heard)&&actual.getJSONObject(2).getString("content").contains("只用英文"),"next actual model request has referent before latest user instruction");
            c.commitReply();int count=DuplexIntegrationTest.history(c).length();c.retainPlayedReply("late");yes(DuplexIntegrationTest.history(c).length()==count,"completed reply cannot be duplicated as partial");
        }finally{c.finishTurn();c.close();}
        AtomicBoolean sessionClosed=new AtomicBoolean();MiniMaxVoiceClient stopped=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),sessionClosed::get,null,null,new ApiOwnedDialogueTest.Wire());
        try{stopped.beginTurn(()->false,r->{r.run();return true;});stopped.stageLocalReply("测试。","未播放。","neutral");sessionClosed.set(true);stopped.retainPlayedReply(heard);yes(DuplexIntegrationTest.history(stopped).length()==2,"closing entire session cannot retain a new partial record");}finally{stopped.finishTurn();stopped.close();}
    }
    private static void unescapedQuotedDialogue()throws Exception{
        for(String spoken:new String[]{"朋友，\"退下吧\"是让人先离开的意思。", "第一句。然后他说\"volume_up\"只是引用，不是操作。"}){
            String malformed="{\"emotion\":\"neutral\",\"continue_listening\":true,\"speech\":\""+spoken+"\"}";
            Wire wire=new Wire(wireText(malformed,terminal(malformed)));NativeDialogueEngine e=engine(wire);StringBuilder heard=new StringBuilder();
            e.reply(ApiOwnedDialogueTest.history("解释引用的台词。"),"",false,new NativeDialogueEngine.Output(){public void speak(ReplySegment s){heard.append(s.speech);yes(s.actions.isEmpty()&&!s.end,"repaired presentation never gains executable authority");}public void status(JSONObject s){}},()->false);
            yes(heard.toString().equals(spoken),"literal inner quotes are preserved; delivered prefix is not repeated");
            yes(wire.requests==1&&e.status().optString("state").equals("ANSWERED"),"completed body survives malformed presentation without regeneration");
        }
        for(String bad:new String[]{"{\"speech\":\"prefix,\"bad\"}","{\"emotion\":\"neutral\",\"continue_listening\":true,\"speech\":\"x\",\"end\":true}","{\"speech\":\"x\",\"extra\":\"y\"}","{\"speech\":\"x\\q\"y\"}"}){
            yes(NativeDialogueEngine.quotedSpeechEnvelope(bad)==null,"ambiguous structure or invalid escape is not text recovery");
        }
    }
    /** Session metadata is final-response control, never an early partial-stream action. */
    private static void sessionMetadataAfterStreaming()throws Exception {
        final String speech="好，去吃饭吧。下次再聊。";
        for(Boolean listen:new Boolean[]{Boolean.TRUE,Boolean.FALSE}){
            JSONObject object=new JSONObject().put("emotion","caring").put("speech",speech);
            if(listen!=null)object.put("continue_listening",listen);
            String json=object.toString();Wire wire=new Wire(wireText(json,terminal(json)));
            NativeDialogueEngine e=engine(wire);StringBuilder spoken=new StringBuilder();final int[] ends={0};
            e.reply(ApiOwnedDialogueTest.history("我要去吃饭了。我们下次再聊。"),"",false,new NativeDialogueEngine.Output(){
                public void speak(ReplySegment s){
                    spoken.append(s.speech);
                    if(s.end){yes(e.status().has("responseCompletedMonotonicMs"),"close requires complete response, not partial boolean");yes(s.speech.isEmpty(),"already streamed farewell is not replayed with terminal metadata");ends[0]++;}
                    else yes(!e.status().has("responseCompletedMonotonicMs"),"substantive speech still streams before final metadata");
                }
                public void status(JSONObject s){}
            },()->false);
            boolean closes=Boolean.FALSE.equals(listen);
            yes(spoken.toString().equals(speech),"exactly one spoken farewell regardless of control metadata");
            yes(ends[0]==(closes?1:0)&&e.status().optBoolean("endRequested")==closes,"model boolean alone determines close");
            yes(wire.requests==1,"no extra lifecycle classifier or model request");
            MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,new Wire(wireText(json,terminal(json))));
            try{client.beginTurn(()->false,r->{r.run();return true;});StringBuilder heard=new StringBuilder();client.replySegments("我要去吃饭了。我们下次再聊。",s->{heard.append(s.speech);return s.speech;});
                yes(heard.toString().equals(speech)&&client.endAfterReply()==closes,"actual client retains empty terminal segment after streamed speech");
            }finally{client.finishTurn();client.close();}
        }
        for(Object invalid:new Object[]{"false",Integer.valueOf(0),JSONObject.NULL}){
            String json=new JSONObject().put("continue_listening",true).put("speech",speech).put("continue_listening",invalid).toString();
            fails(()->NativeDialogueEngine.parse(json,"测试会话。",true),"invalid lifecycle types never coerce to a command");
        }
        final String json=new JSONObject().put("emotion","caring").put("continue_listening",false).put("speech",speech).toString();
        for(boolean cancelled:new boolean[]{false,true}){
            Wire wire=new Wire(wireText(json,cancelled?terminal(json):null));NativeDialogueEngine e=engine(wire);
            AtomicBoolean stop=new AtomicBoolean();final int[] ends={0};
            fails(()->e.reply(ApiOwnedDialogueTest.history("今天先不聊了。"),"",false,new NativeDialogueEngine.Output(){
                public void speak(ReplySegment s){if(s.end)ends[0]++;if(cancelled)stop.set(true);}
                public void status(JSONObject s){}
            },stop::get),"incomplete or cancelled response does not finalize lifecycle");
            yes(ends[0]==0&&!e.status().optBoolean("endRequested")&&wire.requests==1,"no end signal or replay after incomplete/cancelled streamed farewell");
        }
        String quoted="{\"emotion\":\"caring\",\"continue_listening\":false,\"speech\":\"他说\"再见\"。\"}";
        List<ReplySegment> repaired=NativeDialogueEngine.parse(quoted,"今天先聊到这里。",true);
        yes(repaired.size()==1&&repaired.get(0).end&&repaired.get(0).speech.equals("他说\"再见\"。"),"syntax-only quote recovery preserves explicit lifecycle boolean");
    }
    private static void unframedRepairIsBoundedAndModelOwned()throws Exception{
        final String draft="再见的英语是 goodbye。";
        final int[] attempts={0};NativeDialogueEngine ordinary=new NativeDialogueEngine(ApiOwnedDialogueTest.config(),url->{try{
            DuplexIntegrationTest.Connection c=new DuplexIntegrationTest.Connection();c.contentType="text/event-stream";c.body=wireText(draft,terminal(draft));attempts[0]++;return c;
        }catch(Exception x){throw new IOException(x);}});
        final StringBuilder heard=new StringBuilder();ordinary.reply(ApiOwnedDialogueTest.history("再见用英语怎么说？"),"",false,new NativeDialogueEngine.Output(){
            public void speak(ReplySegment s){yes(!s.end&&s.actions.isEmpty(),"plain speech cannot end or operate hardware");heard.append(s.speech);}
            public void feedback(ReplySegment s){}public void status(JSONObject s){}
        },()->false);
        yes(attempts[0]==1&&heard.toString().equals(draft)&&!ordinary.status().optBoolean("endRequested"),"completed plain reply avoids unnecessary model repair");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=0)throw new IOException("OFFLINE_ONLY");
        unframedRepairIsBoundedAndModelOwned();sessionMetadataAfterStreaming();unescapedQuotedDialogue();interruptedContext();parserCases();recordBoundaries();singleEnvelopeStreamsBeforeClosingQuote();engineCases();completedPostToolMessageSurvivesTrailingMetadataLoss();syntheticMissingLifecycle();waitingCases();
        System.out.println("NATIVE_REPLY_STREAM "+checks+" checks passed; offline actual engine/client, no device/network");
    }
}
