package org.sp001.parent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Offline, API 22 compatible policy and metadata ledger. No microphone or network access. */
public final class LocalGuard {
    private static final int MAGIC = 0x53503031;
    private static final int VERSION = 1;
    private static final int MAX_EVENTS = 2048;
    private static final int MAX_EVENTS_PER_DAY = 256;
    private static final int MAX_DAYS = 35;
    private static final long MAX_SESSION_MS = 12L * 60L * 60L * 1000L;
    private static final TimeZone HONG_KONG = TimeZone.getTimeZone("Asia/Hong_Kong");
    private final File file;
    private final LinkedHashMap<Integer, Usage> days = new LinkedHashMap<Integer, Usage>();
    private final ArrayList<Event> events = new ArrayList<Event>();
    private Settings settings = Settings.defaults();
    private long lastCommandSeq;
    private boolean active;
    private String activeSessionId = "";
    private long activeWallMs;
    private long activeElapsedMs;
    private boolean needsRecovery;

    public LocalGuard(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file");
        this.file = file;
        if (file.exists()) read(file);
        else {
            File backup = new File(file.getPath() + ".bak");
            if (backup.exists()) read(backup);
        }
    }

    public static final class Settings {
        public final int dailyMinutes;
        public final int dailySessions;
        public final int quietStartMinute;
        public final int quietEndMinute;
        public final boolean paused;

        public Settings(int dailyMinutes, int dailySessions, int quietStartMinute, int quietEndMinute, boolean paused) {
            if (dailyMinutes < 1 || dailyMinutes > 240 || dailySessions < 1 || dailySessions > 30
                    || quietStartMinute < 0 || quietStartMinute >= 1440 || quietEndMinute < 0
                    || quietEndMinute >= 1440 || quietStartMinute == quietEndMinute) {
                throw new IllegalArgumentException("invalid limits");
            }
            this.dailyMinutes = dailyMinutes;
            this.dailySessions = dailySessions;
            this.quietStartMinute = quietStartMinute;
            this.quietEndMinute = quietEndMinute;
            this.paused = paused;
        }

        public static Settings defaults() { return new Settings(45, 4, 20 * 60 + 30, 7 * 60, false); }
        public Settings withPaused(boolean value) { return new Settings(dailyMinutes, dailySessions, quietStartMinute, quietEndMinute, value); }
    }

    public static final class Usage {
        public int sessions;
        public long durationMs;
        Usage copy() { Usage u = new Usage(); u.sessions = sessions; u.durationMs = durationMs; return u; }
    }

    public static final class Event {
        public final long atMs;
        public final String kind;
        public final String sessionId;
        public final String turnId;
        public final String toolStatus;
        public final String reason;
        Event(long atMs, String kind, String sessionId, String turnId, String toolStatus, String reason) {
            this.atMs = atMs; this.kind = kind; this.sessionId = sessionId; this.turnId = turnId;
            this.toolStatus = toolStatus; this.reason = reason;
        }
    }

    public static final class Decision {
        public final boolean allowNewSession;
        public final String reason;
        public final int sessionsToday;
        public final long usedMsToday;
        Decision(boolean allowNewSession, String reason, int sessionsToday, long usedMsToday) {
            this.allowNewSession = allowNewSession; this.reason = reason;
            this.sessionsToday = sessionsToday; this.usedMsToday = usedMsToday;
        }
    }

    public synchronized Settings settings() { return settings; }
    public synchronized long lastCommandSeq() { return lastCommandSeq; }
    public synchronized List<Event> events() { return new ArrayList<Event>(events); }
    public synchronized Usage usageOn(long wallMs) {
        Usage existing = days.get(dayKey(wallMs));
        return existing == null ? new Usage() : existing.copy();
    }

    public synchronized Decision canStart(long wallMs, long elapsedMs) throws IOException {
        recoverIfNeeded(wallMs);
        int day = dayKey(wallMs);
        Usage usage = days.get(day);
        int sessions = usage == null ? 0 : usage.sessions;
        long used = usage == null ? 0 : usage.durationMs;
        if (active) used += activeDurationOnDay(day, elapsedMs);
        String reason = "ALLOW";
        if (active) reason = "SESSION_ACTIVE";
        else if (settings.paused) reason = "PAUSED";
        else if (isQuiet(wallMs)) reason = "QUIET_HOURS";
        else if (sessions >= settings.dailySessions) reason = "SESSION_LIMIT";
        else if (used >= settings.dailyMinutes * 60000L) reason = "TIME_LIMIT";
        return new Decision("ALLOW".equals(reason), reason, sessions, used);
    }

