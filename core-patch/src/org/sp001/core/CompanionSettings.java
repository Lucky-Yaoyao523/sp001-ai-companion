package org.sp001.core;
import java.io.IOException;
/** Two finite persisted preferences, never credentials or microphone consent. */
public final class CompanionSettings {
 public interface Store {boolean get(String key,boolean fallback);boolean save(String key,boolean value);}
 private final Store store;
 public CompanionSettings(Store value){if(value==null)throw new IllegalArgumentException("BEHAVIOR_STORE");store=value;}
 private static void key(String key){if(!"initiative".equals(key)&&!"expressive".equals(key))throw new IllegalArgumentException("BEHAVIOR_SETTING");}
 public boolean get(String key){key(key);return store.get(key,true);}
 public void set(String key,boolean value)throws IOException{key(key);if(!store.save(key,value)||store.get(key,!value)!=value)throw new IOException("BEHAVIOR_SETTING_NOT_CONFIRMED");}
}
