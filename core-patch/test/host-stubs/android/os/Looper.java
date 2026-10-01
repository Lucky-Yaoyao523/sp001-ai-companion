package android.os;
/** Host-only: no Android event loop is executed by cancel-only tests. */
public final class Looper {
    private static final Looper MAIN=new Looper();
    public static Looper getMainLooper(){return MAIN;}
}
