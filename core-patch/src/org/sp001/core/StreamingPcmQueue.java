package org.sp001.core;

/** One utterance's bounded 16 kHz mono PCM handoff. No waits or external calls under lock. */
final class StreamingPcmQueue implements java.io.Closeable {
    // Ten seconds of 16 kHz PCM bridges the existing eight-second ASR setup bound.
    // Still bounded: a stalled provider cannot retain an entire long utterance.
    static final int CAPACITY = 320000;
    static final int MAX_POLL_BYTES = 3200;
    enum State { OPEN, FINISHED, CANCELLED, OVERFLOW }
    static final class Poll {
        final State state;
        final byte[] pcm;
        private Poll(State state, byte[] pcm) { this.state=state;this.pcm=pcm; }
    }
    private static final Poll EMPTY = new Poll(State.OPEN,new byte[0]);
    private static final Poll END = new Poll(State.FINISHED,new byte[0]);
    private static final Poll CANCELLED = new Poll(State.CANCELLED,new byte[0]);
    private static final Poll OVERFLOW = new Poll(State.OVERFLOW,new byte[0]);
    private final long utteranceId;
    private final byte[] ring = new byte[CAPACITY];
    private int head,size;
    private boolean finished;
    private State terminal;

    StreamingPcmQueue(long utteranceId) {
        if(utteranceId<=0)throw new IllegalArgumentException("utteranceId must be positive");
        this.utteranceId=utteranceId;
    }

    /** Copies caller-owned bytes before returning. False never means a partial enqueue. */
    synchronized boolean offer(long id,byte[] pcm,int off,int len) {
        if(id!=utteranceId||terminal!=null||finished)return false;
        if(pcm==null)throw new NullPointerException("pcm");
        if(off<0||len<0||off>pcm.length-len)throw new IndexOutOfBoundsException();
        if((off&1)!=0||(len&1)!=0)throw new IllegalArgumentException("PCM16 sample alignment required");
        if(len>CAPACITY-size){clear();terminal=State.OVERFLOW;return false;}
        int tail=(head+size)%CAPACITY,first=Math.min(len,CAPACITY-tail);
        System.arraycopy(pcm,off,ring,tail,first);
        System.arraycopy(pcm,off+first,ring,0,len-first);
        size+=len;return true;
    }

    /** A successful finish seals input but retains the queued prefix for draining. */
    synchronized boolean finish(long id) {
        if(id!=utteranceId||terminal!=null)return false;
        finished=true;return true;
    }

    /** OPEN plus empty pcm means temporarily empty; FINISHED means sealed and drained. */
    synchronized Poll poll(int maxBytes) {
        if(maxBytes<2||(maxBytes&1)!=0)throw new IllegalArgumentException("maxBytes must be even and at least 2");
        if(terminal==State.CANCELLED)return CANCELLED;
        if(terminal==State.OVERFLOW)return OVERFLOW;
        if(size==0)return finished?END:EMPTY;
        int n=Math.min(size,Math.min(maxBytes,MAX_POLL_BYTES));
        byte[] owned=new byte[n];int first=Math.min(n,CAPACITY-head);
        System.arraycopy(ring,head,owned,0,first);
        System.arraycopy(ring,0,owned,first,n-first);
        java.util.Arrays.fill(ring,head,head+first,(byte)0);
        java.util.Arrays.fill(ring,0,n-first,(byte)0);
        head=(head+n)%CAPACITY;size-=n;
        return new Poll(State.OPEN,owned);
    }

    synchronized State state() {
        return terminal!=null?terminal:finished&&size==0?State.FINISHED:State.OPEN;
    }
    synchronized int bufferedBytes() { return size; }
    synchronized void cancel() {
        clear();finished=true;
        // Preserve overflow as the root failure even if generic cleanup subsequently cancels.
        if(terminal==null)terminal=State.CANCELLED;
    }
    public void close() { cancel(); }
    private void clear() { java.util.Arrays.fill(ring,(byte)0);head=0;size=0; }
}
