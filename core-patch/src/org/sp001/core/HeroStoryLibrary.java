package org.sp001.core;
/** Curated story beats, not a model-generated biography or raw long-term memory. */
public final class HeroStoryLibrary {
 public static final String VERSION="sailuo-story-v1";
 public static final class Episode {
  public final String id,series,title,source,anchors,beats,skill;
  Episode(String id,String series,String title,String source,String anchors,String beats,String skill){this.id=id;this.series=series;this.title=title.replace("小蛛","蜘蛛侠");this.source=source;this.anchors=anchors.replace("小蛛","蜘蛛侠");this.beats=beats.replace("小蛛","蜘蛛侠");this.skill=skill;}
  public String prompt(){return "故事计划版本="+VERSION+"；系列="+series+"；小章="+title+"；来源="+source+"。原著锚点="+anchors+"。本章叙事顺序="+beats+"。成长重点="+skill+"。改写的对话、试练细节须是适龄新编，不冒充原著原话。三国讲三国演义，不把小说情节当确定史实；蜘蛛侠是讲述者。不要拿现代儿童改写本照抄。";}
 }
 private static final Episode[] E={
  new Episode("journey-0","journey","悟空学本领：先试一小步","西游记第二回适龄改编","孙悟空向菩提祖师学习，并非生来就会筋斗云；魔法只在故事里存在","进师门兴奋却发现不懂的很多；先听清一个步骤；第一次试练没有如愿且有小笑点；主动问具体哪里没懂；换方法练习；小本领有进步并帮助师兄","愿意起步、具体请教、练习而非立刻无敌"),
  new Episode("journey-1","journey","悟空学本领：换一种办法","西游记第二回适龄改编","菩提祖师因悟空的动作特点传授筋斗云；不是所有人的方法都相同","回顾一句前章；练得用力却不顺；停下来观察自己擅长什么；师父提示适合他的办法；悟空重新尝试终于掌握；不炫耀，珍惜所学","观察、调整策略、接受帮助"),
  new Episode("journey-2","journey","花果山的新任务","依西游角色创作的番外，非原著章节","孙悟空和小猴们住花果山；本章新编合作故事不冒充西游记正文","小猴的果篮散了；悟空想一口气全做好却顾不过来；请小猴分别找路和分类；一只小猴担心出错；给它一小份可尝试任务；大家合作收好果篮","善良帮助、分工、允许第一次不熟练"),
  new Episode("kingdoms-0","kingdoms","刘备访贤：没有见到也不乱来","三国演义第三十八回相关情节的适龄改编","刘备请诸葛亮出山，尊重人才；三顾不是强迫别人答应","想解决难题需要新本领；前两次未见到人有失望；整理真正要请教的问题；第三次耐心等候；认真倾听；双方讨论如何帮助百姓","坚持但尊重别人意愿、准备问题、礼貌求助"),
  new Episode("kingdoms-1","kingdoms","草船借箭：准备不是干等","三国演义第四十六回适龄改编","诸葛亮、鲁肃、草船和江雾；小说故事不是儿童可模仿的真实试验","接到难题不急着逞强；观察天气和江面；准备草把和船只，请伙伴配合；鲁肃紧张时解释计划；雾中完成任务；回程感谢伙伴，理解观察和准备","用脑思考、细心准备、合作"),
  new Episode("kingdoms-2","kingdoms","军营里的小地图","依三国人物创作的学习番外，非史实","诸葛亮讲述者角色来自三国演义；送信小助手为原创，无儿童参战","小助手把路线画反；没有被羞辱；把地图拆成三处标记；先在安全场地试走；发现错误自己改；成功帮大家找到物资","发现错误、分解任务、安全试验"),
  new Episode("heroes-0","heroes","小蛛和伙伴的迷雾灯塔","本项目原创英雄故事","这是想象任务，伙伴是故事中的小英雄；小蛛仍是蜘蛛侠，不声称孩子现实做了动作","城市灯塔被迷雾遮住；第一束光照错方向；先观察风和路标；伙伴尝试一个新想法；两位伙伴配合发出幻想光束驱散迷雾；安全接回迷路的小动物","敢尝试、观察后改进、保护弱小"),
  new Episode("heroes-1","heroes","修好的彩虹桥","本项目原创英雄故事","桥和蛛丝为虚构场景，现实不攀高不跳桥","幻想桥缺一块；光靠力气不行；先让路人留在安全处；共同量一量和试一小段；失败的材料换个用法；搭好桥后感谢帮忙的人","先保证安全、耐心搭建、合作"),
  new Episode("heroes-2","heroes","第一次不会也能当英雄","本项目原创英雄故事","小蛛承认故事里也会犯错；不是向儿童保证努力必然获胜","小机器人学开信号灯总弄混；主角先示范一次；让机器人自己试最短一步；遇错停下看规律；再试终于能帮助朋友；大家庆祝具体进步而非排名","学习、自主尝试、善良耐心"),
  new Episode("heroes-3","heroes","停下来也是勇敢","本项目原创英雄故事","没有真实危险任务，不把鲁莽包装成勇敢","故事敌人设置了催人冲刺的假信号；主角有点怕；决定先停下询问；找到安全的绕行方式；用协作关闭装置而非伤害谁；明白敢说不也是本领","边界、求助、勇敢不等于冒险")
 };
 private HeroStoryLibrary(){}
 /** Addressing the device is not choosing a story subject. */
 public static String addressed(String raw){return ChildCompanionPolicy.normalize(raw).replaceFirst("^(?:嘿[，, ]*)?蜘蛛侠[，,：:！! ]*","");}
 /** Conservative retrieval only; a miss must never select an unrelated fallback. */
 public static Episode exactTitle(String raw){String s=addressed(raw);
  // A title mention never overrides negation, another topic, or added plot constraints.
  if(pureTitle(s,"草船借箭"))return get("kingdoms",1);
  if(pureTitle(s,"三顾茅庐")||pureTitle(s,"刘备访贤"))return get("kingdoms",0);
  if(pureTitle(s,"悟空拜师")||pureTitle(s,"悟空学本领"))return get("journey",0);
  if(pureTitle(s,"筋斗云"))return get("journey",1);
  for(Episode e:E)if(pureTitle(s,e.title))return e;return null;
 }
 private static boolean pureTitle(String request,String title){
  int at=request.indexOf(title);
  return at>=0&&request.indexOf(title,at+title.length())<0&&genericRequest(request.substring(0,at)+request.substring(at+title.length()));
 }
 public static boolean genericRequest(String raw){String s=addressed(raw);
  s=s.replaceAll("(?:不要英语|不教英语|只讲故事|只听故事|讲长一点|多讲一点|讲完整|完整的|完整|给伙伴|给朋友|给小伙伴|给我|帮我|我想|我要|我还想|我还要|想听|想|请|一个|一段|一下|一些|一点|个|段|讲|听|说|读|来|换|再|西游记|三国演义|三国|英雄|冒险|故事|睡前|安静的|轻柔的|的|吧|呀|啊)","");
  return s.replaceAll("[\\s\\p{Punct}，。！？、：；《》]","").isEmpty();
 }
 public static int count(String series){int n=0;for(Episode e:E)if(e.series.equals(series))n++;return n;}
 public static Episode get(String series,int index){int n=count(series);if(n==0||index<0)throw new IllegalArgumentException("STORY_PLAN");int wanted=index%n;for(Episode e:E)if(e.series.equals(series)&&wanted--==0)return e;throw new IllegalStateException("STORY_PLAN");}
 public static String series(String text){String s=addressed(text);if(s.matches("(?s).*(?:三国|诸葛|刘备|草船|关羽).*"))return "kingdoms";if(s.matches("(?s).*(?:西游|悟空|八戒|唐僧|筋斗云).*"))return "journey";if(s.matches("(?s).*(?:英雄|蜘蛛侠|小蛛|奥特曼|光波).*"))return "heroes";return "";}
 /** Retrieved source notes, never a command to change the requested topic or a canned reply. */
 public static String references(String raw){String s=addressed(raw);StringBuilder notes=new StringBuilder();
  if(s.contains("白骨精"))notes.append("【三打白骨精；西游记第二十七回】白虎岭妖怪想骗走唐僧。先后变作送饭女子、寻女老妇、寻妻女老翁。前两次留下假身逃脱，第三次才被悟空识破并消灭，显出白骨，骨上有白骨夫人字样。唐僧受假象与八戒言语影响，写贬书赶走悟空，悟空拜别后回花果山。本章在离别处结束；后面请悟空救师涉及黄袍怪，不能说白骨精复活又抓唐僧。适龄讲述省略击打细节，不改写成真实老人是坏人。\n");
  if(s.contains("过五关")||s.contains("斩六将")||s.contains("关羽"))notes.append("【关羽寻兄；三国演义第二十七回前后】徐州战败失散后，关羽为保护刘备两位夫人暂留曹操处；得知刘备消息后，封存赏赐，离开许昌寻找旧主。沿途因无通关文书受阻，经过东岭关、洛阳、汜水、荥阳、黄河渡口。六将包括孔秀、韩福、孟坦、卞喜、王植、秦琪，战斗简略带过即可，不宣扬不放行就打人。过黄河后继续寻刘备；古城与张飞重逢在后文，不是在古城失散才开始过关。不要编造曹操最后送来新通行证。小说主题有守信、责任及困难，并非现实遇到不同意就用暴力的规则。\n");
  if(s.contains("白雪公主"))notes.append("【白雪公主；格林童话Little Snow-White】魔镜起初称王后最美，白雪长大后才改称白雪最美。王后嫉妒，猎人奉命带白雪入森林，却放她逃生。白雪找到七个小矮人的小屋，得到收留。王后乔装再次欺骗，毒苹果使白雪昏倒；小矮人保护着她。原版搬动玻璃棺时，苹果块因颠动离开喉咙，白雪醒来，故事有获救和新生活的结局，不能停在昏倒。儿童改编删去残酷惩罚，淡化婚恋，不讲毒物制作或把童话当急救教程。\n");
  return notes.length()==0?"":"以下是按本轮提及作品检索的资料，不是固定任务。若用户否定某作品就不讲它；仅使用与最新目标匹配的资料，蜘蛛侠保持讲述者，除非明确要求跨界故事，不把自己和伙伴硬塞进原著场景：\n"+notes.toString();
 }
 public static int total(){return E.length;}
}
