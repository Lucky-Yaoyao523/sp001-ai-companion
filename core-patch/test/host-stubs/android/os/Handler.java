package android.os;
/** Host-only constructor substitute. Scheduling in this narrow test is an error. */
public class Handler {
    public Handler(Looper ignored){}
    public boolean post(Runnable ignored){throw new AssertionError("UNEXPECTED_UI_ACTION");}
    public boolean postDelayed(Runnable ignored,long delay){throw new AssertionError("UNEXPECTED_UI_ACTION");}
    public void removeCallbacks(Runnable ignored){throw new AssertionError("UNEXPECTED_UI_ACTION");}
}
