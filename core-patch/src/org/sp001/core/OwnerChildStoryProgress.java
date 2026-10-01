package org.sp001.core;
import android.util.AtomicFile;import java.io.*;
/** Parent-configured story checkpoint only: canonical IDs and counts, no recording/transcript. */
final class OwnerChildStoryProgress {
 private static final Object LOCK=new Object();
 static ChildStorySession.Store open()throws Exception{
  final File path=new File(OwnerVoiceBridge.nativeContext().getFilesDir(),"sp001-child-story-progress.json");
  return new ChildStorySession.Store(){
   public String read()throws Exception{synchronized(LOCK){if(!path.isFile()&&!new File(path+".bak").isFile())return null;FileInputStream in=new AtomicFile(path).openRead();try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[512];int n;while((n=in.read(block))!=-1){if(bytes.size()+n>2048)throw new IOException("CHILD_PROGRESS_BOUND");bytes.write(block,0,n);}return new String(bytes.toByteArray(),"UTF-8");}finally{in.close();}}}
   public void write(String value)throws Exception{synchronized(LOCK){byte[] bytes=value.getBytes("UTF-8");if(bytes.length>2048)throw new IOException("CHILD_PROGRESS_BOUND");AtomicFile f=new AtomicFile(path);FileOutputStream out=null;try{out=f.startWrite();out.write(bytes);f.finishWrite(out);out=null;}finally{if(out!=null)f.failWrite(out);}}}
  };
 }
}
