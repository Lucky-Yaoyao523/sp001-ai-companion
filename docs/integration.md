# 原硬件集成与配置边界

## 这份发布能直接做什么

家长网页和 Mock 可在 Mac、Windows、Linux 上运行。Android Java 核心可单独编译；它不是独立可安装应用。`Owner*` 适配层通过反射调用原 SP001 的运行环境，缺少对应原厂运行环境时不能工作。

仓库没有原厂应用、资源、安装包和签名材料，也不提供破解、恢复、刷写或开启调试入口的操作。若尚未具有合法的设备访问条件，请先参考已有社区项目的公开支持范围，不在陌生设备上试刷本仓库。

## 推荐的代码阅读路径

1. `NativeDialogueProtocol`：模型请求与正式工具定义。
2. `NativeDialogueEngine`、`NativeReplyStream`、`ReplyEnvelopeStream`：增量读取与完整正文。
3. `MiniMaxVoiceClient`、`QwenAsrWire`、`MiniMaxTtsSocket`：供应商适配。
4. `ConversationSession`、`OwnerNativeConversation`：会话、收音与取消。
5. `CompanionMemory`、`OwnerCompanionMemory`：本地偏好和保存回执。
6. `ParentJournalState`、`ParentConversationSync`、`parent-console/toy-pull.mjs`：持久记录及同步。

硬件适配需要在自己的合法开发环境中接入真实麦克风、扬声器、按钮和表情控制。默认关闭配置读取自应用私有目录。配置示例说明字段，不是部署命令。

## 公开副本的变化

- 个人名称、年龄、性别和家庭默认城市已去除；天气要求用户明确提供城市。
- `OwnerControlBridge` 仅拦截保留的命名空间，所有 BLE 远程凭据配置、启动收音和诊断入口均失效。
- `CityWeather` 保留公开城市查询，取消私人固定城市和经纬度。
- 家长启动示例只监听本机；没有家庭地址、随包数据库、凭据或证书。
- 自动云总结没有接入；网页不会自行把对话发送给第三方总结服务。

这些修改没有部署到开发者日常使用的私人玩具。公开源码的物理效果仍需各适配者在自己的设备上验收。

## 自己的配置

`config/voice.disabled.example.json` 和 `config/parent.disabled.example.json` 凭据为空、开关关闭。只在自己的应用私有目录/被忽略的 `private/` 中填写配置，不把真实配置提交到 GitHub。

配对家长后台需要使用者已有的设备标识、共享密钥和可信设备证书。公开版没有生成设备配对或开启调试功能的命令。

如果已有合法配对，把自己的配置放在 `private/parent-sync/home.json`，证书放在同目录，在确认启用后显式运行：

```sh
node parent-console/start-home.mjs --run
```

家长对话正文保存仍由网页开关决定，默认关闭。这个同步入口会确认玩具事件游标：**一台玩具只能由一个持久后台负责同步**。复制旧数据库到新电脑并核对后再转移同步职责；不要同时运行两个后台，也不要用手工 HTTP 读取玩具事件接口“看看”，以免提前确认尚未保存的记录。

## 验收分开记录

离线回归 → 自己账号的有界模型测试 → 原机包/配置核对 → 真人收音/播报/打断 → 断线与长期使用。

任何一步失败都保留证据；不要用“网络返回成功”或“源码编译成功”代替原机与听感验收。真实会话、音频、凭据与精确身份只在自己的本地存放，不上传 Issues。
