package org.sp001.core;
/** Original owner/stock presets only. A request is not proof of physical rendering. */
public final class CompanionVisualPlan {
 private CompanionVisualPlan(){}
 public static final long MAX_PHASE_MS=600000L;
 public static String effectivePhase(String phase,boolean expressive,boolean explicitEye){
  if("IDLE".equals(phase)||"ERROR".equals(phase))return phase;
  // A direct smile/surprise request stays visible through its acknowledgement,
  // but never carries into the next listening phase or overrides disabled preferences.
  if(explicitEye&&("THINKING".equals(phase)||"SYNTHESIZING".equals(phase)||"SPEAKING".equals(phase)))return "SPEAKING";
  return expressive?phase:"PLAIN";
 }
 public static String eye(String phase,String emotion,long elapsed){
  if(elapsed<0)throw new IllegalArgumentException("VISUAL_CLOCK");
  if("IDLE".equals(phase))return "IDLE";
  if("LISTENING".equals(phase))return elapsed%4000>=3800?"SQUINT":"NEUTRAL";
  if("THINKING".equals(phase)||"TRANSCRIBING".equals(phase)||"SYNTHESIZING".equals(phase)||"PREPARING".equals(phase))return elapsed%2400<1800?"SQUINT":"NEUTRAL";
  if("SPEAKING".equals(phase)){
   String base=ReplyExpression.eye(emotion);
   if(elapsed%4400>=4200)return "NEUTRAL".equals(base)?"SQUINT":"NEUTRAL";
   return base;
  }
  if("MISHEARD".equals(phase)||"TURN_FAILED".equals(phase))return "SQUINT";
  return "NEUTRAL";
 }
 public static long nextDelay(String phase,long elapsed){
  if(elapsed<0||elapsed>=MAX_PHASE_MS)return 0;
  return "LISTENING".equals(phase)||"THINKING".equals(phase)||"TRANSCRIBING".equals(phase)||"SYNTHESIZING".equals(phase)||"PREPARING".equals(phase)||"SPEAKING".equals(phase)?200:0;
 }
}
