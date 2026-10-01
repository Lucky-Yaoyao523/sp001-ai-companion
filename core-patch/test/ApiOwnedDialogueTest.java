package org.sp001.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Production entry and real protocol with explicitly synthetic transport. No network or audio. */
public final class ApiOwnedDialogueTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    static JSONObject config() throws Exception {
        return TtsSocketAdapterTest.config().put("enabled", true)
                .put("cloudConsent", true).put("chatModel", "MiniMax-M3");
    }
    static JSONArray history(String user) throws Exception {
        return new JSONArray().put(new JSONObject().put("role", "system").put("content", "test"))
                .put(new JSONObject().put("role", "user").put("content", user));
    }
    static String record(String speech) throws Exception {
        return new JSONObject().put("continue_listening",true).put("speech", speech).put("emotion", "neutral")
                .put("actions", new JSONArray()).put("end", false).toString();
    }
    static JSONObject message(String speech) throws Exception {
        return new JSONObject().put("type", "message").put("role", "assistant")
                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text")
                        .put("text", record(speech))));
    }
    static JSONObject response(JSONObject... output) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject item : output) items.put(item);
        return new JSONObject().put("status", "completed").put("error", JSONObject.NULL)
                .put("output", items);
    }
    static String date(int offset) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.add(Calendar.DAY_OF_MONTH, offset);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(c.getTimeZone());
        return f.format(c.getTime());
    }
    static JSONObject tool(String id, String city, String date) throws Exception {
        return new JSONObject().put("type", "function_call").put("name", "get_weather")
                .put("call_id", id).put("arguments", new JSONObject().put("city", city)
                        .put("date", date).toString());
    }
    static JSONObject forecast(String city, String date) throws Exception {
        return new JSONObject().put("kind", "verified_weather").put("city", city)
                .put("timezone", "Asia/Shanghai")
                .put("current", new JSONObject().put("weatherCode", 1).put("temperatureC", 21).put("feelsLikeC", 22))
                .put("forecast", new JSONObject().put("date", date).put("weatherCode", 2)
                        .put("lowC", 17).put("highC", 26).put("rainProbabilityPercent", 30));
    }
    static final class Wire implements MiniMaxVoiceClient.ConnectionFactory {
        final JSONObject[] responses;
        final List<DuplexIntegrationTest.Connection> sent = new ArrayList<DuplexIntegrationTest.Connection>();
        int calls;
        Wire(JSONObject... replies) { responses = replies; }
        public javax.net.ssl.HttpsURLConnection open(java.net.URL url) throws IOException {
            check("/v1/responses".equals(url.getPath()), "production must not silently use old completions");
            if (calls >= responses.length) throw new IOException("UNEXPECTED_EXTRA_MODEL_REQUEST");
            try {
                DuplexIntegrationTest.Connection c = new DuplexIntegrationTest.Connection();
                c.contentType = "text/event-stream";
                c.body = "data: " + new JSONObject().put("type", "response.completed")
                        .put("response", responses[calls++]).toString() + "\n\n";
                sent.add(c);
                return c;
            } catch (Exception e) { throw new IOException(e); }
        }
        JSONObject request(int index) throws Exception {
            return new JSONObject(new String(sent.get(index).uploaded.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static final class Out extends NativeDialogueEngine.Output {
        final StringBuilder text = new StringBuilder();
        final List<ReplySegment> segments = new ArrayList<ReplySegment>();
        public void speak(ReplySegment segment) { text.append(segment.speech); segments.add(segment); }
        public void status(JSONObject status) { }
    }
    public static void verifyCitySequence() throws Exception {
        String[] users = {"北京明天天气怎么样？", "那后天呢？", "那上海呢？", "我们这里今天呢？"};
        String[] cities = {"北京", "北京", "上海", "南京"};
        String[] days = {date(1), date(2), date(2), date(0)};
        JSONObject[] replies = new JSONObject[8];
        for (int i = 0; i < 4; i++) {
            replies[i * 2] = response(tool("city_" + i, cities[i], days[i]));
            replies[i * 2 + 1] = response(message(cities[i]+"最低17度，最高26度，降雨概率30%。"));
        }
        Wire wire = new Wire(replies);
        final int[] fetches = {0};
        NativeDialogueEngine engine = new NativeDialogueEngine(config(), wire, (city, day, now, cancel) -> {
            int at = fetches[0]++;
            check(city.equals(cities[at]) && day.equals(days[at]), "execute only actual model city/date arguments");
            return forecast(city, day);
        });
        JSONArray h = history(users[0]);
        for (int i = 0; i < 4; i++) {
            if (i > 0) h.put(new JSONObject().put("role", "user").put("content", users[i]));
            Out out = new Out();
            engine.reply(h, "", false, out, () -> false);
            check(out.text.toString().contains(cities[i]), "real selected city in response");
            check(engine.status().getJSONArray("evidence").getJSONObject(0).getJSONObject("arguments")
                    .getString("date").equals(days[i]), "matched date receipt");
            JSONObject first = wire.request(i * 2);
            check("auto".equals(first.getString("tool_choice")), "model makes each decision");
            JSONArray input = first.getJSONArray("input");
            check(users[i].equals(input.getJSONObject(input.length() - 1).getString("content")), "latest user unchanged");
            engine.commit();
            h.put(new JSONObject().put("role", "assistant").put("content", out.text.toString()));
        }
        check(fetches[0] == 4, "one fetch per genuine call, no manufactured keyword calls");
        engine.clear();
    }
    public static void main(String[] args) throws Exception {
        verifyCitySequence();
        JSONObject finalMessage = message("资料中列出了米奇大街和梦幻世界。");
        finalMessage.getJSONArray("content").getJSONObject(0).put("annotations", new JSONArray()
                .put(new JSONObject().put("type", "url_citation").put("title", "Synthetic official source")
                        .put("url", "https://www.shanghaidisneyresort.com/zh-cn/")
                        .put("content", "测试资料：米奇大街和梦幻世界。")));
        JSONObject searched = response(new JSONObject().put("type", "web_search_call").put("status", "completed")
                .put("action", new JSONObject().put("query", "上海迪士尼 官方园区")), finalMessage);
        Wire searchWire = new Wire(searched);
        NativeDialogueEngine search = new NativeDialogueEngine(config(), searchWire, (c, d, n, t) -> {
            throw new AssertionError("no weather dispatch for server search");
        });
        Out out = new Out();
        search.reply(history("上海迪士尼里面有什么？"), "", false, out, () -> false);
        check(searchWire.calls == 1, "server search final answer must not require second model request");
        check(out.text.toString().contains("梦幻世界") && search.status().getInt("sourceCount") == 1, "answer and real source retained");
        search.clear();

        JSONObject invitation = message("苹果的英语是 apple。");
        invitation.getJSONArray("content").getJSONObject(0).put("text",
                new JSONObject(record("苹果的英语是 apple。" )).put("follow_up", "我们再来做一道题。").toString());
        Wire settings = new Wire(invitation, response(message("十五。")));
        // Wrap the first item as a Responses response, rather than a fabricated chat completion.
        settings.responses[0] = response(invitation);
        MiniMaxVoiceClient client = new MiniMaxVoiceClient(config(), () -> false, null, null, settings);
        client.companionInitiative(false);
        client.beginTurn(() -> false, r -> { r.run(); return true; });
        List<ReplySegment> spoken = new ArrayList<ReplySegment>();
        client.replySegments("苹果英语怎么说？", s -> { spoken.add(s); return s.speech; });
        check(settings.request(0).getString("instructions").contains("主动接话设置已关闭"), "settings reach actual new model payload");
        check(spoken.get(spoken.size() - 1).followUp.isEmpty(), "disabled followup cannot be queued from model field");
        client.commitReply(); client.finishTurn();
        client.beginTurn(() -> false, r -> { r.run(); return true; });
        check(client.reply("七加八是多少？").contains("十五"), "buffered entry uses the same native engine");
        client.finishTurn(); client.close();

        AtomicBoolean cancel = new AtomicBoolean();
        Wire afterTool = new Wire(response(tool("cancel", "北京", date(1))));
        NativeDialogueEngine stopped = new NativeDialogueEngine(config(), afterTool, (c, d, n, t) -> {
            cancel.set(true); return forecast(c, d);
        });
        Out noLate = new Out();
        try { stopped.reply(history("北京天气"), "", false, noLate, cancel::get); throw new AssertionError("cancel ignored"); }
        catch (IOException expected) { check(!noLate.text.toString().contains("26"), "cancel wins over verified-data fallback"); }
        stopped.clear();
        System.out.println("API_OWNED_DIALOGUE " + checks + " checks passed; synthetic transport, production entry, no network/device");
    }
}
