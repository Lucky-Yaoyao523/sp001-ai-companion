package org.sp001.core;
/** Public edition has no BLE remote capture, credential provisioning, or session-start endpoint. */
public final class OwnerControlBridge {
 private OwnerControlBridge(){}
 public static boolean tryHandle(String text,String account){
  // Consume the reserved namespace without performing actions. Other legacy traffic is untouched.
  return text!=null&&text.contains("sp001OwnerVoice");
 }
}
