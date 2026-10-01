package org.sp001.core;
import java.io.IOException;import java.lang.reflect.*;
/** Changes only the existing original VOL key, verified in cache and persistent storage. */
public final class LegacyVolumePreference {
 public interface Ports {String cached()throws Exception;String durable()throws Exception;void set(String value)throws Exception;}
 private final Ports ports;private final String beforeCache,beforeDisk;
 public LegacyVolumePreference(Ports p)throws Exception{if(p==null)throw new IllegalArgumentException("VOLUME_PORTS");ports=p;beforeCache=p.cached();beforeDisk=p.durable();if(!java.util.Objects.equals(beforeCache,beforeDisk))throw new IOException("STOCK_VOLUME_ALREADY_INCONSISTENT");}
 public static String fraction(int level,int maximum){if(maximum<1||level<0||level>maximum)throw new IllegalArgumentException("VOLUME_RANGE");float v=level==0?0f:level==maximum?1f:(level+.25f)/maximum;return Float.toString(v);}
 public void save(int level,int maximum)throws Exception{String value=fraction(level,maximum);ports.set(value);if(!value.equals(ports.cached())||!value.equals(ports.durable()))throw new IOException("STOCK_VOLUME_NOT_PERSISTED");}
 public void restore()throws Exception{ports.set(beforeCache);if(!java.util.Objects.equals(beforeCache,ports.cached())||!java.util.Objects.equals(beforeDisk,ports.durable()))throw new IOException("STOCK_VOLUME_ROLLBACK_FAILED");}
 static LegacyVolumePreference original()throws Exception{
  final Class<?> u=Class.forName("com.smarttoy.u"),db=Class.forName("com.greendao.database.UserDefaults");
  final Object defaults=db.getMethod("getInstance").invoke(null);final String key=(String)Class.forName("com.smarttoy.f").getField("iC").get(null);
  if(!"VOL".equals(key))throw new IOException("STOCK_VOLUME_KEY_MISMATCH");
  return new LegacyVolumePreference(new Ports(){
   public String cached()throws Exception{return Boolean.TRUE.equals(u.getMethod("containsKey",String.class).invoke(null,key))?(String)u.getMethod("getString",String.class).invoke(null,key):null;}
   public String durable()throws Exception{return (String)db.getMethod("get",String.class).invoke(defaults,key);}
   public void set(String value)throws Exception{if(value==null)u.getMethod("remove",String.class).invoke(null,key);else u.getMethod("putString",String.class,String.class).invoke(null,key,value);}
  });
 }
}
