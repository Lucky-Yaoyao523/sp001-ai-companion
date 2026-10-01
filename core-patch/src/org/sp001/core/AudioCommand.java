package org.sp001.core;

/** Strict complete local requests. No LLM, audio/device side effects or quoted instructions. */
public final class AudioCommand {
    public static final int NONE=0,LOUDER=1,QUIETER=2,MIC_MORE=3,MIC_NORMAL=4,MIC_LESS=5;
    private AudioCommand(){}
    public static int parse(String heard){
        if(heard==null||heard.length()>120)return NONE;
        String s=heard.trim().replaceAll("[\\s，。！？、,.!?]","")
            .replace('請','请').replace('聲','声').replace('調','调').replace('點','点')
            .replace('說','说').replace('話','话').replace('俠','侠').replace('嗎','吗')
            .replace('麥','麦').replace('風','风').replace('靈','灵').replace('復','复')
            .replace('標','标').replace('準','准').replace('開','开').replace('幫','帮')
            .replace('將','将').replace('煩','烦');
        // Quotes, negations, alternatives, compound actions and conditionals are
        // intentionally left intact so the anchored grammar below rejects them.
        s=s.replaceFirst("^(?:蜘蛛侠|小蜘蛛)","");
        s=s.replaceFirst("^(?:请你|请|麻烦你|麻烦|你能不能|能不能|你可以|可以|你能|能|帮我|给我)","");
        s=s.replaceFirst("^(?:你的|你|把|将)","").replaceFirst("^把","").replaceFirst("^你的","");
        s=s.replaceFirst("(?:好吗|好不好|可以吗|行吗|嘛|吗|啊|呀|吧|啦)+$","");
        String amount="(?:一点点|一点儿|一点|一些|点儿|点|些)?";
        if(s.matches("(?:(?:声音|音量)(?:再)?(?:调大|调高|开大|提高|大)|(?:再)?(?:说话|说)?(?:再)?大声|(?:说话|说)?大点声|(?:调大|调高|开大|提高)(?:声音|音量))"+amount))return LOUDER;
        if(s.matches("(?:(?:声音|音量)(?:再)?(?:调小|调低|开小|降低|小)|(?:再)?(?:说话|说)?(?:再)?小声|(?:说话|说)?小点声|(?:调小|调低|开小|降低)(?:声音|音量))"+amount))return QUIETER;
        if(s.matches("(?:(?:收音|麦克风)(?:再)?(?:调)?灵敏(?:一点点|一点儿|一点|一些|点儿|点|些)|提高(?:收音|麦克风)灵敏度)"))return MIC_MORE;
        if(s.matches("(?:收音|麦克风)恢复标准"))return MIC_NORMAL;
        if(s.matches("降低(?:收音|麦克风)灵敏度"))return MIC_LESS;
        return NONE;
    }
}
