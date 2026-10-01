package org.sp001.core;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Regression over real client/pipeline transactions; no device or cloud calls. */
public final class NativeFeedbackRegressionTest {
    static int checks;
    static void yes(boolean v,String label){checks++;if(!v)throw new AssertionError(label);}
    static void nativeBudget()throws Exception{
        MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,new ModelTurnControlTest.Wire(ModelTurnControlTest.result(false,null)));
        try{
            c.beginTurn(()->false,r->{r.run();return true;});
            // This is where Android constructs its AudioTrack and freezes the pipeline budget.
            for(String name:new String[]{"childStory","childEnglish","knowledgeBackend"}){
                java.lang.reflect.Field field=MiniMaxVoiceClient.class.getDeclaredField(name);field.setAccessible(true);
                yes(field.get(c)==null,"M3 never creates legacy orchestration: "+name);
            }
            yes(c.childOutputLimit()==ChildCompanionPolicy.STORY_PCM_LIMIT,"native playback must reserve full bounded capacity BEFORE intent/first record");
            yes(c.childTextLimit()==ChildCompanionPolicy.STORY_TEXT_LIMIT,"native text capacity cannot depend on Chinese keywords");
        }finally{c.finishTurn();c.close();}
        final int[] bytes={0};SentencePlayback.Result r=new SentencePlayback.Result();
        SentencePlayback.run(new SentencePlayback.Ports(){
            public void check(){}
            public void sentences(SentencePlayback.TextSink s)throws Exception{for(int i=0;i<40;i++)s.accept("这是完整故事中的一小段。");}
            public int synthesize(String s,SentencePlayback.AudioSink out)throws Exception{byte[] chunk=new byte[64000];out.accept(chunk);out.accept(chunk);return 128000;}
            public void write(byte[] pcm){bytes[0]+=pcm.length;}
            public int finish(){return bytes[0];}public void close(){}
        },r,ChildCompanionPolicy.STORY_PCM_LIMIT);
        yes(r.drained&&r.sentences==40&&r.acceptedBytes==5120000,"story survives ordinary 32-record/60-second cap");
    }
    static void nativeAnswerCapacity()throws Exception{
        JSONArray blocks=new JSONArray();StringBuilder text=new StringBuilder();
        for(int i=0;i<35;i++){
            String speech="小伙伴观察了线索，尝试一个办法，又换了一个办法。";
            JSONObject block=new JSONObject().put("continue_listening",true).put("speech",speech).put("emotion","neutral").put("actions",new JSONArray()).put("end",false);
            if(text.length()>0)text.append('\n');text.append(block);
        }
        JSONObject result=new JSONObject().put("status","completed").put("error",JSONObject.NULL).put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text.toString())))));
        ModelTurnControlTest.Wire wire=new ModelTurnControlTest.Wire(result);MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);List<ReplySegment> seen=new ArrayList<>();
        try{c.beginTurn(()->false,r->{r.run();return true;});c.replySegments("我还想听刚才那一段，完整说完。",s->{seen.add(s);return s.speech;});yes(seen.size()==35,"same native entry accepts model-chosen long output, no story keyword required");yes(!c.endAfterReply(),"story completion is not session farewell");}finally{c.finishTurn();c.close();}
    }
    static void noBusinessClassifier()throws Exception{
        String[] requests={"讲一个完整的草船借箭故事。","继续讲下去。","今天先不学英语，我来问问题。","声音有点小，帮我调高一些。"};
        for(String request:requests){
            ModelTurnControlTest.Wire wire=new ModelTurnControlTest.Wire(ModelTurnControlTest.result(false,null));
            MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);
            c.attachChildStoryStore(new ChildStorySession.Store(){public String read(){throw new AssertionError("native consulted story curriculum");}public void write(String text){throw new AssertionError("native mutated story curriculum");}});
            try{c.beginTurn(()->false,r->{r.run();return true;});c.replySegments(request,s->s.speech);c.commitReply();
                JSONObject sent=new JSONObject(new String(wire.connection.uploaded.toByteArray(),"UTF-8"));String instructions=sent.getString("instructions");
                yes("none".equals(sent.getJSONObject("reasoning").getString("effort")),"keep the baseline M3 reasoning setting; activation did not resolve the observed interrupted-context failures");yes("priority".equals(sent.getString("service_tier")),"interactive M3 turns use provider priority admission");
                yes(!instructions.contains("本轮是普通交流")&&!instructions.contains("本轮英语状态")&&!instructions.contains("故事模式讲"),"no local topic or lesson classification in request");
                yes(!c.childStoryMode()&&!c.childAwaitingEnglish()&&"MODEL_OWNED".equals(c.childProgressStatus()),"no curriculum state affects native turn");
                yes(sent.getJSONArray("input").getJSONObject(0).getString("content").equals(request),"unchanged input");
            }finally{c.finishTurn();c.close();}
        }
    }
    static class Volume implements VolumeChange.Ports{
        int value=13,gain=1200;boolean ignoreGain;
        public int volume(){return value;}public int maximum(){return 15;}public int gain(){return gain;}
        public void setVolume(int v){value=v;}public boolean save(int v,int g){gain=ignoreGain?1000:g;return true;}
        public void restoreSaved(){gain=1200;}
    }
    static void volume(){
        Volume p=new Volume();p.ignoreGain=true;VolumeChange.Result r=VolumeChange.run(p,AudioCommand.LOUDER);
        yes(!r.success&&p.value==13,"saved=true does not prove gain changed; roll back and do not claim success");
        p=new Volume();r=VolumeChange.run(p,AudioCommand.LOUDER);yes(r.success&&r.observedVolume==14&&p.gain==1200,"both settings read back");
        p=new Volume();p.value=15;p.gain=2000;r=VolumeChange.run(p,AudioCommand.LOUDER);yes(r.success&&!r.changed&&r.answer.contains("最大"),"no-op is an honest boundary, never louder claim");
        p=new Volume(){int reads;public int volume(){return ++reads>=4?7:value;}};
        r=VolumeChange.run(p,AudioCommand.LOUDER);
        yes(!r.success&&r.observedVolume==7&&"VOLUME_CHANGED_BEFORE_ACK".equals(r.code),"newer actual volume prevents false success acknowledgement");
        p=new Volume(){int reads;public int volume(){if(++reads>=4)throw new IllegalStateException("unavailable");return value;}};
        r=VolumeChange.run(p,AudioCommand.LOUDER);
        yes(!r.success&&"VOLUME_ACK_READBACK_FAILED".equals(r.code),"missing final readback is not success");
    }
    static List<ReplySegment> decoded(JSONObject object,String user,boolean actions)throws Exception{
        ReplyEnvelopeStream parser=ReplyEnvelopeStream.forModel(user,actions);List<ReplySegment> out=new ArrayList<>();
        out.addAll(parser.append(object.toString()));out.addAll(parser.finish("stop"));
        if(parser.malformedRecords()>0||parser.generatedFallback())return Collections.emptyList();return out;
    }
    static void repairReceivesRejectedOutput()throws Exception{
        String user="帮我调大一点声音，然后说你好。";
        JSONObject bad=new JSONObject().put("continue_listening",true).put("speech","你好。").put("emotion","neutral").put("actions",new JSONArray().put("volume_up")).put("action_request","旧的无关请求");
        JSONObject invalid=ApiOwnedDialogueTest.message("");invalid.getJSONArray("content").getJSONObject(0).put("text",bad.toString());
        ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(invalid),ApiOwnedDialogueTest.response(DeviceToolExecutionTest.call("repair_action","volume_up")),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("音量已调整。你好。")));
        MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);List<ReplySegment> parts=new ArrayList<>();final int[] executed={0};
        try{client.beginTurn(()->false,r->{r.run();return true;});client.bindDeviceExecutor(a->{executed[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","音量已调整。");});client.replySegments(user,s->{parts.add(s);return s.speech;});
            yes(wire.calls==3&&executed[0]==1&&parts.size()==1&&parts.get(0).actions.isEmpty(),"repair converts rejected text command to one formal function and result, never executes text");
            String instructions=wire.request(1).getString("instructions");yes(instructions.contains("旧的无关请求")&&instructions.contains("current_user_request"),"repair receives concrete rejected data instead of blind retry");
        }finally{client.finishTurn();client.close();}
    }
    static void nativeControls()throws Exception{
        final String user="今天先聊到这里，我去吃饭了。";
        JSONObject object=new JSONObject().put("continue_listening",true).put("speech","故事讲完了。").put("emotion","neutral").put("actions",new JSONArray()).put("end",true);
        List<ReplySegment> parts=decoded(object,"讲完草船借箭的故事。",true);
        yes(parts.isEmpty(),"old content-end flag is rejected rather than silently ignored");
        object.put("session_control",new JSONObject().put("action","end_session").put("user_request",user));
        parts=decoded(object,user,true);yes(parts.isEmpty(),"even matching session_control cannot bypass end_session tool");
        yes(decoded(object,"再见的英语怎么说？",true).isEmpty(),"old request cannot close a new turn");
        object.remove("session_control");object.put("actions",new JSONArray().put("volume_up"));
        yes(decoded(object,"提高音量。",true).isEmpty(),"unattributed model action rejected");
        object.put("action_request","提高音量。");parts=decoded(object,"提高音量。",true);
        yes(parts.isEmpty(),"matching quoted input cannot authorize a textual command");
        yes(decoded(object,"不要调音量。",true).isEmpty(),"stale action request rejected");
        parts=decoded(object,"查询资料。",false);yes(parts.isEmpty(),"tool-backed text command rejected rather than silently stripped");
        object.remove("end");object.remove("actions");object.remove("action_request");
        parts=decoded(object,user,true);yes(parts.size()==1&&!parts.get(0).end&&parts.get(0).actions.isEmpty(),"speech-only native schema defaults to keep listening");
        object.put("continue_listening",false);parts=decoded(object,user,true);yes(parts.size()==1&&parts.get(0).end&&parts.get(0).actions.isEmpty(),"model response metadata can close after playback without local phrase classification");
        object.put("continue_listening",true);parts=decoded(object,"再见的英语怎么说？",true);yes(parts.size()==1&&!parts.get(0).end,"model can explicitly keep listening after quoted or translated goodbye");
        object.put("continue_listening","false");yes(decoded(object,user,true).isEmpty(),"session metadata must be boolean, never string coercion");
        // A matching quote proves provenance, not semantic truth; real-model negative cases are still required.
    }
    static void overlongAnswerIsRepairedWithLengthEvidence()throws Exception{
        StringBuilder longText=new StringBuilder();for(int i=0;i<1320;i++)longText.append('字');
        String complete="准备好船只后，诸葛亮利用大雾借到了箭，回营向周瑜交差。";
        ApiOwnedDialogueTest.Wire wire=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(longText.toString())),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(complete)));
        MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);StringBuilder output=new StringBuilder();
        try{client.beginTurn(()->false,r->{r.run();return true;});client.replySegments("完整说完。",s->{output.append(s.speech);return s.speech;});
            yes(output.toString().equals(complete)&&wire.calls==2,"overlong answer is repaired before any partial speech");
            String instructions=wire.request(1).getString("instructions");
            yes(instructions.contains("NATIVE_REPLY_TEXT_LIMIT")&&instructions.contains("maximum_speech_characters")&&instructions.contains("1200"),"repair knows actual violated constraint instead of repeating a valid but overlong JSON");
            yes(!client.endAfterReply(),"length repair does not end conversation");
        }finally{client.finishTurn();client.close();}
    }
    static void nativeEscapingCapacity()throws Exception{
        StringBuilder text=new StringBuilder();for(int i=0;i<28;i++)text.append("诸葛亮观察江上的雾气，和鲁肃一起准备好船只。");
        String json=new JSONObject().put("continue_listening",true).put("speech",text.toString()).put("emotion","neutral").toString();
        StringBuilder escaped=new StringBuilder();for(int i=0;i<json.length();i++){char c=json.charAt(i);if(c>127)escaped.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else escaped.append(c);}
        yes(escaped.length()>ReplyEnvelopeStream.MAX_RECORD,"legal JSON escapes exceed legacy record size, not the native decoded text budget");
        for(String encoded:new String[]{json,escaped.toString()}){
            ReplyEnvelopeStream parser=ReplyEnvelopeStream.forModel("请把这段完整讲完。",false);List<ReplySegment> got=new ArrayList<>();
            for(int at=0;at<encoded.length();at+=17)got.addAll(parser.append(encoded.substring(at,Math.min(encoded.length(),at+17))));
            got.addAll(parser.finish("stop"));yes(parser.malformedRecords()==0&&!parser.generatedFallback(),"both legal JSON encodings accepted");
            yes(got.size()==1&&got.get(0).speech.equals(text.toString())&&!got.get(0).end,"escaping never alters speech or session control");
        }
        StringBuilder over=new StringBuilder();for(int i=0;i<1201;i++)over.append('字');
        yes(decoded(new JSONObject().put("continue_listening",true).put("speech",over.toString()),"完整解释。",false).isEmpty(),"decoded text limit still enforced");
        boolean rejected=false;try{ReplyEnvelopeStream p=ReplyEnvelopeStream.forModel("解释。",false);char[] huge=new char[24577];Arrays.fill(huge,' ');p.append(new String(huge));}catch(IOException expected){rejected=true;}
        yes(rejected,"total wire bound still enforced");
    }
    static void singleLongRecord()throws Exception{
        nativeEscapingCapacity();overlongAnswerIsRepairedWithLengthEvidence();
        StringBuilder text=new StringBuilder();for(int i=0;i<28;i++)text.append("诸葛亮观察江上的雾气，和鲁肃一起准备好船只。");
        yes(text.length()>500&&text.length()<1200,"fixture crosses ordinary cap");
        ModelTurnControlTest.Wire wire=new ModelTurnControlTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(text.toString())));
        MiniMaxVoiceClient c=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,wire);StringBuilder actual=new StringBuilder();
        try{c.beginTurn(()->false,r->{r.run();return true;});c.replySegments("完整讲完这一段。",s->{actual.append(s.speech);return s.speech;});
            yes(actual.toString().equals(text.toString()),"one long record survives downstream speech admission");
        }finally{c.finishTurn();c.close();}
    }
    /** Cross-layer regression: a valid long M3 answer must reach actual TTS adapters,
     * not just a text-collecting sink. Only HTTP/socket/audio drivers are simulated. */
    static void longAnswerThroughTts()throws Exception{
        StringBuilder content=new StringBuilder();for(int i=0;i<30;i++)content.append("小伙伴观察纸上的线索，试了一种办法，再比较结果。");
        final String full=content.toString();yes(full.length()>500&&full.length()<1200,"long single-record fixture");
        for(final boolean httpOnly:new boolean[]{true,false}){
            final TtsSocketAdapterTest.FakeWire socketWire=new TtsSocketAdapterTest.FakeWire();
            final int[] httpCalls={0},bytes={0},closed={0};final StringBuilder synthesized=new StringBuilder();
            ApiOwnedDialogueTest.Wire model=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message(full)));
            final MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,TtsSocketAdapterTest.socket(socketWire,()->false),(text,sink)->{httpCalls[0]++;sink.write(new byte[3200]);return 3200;},model);
            java.lang.reflect.Field mode=MiniMaxVoiceClient.class.getDeclaredField("ttsHttpOnly");mode.setAccessible(true);mode.setBoolean(client,httpOnly);
            SentencePlayback.Result result=new SentencePlayback.Result();
            try{
                client.beginTurn(()->false,r->{r.run();return true;});
                SentencePlayback.run(new SentencePlayback.Ports(){
                    public void check(){}
                    public void sentences(SentencePlayback.TextSink sink)throws Exception{client.replySegments("请把刚才的内容完整说完。",segment->{sink.accept(segment.speech);return segment.speech;});}
                    public int synthesize(String text,SentencePlayback.AudioSink sink)throws Exception{synthesized.append(text);return client.synthesizeStream(text,pcm->sink.accept(pcm));}
                    public void write(byte[] pcm){bytes[0]+=pcm.length;}
                    public int finish(){return bytes[0];}
                    public void close(){closed[0]++;}
                },result,client.childOutputLimit());
                yes(result.drained&&result.sentences>1&&closed[0]==1,"long answer drains through "+(httpOnly?"HTTP":"socket")+" TTS");
                yes(full.equals(synthesized.toString()),"no character lost or repeated at TTS boundaries");
                yes(client.ttsCalls==result.sentences&&model.calls==1,"bounded TTS chunks, no story regeneration");
                yes(httpOnly?socketWire.texts==0&&httpCalls[0]==result.sentences:socketWire.texts==result.sentences&&httpCalls[0]==0,"same selected TTS transport");
                yes(!client.endAfterReply(),"playback completion is not session exit");client.commitReply();
            }finally{client.finishTurn();client.close();}
        }
    }
    static void chunkBoundaries()throws Exception{
        StringBuilder longText=new StringBuilder();for(int i=0;i<70;i++)longText.append("完整的句子。Next sentence! ");
        String text=longText.substring(0,1100);List<String> chunks=SentencePlayback.chunks(text);StringBuilder joined=new StringBuilder();
        for(String part:chunks){yes(part.length()<=240,"bounded TTS request");joined.append(part);}
        yes(joined.toString().equals(text),"punctuation, spaces and words retained in order");
        String unicode=String.join("",Collections.nCopies(239,"字"))+"\uD840\uDC00"+String.join("",Collections.nCopies(280,"字"));
        chunks=SentencePlayback.chunks(unicode);joined.setLength(0);
        for(String part:chunks){joined.append(part);yes(!Character.isHighSurrogate(part.charAt(part.length()-1)),"supplementary character not cut");}
        yes(joined.toString().equals(unicode),"Unicode text unchanged");
        for(String invalid:new String[]{"",String.join("",Collections.nCopies(1251,"字")),"正常前缀\uD840"}){
            boolean rejected=false;try{SentencePlayback.chunks(invalid);}catch(IOException expected){rejected=true;}yes(rejected,"invalid entire record rejected before any speech");
        }
        final int[] calls={0},closed={0};SentencePlayback.Result result=new SentencePlayback.Result();
        try{SentencePlayback.run(new SentencePlayback.Ports(){
            public void check()throws Exception{if(calls[0]>0)throw new IOException("TURN_CANCELLED");}
            public void sentences(SentencePlayback.TextSink sink)throws Exception{sink.accept(text);}
            public int synthesize(String part,SentencePlayback.AudioSink sink)throws Exception{calls[0]++;sink.accept(new byte[3200]);return 3200;}
            public void write(byte[] pcm){}public int finish(){throw new AssertionError("cancelled output cannot drain");}public void close(){closed[0]++;}
        },result,ChildCompanionPolicy.STORY_PCM_LIMIT);throw new AssertionError("expected cancellation");}catch(IOException expected){yes("TURN_CANCELLED".equals(expected.getMessage()),"cancellation propagated");}
        yes(calls[0]==1&&!result.drained&&closed[0]==1,"remaining chunks never spoken after cancellation");
    }
    static void transientNetworkRecovery()throws Exception{
        yes(NativeDialogueEngine.transientNetworkFailure(new IOException("HTTP_TRANSFER_FAILED",new java.net.SocketException("fixture"))),"connection reset eligible before any delivery");
        yes(NativeDialogueEngine.transientNetworkFailure(new IOException("HTTP_TRANSFER_FAILED",new java.net.SocketTimeoutException("fixture"))),"socket timeout is a network failure");
        yes(!NativeDialogueEngine.transientNetworkFailure(new IOException("HTTP_TRANSFER_FAILED",new javax.net.ssl.SSLHandshakeException("fixture"))),"TLS identity errors never retry");
        yes(!NativeDialogueEngine.transientNetworkFailure(new IOException("HTTP_TRANSFER_FAILED",new IOException("fixture"))),"unknown transfer failure not blindly retried");
        yes(!NativeDialogueEngine.transientNetworkFailure(new IOException("TURN_CANCELLED",new java.net.SocketException("fixture"))),"cancelled turns never retry");
        final int[] attempts={0};ApiOwnedDialogueTest.Wire response=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("天气的英语是 weather。")));
        MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,url->{if(++attempts[0]==1)throw new java.net.SocketException("fixture reset");return response.open(url);});
        StringBuilder spoken=new StringBuilder();try{client.beginTurn(()->false,r->{r.run();return true;});client.replySegments("天气英语怎么说",s->{spoken.append(s.speech);return s.speech;});
            yes(attempts[0]==2&&client.llmCalls==2&&spoken.toString().equals("天气的英语是 weather。"),"one transient request recovered without duplicate speech");
            yes(client.knowledgeStatus().optInt("networkRetries")==1&&!client.endAfterReply(),"recovery observable without conversation exit");
        }finally{client.finishTurn();client.close();}BoundedHttpTest.zeroSlots();
        final int[] writes={0},calls={0};ApiOwnedDialogueTest.Wire action=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(DeviceToolExecutionTest.call("once","volume_up")));
        MiniMaxVoiceClient afterTool=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,url->{if(++calls[0]==2)throw new java.net.SocketException("fixture after action");return action.open(url);});
        try{afterTool.beginTurn(()->false,r->{r.run();return true;});afterTool.bindDeviceExecutor(a->{writes[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","声音已调大。");});afterTool.replySegments("大声一点",s->s.speech);
            yes(writes[0]==1&&calls[0]==2,"post-effect failure never retries device turn");
        }finally{afterTool.finishTurn();afterTool.close();}BoundedHttpTest.zeroSlots();
    }
    /** Reproduce a complete function item followed by a stalled terminal frame.
     * No effect is executed until a complete retry response arrives. Socket
     * inactivity is bounded independently of story length and playback duration. */
    static void incompleteTerminalRecovery()throws Exception{
        final JSONObject tool=DeviceToolExecutionTest.call("tail_stall","volume_up");
        final String prefix="data: "+new JSONObject().put("type","response.output_item.added").put("output_index",0).put("item",tool)+"\n\n"+
            "data: "+new JSONObject().put("type","response.output_item.done").put("output_index",0).put("item",tool)+"\n\n"+
            "event: response.completed\ndata: {\"type\":\"response.completed\",";
        final int[] calls={0},writes={0};final java.util.List<javax.net.ssl.HttpsURLConnection> observed=new ArrayList<>();
        final ApiOwnedDialogueTest.Wire healthy=new ApiOwnedDialogueTest.Wire(ApiOwnedDialogueTest.response(tool),ApiOwnedDialogueTest.response(ApiOwnedDialogueTest.message("声音已调大。我们接着聊。")));
        MiniMaxVoiceClient client=new MiniMaxVoiceClient(ApiOwnedDialogueTest.config(),()->false,null,null,url->{
            if(++calls[0]>1){javax.net.ssl.HttpsURLConnection next=healthy.open(url);observed.add(next);return next;}
            javax.net.ssl.HttpsURLConnection stalled=new javax.net.ssl.HttpsURLConnection(url){
                public java.io.OutputStream getOutputStream(){return new java.io.ByteArrayOutputStream();}
                public int getResponseCode(){return 200;}public String getContentType(){return "text/event-stream";}
                public java.io.InputStream getInputStream()throws IOException{
                    return new java.io.InputStream(){final byte[] data=prefix.getBytes("UTF-8");int at;
                        public int read()throws IOException{if(at==data.length)throw new java.net.SocketTimeoutException("fixture stalled after completed tool item");return data[at++]&255;}
                        public int read(byte[] target,int offset,int length)throws IOException{if(length==0)return 0;if(at==data.length)throw new java.net.SocketTimeoutException("fixture stalled after completed tool item");int n=Math.min(length,data.length-at);System.arraycopy(data,at,target,offset,n);at+=n;return n;}
                    };
                }
                public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
                public String getCipherSuite(){return "TEST";}public java.security.cert.Certificate[] getLocalCertificates(){return null;}public java.security.cert.Certificate[] getServerCertificates(){return null;}
            };observed.add(stalled);return stalled;
        });
        final StringBuilder text=new StringBuilder();
        try{client.beginTurn(()->false,r->{r.run();return true;});client.bindDeviceExecutor(a->{yes(calls[0]>=2,"incomplete terminal never executes a speculative tool");writes[0]++;return DeviceToolExecutionTest.receipt(true,true,"VOLUME_APPLIED","声音已调大。");});
            client.replySegments("调大声音后继续聊天。",s->{text.append(s.speech);return s.speech;});
            yes(calls[0]==3&&writes[0]==1,"one retry before delivery then one tool and its final answer");
            yes(text.toString().equals("声音已调大。我们接着聊。")&&!client.endAfterReply(),"recovery retains final speech without duplicate acknowledgement");
            for(javax.net.ssl.HttpsURLConnection connection:observed)yes(connection.getReadTimeout()==10000,"10s read inactivity, not a whole-response deadline");
            yes(client.knowledgeStatus().optInt("networkRetries")==1,"single transport recovery reported");
            yes(ChildCompanionPolicy.STORY_REQUEST_MS>10000,"long story budget remains separate");
        }finally{client.finishTurn();client.close();}BoundedHttpTest.zeroSlots();
    }
    public static void main(String[] args)throws Exception{
        incompleteTerminalRecovery();transientNetworkRecovery();chunkBoundaries();longAnswerThroughTts();nativeBudget();nativeAnswerCapacity();noBusinessClassifier();volume();nativeControls();singleLongRecord();repairReceivesRejectedOutput();System.out.println("NATIVE_FEEDBACK "+checks+" checks passed; actual classes; no device/network");
    }
}