    /** Check before taking the next utterance; finish the current spoken answer first. */
    public synchronized Decision canContinue(long wallMs, long elapsedMs) throws IOException {
        recoverIfNeeded(wallMs);
        Usage usage = days.get(dayKey(wallMs));
        int sessions = usage == null ? 0 : usage.sessions;
        long used = (usage == null ? 0 : usage.durationMs) + (active ? activeDurationOnDay(dayKey(wallMs), elapsedMs) : 0);
        String reason = !active ? "NO_SESSION" : settings.paused ? "PAUSED" : isQuiet(wallMs) ? "QUIET_HOURS"
                : used >= settings.dailyMinutes * 60000L ? "TIME_LIMIT" : "ALLOW";
        return new Decision("ALLOW".equals(reason), reason, sessions, used);
    }

    public synchronized Decision startSession(String sessionId, long wallMs, long elapsedMs) throws IOException {
        Decision decision = canStart(wallMs, elapsedMs);
        if (!decision.allowNewSession) return decision;
        if (!validCode(sessionId, 64)) throw new IllegalArgumentException("sessionId");
        int day = dayKey(wallMs);
        Usage usage = getOrCreate(day);
        usage.sessions++;
        active = true; activeSessionId = sessionId; activeWallMs = wallMs; activeElapsedMs = elapsedMs;
        append(new Event(wallMs, "SESSION_START", sessionId, "", "", ""));
        try { save(); } catch (IOException ex) {
            usage.sessions--; active = false; activeSessionId = ""; events.remove(events.size() - 1); throw ex;
        }
        return new Decision(true, "ALLOW", usage.sessions, usage.durationMs);
    }

    /** End after the currently playing answer. Elapsed time comes from Android elapsedRealtime. */
    public synchronized void endSession(String sessionId, long wallMs, long elapsedMs, String reason) throws IOException {
        if (!active || !activeSessionId.equals(sessionId)) throw new IllegalStateException("no matching session");
        long duration = boundedDuration(elapsedMs - activeElapsedMs);
        addDuration(activeWallMs, duration);
        append(new Event(wallMs, "SESSION_END", activeSessionId, "", "", cleanCode(reason, 60)));
        active = false; activeSessionId = "";
        save();
    }

    /** A failed metadata write never interrupts conversation playback. No literal text or audio is accepted. */
    public synchronized boolean recordEvent(long atMs, String kind, String sessionId, String turnId, String toolStatus, String reason) {
        if (!validKind(kind) || !validCode(sessionId, 64) || !optionalCode(turnId, 64) || !optionalCode(reason, 60)) return false;
        if ("TOOL_RESULT".equals(kind) && !("SUCCESS".equals(toolStatus) || "FAILED".equals(toolStatus) || "UNKNOWN".equals(toolStatus))) return false;
        append(new Event(atMs, kind, sessionId, cleanCode(turnId, 64), cleanCode(toolStatus, 16), cleanCode(reason, 60)));
        try { save(); return true; } catch (IOException ignored) { return false; }
    }

    /** Caller must authenticate the toy command transport before invoking this method. */
    public synchronized String applyCommand(long seq, long expiresAtMs, long nowWallMs, String type, Settings requested) throws IOException {
        recoverIfNeeded(nowWallMs);
        if (seq <= lastCommandSeq) return "DUPLICATE";
        if (expiresAtMs <= nowWallMs || expiresAtMs - nowWallMs > 24L * 60L * 60L * 1000L) return "EXPIRED";
        Settings next;
        if ("SET_LIMITS".equals(type)) {
            if (requested == null) return "INVALID";
            next = requested;
        } else if ("PAUSE".equals(type)) next = settings.withPaused(true);
        else if ("RESUME".equals(type)) next = settings.withPaused(false);
        else return "INVALID";
        Settings previous = settings; long previousSeq = lastCommandSeq;
        settings = next; lastCommandSeq = seq;
        try { save(); } catch (IOException ex) { settings = previous; lastCommandSeq = previousSeq; throw ex; }
        return "APPLIED";
    }

    private void recoverIfNeeded(long nowWallMs) throws IOException {
        if (!needsRecovery) return;
        long estimated = nowWallMs >= activeWallMs ? Math.min(nowWallMs - activeWallMs, MAX_SESSION_MS) : settings.dailyMinutes * 60000L;
        addDuration(activeWallMs, estimated);
        append(new Event(nowWallMs, "SESSION_END", activeSessionId, "", "", "RESTART_RECOVERY_ESTIMATE"));
        active = false; activeSessionId = ""; needsRecovery = false;
        save();
    }

    private long activeDurationOnDay(int day, long elapsedMs) {
        long duration = boundedDuration(elapsedMs - activeElapsedMs);
        long cursor = activeWallMs, end = activeWallMs + duration, count = 0;
        while (cursor < end) {
            long next = Math.min(end, nextMidnight(cursor));
            if (dayKey(cursor) == day) count += next - cursor;
            cursor = next;
        }
        return count;
    }

    private void addDuration(long startWallMs, long durationMs) {
        long cursor = startWallMs, end = startWallMs + boundedDuration(durationMs);
        while (cursor < end) {
            long next = Math.min(end, nextMidnight(cursor));
            getOrCreate(dayKey(cursor)).durationMs += next - cursor;
            cursor = next;
        }
    }

