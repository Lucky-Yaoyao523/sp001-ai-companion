package org.sp001.core;
import android.util.AtomicFile;import java.io.*;
/** One process-wide atomic store. Current speaker selection belongs to a conversation, not a microphone guess. */
final class OwnerCompanionMemory {
 private static final Object LOCK=new Object();
 static CompanionMemory open()throws Exception{
  final File path=new File(OwnerVoiceBridge.nativeContext().getFilesDir(),"sp001-companion-memory.json");
  return new CompanionMemory(new CompanionMemory.VersionedStore(){
   public String read()throws Exception{synchronized(LOCK){AtomicFile f=new AtomicFile(path);if(!path.exists()&&!new File(path+".bak").exists())return null;FileInputStream in=f.openRead();try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[2048];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>CompanionMemory.MAX_BYTES)throw new IOException("MEMORY_BOUND");bytes.write(block,0,n);}return new String(bytes.toByteArray(),"UTF-8");}finally{in.close();}}}
   public void writeExpected(String expected,String value)throws Exception{synchronized(LOCK){String actual=read();if(!java.util.Objects.equals(expected,actual))throw new IOException("MEMORY_STORE_CONFLICT");write(value);}}
   public void write(String value)throws Exception{synchronized(LOCK){AtomicFile f=new AtomicFile(path);FileOutputStream out=null;try{out=f.startWrite();out.write(value.getBytes("UTF-8"));f.finishWrite(out);out=null;}finally{if(out!=null)f.failWrite(out);}}}
  });
 }
}
