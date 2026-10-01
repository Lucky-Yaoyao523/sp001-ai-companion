# 开发与回归

## Node.js

Node.js 22+，没有第三方 npm 依赖：

```sh
npm test
npm run check:privacy
```

测试使用合成记录、临时文件和本机 HTTP 测试服务器。它们不连接玩具、不调用付费模型、不录音。`npm start` 的网页默认只监听回环地址，并生成新的访问码；没有云总结接入。

## Android Java 源码

需要 JDK 17+、Android API 22 的 `android.jar`，以及下列 Maven 依赖。Java 源码以 Java 8 / API 22 编译，适用于旧设备。依赖版本固定以便复现原型；部署到新环境前应评估升级兼容性。

| Maven 坐标 | 用途 |
|---|---|
| `com.squareup.okhttp3:okhttp:3.12.13` | Android 旧版本 HTTPS/WebSocket |
| `com.squareup.okio:okio:1.15.0` | 网络字节流 |
| `org.json:json:20240303` | 仅宿主 Java 测试；设备使用 Android 的 org.json |

准备 Android SDK 的 `platforms;android-22` 后设置 `ANDROID_JAR`，并可用 `JAVA_HOME` 指定 JDK。

```sh
node scripts/test-java.mjs --fetch-deps
node scripts/test-java.mjs
```

`--fetch-deps` 只从固定 Maven Central HTTPS 地址下载以上三份 JAR，并校验 SHA-256；未传参数时不下载。输出存放于被 Git 忽略的 `.deps/`、`build/`。脚本不使用私人工程中的构建报告、已有 APK、厂商 JAR 或密钥。

脚本编译全部公开 Java 核心源码，再执行九组离线测试，其中包括正文完整性、工具执行和取消等回归。宿主替身只提供测试需要的 Android 时间/线程接口；它们不能打进设备应用。编译成功只证明源码和接口一致，不等于原机安装或物理语音验收。

## 目录

```text
core-patch/src/        自有 Android Java 对话与硬件适配代码
core-patch/test/       合成离线回归与宿主替身
parent-console/       本机家长网页及配对同步组件
src/                  平台无关 Mock 参考
test/                 Node.js 离线回归
config/               空凭据、关闭状态的示例
scripts/              公开文件检查与离线 Java 检查
docs/                 开发、集成与项目边界
```

不要把 `private/`、`.deps/`、`build/`、家长数据库、录音或配置密钥放进提交。开发前后运行公开文件检查；它是第一道检查，不能代替对新增文件的人工审阅。
