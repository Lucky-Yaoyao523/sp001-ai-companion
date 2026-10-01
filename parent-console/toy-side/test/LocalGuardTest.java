import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import org.sp001.parent.LocalGuard;

public final class LocalGuardTest {
    private static int checks;
    private static long at(String value) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong"));
        return format.parse(value).getTime();
    }
    private static void eq(Object actual, Object expected, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static void yes(boolean actual, String label) { checks++; if (!actual) throw new AssertionError(label); }
    private static File ledger(String name) throws Exception {
        File temp = File.createTempFile("sp001-parent-" + name, ".bin");
        if (!temp.delete()) throw new Exception("temporary file");
        temp.deleteOnExit();
        return temp;
    }

    public static void main(String[] args) throws Exception {
        quietAndPause();
        timeAndSessionLimits();
        activeLimitAndBackupRecovery();
        midnightSplitAndRestart();
        commandsAndMetadata();
        System.out.println("LocalGuard checks passed: " + checks);
    }

    private static void quietAndPause() throws Exception {
        File file = ledger("quiet");
        LocalGuard guard = new LocalGuard(file);
        eq(guard.canStart(at("2026-09-25 20:29"), 100).reason, "ALLOW", "before quiet");
        eq(guard.canStart(at("2026-09-25 20:30"), 100).reason, "QUIET_HOURS", "quiet begins");
        eq(guard.canStart(at("2026-09-26 06:59"), 100).reason, "QUIET_HOURS", "quiet overnight");
        eq(guard.canStart(at("2026-09-26 07:00"), 100).reason, "ALLOW", "quiet ends");
        long now = at("2026-09-25 10:00");
        eq(guard.applyCommand(1, now + 1000, now, "PAUSE", null), "APPLIED", "pause command");
        eq(new LocalGuard(file).canStart(now, 100).reason, "PAUSED", "pause survives restart");
    }

    private static void timeAndSessionLimits() throws Exception {
        LocalGuard guard = new LocalGuard(ledger("minutes"));
        long first = at("2026-09-25 09:00");
        guard.applyCommand(1, first + 3600000, first, "SET_LIMITS", new LocalGuard.Settings(10, 4, 720, 780, false));
        yes(guard.startSession("s1", first, 1000).allowNewSession, "first session allowed");
        guard.endSession("s1", first + 360000, 361000, "NORMAL_END");
        long second = at("2026-09-25 09:20");
        guard.startSession("s2", second, 1000000);
        guard.endSession("s2", second + 300000, 1300000, "NORMAL_END");
        eq(guard.canStart(at("2026-09-25 09:30"), 2000000).reason, "TIME_LIMIT", "duration limit persists");

        LocalGuard count = new LocalGuard(ledger("count"));
        count.applyCommand(1, first + 3600000, first, "SET_LIMITS", new LocalGuard.Settings(45, 2, 720, 780, false));
        count.startSession("c1", first, 1000); count.endSession("c1", first + 1000, 2000, "NORMAL_END");
        count.startSession("c2", first + 2000, 3000); count.endSession("c2", first + 3000, 4000, "NORMAL_END");
        eq(count.canStart(first + 4000, 5000).reason, "SESSION_LIMIT", "session count limit");
    }

    private static void activeLimitAndBackupRecovery() throws Exception {
        File file = ledger("active");
        LocalGuard guard = new LocalGuard(file);
        long first = at("2026-09-25 09:00");
        guard.applyCommand(1, first + 3600000, first, "SET_LIMITS", new LocalGuard.Settings(5, 4, 720, 780, false));
        guard.startSession("long", first, 1000);
        eq(guard.canContinue(first + 299000, 300000).reason, "ALLOW", "active use below limit");
        eq(guard.canContinue(first + 300000, 301000).reason, "TIME_LIMIT", "active use stops next turn at limit");
        File backup = new File(file.getPath() + ".bak");
        yes(file.renameTo(backup), "simulate interrupted ledger replacement");
        LocalGuard restored = new LocalGuard(file);
        eq(restored.canStart(first + 300000, 200).reason, "TIME_LIMIT", "backup recovers limit and active use");
    }

    private static void midnightSplitAndRestart() throws Exception {
        File file = ledger("midnight");
        LocalGuard guard = new LocalGuard(file);
        long before = at("2026-09-25 23:50");
        guard.applyCommand(1, before + 3600000, before, "SET_LIMITS", new LocalGuard.Settings(15, 4, 720, 780, false));
        guard.startSession("night", before, 1000);
        guard.endSession("night", at("2026-09-26 00:10"), 1201000, "NORMAL_END");
        eq(Long.valueOf(guard.usageOn(before).durationMs), Long.valueOf(600000), "first day gets 10 minutes");
        eq(Long.valueOf(guard.usageOn(at("2026-09-26 00:10")).durationMs), Long.valueOf(600000), "second day gets 10 minutes");
        eq(Integer.valueOf(new LocalGuard(file).usageOn(before).sessions), Integer.valueOf(1), "count persisted");
        eq(Integer.valueOf(new LocalGuard(file).usageOn(at("2026-09-26 00:10")).sessions), Integer.valueOf(0), "next day count is distinct");

        File recovery = ledger("recovery");
        LocalGuard original = new LocalGuard(recovery);
        long start = at("2026-09-25 09:00");
        original.applyCommand(1, start + 3600000, start, "SET_LIMITS", new LocalGuard.Settings(5, 4, 720, 780, false));
        original.startSession("unfinished", start, 1000);
        LocalGuard reopened = new LocalGuard(recovery);
        eq(reopened.canStart(start + 600000, 500).reason, "TIME_LIMIT", "restart cannot erase unfinished use");
        eq(Integer.valueOf(reopened.usageOn(start).sessions), Integer.valueOf(1), "restart keeps session count");
    }

    private static void commandsAndMetadata() throws Exception {
        File file = ledger("commands");
        LocalGuard guard = new LocalGuard(file);
        long now = at("2026-09-25 10:00");
        eq(guard.applyCommand(2, now, now, "PAUSE", null), "EXPIRED", "expired command rejected");
        eq(guard.applyCommand(2, now + 3600000, now, "PAUSE", null), "APPLIED", "fresh command applied");
        eq(guard.applyCommand(2, now + 3600000, now, "RESUME", null), "DUPLICATE", "replay rejected");
        eq(guard.applyCommand(1, now + 3600000, now, "RESUME", null), "DUPLICATE", "old sequence rejected");
        eq(guard.applyCommand(3, now + 25L * 3600000, now, "RESUME", null), "EXPIRED", "oversized validity rejected");
        eq(guard.applyCommand(3, now + 3600000, now, "RESUME", null), "APPLIED", "resume applied");
        eq(Long.valueOf(new LocalGuard(file).lastCommandSeq()), Long.valueOf(3), "sequence persisted");
        yes(!guard.recordEvent(now, "TOOL_RESULT", "s1", "t1", "SUCCESS", "孩子家在这里"), "raw speech cannot enter reason field");
        yes(guard.recordEvent(now, "TOOL_REQUESTED", "s1", "t1", "", ""), "request recorded");
        yes(guard.recordEvent(now + 1000, "TOOL_RESULT", "s1", "t1", "FAILED", "TIMEOUT"), "failed tool receipt recorded");
        eq(Integer.valueOf(guard.events().size()), Integer.valueOf(2), "no literal content in ledger");
        for (int i = 0; i < 260; i++) guard.recordEvent(now + i * 1000, "ASR_ERROR", "s1", "t1", "", "QUEUE_FULL");
        yes(guard.events().size() <= 256, "daily event capacity is bounded");
    }
}