    private static long boundedDuration(long value) { return Math.max(0, Math.min(MAX_SESSION_MS, value)); }
    private Usage getOrCreate(int day) {
        Usage usage = days.get(day);
        if (usage == null) { usage = new Usage(); days.put(day, usage); }
        while (days.size() > MAX_DAYS) days.remove(days.keySet().iterator().next());
        return usage;
    }
    private void append(Event event) {
        events.add(event);
        int day = dayKey(event.atMs), dailyCount = 0;
        for (Event item : events) if (dayKey(item.atMs) == day) dailyCount++;
        if (dailyCount > MAX_EVENTS_PER_DAY) {
            for (int i = 0; i < events.size(); i++) {
                if (dayKey(events.get(i).atMs) == day) { events.remove(i); break; }
            }
        }
        if (events.size() > MAX_EVENTS) events.remove(0);
    }
    private boolean isQuiet(long wallMs) {
        Calendar c = calendar(wallMs);
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int start = settings.quietStartMinute, end = settings.quietEndMinute;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }
    private static Calendar calendar(long wallMs) { Calendar c = Calendar.getInstance(HONG_KONG); c.setTimeInMillis(wallMs); return c; }
    private static int dayKey(long wallMs) {
        Calendar c = calendar(wallMs);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }
    private static long nextMidnight(long wallMs) {
        Calendar c = calendar(wallMs); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, 1); return c.getTimeInMillis();
    }
    private static boolean validCode(String value, int max) { return value != null && value.length() > 0 && value.length() <= max && value.matches("[A-Za-z0-9_-]+"); }
    private static boolean optionalCode(String value, int max) { return value == null || value.length() == 0 || validCode(value, max); }
    private static String cleanCode(String value, int max) { return optionalCode(value, max) ? (value == null ? "" : value) : "INVALID_CODE"; }
    private static boolean validKind(String kind) {
        return "TURN_START".equals(kind) || "ANSWER_COMPLETE".equals(kind) || "TOOL_REQUESTED".equals(kind)
                || "TOOL_RESULT".equals(kind) || "ASR_ERROR".equals(kind) || "TTS_ERROR".equals(kind)
                || "INTERRUPTED".equals(kind) || "CANCELLED".equals(kind);
    }

    private void save() throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("cannot create ledger directory");
        File temp = new File(parent, file.getName() + ".tmp");
        FileOutputStream stream = new FileOutputStream(temp);
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream));
            out.writeInt(MAGIC); out.writeInt(VERSION);
            out.writeInt(settings.dailyMinutes); out.writeInt(settings.dailySessions); out.writeInt(settings.quietStartMinute); out.writeInt(settings.quietEndMinute); out.writeBoolean(settings.paused);
            out.writeLong(lastCommandSeq); out.writeBoolean(active); out.writeUTF(activeSessionId); out.writeLong(activeWallMs); out.writeLong(activeElapsedMs);
            out.writeInt(days.size());
            for (Map.Entry<Integer, Usage> item : days.entrySet()) { out.writeInt(item.getKey()); out.writeInt(item.getValue().sessions); out.writeLong(item.getValue().durationMs); }
            out.writeInt(events.size());
            for (Event event : events) {
                out.writeLong(event.atMs); out.writeUTF(event.kind); out.writeUTF(event.sessionId); out.writeUTF(event.turnId); out.writeUTF(event.toolStatus); out.writeUTF(event.reason);
            }
            out.flush(); stream.getFD().sync(); out.close();
        } finally { stream.close(); }
        File backup = new File(file.getPath() + ".bak");
        if (file.exists()) {
            if (backup.exists() && !backup.delete()) throw new IOException("cannot remove old backup");
            if (!file.renameTo(backup)) throw new IOException("cannot back up ledger");
        }
        if (!temp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file);
            throw new IOException("cannot commit ledger");
        }
        if (backup.exists()) backup.delete();
    }

    private void read(File source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(source)));
        try {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("ledger version or magic mismatch");
            settings = new Settings(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readBoolean());
            lastCommandSeq = in.readLong(); active = in.readBoolean(); activeSessionId = in.readUTF(); activeWallMs = in.readLong(); activeElapsedMs = in.readLong();
            int count = in.readInt(); if (count < 0 || count > MAX_DAYS) throw new IOException("invalid day count");
            for (int i = 0; i < count; i++) { int key = in.readInt(); Usage usage = new Usage(); usage.sessions = in.readInt(); usage.durationMs = in.readLong(); days.put(key, usage); }
            count = in.readInt(); if (count < 0 || count > MAX_EVENTS) throw new IOException("invalid event count");
            for (int i = 0; i < count; i++) events.add(new Event(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF()));
            needsRecovery = active;
        } catch (RuntimeException ex) { throw new IOException("invalid ledger", ex); }
        finally { in.close(); }
    }
}
