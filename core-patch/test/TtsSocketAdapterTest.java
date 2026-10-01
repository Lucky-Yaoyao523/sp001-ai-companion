package org.sp001.core;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Actual production adapter/client with deterministic in-process transport, never a network. */
public final class TtsSocketAdapterTest {
 static int checks;
 static void yes(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 interface Work{void run()throws Exception;}
 static void fails(Work work)throws Exception{try{work.run();throw new AssertionError("expected failure");}catch(IOException expected){checks++;}}
 static final MiniMaxTtsSocket.Clock CLOCK=new MiniMaxTtsSocket.Clock(){public long now(){return System.nanoTime()/1000000;}public void pause(long ms)throws InterruptedException{Thread.sleep(ms);}};
 static final MiniMaxVoiceClient.Cancel NEVER=new MiniMaxVoiceClient.Cancel(){public boolean cancelled(){return false;}};
 static final class FakeWire implements MiniMaxTtsSocket.Wire {
  volatile MiniMaxTtsSocket.Events listener;String session,languageBoost,lastText;boolean englishNormalization;volatile boolean closed;
  int opens,texts,flushes;boolean holdHandshake,handshakeFails,sendFalse,flushFalse,badFormat,noFinal,oldSession,holdAudio,emptyFlush;
  final CountDownLatch opened=new CountDownLatch(1),flushed=new CountDownLatch(1);
  public void open(String origin,String key,MiniMaxTtsSocket.Events events){opens++;listener=events;opened.countDown();
   if(handshakeFails){events.failure("TEST_CONNECT_FAILED");return;}
   if(!holdHandshake)events.message("{\"event\":\"connected_success\"}");
  }
  void event(String type)throws Exception{listener.message(new JSONObject().put("event",type).put("session_id",oldSession?"old":session).toString());}
  void audio(boolean last)throws Exception{
   audioBytes(3200,last);
  }
  void audioBytes(int bytes,boolean last)throws Exception{
   char[] hex=new char[bytes*2];java.util.Arrays.fill(hex,'0');hex[1]='1';
   JSONObject row=new JSONObject().put("event","task_continued").put("session_id",oldSession?"old":session)
    .put("data",new JSONObject().put("audio",new String(hex)));
   if(last)row.put("is_final",true).put("extra_info",new JSONObject().put("audio_sample_rate",badFormat?16000:32000).put("audio_channel",1).put("audio_format","pcm"));
   listener.message(row.toString());
  }
  void reply()throws Exception{if(emptyFlush){event("task_flushed");return;}event("sentence_start");audio(!noFinal);event("sentence_end");event("task_flushed");}
  public boolean send(String text)throws Exception{
   JSONObject command=new JSONObject(text);String type=command.getString("event");
   if(type.equals("task_start")){session=command.getString("session_id");languageBoost=command.getString("language_boost");englishNormalization=command.getJSONObject("voice_setting").optBoolean("english_normalization");listener.message(new JSONObject().put("event","task_started").put("session_id",session).toString());}
   else if(type.equals("task_continue")){texts++;lastText=command.getString("text");if(sendFalse)return false;}
   else if(type.equals("task_flush")){flushes++;flushed.countDown();if(flushFalse)return false;if(!holdAudio)reply();}
   return true;
  }
  public void close(){closed=true;}
 }
 static MiniMaxTtsSocket socket(FakeWire wire,MiniMaxVoiceClient.Cancel cancel){return new MiniMaxTtsSocket("https://api.minimax.io","unused-test-key-123456789","male-qn-qingse",cancel,wire,CLOCK);}
 static JSONObject config()throws Exception{return new JSONObject().put("region","global").put("apiKey","unused-test-key-123456789").put("voiceId","male-qn-qingse");}
 static final class Http implements MiniMaxVoiceClient.HttpSpeech{int count;public int synthesize(String text,MiniMaxVoiceClient.PcmSink sink)throws Exception{count++;sink.write(new byte[3200]);return 3200;}}
 static final class Sink implements MiniMaxVoiceClient.PcmSink{int bytes;public void write(byte[] audio){bytes+=audio.length;}}
 static void complete(Future<?> future)throws Exception{future.get(2,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  ExecutorService pool=Executors.newCachedThreadPool();
  try{
   final FakeWire w0=new FakeWire();final MiniMaxTtsSocket s0=socket(w0,NEVER);s0.close();long now=CLOCK.now();yes(!s0.awaitReady()&&CLOCK.now()-now<100&&w0.opens==0,"close before prepare");
   final FakeWire waiting=new FakeWire();waiting.holdHandshake=true;final MiniMaxTtsSocket sw=socket(waiting,NEVER);
   Future<Boolean> waiter=pool.submit(new Callable<Boolean>(){public Boolean call()throws Exception{return sw.awaitReady();}});
   yes(waiting.opened.await(1,TimeUnit.SECONDS),"prepare began");sw.close();yes(!waiter.get(1,TimeUnit.SECONDS)&&waiting.closed,"close during await");
   waiting.listener.message("{\"event\":\"connected_success\"}");yes(!sw.awaitReady()&&waiting.texts==0,"late handshake ignored");
   FakeWire happy=new FakeWire();MiniMaxTtsSocket hs=socket(happy,NEVER);Sink sink=new Sink();Http unused=new Http();MiniMaxVoiceClient hc=new MiniMaxVoiceClient(config(),NEVER,hs,unused);
   hc.prepareTts();yes(hc.synthesizeStream("你好。",sink)==3200,"first turn");String mixed="今天是30摄氏度，英文是 thirty degrees Celsius。";yes(hc.synthesizeStream(mixed,sink)==3200,"mixed language turn");
   yes("auto".equals(happy.languageBoost)&&happy.englishNormalization&&mixed.equals(happy.lastText),"mixed Chinese and English uses provider normalization without text rewrite");
   yes(happy.opens==1&&happy.texts==2&&happy.flushes==2&&unused.count==0&&hc.ttsCalls==2,"same connection no fallback");hc.close();yes(happy.closed,"owner closes connection");
   FakeWire badHandshake=new FakeWire();badHandshake.handshakeFails=true;Http http=new Http();MiniMaxVoiceClient fallback=new MiniMaxVoiceClient(config(),NEVER,socket(badHandshake,NEVER),http);
   fallback.synthesizeStream("你好。",new Sink());fallback.synthesizeStream("再见。",new Sink());yes(http.count==2&&badHandshake.texts==0&&badHandshake.opens==1,"pre-text fallback sticks");fallback.close();
   for(int failure=0;failure<7;failure++){
    FakeWire wire=new FakeWire();wire.sendFalse=failure==0;wire.flushFalse=failure==1;wire.badFormat=failure==2;wire.noFinal=failure==3;wire.oldSession=failure==4;
    wire.emptyFlush=failure==6;
    final Sink target=new Sink();final int fault=failure;Http forbidden=new Http();MiniMaxTtsSocket sock=socket(wire,NEVER);
    final MiniMaxVoiceClient client=new MiniMaxVoiceClient(config(),NEVER,sock,forbidden);
    fails(new Work(){public void run()throws Exception{client.synthesizeStream("测试。",new MiniMaxVoiceClient.PcmSink(){public void write(byte[] pcm)throws Exception{if(fault==5)throw new IOException("TEST_SINK_FAILURE");target.write(pcm);}});}});
    fails(new Work(){public void run()throws Exception{client.synthesizeStream("不能补发。",new Sink());}});
    yes(wire.texts==1&&forbidden.count==0&&wire.closed,"no retry after any text failure "+failure);
    if(failure==2)yes(target.bytes==0,"bad metadata rejected before sink");
    if(failure==3){JSONObject diagnostic=client.ttsTransportStatus().getJSONObject("lastFailedText");
     yes("sentence_end".equals(diagnostic.getString("lastEvent"))&&diagnostic.getInt("bytes")==3200
      &&diagnostic.getInt("starts")==1&&diagnostic.getInt("ends")==0&&diagnostic.getInt("finals")==0,
      "failed text reports missing final before sentence end without speech content");}
    if(failure==6){JSONObject diagnostic=client.ttsTransportStatus().getJSONObject("lastFailedText");
     yes("task_flushed".equals(diagnostic.getString("lastEvent"))&&diagnostic.getInt("bytes")==0
      &&diagnostic.getInt("starts")==0&&diagnostic.getInt("ends")==0&&diagnostic.getInt("finals")==0,
      "empty flush has identifiable per-text evidence without speech content");}
    client.close();
   }
   final FakeWire async=new FakeWire();async.holdAudio=true;final MiniMaxTtsSocket as=socket(async,NEVER);yes(as.awaitReady(),"async connection ready");
   Future<?> receive=pool.submit(new Callable<Object>(){public Object call()throws Exception{fails(new Work(){public void run()throws Exception{as.synthesize("测试。",new Sink());}});return null;}});
   yes(async.flushed.await(1,TimeUnit.SECONDS),"text sent before disconnect");async.listener.failure("TEST_ASYNC_FAILURE");complete(receive);yes(async.texts==1&&async.closed,"async failure terminates");
   final AtomicBoolean cancel=new AtomicBoolean();final FakeWire crowded=new FakeWire();crowded.holdAudio=true;
   final MiniMaxTtsSocket cs=socket(crowded,new MiniMaxVoiceClient.Cancel(){public boolean cancelled(){return cancel.get();}});yes(cs.awaitReady(),"slow sink ready");
   final CountDownLatch inSink=new CountDownLatch(1),releaseSink=new CountDownLatch(1);
   Future<?> slow=pool.submit(new Callable<Object>(){public Object call()throws Exception{fails(new Work(){public void run()throws Exception{cs.synthesize("测试。",new MiniMaxVoiceClient.PcmSink(){public void write(byte[] pcm)throws Exception{inSink.countDown();if(!releaseSink.await(2,TimeUnit.SECONDS))throw new IOException("TEST_SINK_WAIT");}});}});return null;}});
   yes(crowded.flushed.await(1,TimeUnit.SECONDS),"slow text sent");crowded.event("sentence_start");crowded.audio(false);yes(inSink.await(1,TimeUnit.SECONDS),"speaker blocked");
   for(int i=0;i<300;i++)crowded.audio(false);cancel.set(true);cs.close();crowded.audio(false);releaseSink.countDown();complete(slow);
   yes(crowded.texts==1&&crowded.closed,"cancel clears queued and late audio");
   // Real adapter: one legal sixty-second flush, rapid network and stalled speaker.
   // Hex is decoded before queueing; no callback waits for the audio consumer.
   final FakeWire full=new FakeWire();full.holdAudio=true;final MiniMaxTtsSocket fs=socket(full,NEVER);yes(fs.awaitReady(),"full PCM ready");
   final CountDownLatch firstPcm=new CountDownLatch(1),resumePcm=new CountDownLatch(1);final AtomicInteger fullBytes=new AtomicInteger();
   Future<Integer> fullResult=pool.submit(new Callable<Integer>(){public Integer call()throws Exception{return fs.synthesize("完整长回答。",new MiniMaxVoiceClient.PcmSink(){public void write(byte[] pcm)throws Exception{
    yes(pcm.length>0&&pcm[0]==1,"PCM survives until sink");fullBytes.addAndGet(pcm.length);firstPcm.countDown();if(!resumePcm.await(5,TimeUnit.SECONDS))throw new IOException("TEST_SINK_WAIT");
   }});}});
   yes(full.flushed.await(1,TimeUnit.SECONDS),"full text sent");full.event("sentence_start");full.audio(false);yes(firstPcm.await(1,TimeUnit.SECONDS),"full speaker stalled");
   Future<?> producer=pool.submit(new Callable<Object>(){public Object call()throws Exception{full.audioBytes(1920000,false);full.audioBytes(1916800,true);full.event("sentence_end");full.event("task_flushed");return null;}});
   producer.get(4,TimeUnit.SECONDS);JSONObject buffered=fs.status().getJSONObject("buffer");
   yes(buffered.getInt("queuedPcmBytes")==3836800&&buffered.getInt("maxMessageChars")>1048576,"full legal hex frames queued compactly without blocking producer");
   resumePcm.countDown();yes(fullResult.get(4,TimeUnit.SECONDS)==3840000&&fullBytes.get()==3840000,"full legal turn drains without truncation or retry");
   yes(full.texts==1&&fs.status().getJSONObject("buffer").getInt("queuedPcmBytes")==0,"one send and no residual PCM");fs.close();
   final FakeWire over=new FakeWire();over.holdAudio=true;MiniMaxTtsSocket os=socket(over,NEVER);yes(os.awaitReady(),"overflow diagnostic ready");
   over.listener.message(new String(new char[TtsEventBuffer.MAX_MESSAGE_CHARS+1]));yes("TTS_MESSAGE_BOUND".equals(os.status().getString("failure"))&&over.closed,"specific frame bound survives adapter");os.close();
   final FakeWire stale=new FakeWire();MiniMaxTtsSocket ss=socket(stale,NEVER);yes(ss.awaitReady(),"stale ready");ss.synthesize("测试。",new Sink());stale.event("sentence_start");
   final MiniMaxTtsSocket old=ss;fails(new Work(){public void run()throws Exception{old.synthesize("新句。",new Sink());}});yes(stale.texts==1,"late old event prevents next text");
   System.out.println("Actual TTS adapter/client scenarios: "+checks+" checks");
  }finally{pool.shutdownNow();}
 }
}
