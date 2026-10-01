package org.sp001.core;

/** Persistent ownership is not inferred from the success of today's credential read. */
public final class ModePolicy {
    public static final int UNAVAILABLE=0, ENABLED=1, DISABLED=2;
    private ModePolicy(){}
    public static boolean voiceKind(String kind){return "minimax".equals(kind)||"diagnostic".equals(kind)||"loopback".equals(kind)||"cloud".equals(kind)||"playback_test".equals(kind);}
    public static boolean managed(int configState,Boolean stored,boolean selectionExists){
        if(configState==ENABLED)return true;
        if(configState==DISABLED)return false;
        return stored!=null?stored.booleanValue():selectionExists;
    }
}
