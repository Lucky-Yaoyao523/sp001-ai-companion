# SP001 AI Companion · 小胖开源伙伴

[English](README.en.md)

让停产的 Sphero Spider-Man SP001 继续成为能说中文、记住偏好、一起聊天的 AI 伙伴。

**Source release for a voice AI companion on the original SP001 hardware, with streaming dialogue, local memory, interruption handling, and a local parent console.**

这份仓库分享我们编写的代码和经验。原主板、麦克风、扬声器、眼睛和外壳都保留，模型通过家庭 Wi-Fi 在云端运行；正常聊天不要求电脑常驻。家长网页是单独运行的可选组件。

**当前发布为开发者源码版，不提供给任意玩具一键刷入的固件或 APK。** 家长网页可直接在电脑上启动；玩具端需要具备合法访问条件并完成原硬件适配。公开版移除了家庭档案和远程调试操作，与私人装机包有明确差异。

## 可以学到和复用什么

| 部分 | 包含内容 | 公开版可运行范围 |
|---|---|---|
| 对话 | MiniMax 对话、Qwen 流式识别、流式 TTS、模型工具回执 | Android Java 源码，需要自有 API 凭据和硬件适配 |
| 完整播报 | 增量解析、完整后续正文、工具后多条助手消息、取消和队列边界 | 含离线失败场景回归 |
| 语音交互 | 收音窗口、回声处理、插话确认、播放状态 | 实机适配源码；离线测试不代表真人听感 |
| 记忆 | 本地偏好存储、近期上下文、真实保存回执 | 应用私有存储；公开仓库没有任何家庭记忆 |
| 家长网页 | 使用记录、完成/部分回答、问题提示、保留周期 | Node.js 本机启动；默认不连接玩具、不保存对话正文 |
| 同步 | 认证、证书校验、持久游标、去重和断线补传 | 仅显式配置自己的配对资料后启用 |
| 参考演示 | 平台无关的语音会话与协议 Mock | 不用麦克风、设备、账号或付费 API |

## 三分钟看看家长网页

安装 [Node.js 22 或更新版本](https://nodejs.org/)，然后：

```sh
git clone https://github.com/Lucky-Yaoyao523/sp001-ai-companion.git
cd sp001-ai-companion
npm start
```

打开终端显示的 `http://127.0.0.1:8787/`，输入本次启动的随机访问码。初次运行是空数据库，可在网页中主动载入明确标记的演示记录。

Windows 也可双击 `START-PARENT.cmd`；Mac/Linux 可运行 `sh START-PARENT.command`。这些入口只启动本机网页，不安装或修改玩具。Mac 首次运行仍需要自行安装 Node.js。

```sh
npm test
npm run check:privacy
npm run voice:demo
```

Java 编译和回归见 [开发说明](docs/development.md)。原硬件集成和配置边界见 [集成说明](docs/integration.md)。

## 已经做到，以及仍未解决的事

私人原型已经在原 SP001 硬件上运行中文多轮聊天、播报、打断、记忆和家长记录。最近修复了两类明确的正文丢失：合法语音对象后面的普通正文被忽略，以及工具执行后过早结束、漏掉后续助手消息。相关回归在 `NativeReplyCompletenessTest.java`。

公开源码经过匿名化，并关闭了 BLE 远程配置/收音启动入口；这些变化只作用于公开副本，没有改动日常使用中的原型。

仍需改善：模型事实准确性、名称理解、对话自然度、部分回答的取消原因、长会话体验，以及不同玩具和网络环境的适配。源码测试通过不能代表所有玩具都可直接使用，也不能代表所有语音体验已经解决。

我们不宣称“世界第一”或“完成度全球最高”。已有其他优秀的 SP001 恢复项目；这个项目希望补充开放的中文 AI 对话组件和真实使用中的工程经验。

## 隐私与发布范围

仓库没有家庭对话、孩子姓名/年龄/住址、Wi-Fi 信息、设备序列号、API 密钥、配对密钥、私有证书、原音频或历史 Git 提交。天气没有家庭默认城市，需要明确给出城市。配置示例全部关闭且凭据为空。

云端语音模式会向用户自己选择的供应商发送语音/文字；必须由使用者配置自己的账号并明确同意。供应商按自己的政策处理数据。`store:false` 只是请求参数，不能视为供应商绝不留存的保证。家长记录默认不保存对话正文，自动总结未接入外部模型。

公开版不含原厂 APK、拆包资源、角色音频、签名私钥、刷机/恢复工具、调试入口开启步骤或未经认证的远程收音入口。详见 [安全边界](SECURITY.md) 和 [隐私说明](PRIVACY.md)。

## 参与和参考

欢迎帮助改善泛化配置、适配接口、长答回归和使用说明。提交问题前请按 [贡献指南](CONTRIBUTING.md) 删除个人信息。

- [Second Life Toys](https://github.com/second-life-toys/second-life-toys)：已有的 Spider-Man 恢复应用和公开资料。
- [SpheroRevived](https://github.com/Ric-614/SpheroRevived)：停产 Sphero 设备的社区保全项目。
- [Sphero-Spiderman](https://github.com/helenclarko/Sphero-Spiderman)：SP001 社区应用研究。

我们使用这些公开项目作为背景和参考，本仓库没有复制它们的应用包、专有代码或资源。

## 许可

我们编写的源码和文档采用 [MIT License](LICENSE)。第三方运行时、Android SDK 和依赖不随仓库分发；它们各自的许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。

这是独立社区项目，与 Sphero、Marvel 或 Disney 没有隶属、授权或背书关系。商标只用于说明兼容的停产设备，不转授原厂软件和角色素材的使用权。
