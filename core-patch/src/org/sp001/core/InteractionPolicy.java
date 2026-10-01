package org.sp001.core;

/** Pure product signals: full chest brightness means the microphone is actually recording. */
public final class InteractionPolicy {
    private InteractionPolicy() {}
    public static int brightness(String phase, long elapsedMs) {
        if ("LISTENING".equals(phase)) return 255;
        if ("IDLE".equals(phase)) return 0;
        if ("SPEAKING".equals(phase)) return 36;
        if ("ERROR".equals(phase)) return 0;
        return (elapsedMs / 650L) % 2 == 0 ? 100 : 12;
    }
    public static final double SPEECH_SPEED = 1.12;
    public static final int OUTPUT_RATE = 32000;
    public static final int LOUDNESS_GAIN_MB = 1200;
}
