package org.sp001.core;

/** Conservative whole-utterance departure requests, not quoted/conditional goodbyes. */
public final class ClosingIntent {
    private ClosingIntent() {}
    public static boolean afterReply(String text) {
        if(text==null||text.length()>80)return false;
        String s=text.trim().replaceAll("[\\s，。！？、,.!?]","");
        s=s.replaceFirst("^(?:蜘蛛侠|蜘蛛俠|小蜘蛛)","");
        s=s.replaceFirst("^(?:好的|好吧|好)","").replaceFirst("^那","");
        return s.matches("(?:(?:我|我们|我們)(?:要|先|准备|準備)?(?:去睡觉|去睡覺|睡觉|睡覺|去休息|走|离开|離開)(?:了|啦)?|(?:今天)?(?:先)?(?:不聊了|聊到这里|聊到這裡|到这里吧|到這裡吧)|(?:拜拜|再见|再見|晚安)(?:啦|了|吧)?)");
    }
}
