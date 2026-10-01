package org.sp001.core;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONObject;

/** Physical-button entry. Inert without explicitly provisioned owner configuration.
 * No boot recording, remote start command, installer or shell.
 */
public final class OwnerHeadless {
    private static final Object LOCK = new Object();
    private static Session active;
    private static Session lastSession;
    private static long generation;
    private static String lastResult = "IDLE";
    private static final ButtonGesture GESTURES = new ButtonGesture();
    private static final Handler BUTTON_UI = new Handler(Looper.getMainLooper());
    private static boolean modeSwitching;
    private static Runnable pendingSingle;
    private static final WakeGate WAKE=new WakeGate();
    private static final PassiveWakeGate PASSIVE=new PassiveWakeGate();
    private static long lastPassiveReceipt;
    /** BEFORE stock onReceive: covers waitingForMotion bypass and original 1s suppression.
     * Rejected busy events are consumed, never toggled or deferred until after conversation. */
    public static boolean tryPassiveInterrupt(android.content.Intent intent){
        if(intent==null||!"smarttoy.interrupt".equals(intent.getAction()))return false;
        String source=intent.getStringExtra("type");if(!PassiveWakeGate.source(source))return false;
        synchronized(LOCK){
            long now=android.os.SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
            long eventAt=intent.getLongExtra("time",0L);
            if(active!=null||modeSwitching||pendingSingle!=null||WAKE.tailPending()){
                PASSIVE.offer(source,eventAt,wall,now,12,false);
                passiveReceipt(source,"IGNORED_BUSY",false,now);return true;
            }
            if(!CharacterMode.managed()||CharacterMode.legacy())return false;
            boolean eligible=false;try{eligible=stockWakeEligible(false)&&OwnerVoiceBridge.resourcesReleased();}catch(Exception ignored){}
            boolean available=WAKE.canStart(now,false,false,false,eligible);
            int hour=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei")).get(java.util.Calendar.HOUR_OF_DAY);
            String decision=PASSIVE.offer(source,eventAt,wall,now,hour,available);
            if(!"ACCEPT".equals(decision)){passiveReceipt(source,decision,false,now);return true;}
            boolean started=startOrCancel(true,true,"ir".equals(source)?"ORIGINAL_IR":"ORIGINAL_MOTION");
            if(started)PASSIVE.started(now);
            passiveReceipt(source,started?"STARTED":"START_REJECTED",started,now);return true;
        }
    }
    /** A stale stock enable callback must not re-enable sensors while a session owns audio. */
    public static boolean tryEnablePassiveSensors(Object main){synchronized(LOCK){
        if(active!=null||modeSwitching||pendingSingle!=null||WAKE.tailPending())return true;
        if(!CharacterMode.managed()||CharacterMode.legacy())return false;
        try{ParentConversationSync.startBackground(OwnerVoiceBridge.nativeContext());}catch(Throwable ignored){}
        try{
            if(explicitSleepOrShutdown())return false;
            if(!stockWakeEligible(false)||!OwnerVoiceBridge.resourcesReleased())return true;
            main.getClass().getMethod("ownerOriginalEnableSensors").invoke(main);
            main.getClass().getMethod("J",Boolean.TYPE).invoke(main,Boolean.TRUE);
            CharacterMode.receipt("PASSIVE_SENSORS_ARM_REQUESTED","ORIGINAL_IR_AND_ACCEL");
        }catch(Exception e){CharacterMode.receipt("PASSIVE_SENSORS_ARM_FAILED",e.getClass().getSimpleName());}
        return true;
    }}
    private static void passiveReceipt(String source,String result,boolean started,long now){
        if(!started&&now-lastPassiveReceipt<3000)return;lastPassiveReceipt=now;
        try{
            JSONObject o=new JSONObject().put("source",source).put("result",result).put("started",started)
                .put("sessionId",active==null?"":active.nativeSessionId).put("busy",active!=null)
                .put("accepted",PASSIVE.accepted()).put("ignored",PASSIVE.ignored())
                .put("blockedUntilMonotonicMs",PASSIVE.blockedUntil()).put("atMs",System.currentTimeMillis());
            byte[] bytes=o.toString().getBytes("UTF-8");
            java.io.FileOutputStream out=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-passive-wake-event.json",Context.MODE_PRIVATE);
            try{out.write(bytes);}finally{out.close();}
            if(started){out=OwnerVoiceBridge.nativeContext().openFileOutput("sp001-passive-wake-last-start.json",Context.MODE_PRIVATE);try{out.write(bytes);}finally{out.close();}}
        }catch(Exception ignored){}
    }

