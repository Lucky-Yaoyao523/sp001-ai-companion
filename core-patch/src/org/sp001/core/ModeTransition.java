package org.sp001.core;

/** One production transaction used by Android and fault-injection tests.
 * Persist only after the local prompt and target activation succeed. Roll back on every error.
 */
public final class ModeTransition {
    public interface Ports {
        boolean current();
        void prepare() throws Exception;
        void stage(boolean legacy) throws Exception;
        void announce(boolean legacy) throws Exception;
        void activate(boolean legacy) throws Exception;
        void commit(boolean legacy) throws Exception;
        void finish();
    }
    public static final class Result {
        public boolean success,rollbackSucceeded;
        public String error="",rollbackError="";
    }
    private ModeTransition(){}
    private static String code(Exception error){
        String message=error.getMessage();
        return message!=null&&message.matches("[A-Z0-9_]{1,80}")?message:error.getClass().getSimpleName();
    }
    public static Result run(Ports p,boolean target){
        if(p==null)throw new IllegalArgumentException("MODE_PORTS");
        Result r=new Result();boolean before=p.current(),prepared=false,commitAttempted=false;
        try{
            // prepare() can stop an activity before it throws; rollback is required then too.
            prepared=true;p.prepare();p.stage(target);p.announce(target);p.activate(target);
            commitAttempted=true;p.commit(target);r.success=true;
        }catch(Exception e){
            r.error=code(e);
            // Independent recovery steps: a failed activity recovery must never
            // prevent restoration of the persisted mode after a partial commit.
            try{p.stage(before);}catch(Exception rollback){r.rollbackError=code(rollback);}
            try{if(prepared){p.prepare();p.activate(before);}}catch(Exception rollback){r.rollbackError=code(rollback);}
            try{if(commitAttempted)p.commit(before);}catch(Exception rollback){r.rollbackError=code(rollback);}
            r.rollbackSucceeded=r.rollbackError.length()==0;
        }finally{p.finish();}
        return r;
    }
}
