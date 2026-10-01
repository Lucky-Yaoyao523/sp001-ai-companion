package org.sp001.core;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The same LLM reply carries one optional allowlisted mood; tags are never spoken. */
public final class ReplyExpression {
    private static final Pattern PREFIX=Pattern.compile("^\\[([a-z]{2,16})\\]\\s*");
    public final String text,emotion,eye;
    public final boolean endSession;
    private ReplyExpression(String t,String e,boolean end){text=t;emotion=e;eye=eye(e);endSession=end;}
    public static String eye(String emotion){
        if("happy".equals(emotion)||"playful".equals(emotion))return "SMILE";
        if("surprised".equals(emotion))return "SURPRISED";
        if("curious".equals(emotion))return "SQUINT";
        return "NEUTRAL";
    }
    public static ReplyExpression parse(String raw)throws IOException {return parse(raw,500);}
    static ReplyExpression parse(String raw,int maximum)throws IOException {
        String value=MiniMaxCodec.speech(raw,maximum+50);String mood="neutral";
        Matcher m=PREFIX.matcher(value);
        if(m.find()){
            String e=m.group(1);
            if(e.matches("neutral|happy|playful|surprised|curious|caring"))mood=e;
            value=value.substring(m.end()).trim();
        }
        boolean end=value.matches("(?s).*\\[end\\][。.!！\\s]*$");
        if(end)value=value.replaceFirst("\\[end\\][。.!！\\s]*$","").trim();
        value=value.replaceAll("\\[(?:neutral|happy|playful|surprised|curious|caring|end)\\]","").trim();
        // Closing words without a tag still end; a literal tag in an explanation does not.
        boolean closing=closingSpeech(value)||(end&&taggedClosingSpeech(value));
        return new ReplyExpression(MiniMaxCodec.speech(value,maximum),mood,closing);
    }
    static boolean closingSpeech(String value){
        if(value.length()>140||value.matches("(?s).*(?:[\"'“”‘’《》]|他说|她说|它说|故事|意思|标记|标签|不要|别说|不说|不是|如果|例如|比如).*"))return false;
        return value.matches("(?s)(?:.*[。！!？，,])?(?:好|好的|好呀|好啦)?(?:那|那就)?(?:我们|咱们)?(?:拜拜|再见|再見|晚安|下次再聊|明天见)(?:啦|了|咯|吧|哦|喽)?(?:[，,](?:小伙伴|朋友|小英雄|宝贝))?[。！？!～~\\s]*");
    }
    private static boolean taggedClosingSpeech(String value){
        if(value.length()>140||value.matches("(?s).*(?:[\"'“”‘’《》]|他说|她说|它说|故事|意思|标记|标签|不要|别说|不说|不是|如果|例如|比如|吗|？|\\?).*"))return false;
        return value.matches("(?s).*(?:做个好梦|做個好夢|早点休息|早點休息|下次见|下次見|聊到这里|聊到這裡|明天见|明天見|回头见|回頭見|睡个好觉|睡個好覺|好好休息).*");
    }
}