    private static boolean wakeArmPending;
    private static long wakeArmTicket;
    /** Original DSP event is start-only. A rejected owned event never falls back to English. */
    public static boolean tryVoiceWake(){
        if(!CharacterMode.managed()||CharacterMode.legacy())return false;
        synchronized(LOCK){
            boolean eligible=false;try{eligible=stockWakeEligible(true);}catch(Exception ignored){}
            if(!WAKE.canStart(android.os.SystemClock.elapsedRealtime(),active!=null,modeSwitching,pendingSingle!=null,eligible)){
                CharacterMode.receipt("VOICE_WAKE_IGNORED",active!=null?"BUSY":"NOT_READY");return true;
            }
            // The same lock prevents the toggle entry from seeing another active session.
            boolean started=startOrCancel(true);CharacterMode.receipt(started?"VOICE_WAKE_STARTED":"VOICE_WAKE_REJECTED",started?"ORIGINAL_DSP":lastResult);
            return true;
        }
    }
    private static Object sleepManager()throws Exception{return Class.forName("com.smarttoy.q").getMethod("em").invoke(null);}
    private static boolean sleeping()throws Exception{Object sleep=sleepManager();return "SLEEP".equals(((Enum<?>)sleep.getClass().getMethod("en").invoke(sleep)).name());}
    private static boolean explicitSleepOrShutdown()throws Exception{
        if(Boolean.TRUE.equals(Class.forName("com.smarttoy.embedded.receivers.InterruptReceiver").getMethod("fV").invoke(null)))return true;
        return sleeping();
    }
    /** Serialize the complete original explicit-sleep transaction against owner startup. */
    public static void runStockSleep(Object manager){synchronized(LOCK){
        WAKE.sleepRequested();wakeArmPending=false;
        if(CharacterMode.managed()&&!CharacterMode.legacy()&&active!=null)cancel();
        try{manager.getClass().getMethod("ownerOriginalSleep").invoke(manager);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException("STOCK_SLEEP_FAILED",e.getCause());}
        catch(Exception e){throw new IllegalStateException("STOCK_SLEEP_BINDING_FAILED",e);}
    }}
    private static boolean stockWakeEligible(boolean requireDsp)throws Exception{
        if(!CharacterMode.managed()||CharacterMode.legacy()||!OwnerVoiceBridge.enabled(OwnerVoiceBridge.nativeContext())||explicitSleepOrShutdown())return false;
        Object setup=Class.forName("com.smarttoy.embedded.managers.f").getMethod("jK").invoke(null);
        if(!Boolean.TRUE.equals(setup.getClass().getMethod("jL").invoke(setup)))return false;
        Object test=Class.forName("com.smarttoy.embedded.managers.a").getMethod("iG").invoke(null);
        if(Boolean.TRUE.equals(test.getClass().getMethod("iN").invoke(test)))return false;
        Object radio=Class.forName("com.wwm.WWMWrapper").getMethod("tK").invoke(null);
        if(Boolean.TRUE.equals(radio.getClass().getMethod("isRunning").invoke(radio)))return false;
        return !requireDsp||Boolean.TRUE.equals(Class.forName("com.smarttoy.m").getMethod("ea").invoke(null));
    }
    /** Also called AFTER the stock hS body: late stock disables cannot permanently disarm Chinese idle. */
    public static void stockSensorsStopped(){synchronized(LOCK){scheduleWakeArm();}}
    private static void scheduleWakeArm(){
        if(active!=null||modeSwitching||pendingSingle!=null||wakeArmPending)return;
        try{if(!stockWakeEligible(false))return;}catch(Exception ignored){return;}
        long wait=WAKE.delay(android.os.SystemClock.elapsedRealtime());if(wait<0)return;
        final long ticket=WAKE.ticket();wakeArmTicket=ticket;wakeArmPending=true;
        BUTTON_UI.postDelayed(new Runnable(){public void run(){synchronized(LOCK){
            if(ticket!=wakeArmTicket)return;
            wakeArmPending=false;
            if(!WAKE.current(ticket)){scheduleWakeArm();return;}
            try{
                if(!WAKE.canArm(ticket,android.os.SystemClock.elapsedRealtime(),active!=null,modeSwitching,pendingSingle!=null,stockWakeEligible(false)))return;
                Object main=Class.forName("com.smarttoy.embedded.MainActivity").getMethod("ia").invoke(null);
                main.getClass().getMethod("hR").invoke(main);
                Object sleep=sleepManager();sleep.getClass().getMethod("ep").invoke(sleep);
                CharacterMode.receipt("WAKE_REARM_REQUESTED","ORIGINAL_SENSOR_SETTINGS");
            }catch(Exception e){CharacterMode.receipt("WAKE_REARM_FAILED",e.getClass().getSimpleName());}
        }}},wait);
    }
    static void feedbackTailEnded(long owner){synchronized(LOCK){
        if(active!=null||generation!=owner)return;WAKE.tailEnded(android.os.SystemClock.elapsedRealtime());wakeArmPending=false;scheduleWakeArm();
    }}
    /** Guard the actual stock sleep callbacks, including ones queued before a new session. */
    public static boolean allowStockSleep(){synchronized(LOCK){
        boolean owned=CharacterMode.managed()&&!CharacterMode.legacy();
        try{boolean explicit=explicitSleepOrShutdown();if(explicit&&owned&&active!=null)cancel();
            return WakeGate.allowSleep(owned,active!=null||modeSwitching||WAKE.tailPending(),explicit);
        }catch(Exception unknown){if(owned&&active!=null)cancel();return true;}
    }}
    public static boolean tryKeyUp(int code) {
        if (!ButtonPressGate.isShortKey(code)) return false;
        return hardwarePress("ANDROID_KEY_" + code);
    }
    public static boolean tryHardwareButton() { return hardwarePress("SENSOR_POWER"); }
    private static boolean hardwarePress(final String source) {
        if(!CharacterMode.managed())return false;
        synchronized (LOCK) {
            if(modeSwitching){buttonReceipt(source,"MODE_SWITCH_BUSY",true);return true;}
            final long now=android.os.SystemClock.elapsedRealtime();
            int gesture=GESTURES.press(source,now,active!=null);
            if(gesture==ButtonGesture.IGNORE){buttonReceipt(source,"DUPLICATE_IGNORED",true);return true;}
            if(gesture==ButtonGesture.STOP){
                if(pendingSingle!=null){BUTTON_UI.removeCallbacks(pendingSingle);pendingSingle=null;}
                cancel();buttonReceipt(source,"STOP_REQUESTED_IMMEDIATE",true);return true;
            }
            // Settle an expired press BEFORE removing its delayed Handler callback.
            int expired=GESTURES.takeExpired();
            if(pendingSingle!=null){BUTTON_UI.removeCallbacks(pendingSingle);pendingSingle=null;}
            if(expired!=ButtonGesture.IGNORE)dispatchGesture(expired,source);
            if(modeSwitching){GESTURES.reset();return true;}
            // A delayed earlier SINGLE may just have started a session. The new,
            // distinct press must now stop it rather than vanish or wait again.
            if(active!=null){GESTURES.suppress(now);cancel();buttonReceipt(source,"STOP_REQUESTED_IMMEDIATE",true);return true;}
            OwnerFeedback.pressAccepted();
            final long token=GESTURES.generation();
            buttonReceipt(source,"PRESS_PENDING_SINGLE_OR_TRIPLE",true);
            pendingSingle=new Runnable(){public void run(){
                synchronized(LOCK){
                    if(modeSwitching)return;
                    int settled=GESTURES.consume(token,android.os.SystemClock.elapsedRealtime());
                    if(settled==ButtonGesture.IGNORE)return;
                    pendingSingle=null;dispatchGesture(settled,source);
                }
            }};
            BUTTON_UI.postDelayed(pendingSingle,GESTURES.waitMs(android.os.SystemClock.elapsedRealtime()));return true;
        }
    }
    private static void dispatchGesture(int gesture,final String source){
        // Repeated everyday button presses must not silently select another dialogue engine.
        // Original-mode transition remains an explicit maintenance operation, not a toy gesture.
        if(gesture==ButtonGesture.TRIPLE){buttonReceipt(source,"MULTI_PRESS_IGNORED",true);scheduleWakeArm();return;}
        if(gesture==ButtonGesture.EXCESS){buttonReceipt(source,"EXCESS_PRESSES_IGNORED",true);OwnerFeedback.errorCue();scheduleWakeArm();return;}
        if(gesture!=ButtonGesture.SINGLE)return;
        if(CharacterMode.legacy()){
            new Thread(new Runnable(){public void run(){
                try{Object main=Class.forName("com.smarttoy.embedded.MainActivity").getMethod("ia").invoke(null);
                    main.getClass().getMethod("cu").invoke(main);buttonReceipt(source,"LEGACY_SHORT_PRESS",true);
                }catch(Exception e){buttonReceipt(source,"LEGACY_BUTTON_FAILED",true);OwnerFeedback.errorCue();}
            }},"SP001-LegacyButton").start();
        }else{
            boolean busy=active!=null;boolean ok=tryButton();
            buttonReceipt(source,ok?(busy?"STOP_REQUESTED":"START_REQUESTED"):lastResult,ok);
            if(!ok)OwnerFeedback.errorCue(); // Config failure stays Chinese; never fall through to English.
        }
    }
    static boolean requestModeAfterTurn(Object connector,boolean legacy){
        synchronized(LOCK){if(active==null||active.connector!=connector||active.cancelled)return false;active.requestedMode=Boolean.valueOf(legacy);return true;}
    }
    private static void switchMode(final boolean english){
        if(modeSwitching)return;
        WAKE.invalidate();wakeArmPending=false;
        modeSwitching=true;GESTURES.reset();cancel();
        new Thread(new Runnable(){public void run(){
            PowerManager.WakeLock wake=null;
            try{
                final boolean before=CharacterMode.legacy();
                PowerManager manager=(PowerManager)OwnerVoiceBridge.nativeContext().getSystemService(Context.POWER_SERVICE);
                wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SP001:ModeTransition");wake.acquire(35000);
                final Object program=Class.forName("com.smarttoy.jsactivities.a").getMethod("lc").invoke(null);
                ModeTransition.Result result=ModeTransition.run(new ModeTransition.Ports(){
                    public boolean current(){return before;}
                    public void prepare()throws Exception{
                        long until=android.os.SystemClock.elapsedRealtime()+7000;
                        while(true){synchronized(LOCK){if(active==null)break;}if(android.os.SystemClock.elapsedRealtime()>=until)throw new java.io.IOException("MODE_CANCEL_TIMEOUT");Thread.sleep(25);}
                        program.getClass().getMethod("in").invoke(program);program.getClass().getMethod("bW",String.class).invoke(program,(Object)null);
                    }
                    public void stage(boolean target){CharacterMode.stage(Boolean.valueOf(target));}
                    public void announce(boolean target)throws Exception{CharacterMode.announce(target);}
                    public void activate(boolean target)throws Exception{
                        if(target){
                            Object activity=Class.forName("com.smarttoy.util.c").getField("Gv").get(null);
                            Object loaded=program.getClass().getMethod("cT",String.class).invoke(program,activity);
                            if(!Boolean.TRUE.equals(loaded))throw new java.io.IOException("LEGACY_ACTIVITY_REJECTED");
                        }
                        else{Object eyes=Class.forName("com.smarttoy.embedded.a.b").getMethod("iq").invoke(null);
                            eyes.getClass().getMethod("g",String.class,Integer.TYPE).invoke(eyes,"IDLE",Integer.valueOf(1));}
                    }
                    public void commit(boolean target)throws Exception{CharacterMode.save(target);}
                    public void finish(){CharacterMode.stage(null);}
                },english);
                if(result.success)CharacterMode.receipt("MODE_SWITCH_COMPLETE",english?"legacy":"chinese");
                else{CharacterMode.receipt(result.rollbackSucceeded?"MODE_SWITCH_FAILED_ROLLED_BACK":"MODE_SWITCH_ROLLBACK_FAILED",result.error+":"+result.rollbackError);OwnerFeedback.errorCue();}
            }catch(Exception e){CharacterMode.stage(null);CharacterMode.receipt("MODE_SWITCH_FAILED",e.getClass().getSimpleName());OwnerFeedback.errorCue();}
            finally{if(wake!=null&&wake.isHeld())wake.release();synchronized(LOCK){GESTURES.suppress(android.os.SystemClock.elapsedRealtime());modeSwitching=false;WAKE.tailEnded(android.os.SystemClock.elapsedRealtime());wakeArmPending=false;scheduleWakeArm();}}
        }},"SP001-ModeSwitch").start();
    }
    private static void buttonReceipt(String source,String action,boolean handled) {
        try {
            Context context=OwnerVoiceBridge.nativeContext();
            JSONObject data=new JSONObject().put("source",source).put("action",action)
                .put("handled",handled).put("busy",active!=null).put("atMs",System.currentTimeMillis());
            java.io.FileOutputStream out=context.openFileOutput("sp001-button-event.json",Context.MODE_PRIVATE);
            try { out.write(data.toString().getBytes("UTF-8")); } finally { out.close(); }
        } catch(Exception ignored) { }
    }

