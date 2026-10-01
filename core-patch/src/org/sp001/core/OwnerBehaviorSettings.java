package org.sp001.core;
import android.content.Context;
import android.content.SharedPreferences;
final class OwnerBehaviorSettings {
 private OwnerBehaviorSettings(){}
 static CompanionSettings load()throws Exception{
  final SharedPreferences prefs=OwnerVoiceBridge.nativeContext().getSharedPreferences("sp001-behavior-settings",Context.MODE_PRIVATE);
  return new CompanionSettings(new CompanionSettings.Store(){
   public boolean get(String key,boolean fallback){return prefs.getBoolean(key,fallback);}
   public boolean save(String key,boolean value){return prefs.edit().putBoolean(key,value).commit();}
  });
 }
}
