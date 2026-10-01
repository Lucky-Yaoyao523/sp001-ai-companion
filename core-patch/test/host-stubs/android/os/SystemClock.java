package android.os;
/** Host tests only. Never package this class in the Android application. */
public final class SystemClock {
 private static volatile long offset;
 public static void advanceForTest(long ms){offset+=ms;}
 public static void resetForTest(){offset=0;}
 public static long elapsedRealtime(){return System.nanoTime()/1000000L+offset;}
 public static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
