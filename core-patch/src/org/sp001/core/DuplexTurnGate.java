package org.sp001.core;

import java.io.IOException;

/** Serializes speech onset against response ownership; no audio/network work under its lock.
 * Utterance IDs come from one continuous recorder, never from completion timestamps.
 * Shared by the continuous conversation adapter and deterministic fault tests.
 */
public final class DuplexTurnGate {
    private long latestSpeech, responseSpeech, generation;
    private boolean responding, interrupted, closed;
    public static final class Interrupted extends IOException {
        public Interrupted(){super("BARGE_IN");}
    }
    /** Returns the invalidated response token, or zero if there is no new cancellation.
     * The caller must stop the returned generation's playback and network resources outside this lock. */
    public synchronized long speechStarted(long speechId) {
        if(speechId<1||speechId<=latestSpeech)throw new IllegalArgumentException("SPEECH_SEQUENCE");
        if(closed)return 0;
        latestSpeech=speechId;
        if(responding&&speechId>responseSpeech&&!interrupted){interrupted=true;return generation;}
        return 0;
    }
    /** Called only after the previous response worker has exited, for the captured utterance. */
    public synchronized long beginResponse(long speechId)throws IOException {
        if(closed)throw new IOException("SESSION_CLOSED");
        if(responding)throw new IllegalStateException("RESPONSE_STILL_OWNED");
        if(speechId<1||speechId>latestSpeech||speechId<=responseSpeech)throw new IllegalArgumentException("RESPONSE_SEQUENCE");
        responseSpeech=speechId;generation++;interrupted=latestSpeech>speechId;responding=true;
        return generation;
    }
    /** Quiet opportunity owns a response, not an invented user utterance. */
    synchronized long beginInitiative(long observedSpeech)throws IOException{
        if(closed||responding||observedSpeech<1||latestSpeech!=observedSpeech)return 0;
        responseSpeech=observedSpeech;generation++;interrupted=false;responding=true;return generation;
    }
    /** Must guard every callback/queue admission as well as the final transcript commit. */
    public synchronized void check(long token)throws IOException {
        if(closed)throw new IOException("SESSION_CLOSED");
        if(!responding||token!=generation)throw new IOException("STALE_RESPONSE");
        if(interrupted)throw new Interrupted();
    }
    public synchronized boolean wasInterrupted(long token){return !closed&&responding&&token==generation&&interrupted;}
    /** Atomic bounded in-memory commit/admission. Never pass network/audio/file work here.
     * A preceding check() alone cannot authorize a later transcript mutation. */
    public synchronized boolean tryCommit(long token,Runnable memoryCommit){
        if(memoryCommit==null)throw new IllegalArgumentException("COMMIT_REQUIRED");
        if(closed||!responding||token!=generation||interrupted)return false;
        memoryCommit.run();return true;
    }
    /** A late old worker cannot release a newer response. */
    public synchronized void finish(long token){if(responding&&token==generation)responding=false;}
    /** Linearizes a farewell against a new onset. A later onset belongs to a new session. */
    public synchronized boolean endSession(long token){if(closed||!responding||token!=generation||interrupted)return false;closed=true;responding=false;generation++;return true;}
    /** An idle timeout cannot terminate speech which began after that timeout was queued. */
    public synchronized boolean endIdle(long speechId){if(closed||responding||latestSpeech!=speechId)return false;closed=true;generation++;return true;}
    public synchronized long currentGeneration(){return generation;}
    public synchronized void close(){closed=true;responding=false;generation++;}
}
