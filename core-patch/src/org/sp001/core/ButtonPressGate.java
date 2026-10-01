package org.sp001.core;

/** Deduplicate two native reports of one short press. Caller owns synchronization. */
public final class ButtonPressGate {
    public static final long DEBOUNCE_MS = 500;
    private long lastAccepted;
    private boolean seen;
    public static boolean isShortKey(int code) { return code == 29 || code == 25; }
    public boolean duplicate(long now) {
        return seen && now >= lastAccepted && now - lastAccepted < DEBOUNCE_MS;
    }
    public void accepted(long now) { lastAccepted = now; seen = true; }
}