    private OwnerHeadless() {}
    private static final class Session implements Runnable {
        final Context context;
        final long ticket;
        volatile Object connector;
        volatile boolean cancelled;
        volatile Boolean requestedMode;
        final String nativeSessionId=java.util.UUID.randomUUID().toString();
        boolean ready; long readyAt; int window; String result="STARTING";
        int asr,llm,tts,completed,interrupted;
        final long sleepRevision=WAKE.sleepRevision();
        boolean initiallySleeping;
        String activationSource="BUTTON_OR_CONTROL";
        Thread thread;
        Session(Context context) { this.context = context; this.ticket = ++generation; }
        private void prepareSensors(final Object main)throws Exception{
            final java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicBoolean success=new java.util.concurrent.atomic.AtomicBoolean();
            BUTTON_UI.post(new Runnable(){public void run(){try{synchronized(LOCK){
                if(active!=Session.this||cancelled||modeSwitching)return;
                if(Boolean.TRUE.equals(Class.forName("com.smarttoy.embedded.receivers.InterruptReceiver").getMethod("fV").invoke(null)))return;
                if(!WAKE.canPrepare(sleepRevision,initiallySleeping,sleeping()))return;
                Object sleep=sleepManager();sleep.getClass().getMethod("bK",String.class).invoke(sleep,"idle");
                if("SLEEP".equals(((Enum<?>)sleep.getClass().getMethod("en").invoke(sleep)).name()))return;
                sleep.getClass().getMethod("eq").invoke(sleep);main.getClass().getMethod("hS").invoke(main);success.set(true);
            }}catch(Exception ignored){}finally{ready.countDown();}}});
            if(!ready.await(2500,java.util.concurrent.TimeUnit.MILLISECONDS)||!success.get())throw new java.io.IOException("OWNER_SENSOR_PREPARE_FAILED");
        }
        public void run() {
            PowerManager.WakeLock wake = null; Object program = null;
            try {
                if (cancelled) return;
                Object main = Class.forName("com.smarttoy.embedded.MainActivity").getMethod("ia").invoke(null);
                main.getClass().getMethod("fJ").invoke(main);
                PowerManager manager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
                wake = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SP001:OwnerVoiceTurn");
                wake.acquire(660000L); // Ten-minute session with bounded native cleanup allowance.
                prepareSensors(main);
                program = Class.forName("com.smarttoy.jsactivities.a").getMethod("lc").invoke(null);
                // Abort the currently owned activity before assigning our temporary connector.
                program.getClass().getMethod("in").invoke(program);
                connector = Class.forName("com.smarttoy.jsactivities.JSConnector").getConstructor(String.class).newInstance((Object)null);
                if (cancelled) { abortConnector(connector); return; }
                // MiniMax's ready cue is emitted after recorder initialization, before capture.
                if (!"minimax".equals(OwnerVoiceBridge.readConfig(context).optString("mode")) &&
                    !"diagnostic".equals(OwnerVoiceBridge.readConfig(context).optString("mode"))) OwnerFeedback.readyCue();
                if (cancelled) { abortConnector(connector); return; }
                boolean handled = OwnerVoiceBridge.tryRun(connector);
                synchronized (LOCK) { lastResult = cancelled ? "CANCELLED" : handled ? "TURN_FINISHED_CHECK_RECEIPT" : "CONFIG_UNAVAILABLE"; }
            } catch (Exception e) {
                synchronized (LOCK) { lastResult = cancelled ? "CANCELLED" : "HEADLESS_ENTRY_FAILED"; }
            } finally {
                abortConnector(connector);
                if (wake != null && wake.isHeld()) wake.release();
                final Object previousProgram = program;
                if(retireSession(this)){
                OwnerFeedback.end(ticket); OwnerFeedback.exitCue(ticket);
                synchronized(LOCK){if(requestedMode!=null&&active==null&&generation==ticket)switchMode(requestedMode.booleanValue());}
                if (previousProgram != null && (!CharacterMode.managed() || CharacterMode.legacy())) new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        synchronized (LOCK) {
                            if (active != null || generation != ticket || modeSwitching || (CharacterMode.managed()&&!CharacterMode.legacy())) return;
                            try { previousProgram.getClass().getMethod("lt").invoke(previousProgram); }
                            catch (Exception ignored) {}
                        }
                    }
                });
                }
            }
        }
    }
    /** A cleanup error is not permission to start another native recorder or stock wake. */
    private static boolean retireSession(Session session){synchronized(LOCK){
        if(active!=session)return false;
        if(!OwnerVoiceBridge.resourcesReleased()){lastResult="AUDIO_RELEASE_UNCONFIRMED";return false;}
        session.ready=false;if("STARTING".equals(session.result))session.result=session.cancelled?"CANCELLED":lastResult;
        PASSIVE.ended(android.os.SystemClock.elapsedRealtime(),PassiveWakeGate.explicitEnd(session.result,session.cancelled));
        lastSession=session;active=null;WAKE.ending();wakeArmPending=false;return true;
    }}
    private static void abortConnector(Object connector) {
        if (connector != null) try { connector.getClass().getMethod("abort").invoke(connector); } catch (Exception ignored) {}
    }
    public static boolean tryButton() {return startOrCancel(false);}
    public static final class StartResult {
        public final boolean started; public final String sessionId;
        private StartResult(boolean started,String id){this.started=started;sessionId=id;}
    }
    public static StartResult startOnly(){synchronized(LOCK){
        if(active!=null)return new StartResult(false,"");
        boolean started=startOrCancel(false,true);
        return new StartResult(started,started?active.nativeSessionId:"");
    }}
    private static boolean startOrCancel(boolean voiceEvent){return startOrCancel(voiceEvent,false);}
    private static boolean startOrCancel(boolean voiceEvent,boolean startOnly) {return startOrCancel(voiceEvent,startOnly,voiceEvent?"ORIGINAL_DSP":"BUTTON_OR_CONTROL");}
    private static boolean startOrCancel(boolean voiceEvent,boolean startOnly,String source) {
        try {
            synchronized (LOCK) {
                if (active != null) { if(startOnly)return false; if(!voiceEvent)cancel(); return !voiceEvent; }
                if(!OwnerVoiceBridge.resourcesReleased()){lastResult="AUDIO_RELEASE_UNCONFIRMED";return false;}
                if(modeSwitching || (CharacterMode.managed()&&CharacterMode.legacy())){lastResult="LEGACY_MODE_SELECTED";return false;}
                Context context = OwnerVoiceBridge.nativeContext();
                if (!OwnerVoiceBridge.enabled(context)) { lastResult="OWNER_MODE_DISABLED"; return false; }
                Class<?> communication = Class.forName("com.smarttoy.f");
                if (Boolean.TRUE.equals(communication.getMethod("ce").invoke(null)) || Boolean.TRUE.equals(communication.getMethod("cf").invoke(null))) { lastResult="FACTORY_OR_POSTTEST_MODE"; return false; }
                Object setup = Class.forName("com.smarttoy.embedded.managers.f").getMethod("jK").invoke(null);
                if (!Boolean.TRUE.equals(setup.getClass().getMethod("jL").invoke(setup))) { lastResult="SETUP_NOT_COMPLETE"; return false; }
                if(Boolean.TRUE.equals(Class.forName("com.smarttoy.embedded.receivers.InterruptReceiver").getMethod("fV").invoke(null))){lastResult="SHUTDOWN_REQUESTED";return false;}
                boolean initialSleep=sleeping();if(voiceEvent&&initialSleep){lastResult="SLEEP_SELECTED";return false;}
                Session session = new Session(context);session.initiallySleeping=initialSleep;session.activationSource=source;session.thread = new Thread(session, "SP001-OwnerTurn");
                active = session; lastResult = "STARTING";
                WAKE.begin();wakeArmPending=false;
                OwnerFeedback.begin(session.ticket);
                try { session.thread.start(); } catch (RuntimeException e) { active = null; OwnerFeedback.end(session.ticket);scheduleWakeArm();throw e; }
                return true;
            }
        } catch (Exception e) { synchronized(LOCK) { lastResult="BUTTON_ENTRY_FAILED"; } return false; }
    }
    public static void cancel() {
        synchronized (LOCK) {
            if (active == null) return;
            active.ready=false;active.requestedMode=null; active.cancelled = true; abortConnector(active.connector); active.thread.interrupt();
        }
    }
    static void bindNativeSession(Object connector,String sessionId) {
        synchronized(LOCK){if(active==null)return;if(active.connector!=connector||active.cancelled||!active.nativeSessionId.equals(sessionId))throw new IllegalStateException("STALE_SESSION");}
    }
    static String nativeSessionId(Object connector){synchronized(LOCK){
        if(active==null)return java.util.UUID.randomUUID().toString();
        if(active.connector!=connector||active.cancelled)throw new IllegalStateException("STALE_SESSION");
        return active.nativeSessionId;
    }}
    static void captureReady(Object connector,String id,int window,long at){synchronized(LOCK){
        if(active!=null&&active.connector==connector&&!active.cancelled&&active.nativeSessionId.equals(id)){
            active.ready=true;active.window=window;active.readyAt=at;
        }
    }}
    static void captureNotReady(Object connector,String id){synchronized(LOCK){
        if(active!=null&&active.connector==connector&&active.nativeSessionId.equals(id))active.ready=false;
    }}
    static void completed(Object connector,String id,ConversationSession.Result result,int[] calls){synchronized(LOCK){
        if(active==null||active.connector!=connector||!active.nativeSessionId.equals(id))return;
        active.ready=false;active.result=result.code;active.asr=calls[0];active.llm=calls[1];active.tts=calls[2];
        active.completed=result.completedTurns;active.interrupted=result.interruptedTurns;
    }}
    static JSONObject sessionStatus(String id)throws Exception{synchronized(LOCK){
        Session s=active!=null&&active.nativeSessionId.equals(id)?active:lastSession!=null&&lastSession.nativeSessionId.equals(id)?lastSession:null;
        JSONObject data=new JSONObject().put("sessionId",id).put("result",s==null?"STALE_SESSION":s.result);
        if(s!=null){boolean busy=active==s;data.put("busy",busy).put("released",!busy).put("ready",busy&&!s.cancelled&&s.ready)
            .put("activationSource",s.activationSource).put("passiveIgnored",PASSIVE.ignored()).put("windowId",s.window).put("readyMonotonicMs",s.readyAt).put("nowMonotonicMs",android.os.SystemClock.elapsedRealtime())
            .put("asr",s.asr).put("llm",s.llm).put("tts",s.tts).put("completed",s.completed).put("interrupted",s.interrupted);}
        return data;
    }}
    /** Cancel only the named in-process session. Never starts recording, even if it just ended. */
    public static boolean cancelSession(String expectedSessionId) {
        synchronized(LOCK){
            if(expectedSessionId==null||!expectedSessionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||
                active==null||!expectedSessionId.equals(active.nativeSessionId))return false;
            cancel();return true;
        }
    }
    static JSONObject status() throws Exception {
        synchronized (LOCK) { return new JSONObject().put("busy", active != null).put("sessionId",active==null?"":active.nativeSessionId).put("lastResult", lastResult).put("characterMode",CharacterMode.name()).put("modeSwitching",modeSwitching); }
    }
}
