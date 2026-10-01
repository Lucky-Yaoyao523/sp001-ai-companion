package org.sp001.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded process-local continuity across ordinary voice session boundaries. */
final class RecentConversation {
    static final long MAX_IDLE_MS = 15 * 60 * 1000L;
    static final int MAX_PAIRS = 6;
    static final int MAX_CHARS = 8000;
    private static String profile = "";
    private static long savedAt;
    private static JSONArray turns = new JSONArray();

    private RecentConversation() {}

    static synchronized void clear() {
        profile = "";
        savedAt = 0;
        turns = new JSONArray();
    }

    static synchronized JSONArray resume(String selectedProfile, long now) throws Exception {
        if (selectedProfile == null || selectedProfile.isEmpty() || now < savedAt || now - savedAt > MAX_IDLE_MS || !selectedProfile.equals(profile)) {
            clear();
            return new JSONArray();
        }
        return new JSONArray(turns.toString());
    }

    static synchronized void record(String selectedProfile, JSONArray history, long now) throws Exception {
        if (selectedProfile == null || selectedProfile.isEmpty() || history == null || now <= 0) return;
        JSONArray newest = new JSONArray();
        int characters = 0;
        int pairs = 0;
        for (int i = history.length() - 1; i > 1 && pairs < MAX_PAIRS; i -= 2) {
            JSONObject answer = history.optJSONObject(i);
            JSONObject question = history.optJSONObject(i - 1);
            if (answer == null || question == null || !"assistant".equals(answer.optString("role")) || !"user".equals(question.optString("role"))) break;
            String user = question.optString("content");
            String assistant = answer.optString("content");
            if (user.isEmpty() || assistant.isEmpty() || user.length() > 1000 || assistant.length() > 6000) break;
            if (characters + user.length() + assistant.length() > MAX_CHARS) break;
            newest.put(new JSONObject().put("role", "user").put("content", user));
            newest.put(new JSONObject().put("role", "assistant").put("content", assistant));
            characters += user.length() + assistant.length();
            pairs++;
        }
        JSONArray ordered = new JSONArray();
        for (int i = newest.length() - 2; i >= 0; i -= 2) {
            ordered.put(newest.getJSONObject(i));
            ordered.put(newest.getJSONObject(i + 1));
        }
        profile = selectedProfile;
        savedAt = now;
        turns = ordered;
    }
}
