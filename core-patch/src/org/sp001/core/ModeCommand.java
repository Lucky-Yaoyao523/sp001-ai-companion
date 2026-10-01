package org.sp001.core;

/** Full explicit utterances only; quoted mentions and incidental English do not change mode. */
public final class ModeCommand {
    private ModeCommand(){}
    public static Boolean target(String heard){
        if(heard==null)return null;
        String t=heard.trim().replaceAll("[\\s，。！？、,.!?]","");
        if(t.equals("切换到英文模式")||t.equals("切換到英文模式")||t.equals("进入英文模式")||t.equals("進入英文模式")||t.equals("切换到英文原版")||t.equals("切換到英文原版")||t.equals("进入原版模式")||t.equals("進入原版模式")||t.equals("进入英文原版")||t.equals("切换到原版模式")||t.equals("切換到原版模式"))return Boolean.TRUE;
        if(t.equals("回到中文模式")||t.equals("进入中文模式")||t.equals("進入中文模式")||t.equals("切换到中文模式")||t.equals("切換到中文模式")||t.equals("退出原版模式"))return Boolean.FALSE;
        return null;
    }
}
