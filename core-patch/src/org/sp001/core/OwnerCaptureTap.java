package org.sp001.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Copies only valid original AudioRecord bytes before its existing consumer advances the buffer.
 * No microphone, file, network, or playback operation occurs in this class.
 */
public final class OwnerCaptureTap {
    private static final int MAX_MONO = 16000 * 2 * 8;
    private static byte[] capture;
    private static SpeechWindow speech;
    private static int used;
    private static boolean stereo, invalid;
    private static long generation;
    private OwnerCaptureTap() {}
    public static synchronized long begin(boolean twoChannels) {
        if (capture != null) throw new IllegalStateException("CAPTURE_ALREADY_OWNED");
        capture = new byte[MAX_MONO]; used = 0; invalid = false; stereo = twoChannels;
        return ++generation;
    }
    public static synchronized long beginSpeech(boolean twoChannels) {return beginSpeech(twoChannels,1);}
    public static synchronized long beginSpeech(boolean twoChannels,int sensitivity) {
        return beginSpeech(twoChannels,sensitivity,0);
    }
    public static synchronized long beginSpeech(boolean twoChannels,int sensitivity,double ambient) {
        if (capture != null) throw new IllegalStateException("CAPTURE_ALREADY_OWNED");
        SpeechWindow window=new SpeechWindow(sensitivity,ambient);
        capture = new byte[0]; used = 0; invalid = false; stereo = twoChannels;
        speech = window; return ++generation;
    }
    public static synchronized boolean speechComplete(long token) {
        if (token != generation || capture == null || speech == null) throw new IllegalStateException("STALE_CAPTURE");
        if (invalid) throw new IllegalStateException("INVALID_NATIVE_PCM");
        return speech.completed();
    }
    public static synchronized int speechSamples(long token) {
        if (token != generation || speech == null) throw new IllegalStateException("STALE_CAPTURE");
        return speech.samplesSeen();
    }
    public static synchronized String speechReason(long token){
        if(token!=generation||speech==null)throw new IllegalStateException("STALE_CAPTURE");
        return speech.reason();
    }
    /** Original enqueue argument is sampleCount = AudioRecord.read(...) / 2. */
    public static synchronized void observe(ByteBuffer original, int sampleCount) {
        if (capture == null || invalid) return;
        if (original == null || sampleCount <= 0 || sampleCount > 1280 ||
                sampleCount * 2 > original.capacity() || (stereo && sampleCount % 2 != 0)) {
            invalid = true; return;
        }
        // Android's direct-buffer read starts at zero. A duplicate preserves every original cursor.
        ByteBuffer view = original.duplicate(); view.clear();
        int inputBytes = sampleCount * 2, stride = stereo ? 4 : 2;
        // Stock SpeechRecognizer.java537-556 treats channel0 as near-end microphone,
        // channel1 as playback reference. This half-duplex patch does not claim new AEC.
        for (int at = 0; at < inputBytes; at += stride) {
            if (speech != null) speech.sample(view.get(at), view.get(at + 1));
            else if (used < capture.length) { capture[used++] = view.get(at); capture[used++] = view.get(at + 1); }
        }
    }
    public static synchronized byte[] finish(long token) {
        if (token != generation || capture == null) throw new IllegalStateException("STALE_CAPTURE");
        try {
            if (invalid) throw new IllegalStateException("INVALID_NATIVE_PCM");
            if (speech != null) return speech.finish();
            return Arrays.copyOf(capture, used);
        } finally { clear(); }
    }
    public static synchronized void cancel(long token) { if (token == generation) clear(); }
    private static void clear() { if (capture != null) Arrays.fill(capture, (byte)0); if (speech != null) speech.cancel(); speech = null; capture = null; used = 0; }
    public static byte[] wav(byte[] pcm, int rate) {
        if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > 2880000 ||
                (rate != 16000 && rate != 24000)) throw new IllegalArgumentException("INVALID_PCM_WAV");
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt(36 + pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '});
        out.putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate * 2);
        out.putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return out.array();
    }
}
