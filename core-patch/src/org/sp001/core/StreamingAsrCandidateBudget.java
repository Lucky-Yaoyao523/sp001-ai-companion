package org.sp001.core;

import java.io.IOException;

/** Explicit, persisted trial grant. Reservation commits before any provider connection. */
final class StreamingAsrCandidateBudget {
    interface Store { String read(String id); boolean write(String id,String value); }
    private static final Object LOCK=new Object();
    private final Store store;private final String id,spec;
    private final long issuedAt,expiresAt,monotonicExpiry;private final int maximum;
    private boolean expired;
    StreamingAsrCandidateBudget(String id,long issued,long expires,int maximum,Store store,long wallNow,long monotonicNow)throws IOException{
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||issued<=0||expires<=issued||expires-issued>3600000L||maximum<1||maximum>10||store==null)throw new IOException("ASR_CANDIDATE_GRANT_INVALID");
        this.id=id;issuedAt=issued;expiresAt=expires;this.maximum=maximum;this.store=store;
        monotonicExpiry=monotonicNow+Math.max(0,Math.min(3600000L,expires-wallNow));spec=issued+":"+expires+":"+maximum+":";
        synchronized(LOCK){used();valid(wallNow,monotonicNow);}
    }
    boolean valid(long wallNow,long monotonicNow)throws IOException{synchronized(LOCK){
        if(expired||(spec+"expired").equals(store.read(id)))return false;
        if(wallNow>=expiresAt||monotonicNow>=monotonicExpiry){
            expired=true;if(!store.write(id,spec+"expired"))throw new IOException("ASR_CANDIDATE_PERSIST_FAILED");return false;
        }
        return wallNow>=issuedAt;
    }}
    boolean remaining()throws IOException{synchronized(LOCK){return used()<maximum;}}
    void reserve(long wallNow,long monotonicNow)throws IOException{
        synchronized(LOCK){if(!valid(wallNow,monotonicNow))throw new IOException("ASR_CANDIDATE_EXPIRED");int n=used();if(n>=maximum)throw new IOException("ASR_CANDIDATE_EXHAUSTED");if(!store.write(id,spec+(n+1)))throw new IOException("ASR_CANDIDATE_PERSIST_FAILED");}
    }
    private int used()throws IOException{
        String saved=store.read(id);if(saved==null)return 0;
        if(!saved.startsWith(spec))throw new IOException("ASR_CANDIDATE_GRANT_CHANGED");
        if(saved.equals(spec+"expired")){expired=true;return maximum;}
        try{int n=Integer.parseInt(saved.substring(spec.length()));if(n<0||n>maximum)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IOException("ASR_CANDIDATE_LEDGER_INVALID");}
    }
}
