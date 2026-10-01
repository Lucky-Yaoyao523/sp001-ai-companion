package org.sp001.core;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** Optional parent limits. No policy file means the existing voice path is unchanged. */
final class ParentUseGuard {
    private static LocalGuard guard;
    private static String activeSession="";
    private ParentUseGuard(){}
    private static File file(Context context){return new File(context.getFilesDir(),"sp001-parent-use.bin");}
    private static LocalGuard load(Context context,boolean create)throws IOException{
        if(guard!=null)return guard;
        File path=file(context);
        if(!create&&!path.isFile()&&!new File(path.getPath()+".bak").isFile())return null;
        guard=new LocalGuard(path);return guard;
    }
    static synchronized String apply(Context context,long seq,long expiresAtMs,String type,JSONObject payload)throws IOException{
        if(context==null||payload==null)throw new IOException("PARENT_COMMAND_INVALID");
        // Stage a new policy locally. A rejected or expired command must not
        // activate default limits for a toy that has never enabled controls.
        LocalGuard policy=guard!=null?guard:new LocalGuard(file(context));
        LocalGuard.Settings requested=null;
        if("SET_LIMITS".equals(type)){
            try{requested=new LocalGuard.Settings(payload.getInt("dailyMinutes"),payload.getInt("dailySessions"),
                minutes(payload.getString("quietStart")),minutes(payload.getString("quietEnd")),policy.settings().paused);}
            catch(Exception invalid){return "INVALID";}
        }else if(payload.length()!=0)return "INVALID";
        String result=policy.applyCommand(seq,expiresAtMs,System.currentTimeMillis(),type,requested);
        if("APPLIED".equals(result)||"DUPLICATE".equals(result))guard=policy;
        return result;
    }
    private static int minutes(String value){
        if(value==null||!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw new IllegalArgumentException("time");
        return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3));
    }
    static synchronized String start(Context context,String sessionId){
        try{
            LocalGuard policy=load(context,false);if(policy==null)return "ALLOW";
            LocalGuard.Decision decision=policy.startSession(sessionId,System.currentTimeMillis(),SystemClock.elapsedRealtime());
            if(decision.allowNewSession)activeSession=sessionId;
            return decision.reason;
        }catch(Exception failed){return "ALLOW";} // A broken optional ledger cannot disable existing voice.
    }
    static synchronized String continueSession(String sessionId){
        if(guard==null)return "ALLOW";
        try{
            if(sessionId.equals(activeSession))return guard.canContinue(System.currentTimeMillis(),SystemClock.elapsedRealtime()).reason;
            // A parent may enable controls while an older unmetered session is still
            // speaking. Pause and quiet hours then apply at the next capture.
            String reason=guard.canStart(System.currentTimeMillis(),SystemClock.elapsedRealtime()).reason;
            return "PAUSED".equals(reason)||"QUIET_HOURS".equals(reason)?reason:"ALLOW";
        }
        catch(Exception failed){return "ALLOW";}
    }
    static synchronized void end(String sessionId,String reason){
        if(!sessionId.equals(activeSession)||guard==null)return;
        try{guard.endSession(sessionId,System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason);}
        catch(Exception ignored){}finally{activeSession="";}
    }
}
