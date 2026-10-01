package org.sp001.core;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production-entry contract tests. All provider replies are synthetic; no cloud or hardware I/O. */
public final class ApiFirstDialogueAcceptanceTest {
    private static int checks;
    private static final JSONArray cases = new JSONArray();
    private interface Work { void run() throws Exception; }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void test(String name, Work work) throws Exception {
        JSONObject row = new JSONObject().put("case", name);
        try { work.run(); row.put("passed", true); }
        catch (Throwable failure) {
            row.put("passed", false).put("failureType", failure.getClass().getSimpleName())
                .put("message", String.valueOf(failure.getMessage()));
        }
        cases.put(row);
    }
    private static JSONObject config() throws Exception {
        return TtsSocketAdapterTest.config().put("enabled", true)
            .put("cloudConsent", true).put("chatModel", "MiniMax-M3");
    }
    private static JSONObject spoken(String text) throws Exception {
        return new JSONObject().put("continue_listening",true).put("speech", text).put("emotion", "neutral")
            .put("actions", new JSONArray()).put("end", false);
    }
    private static JSONObject message(JSONObject text) throws Exception {
        return new JSONObject().put("type", "message").put("role", "assistant")
            .put("status", "completed").put("content", new JSONArray().put(
                new JSONObject().put("type", "output_text").put("text", text.toString())
                    .put("annotations", new JSONArray())));
    }
    private static JSONObject response(JSONObject... items) throws Exception {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return new JSONObject().put("status", "completed").put("error", JSONObject.NULL)
            .put("output", out);
    }
    private static JSONObject searched(boolean withSources, JSONObject answer) throws Exception {
        JSONObject call = new JSONObject().put("type", "web_search_call").put("id", "search_fixture")
            .put("status", "completed").put("action", new JSONObject().put("query", "public museum exhibit"));
        JSONObject msg = message(answer);
        if (withSources) msg.getJSONArray("content").getJSONObject(0).getJSONArray("annotations").put(
            new JSONObject().put("type", "url_citation").put("title", "Synthetic source fixture")
                .put("url", "https://example.org/exhibit").put("content", "Synthetic evidence: the test exhibit is a model boat."));
        return response(message(spoken("尚未查证的前置草稿，不应播出。")), call, msg);
    }
    private static final class Wire implements MiniMaxVoiceClient.ConnectionFactory {
        final JSONObject[] responses;
        final List<DuplexIntegrationTest.Connection> sent = new ArrayList<DuplexIntegrationTest.Connection>();
        int requests;boolean searchProgress;
        Wire(JSONObject... values) { responses = values; }
        public HttpsURLConnection open(URL url) throws IOException {
            try {
                require("/v1/responses".equals(url.getPath()), "production must use the native endpoint");
                if (requests >= responses.length) throw new IOException("UNEXPECTED_EXTRA_MODEL_REQUEST");
                DuplexIntegrationTest.Connection c = new DuplexIntegrationTest.Connection();
                c.contentType = "text/event-stream";
                c.body = "data: " + new JSONObject().put("type", "response.completed")
                    .put("response", responses[requests++]) + "\n\n";
                if(searchProgress)c.body="data: "+new JSONObject().put("type","response.web_search_call.in_progress")+"\n\n"+c.body;
                sent.add(c);
                return c;
            } catch (IOException failure) { throw failure; }
            catch (Exception failure) { throw new IOException(failure); }
        }
        JSONObject request(int index) throws Exception {
            return new JSONObject(new String(sent.get(index).uploaded.toByteArray(), "UTF-8"));
        }
    }
    private static List<ReplySegment> turn(MiniMaxVoiceClient client, String input) throws Exception {
        final List<ReplySegment> segments = new ArrayList<ReplySegment>();
        client.beginTurn(() -> false, work -> { work.run(); return true; });
        try {
            client.replySegments(input, segment -> { segments.add(segment); return segment.speech; });
            client.commitReply();
            return segments;
        } finally { client.finishTurn(); }
    }
    private static String text(List<ReplySegment> segments) {
        StringBuilder result = new StringBuilder();
        for (ReplySegment segment : segments) result.append(segment.speech);
        return result.toString();
    }
    public static void main(String[] args) throws Exception {
        test("server search uses its same-request final answer", () -> {
            Wire wire = new Wire(searched(true, spoken("展品是一艘模型船。")));
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            try {
                String value = text(turn(client, "帮我查一查展馆里有什么。"));
                require(wire.requests == 1 && client.llmCalls == 1, "search must not force a second summary");
                require(value.contains("模型船") && !value.contains("前置草稿"), "only the final supported reply is delivered");
                require(!value.contains("我查一下"), "completed-only response must not produce a retrospective search promise");
                require(client.knowledgeStatus().optInt("sourceCount") == 1, "retain actual source receipt");
                require(!wire.request(0).getBoolean("store"), "do not persist conversation on provider");
            } finally { client.close(); }
        });
        test("translation correction and sharing preserve latest input", () -> {
            String[] inputs = {"天气这个词的英语是什么？", "不是天气，我问的是苹果！", "不学英语了，我今天自己穿好鞋了。", "别再问了，七加八是多少？"};
            String[] replies = {"天气的英语是 weather。", "苹果的英语是 apple。", "你愿意自己试着穿鞋，这个尝试值得肯定。", "七加八等于十五。"};
            JSONObject[] responses = new JSONObject[replies.length];
            for (int i = 0; i < replies.length; i++) responses[i] = response(message(spoken(replies[i])));
            Wire wire = new Wire(responses);
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            try {
                String tools = null;
                for (int i = 0; i < inputs.length; i++) {
                    require(text(turn(client, inputs[i])).equals(replies[i]), "do not replace provider reply with a local lesson");
                    JSONObject request = wire.request(i); JSONArray input = request.getJSONArray("input");
                    require(input.getJSONObject(input.length() - 1).getString("content").equals(inputs[i]), "unaltered latest child utterance");
                    String available = request.getJSONArray("tools").toString();
                    if (tools == null) tools = available;
                    require(tools.equals(available) && "auto".equals(request.getString("tool_choice")), "every intent gets the same model-owned tools");
                }
                require(wire.requests == 4 && client.llmCalls == 4, "no extra pre-classifier requests");
            } finally { client.close(); }
        });
        test("initiative-off reaches both request and returned follow-up", () -> {
            JSONObject a = spoken("七加八等于十五。").put("follow_up", "再猜一个题目好吗？");
            Wire wire = new Wire(response(message(a)));
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            try {
                client.companionInitiative(false);
                List<ReplySegment> segments = turn(client, "七加八是多少？");
                for (ReplySegment segment : segments) require(segment.followUp.isEmpty(), "disabled follow-up must not survive model output");
                require(wire.request(0).getString("instructions").contains("主动接话设置已关闭"), "model receives actual setting snapshot");
            } finally { client.close(); }
        });
        test("search material cannot introduce unsolicited actions or memory", () -> {
            JSONObject a = spoken("展品是一艘模型船。").put("actions", new JSONArray().put("volume_up"))
                .put("memory", new JSONObject().put("kind", "preference").put("quote", "我喜欢模型船"));
            Wire wire = new Wire(searched(true, a));
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            try {
                for (ReplySegment segment : turn(client, "帮我查询展品。")) {
                    require(segment.actions.isEmpty(), "search evidence cannot change local volume");
                    require(segment.memoryQuote.isEmpty(), "web contents cannot become the child's preference");
                }
                require(wire.requests == 1, "guarding an effect must not add another classifier");
            } finally { client.close(); }
        });
        test("search with no sources never plays invented answer", () -> {
            Wire wire = new Wire(searched(false, spoken("新的秘密展馆已经开放了。")));
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            try {
                String value = text(turn(client, "帮我查一下展馆是否开放。"));
                require(!value.contains("秘密展馆已经开放"), "ungrounded generated facts must not be delivered");
                require("ANSWER_FAILED".equals(client.knowledgeStatus().optString("state")), "retain explicit failure state");
            } finally { client.close(); }
        });
        test("cancel after first searched answer stops further speech", () -> {
            AtomicBoolean stop = new AtomicBoolean();
            Wire wire = new Wire(searched(true, spoken("被取消的答案不能继续播放。")));
            wire.searchProgress=true;
            MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, wire);
            List<ReplySegment> said = new ArrayList<ReplySegment>();
            client.beginTurn(stop::get, work -> { if (stop.get()) return false; work.run(); return true; });
            try {
                try {
                    client.replySegments("帮我查一下资料。", segment -> { said.add(segment); stop.set(true); return segment.speech; });
                    throw new AssertionError("cancellation must exit the turn");
                } catch (IOException expected) {
                    require(said.size()==1&&text(said).contains("被取消的答案"), "only the already delivered answer may play before cancellation");
                    require(wire.requests == 1, "no retry or second model after cancellation");
                }
            } finally { client.finishTurn(); client.close(); }
        });
        boolean passed = true;
        for (int i = 0; i < cases.length(); i++) passed &= cases.getJSONObject(i).getBoolean("passed");
        System.out.println(new JSONObject().put("checks", checks).put("cases", cases)
            .put("passed", passed).put("deviceIo", false).put("apiCalls", 0)
            .put("scope", "actual production entry with synthetic provider transport, not live semantic quality"));
        if (!passed) System.exit(1);
    }
}
