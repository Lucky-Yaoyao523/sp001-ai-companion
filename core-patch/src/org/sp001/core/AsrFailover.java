package org.sp001.core;
import java.io.IOException;

/** One primary attempt, then one existing-account backup per utterance. No retry loop. */
final class AsrFailover {
 interface Ports {
  void check()throws Exception;
  String primary(byte[] pcm)throws Exception;
  void cancelPrimary();
  String backup(byte[] pcm)throws Exception;
 }
 private final Ports ports;
 private boolean backupMode;
 private String primaryFailure="",backupFailure="";
 private int backupCalls;
 AsrFailover(Ports ports){if(ports==null)throw new IllegalArgumentException("ASR_PORTS");this.ports=ports;}
 static boolean primaryUnavailable(String code){
  return code!=null&&(code.matches("ASR_(OPEN_TIMEOUT|TASK_START_TIMEOUT|RESULT_WAIT_TIMEOUT|PROVIDER_RESULT_TIMEOUT|TRANSPORT_FAILED|TRANSPORT_CLOSED|STANDBY_EXPIRED|RUN_SEND_FAILED|AUDIO_SEND_FAILED|FINISH_SEND_FAILED|INPUT_OVERFLOW|INCOMPLETE_TRANSCRIPT)")||code.matches("ASR_HTTP_(429|5[0-9]{2})"));
 }
 static boolean backupUnavailable(String code){
  return code!=null&&(code.matches("HTTP_(TRANSFER_FAILED|TIME_LIMIT|NETWORK_TIMEOUT|CAPACITY_EXHAUSTED|WORKER_UNAVAILABLE)")||code.matches("MINIMAX_HTTP_(429|5[0-9]{2})"));
 }
 String recognize(byte[] pcm)throws Exception{
  ports.check();
  if(!backupMode){
   try{String text=ports.primary(pcm);ports.check();return text;}
   catch(IOException failure){ports.check();if(!primaryUnavailable(failure.getMessage()))throw failure;
    primaryFailure=failure.getMessage();
    // A missing final sentence belongs to this utterance; provider outages can
    // switch the remaining conversation to backup.
    if(!"ASR_INCOMPLETE_TRANSCRIPT".equals(primaryFailure))backupMode=true;
    ports.cancelPrimary();}
  }
  ports.check();backupCalls++;backupFailure="";
  try{String text=ports.backup(pcm);ports.check();return text;}
  catch(IOException failure){ports.check();String code=failure.getMessage();backupFailure=code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"ASR_BACKUP_FAILED";
   if(backupUnavailable(code))throw new IOException("ASR_BACKUP_UNAVAILABLE",failure);throw failure;}
 }
 boolean usingBackup(){return backupMode;}
 String primaryFailure(){return primaryFailure;}
 String backupFailure(){return backupFailure;}
 int backupCalls(){return backupCalls;}
}
