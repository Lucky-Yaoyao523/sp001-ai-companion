package org.sp001.core;

/** The stock Application is a plain singleton, NOT an Android Context. */
public final class OwnerRuntime {
    private OwnerRuntime() {}
    public static <T> T contextFrom(Object singleton, Class<T> contextType) throws Exception {
        if (singleton == null || contextType == null) throw new IllegalArgumentException("CONTEXT_SOURCE_MISSING");
        Object result = singleton.getClass().getMethod("getApplicationContext").invoke(singleton);
        if (!contextType.isInstance(result)) throw new IllegalStateException("NATIVE_CONTEXT_UNAVAILABLE");
        return contextType.cast(result);
    }
    public static Object application() throws Exception {
        return Class.forName("com.smarttoy.android.Application").getMethod("getInstance").invoke(null);
    }
}
