package org.sp001.core;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.Proxy;
import javax.net.ssl.HttpsURLConnection;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional replacement for only the original activateVoiceAssistant method.
 * Missing/disabled config preserves original AVS. No boot listener, port, installer,
 * original-package stop, or ambient recording. MiniMax invocation owns a bounded multi-turn session.
 */
public final class OwnerVoiceBridge {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private OwnerVoiceBridge() {}
    private static boolean aborted(Object connector) {
        try { return (Boolean)connector.getClass().getMethod("isActivityAborted").invoke(connector); }
        catch (Exception e) { return true; }
    }
    private static void check(Object c) throws IOException { if (aborted(c) || Thread.currentThread().isInterrupted()) throw new IOException("TURN_CANCELLED"); }
    private static byte[] bounded(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int n;
        while ((n = in.read(chunk)) != -1) { if (out.size() + n > maximum) throw new IOException("INPUT_BOUND"); out.write(chunk, 0, n); }
        return out.toByteArray();
    }
    private static void retainEndReason(Context context, JSONObject receipt) {
        try {
            File path=new File(context.getFilesDir(),"sp001-session-end-reasons.json");
            android.util.AtomicFile file=new android.util.AtomicFile(path);
            JSONArray previous=new JSONArray();
            if(path.isFile()||new File(path+".bak").isFile()){
                try{FileInputStream in=file.openRead();try{previous=new JSONArray(new String(bounded(in,65536),"UTF-8"));}finally{in.close();}}
                catch(Exception ignored){previous=new JSONArray();}
            }
            JSONArray next=new JSONArray();
            for(int i=Math.max(0,previous.length()-63);i<previous.length();i++)next.put(previous.getJSONObject(i));
            next.put(new JSONObject().put("sessionId",receipt.optString("sessionId"))
                .put("result",receipt.optString("result")).put("completedTurns",receipt.optInt("completedTurns"))
                .put("resultBeforeClose",receipt.optString("resultBeforeClose")).put("closeError",receipt.optString("closeError")).put("lastTurnError",receipt.optString("lastTurnError")).put("atMs",receipt.optLong("atMs")));
            FileOutputStream out=null;
            try{out=file.startWrite();out.write(next.toString().getBytes("UTF-8"));file.finishWrite(out);out=null;}
            finally{if(out!=null)file.failWrite(out);}
        }catch(Exception ignored){/* Diagnostics never affect conversation shutdown. */}
    }
    static Context nativeContext() throws Exception {
        return OwnerRuntime.contextFrom(OwnerRuntime.application(), Context.class);
    }
    static JSONObject readConfig(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "sp001-owner-voice.json");
        if (!file.isFile() && !new File(file.getPath() + ".bak").isFile()) return new JSONObject();
        FileInputStream input = new android.util.AtomicFile(file).openRead();
        try { return new JSONObject(new String(bounded(input, 4096), "UTF-8")); }
        finally { input.close(); }
    }
    static JSONObject readLastReceipt(Context context) {
        File file = new File(context.getFilesDir(), "sp001-owner-voice-result.json");
        if (!file.isFile()) return new JSONObject();
        try {
            FileInputStream input = new FileInputStream(file);
            try { return new JSONObject(new String(bounded(input, 4096), "UTF-8")); }
            finally { input.close(); }
        } catch (Exception e) { return new JSONObject(); }
    }
    static boolean enabled(Context context) {
        try {
            JSONObject config = readConfig(context);
            return Boolean.TRUE.equals(config.opt("enabled")) && Boolean.TRUE.equals(config.opt("recordingConsent"))
                    && (("playback_test".equals(config.optString("mode")) && validFixture(config)) || ("diagnostic".equals(config.optString("mode")) && validDiagnostic(config)) || "loopback".equals(config.optString("mode")) || ("cloud".equals(config.optString("mode")) && validCloud(config)) || ("minimax".equals(config.optString("mode")) && validMiniMax(config)));
        } catch (Exception e) { return false; }
    }
    public static boolean tryRun(Object connector) {
        if(CharacterMode.managed()&&CharacterMode.legacy())return false;
        Context context; JSONObject config;
        try { context = nativeContext(); config = readConfig(context); }
        catch (Exception e) { if(CharacterMode.managed()){OwnerFeedback.errorCue();return true;}return false; }
        if (!Boolean.TRUE.equals(config.opt("enabled"))) { if(CharacterMode.managed()){OwnerFeedback.errorCue();return true;}return false; }
        if (!Boolean.TRUE.equals(config.opt("recordingConsent"))) return true;
        String mode = config.optString("mode", "");
        if (mode.equals("playback_test")) return runFixture(connector,context,config);
        if (mode.equals("minimax") || mode.equals("diagnostic")) return runMiniMax(connector, context, config);
        if (!mode.equals("loopback") && !mode.equals("cloud")) return true;
        if (mode.equals("cloud") && !validCloud(config)) return true;
        if (!BUSY.compareAndSet(false, true)) return true;
        long token = 0; byte[] pcm = null, response = null;
        String result = "FAILED"; boolean played = false;
        try {
            check(connector);
            boolean stereo = (Boolean)Class.forName("com.smarttoy.util.c").getMethod("nb").invoke(null);
            token = OwnerCaptureTap.begin(stereo);
            // Existing native method owns start/stop, pause and abort. English text is not used.
            // A fresh headless JSConnector has no _program. The stock four-argument
            // listenFor(..., true) explicitly registers it before native capture starts.
            connector.getClass().getMethod("listenFor", String[].class, String.class, Float.TYPE, Boolean.TYPE)
                    .invoke(connector, null, null, Float.valueOf(8.0f), Boolean.TRUE);
            pcm = OwnerCaptureTap.finish(token); check(connector);
            if (pcm.length < 3200 || !hasSignal(pcm)) throw new IOException("NO_VALID_AUDIO");
            if (mode.equals("cloud")) response = cloud(pcm, config, connector);
            else response = pcm;
            check(connector); play(response, mode.equals("cloud") ? 24000 : 16000, connector);
            played = true; result = mode.equals("cloud") ? "CLOUD_AUDIO_PLAYED" : "NATIVE_LOOPBACK_PLAYED";
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            String code = cause.getMessage();
            result = aborted(connector) || Thread.currentThread().isInterrupted() ? "TURN_CANCELLED" :
                    code != null && code.matches("[A-Z_]{1,60}") ? code : "VOICE_BRIDGE_FAILED";
        }
        finally {
            OwnerCaptureTap.cancel(token);
            try {
                JSONObject receipt = new JSONObject(); receipt.put("mode", mode); receipt.put("result", result);
                receipt.put("capturedBytes", pcm == null ? 0 : pcm.length); receipt.put("outputBytes", response == null ? 0 : response.length);
                receipt.put("playbackDrained", played); receipt.put("humanSpeechConfirmed", false);
                receipt.put("audioSaved", false); receipt.put("atMs", System.currentTimeMillis());
                FileOutputStream out = context.openFileOutput("sp001-owner-voice-result.json", Context.MODE_PRIVATE);
                try { out.write((receipt.toString() + "\n").getBytes("UTF-8")); } finally { out.close(); }
            } catch (Exception ignored) {}
            if (pcm != null) Arrays.fill(pcm, (byte)0);
            if (response != null && response != pcm) Arrays.fill(response, (byte)0);
            BUSY.set(false);
        }
        return true;
    }
    private static boolean validFixture(JSONObject config) {
        return Boolean.TRUE.equals(config.opt("fixtureConsent")) && Boolean.FALSE.equals(config.opt("cloudConsent"))
            && config.optString("fixtureSha256").matches("[a-f0-9]{64}");
    }
    private static boolean runFixture(Object connector,Context context,JSONObject config) {
        if(!validFixture(config)||!BUSY.compareAndSet(false,true))return true;
        byte[] pcm=null;String result="FIXTURE_FAILED";boolean played=false;
        try {
            FileInputStream input=new FileInputStream(new File(context.getFilesDir(),"sp001-playback-fixture.pcm"));
            try{pcm=bounded(input,960000);}finally{input.close();}
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(pcm);
            StringBuilder hex=new StringBuilder();for(byte v:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
            if(!hex.toString().equals(config.getString("fixtureSha256")))throw new IOException("FIXTURE_HASH_MISMATCH");
            check(connector);play(pcm,16000,connector);played=true;result="DIGITAL_FIXTURE_PLAYED";
        }catch(Exception e){result=aborted(connector)||Thread.currentThread().isInterrupted()?"TURN_CANCELLED":"FIXTURE_PLAY_FAILED";}
        finally{
            try{
                JSONObject receipt=new JSONObject().put("mode","playback_test").put("result",result)
                    .put("capturedBytes",0).put("outputBytes",pcm==null?0:pcm.length).put("playbackDrained",played)
                    .put("microphoneOpened",false).put("apiCalls",0).put("atMs",System.currentTimeMillis());
                FileOutputStream out=context.openFileOutput("sp001-owner-voice-result.json",Context.MODE_PRIVATE);
                try{out.write(receipt.toString().getBytes("UTF-8"));}finally{out.close();}
            }catch(Exception ignored){}
            if(pcm!=null)Arrays.fill(pcm,(byte)0);BUSY.set(false);
        }
        return true;
    }
    static boolean validMiniMax(JSONObject config) {
        try {
            MiniMaxCodec.origin(config.optString("region"));
            return Boolean.TRUE.equals(config.opt("recordingConsent")) && Boolean.TRUE.equals(config.opt("cloudConsent"))
                && MiniMaxCodec.validKey(config.optString("apiKey")) && MiniMaxCodec.validVoice(config.optString("voiceId", "male-qn-qingse"));
        } catch (Exception e) { return false; }
    }
    static boolean validDiagnostic(JSONObject config) {
        return "diagnostic".equals(config.optString("mode")) && Boolean.TRUE.equals(config.opt("enabled"))
            && Boolean.TRUE.equals(config.opt("recordingConsent")) && Boolean.FALSE.equals(config.opt("cloudConsent"));
    }
    private static boolean runMiniMax(final Object connector,Context context,JSONObject config) {
        final boolean diagnostic = "diagnostic".equals(config.optString("mode"));
        if (!(diagnostic ? validDiagnostic(config) : validMiniMax(config)) || !BUSY.compareAndSet(false,true)) return true;
        final String nativeId;
        try{nativeId=OwnerHeadless.nativeSessionId(connector);}catch(Exception stale){BUSY.set(false);return true;}
        final long parentStartedAt=System.currentTimeMillis();
        ConversationSession.Result result = new ConversationSession.Result();
        OwnerNativeConversation ports=null;
        String parentDecision=diagnostic?"ALLOW":ParentUseGuard.start(context,nativeId);
        if(!diagnostic&&"ALLOW".equals(parentDecision))ParentConversationSync.start(context,nativeId);
        try {
            if(!"ALLOW".equals(parentDecision))result.code="PARENT_"+parentDecision;
            else{
                ports = new OwnerNativeConversation(connector, config,nativeId);
                result = new ConversationSession(ports, diagnostic ? 2 : ConversationSession.DEFAULT_TURNS, diagnostic ? 150000L : ConversationSession.DEFAULT_MS).run();
            }
        } catch(Exception e) {
            result.code = aborted(connector) ? "TURN_CANCELLED" : "MINIMAX_INITIALIZATION_FAILED";
        } finally {
            if(!diagnostic)ParentUseGuard.end(nativeId,result.code);
            if(!diagnostic&&"ALLOW".equals(parentDecision))ParentConversationSync.end(context,nativeId,result.code,System.currentTimeMillis()-parentStartedAt);
            try {
                int[] actual=ports==null?new int[]{0,0,0}:ports.actualApiCounts();
                OwnerHeadless.completed(connector,nativeId,result,actual);
                JSONObject receipt = new JSONObject().put("sessionId",nativeId).put("mode", diagnostic ? "diagnostic" : "minimax").put("result", result.code)
                    .put("diagnosticOnly", diagnostic).put("simulatedCloudStages", diagnostic ? result.asrCalls + result.llmCalls + result.ttsCalls : 0)
                    .put("completedTurns", result.completedTurns).put("interruptedTurns",result.interruptedTurns).put("autonomousTurns",result.autonomousTurns).put("autonomousInterruptedTurns",result.autonomousInterruptedTurns).put("asrRecoveries",result.asrRecoveries).put("transportRecoveries",result.transportRecoveries).put("replyRecoveries",result.replyRecoveries).put("resultBeforeClose",result.resultBeforeClose).put("closeError",result.closeError).put("lastTurnError",result.lastTurnError).put("continuousInput",!diagnostic).put("capturedBytes", result.capturedBytes)
                    .put("outputBytes", result.outputBytes).put("playbackDrained", result.playbackDrained)
                    .put("asrCalls", actual[0]).put("llmCalls", actual[1]).put("ttsCalls", actual[2])
                    .put("audioSaved", (diagnostic && config.optBoolean("saveDiagnosticAudio", false))||(ports!=null&&ports.traceAudioSaved())).put("transcriptSaved", ports!=null&&ports.traceTranscriptSaved()).put("wakeWordImplemented", false).put("stockVoiceWakeRouted",true).put("wakeStrategy","original-dsp-and-button")
                    .put("endpointing", "energy-tonal-reject-20ms-"+(diagnostic?SpeechWindow.SILENCE_MS:ContinuousSpeechInput.UTTERANCE_SILENCE_MS)+"ms-tail")
                    .put("endpointSilenceMs",diagnostic?SpeechWindow.SILENCE_MS:ContinuousSpeechInput.UTTERANCE_SILENCE_MS)
                    .put("outputBytesScope","completed-drained-user-and-autonomous-turns").put("atMs", System.currentTimeMillis());
                FileOutputStream out = context.openFileOutput("sp001-owner-voice-result.json", Context.MODE_PRIVATE);
                try { out.write((receipt.toString()+"\n").getBytes("UTF-8")); } finally { out.close(); }
                if(!diagnostic)retainEndReason(context,receipt);
            } catch(Exception ignored) {}
            releaseNativeAdmission(ports==null?null:ports.captureOwner());
        }
        return true;
    }
    static void releaseNativeAdmission(OwnerDuplexCapture capture){if(capture==null||capture.awaitRelease())BUSY.set(false);}
    static boolean resourcesReleased(){return !BUSY.get();}
    static boolean validCloud(JSONObject config) {
        if (!Boolean.TRUE.equals(config.opt("cloudConsent"))) return false;
        String workspace = config.optString("workspace", ""), key = config.optString("apiKey", "");
        return workspace.matches("[A-Za-z0-9][A-Za-z0-9-]{0,62}") && key.matches("[A-Za-z0-9._-]{8,1024}");
    }
    private static boolean hasSignal(byte[] pcm) {
        long squares = 0;
        for (int i = 0; i < pcm.length; i += 2) { int v = (short)((pcm[i]&255)|((pcm[i+1]&255)<<8)); squares += (long)v*v; }
        return squares / Math.max(1, pcm.length / 2) > 1024;
    }
    private static byte[] cloud(byte[] pcm, JSONObject config, final Object connector) throws Exception {
        // Official Qwen-Omni docs, retrieved2026-09-15. Streaming data is PCM16LE24k mono,
        // despite the request's audio.format="wav". No TLS/hostname verifier overrides.
        URL url = new URL("https://" + config.getString("workspace") + ".cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions");
        final HttpsURLConnection connection = (HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);
        final AtomicBoolean expired = new AtomicBoolean();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        final long deadline = SystemClock.elapsedRealtime() + 60000;
        watchdog.scheduleAtFixedRate(new Runnable() { public void run() {
            if (aborted(connector) || SystemClock.elapsedRealtime() >= deadline) { expired.set(true); connection.disconnect(); }
        }}, 100, 100, TimeUnit.MILLISECONDS);
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + config.getString("apiKey"));
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "text/event-stream");
            String encoded = Base64.encodeToString(OwnerCaptureTap.wav(pcm,16000), Base64.NO_WRAP);
            JSONObject audioInput = new JSONObject().put("type","input_audio").put("input_audio",new JSONObject().put("data","data:audio/wav;base64,"+encoded).put("format","wav"));
            JSONObject prompt = new JSONObject().put("type","text").put("text","请用自然中文回答刚才说的话，控制在两句话以内。你是友善的蜘蛛侠伙伴。");
            JSONArray messages = new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(audioInput).put(prompt)));
            JSONObject request = new JSONObject().put("model","qwen3.5-omni-plus").put("messages",messages)
                    .put("modalities",new JSONArray().put("text").put("audio")).put("audio",new JSONObject().put("voice","Tina").put("format","wav"))
                    .put("stream",true).put("stream_options",new JSONObject().put("include_usage",true));
            byte[] body = request.toString().getBytes("UTF-8"); connection.setFixedLengthStreamingMode(body.length);
            try { OutputStream out = connection.getOutputStream(); try { out.write(body); } finally { out.close(); } }
            finally { Arrays.fill(body,(byte)0); }
            if (connection.getResponseCode() != 200) throw new IOException("CLOUD_HTTP_FAILED");
            String type = connection.getContentType(); if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) throw new IOException("CLOUD_FORMAT_FAILED");
            InputStream source = connection.getInputStream();
            InputStream limited = new FilterInputStream(source) { int count;
                @Override public int read() throws IOException { int v = super.read(); if(v>=0 && ++count>4194304)throw new IOException("STREAM_BOUND");return v; }
                @Override public int read(byte[] b,int off,int len)throws IOException { int n=in.read(b,off,len);if(n>0 && (count+=n)>4194304)throw new IOException("STREAM_BOUND");return n; }
            };
            BufferedReader reader = new BufferedReader(new InputStreamReader(limited,"UTF-8"));
            StringBuilder encodedAudio = new StringBuilder(); boolean stopped = false; String line;
            try {
                while ((line=reader.readLine())!=null) {
                    check(connector); if(expired.get())throw new IOException("CLOUD_DEADLINE");
                    if(!line.startsWith("data:"))continue;
                    String data=line.substring(5).trim();if(data.equals("[DONE]"))break;
                    JSONObject event=new JSONObject(data);if(event.has("error"))throw new IOException("PROVIDER_ERROR");
                    JSONArray choices=event.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                    JSONObject choice=choices.getJSONObject(0);
                    Object reason=choice.opt("finish_reason");
                    if(reason!=null&&reason!=JSONObject.NULL){if(!"stop".equals(reason))throw new IOException("INCOMPLETE_REPLY");stopped=true;}
                    JSONObject delta=choice.optJSONObject("delta");JSONObject a=delta==null?null:delta.optJSONObject("audio");
                    if(a!=null&&a.has("data")){
                        Object fragment=a.opt("data");if(!(fragment instanceof String))throw new IOException("AUDIO_FIELD_TYPE");
                        if(encodedAudio.length()+((String)fragment).length()>3840000)throw new IOException("AUDIO_BOUND");
                        encodedAudio.append((String)fragment);
                    }
                }
            } finally { reader.close(); }
            check(connector);if(expired.get()||!stopped||encodedAudio.length()==0)throw new IOException("INCOMPLETE_AUDIO");
            byte[] result=Base64.decode(encodedAudio.toString(),Base64.DEFAULT);
            if(result.length==0||result.length>2880000||result.length%2!=0)throw new IOException("AUDIO_PCM_BOUND");
            if(result.length>=4&&result[0]=='R'&&result[1]=='I'&&result[2]=='F'&&result[3]=='F')throw new IOException("UNEXPECTED_WAV_CONTAINER");
            return result;
        } finally { connection.disconnect(); watchdog.shutdownNow(); }
    }
    static void play(byte[] pcm,int rate,Object connector)throws Exception {
        play(pcm,rate,connector,Long.MAX_VALUE);
    }
    static void play(byte[] pcm,int rate,Object connector,long sessionDeadline)throws Exception {
        if(pcm==null||pcm.length==0||pcm.length%2!=0||pcm.length>2880000)throw new IOException("OUTPUT_BOUND");
        AudioTrack track=new AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,pcm.length,AudioTrack.MODE_STATIC);
        OwnerPlaybackEffects effects=null;
        try {
            if(track.getState()==AudioTrack.STATE_UNINITIALIZED)throw new IOException("OUTPUT_INIT");
            effects=new OwnerPlaybackEffects(track.getAudioSessionId());
            try {
                JSONObject quality=effects.status(rate).put("bytes",pcm.length).put("atMs",System.currentTimeMillis());
                FileOutputStream q=nativeContext().openFileOutput("sp001-playback-quality.json",Context.MODE_PRIVATE);
                try{q.write(quality.toString().getBytes("UTF-8"));}finally{q.close();}
            }catch(Exception ignored){}
            track.setVolume(1.0f);
            if(track.write(pcm,0,pcm.length)!=pcm.length)throw new IOException("OUTPUT_WRITE");
            check(connector);track.play();long deadline=SystemClock.elapsedRealtime()+pcm.length*500L/rate+3000;
            while((track.getPlaybackHeadPosition()&0xffffffffL)<pcm.length/2){check(connector);if(SystemClock.elapsedRealtime()>=sessionDeadline)throw new IOException("SESSION_TIME_LIMIT");if(SystemClock.elapsedRealtime()>deadline)throw new IOException("OUTPUT_DEADLINE");Thread.sleep(20);}
        } finally { try { if(track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING)track.stop(); }
            finally{if(effects!=null)effects.close();track.release();} }
    }
}
