package org.sp001.core;

import java.io.IOException;
import java.util.Arrays;

/** Pure, bounded format and speech helpers; contains no I/O or credentials. */
public final class MiniMaxCodec {
    private MiniMaxCodec() {}
    public static String origin(String region) throws IOException {
        if ("global".equals(region)) return "https://api.minimax.io";
        if ("china".equals(region)) return "https://api.minimaxi.com";
        throw new IOException("MINIMAX_REGION_INVALID");
    }
    public static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9._-]{20,1024}");
    }
    public static boolean validVoice(String id) {
        return id != null && id.matches("[A-Za-z0-9_() -]{1,100}");
    }
    public static byte[] decodePcm(String hex) throws IOException {
        if (hex == null || hex.length() < 4 || hex.length() > 3840000 || hex.length() % 4 != 0)
            throw new IOException("MINIMAX_AUDIO_LENGTH_INVALID");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int a = Character.digit(hex.charAt(i * 2), 16), b = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) throw new IOException("MINIMAX_AUDIO_HEX_INVALID");
            out[i] = (byte)((a << 4) | b);
        }
        if (out.length >= 4 && ((out[0]=='R' && out[1]=='I' && out[2]=='F' && out[3]=='F') ||
                (out[0]=='O' && out[1]=='g' && out[2]=='g' && out[3]=='S') ||
                (out[0]=='I' && out[1]=='D' && out[2]=='3')))
            throw new IOException("MINIMAX_EXPECTED_RAW_PCM");
        return out;
    }
    /** Never read a model's internal reasoning aloud, including malformed tags. */
    public static String speech(String text) throws IOException {return speech(text,500);}
    /** Narrative assembly can opt into the existing bounded chapter budget; TTS chunks keep500. */
    public static String speech(String text,int maximum) throws IOException {
        if(maximum<500||maximum>1250)throw new IOException("MINIMAX_SPEECH_LIMIT_INVALID");
        if (text == null || text.length() > 32768) throw new IOException("MINIMAX_TEXT_INVALID");
        String value = text.replaceAll("(?is)<think>.*?</think>", "").trim();
        if (value.toLowerCase(java.util.Locale.ROOT).contains("<think") ||
                value.toLowerCase(java.util.Locale.ROOT).contains("</think"))
            throw new IOException("MINIMAX_REASONING_NOT_SPEECH");
        value = value.replaceAll("[*#`]+", "").trim();
        if (value.length() == 0 || value.length() > maximum || value.contains("http://") || value.contains("https://"))
            throw new IOException("MINIMAX_SPEECH_BOUND");
        return value;
    }
    public static boolean stopCommand(String text) {
        if (text == null) return false;
        String t = text.replaceAll("[\\s\\p{Punct}，。！？、]", "");
        return t.equals("停止对话") || t.equals("结束聊天") || t.equals("去休息吧") ||
                t.equals("再见") || t.equals("再見") || t.equals("不聊了") || t.equals("拜拜") ||
                t.equals("停止") || t.equals("结束对话") || t.equals("結束對話") || t.equals("去休息") ||
                t.equals("好了不聊了") || t.equals("停止對話") || t.equals("結束聊天");
    }
    public static int peak(byte[] pcm) {
        if (pcm == null || pcm.length % 2 != 0) throw new IllegalArgumentException("PCM_ALIGNMENT");
        int peak = 0;
        for (int i=0;i<pcm.length;i+=2) peak=Math.max(peak,Math.abs((short)((pcm[i]&255)|((pcm[i+1]&255)<<8))));
        return peak;
    }
    public static double rms(byte[] pcm) {
        if (pcm == null || pcm.length % 2 != 0) throw new IllegalArgumentException("PCM_ALIGNMENT");
        double sum=0;
        for(int i=0;i<pcm.length;i+=2){int v=(short)((pcm[i]&255)|((pcm[i+1]&255)<<8));sum+=(double)v*v;}
        return Math.sqrt(sum/Math.max(1,pcm.length/2));
    }
    /** DC/low-frequency removal and conservative level control. Not a claimed acoustic-noise cure. */
    public static byte[] prepareInput(byte[] pcm) throws IOException {
        if(pcm==null||pcm.length<3200||pcm.length>960000||pcm.length%2!=0)throw new IOException("MINIMAX_INPUT_BOUND");
        double[] filtered=new double[pcm.length/2]; double previousX=0,previousY=0,sum=0,peak=0;
        for(int i=0;i<filtered.length;i++){
            int x=(short)((pcm[2*i]&255)|((pcm[2*i+1]&255)<<8));
            double y=x-previousX+0.97*previousY;previousX=x;previousY=y;filtered[i]=y;
            sum+=y*y;peak=Math.max(peak,Math.abs(y));
        }
        double rms=Math.sqrt(sum/filtered.length);
        if(rms<32){Arrays.fill(filtered,0);throw new IOException("NO_VALID_AUDIO");}
        double gain=Math.min(10.0,Math.min(4200.0/rms,28000.0/Math.max(1,peak)));
        byte[] out=new byte[pcm.length];
        for(int i=0;i<filtered.length;i++){
            int sample=(int)Math.round(Math.max(-28000,Math.min(28000,filtered[i]*gain)));
            out[2*i]=(byte)sample;out[2*i+1]=(byte)(sample>>8);
        }
        Arrays.fill(filtered,0);return out;
    }
}
