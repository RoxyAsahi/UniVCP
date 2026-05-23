# Chat Sync Development Runbook

本文档记录 UniVCP 与 VCPChat 聊天记录实时同步功能的开发进程、调试方式，以及 Android 无线调试连接流程。文档中的命令默认在 Windows PowerShell 中执行。

不要把真实密钥、Firebase auth token、Supabase key、`local.properties`、`config.env` 提交到仓库。

## 当前链路

同步链路为：

```text
VCPChat history.json
  -> ChatSyncBridge 插件
  -> Firebase Realtime Database
  -> UniVCP ChatSyncManager
  -> Room conversationentity / message_node
  -> DataStore assistants
```

已经验证过的能力：

- VCPChat 可以把真实聊天记录推到 Firebase RTDB。
- UniVCP 可以冷启动拉取 Firebase 上的会话。
- UniVCP 可以在 App 运行时通过 Firebase SSE 实时接收远端新增会话。
- VCPChat Agent 的 `name` 和 `systemPrompt` 会作为 `assistant` 元数据同步。
- UniVCP 收到 VCPChat Agent 信息后，会创建或更新对应 Assistant，并把会话挂到这个 Assistant 下。
- 已导入过的会话再次拉取时会跳过，不会重复插入。

当前开发环境中使用过的路径：

```text
UniVCP:  C:\VCP\Eric\UniVCP
VCPChat: C:\VCP\VCPChat
Plugin:  C:\VCP\VCPChat\VCPDistributedServer\Plugin\ChatSyncBridge
```

## 代码位置

UniVCP 侧核心代码：

```text
app/src/main/java/me/rerere/rikkahub/data/sync/chat/
app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ChatSyncTab.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt
app/src/main/java/me/rerere/rikkahub/UniVCPApp.kt
app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
app/src/test/java/me/rerere/rikkahub/data/sync/chat/ChatSyncMapperTest.kt
```

VCPChat 侧插件代码：

```text
C:\VCP\VCPChat\VCPDistributedServer\Plugin\ChatSyncBridge
```

主要文件：

```text
ChatSyncBridgeService.js
ChatSyncBridge.js
ChatSyncRemoteProvider.js
chatSyncMapper.js
plugin-manifest.json
README.md
config.env.example
```

## 开发进程摘要

1. 先梳理 UniVCP 数据模型：`Conversation`、`MessageNode`、`UIMessage`、`Assistant`。
2. 再梳理 VCPChat 数据目录：`AppData/UserData/{agentId}/topics/{topicId}/history.json` 和 `AppData/Agents/{agentId}/config.json`。
3. 定义通用同步格式：`SyncConversation`、`SyncMessage`、`SyncMessagePart`、`SyncAssistant`。
4. 在 VCPChat 中做成 `ChatSyncBridge` 插件，负责监听本地 `history.json`，并通过 provider 推送到 Firebase。
5. 在 UniVCP 中实现 `ChatSyncManager`，负责启动配置、拉取远端、监听 Firebase SSE、本地变更推送。
6. 加入设置页入口：备份页面的“聊天同步”标签。
7. 补 mapper 单元测试，覆盖 UniVCP/VCPChat 格式转换、Firebase envelope 解码、非 UUID ID 的稳定映射、Assistant 元数据。
8. 用 Firebase RTDB、VCPChat 真实历史、Android 真机无线调试做端到端验证。

## UniVCP 配置

Debug 开发时，UniVCP 从仓库根目录的 `local.properties` 读取聊天同步配置，并写入 DataStore。示例：

```properties
univcp.chatSync.enabled=true
univcp.chatSync.firebaseDatabaseUrl=https://your-project-default-rtdb.region.firebasedatabase.app
univcp.chatSync.firebaseAuthToken=
univcp.chatSync.roomId=univcp-main
univcp.chatSync.deviceId=univcp-android-dev
```

注意：

- `local.properties` 是本地文件，不要提交。
- `firebaseAuthToken` 为空时，必须依赖 Firebase rules 允许对应路径读写。
- `deviceId` 用于避免接收自己推送的变更。

Debug 包名：

```text
com.univcp.android.debug
```

主 Activity：

```text
me.rerere.rikkahub.RouteActivity
```

## VCPChat 插件配置

在插件目录创建 `config.env`：

```env
CHAT_SYNC_ENABLED=true
CHAT_SYNC_PROVIDER=firebase-rtdb
CHAT_SYNC_FIREBASE_DATABASE_URL=https://your-project-default-rtdb.region.firebasedatabase.app
CHAT_SYNC_FIREBASE_AUTH_TOKEN=
CHAT_SYNC_ROOM_ID=univcp-main
CHAT_SYNC_DEVICE_ID=vcpchat-desktop-dev
CHAT_SYNC_DEBOUNCE_MS=800
CHAT_SYNC_DEFAULT_AGENT_ID=_Agent_xxx
CHAT_SYNC_DEFAULT_ITEM_TYPE=agent
```

说明：

- `CHAT_SYNC_DEFAULT_AGENT_ID` 用于 VCPChat 接收 UniVCP 新建会话时的默认写入 Agent。
- VCPChat -> UniVCP 时，每条会话会携带对应 Agent 的 `name` 和 `systemPrompt`。
- UniVCP -> VCPChat 时，如果同步包带有 `assistant`，VCPChat 会用它更新 Agent 的 `name/systemPrompt`。

## Firebase RTDB

数据路径：

```text
chatSync/{base64url(roomId)}/conversations/{base64url(conversationId)}
```

开发时可临时使用按房间开放的规则：

```json
{
  "rules": {
    "chatSync": {
      "$room": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

正式使用时应改为 Firebase Authentication 或 token 规则，不要公开真实聊天数据。

检查房间数据：

```powershell
$databaseUrl = "https://your-project-default-rtdb.region.firebasedatabase.app"
$roomKey = "dW5pdmNwLW1haW4" # base64url("univcp-main")
Invoke-RestMethod -Uri "$databaseUrl/chatSync/$roomKey/conversations.json"
```

如果根路径 `/chatSync.json` 返回 401，但具体房间路径可读写，通常是因为 rules 只开放了 `chatSync/$room`，这是正常的。

## 构建和测试

UniVCP 编译：

```powershell
cd C:\VCP\Eric\UniVCP
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

安装到已连接设备：

```powershell
.\gradlew.bat :app:installDebug --console=plain
```

聊天同步 mapper 单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.sync.chat.ChatSyncMapperTest" --console=plain
```

## 渲染测试种子数据

Debug 包支持导入一组脱敏的聊天渲染种子数据，用于测试聊天页面的 Markdown、LaTeX、代码块、Mermaid、HTML/VCP 块、工具调用、附件和长线程。

聊天列表中的安全静态/交互型富 HTML 当前走 Compose 原生管线：

```text
RichHtmlCompiler -> RichHtmlRenderModel -> RichHtmlRenderer
```

WebView 只保留给动态预览、全屏检查和 WebView 调试路径。

当前聊天富 HTML 渲染链路的开发现状记录在：

```text
docs/rich-html-rendering-status.md
```

当前 seed 还专门加入了来自 VCPChat 真实记录形态的合成 HTML/CSS 覆盖：`<style>`、选择器级联、CSS 变量、`@keyframes`、`button`、`details/summary`、`pre/code`、`svg/path/rect/circle/ellipse/line/polyline/polygon/text`、`sub/sup`、HTML 表格、`vcp-card-*`、`vcp-feature-card`、`vcp-param-tag`、`action-btn`、`window-item`、`proc-name`、`pid-badge`、`hwnd-tag`、`VCPDesktop`、`vcp-net-widget` 和 `vcp-clock-widget`。脚本样例只以转义文本保存在代码块中，不写入可执行 `<script>`。

种子文件位置：

```text
app/src/debug/assets/render_seed/chat_render_seed.json
```

导入器位置：

```text
app/src/main/java/me/rerere/rikkahub/data/renderseed/RenderSeedImporter.kt
```

在 `local.properties` 中打开：

```properties
univcp.renderSeed.enabled=true
```

然后重新安装并启动 debug 包：

```powershell
cd C:\VCP\Eric\UniVCP
.\gradlew.bat :app:installDebug --console=plain

$adb = "C:\Users\CHENXI\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.6.121:46127"
& $adb -s $serial shell am force-stop com.univcp.android.debug
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n com.univcp.android.debug/me.rerere.rikkahub.RouteActivity
```

检查导入日志：

```powershell
& $adb -s $serial logcat -d -v time | Select-String -Pattern 'RenderSeedImporter'
```

期望类似：

```text
Seeded render conversations inserted=9 updated=0
```

种子导入是幂等的：同 ID 已存在且未过期时不会重复插入。需要重置时，可以卸载 debug 包或手动删除对应会话。

渲染编译器快速回归：

```powershell
cd C:\VCP\Eric\UniVCP
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --console=plain
```

富 HTML 视觉保真调试时，优先看 `docs/rich-html-rendering-status.md`。当前聊天列表安全静态/交互 HTML 默认走 Compose 原生 IR 渲染，WebView 只保留给动态预览、全屏检查、debug renderer server，以及后续可能的静态快照兜底。

VCPChat 插件语法检查：

```powershell
cd C:\VCP\VCPChat
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncBridgeService.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncRemoteProvider.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncBridge.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\chatSyncMapper.js
```

如果 Gradle daemon/cache 状态异常，可以先停 daemon 再重跑：

```powershell
.\gradlew.bat --stop
```

## VCPChat 推送调试

手动执行一次 VCPChat -> Firebase push：

```powershell
cd C:\VCP\VCPChat
@'
const fs = require('fs');
const dotenv = require('dotenv');
const svc = require('./VCPDistributedServer/Plugin/ChatSyncBridge/ChatSyncBridgeService.js');
const cfg = dotenv.parse(fs.readFileSync('./VCPDistributedServer/Plugin/ChatSyncBridge/config.env'));
const app = { get(){}, post(){} };

(async () => {
  svc.registerRoutes(app, cfg, null);
  await new Promise(r => setTimeout(r, 1200));
  const result = await svc.processToolCall({ command: 'SyncOnce', direction: 'push' });
  console.log(JSON.stringify(result, null, 2));
  svc.cleanup();
})().catch(e => {
  console.error(e);
  process.exit(1);
});
'@ | node -
```

检查导出的会话是否带 Assistant：

```powershell
cd C:\VCP\VCPChat
@'
const bridge = require('./VCPDistributedServer/Plugin/ChatSyncBridge/ChatSyncBridge.js');

(async () => {
  const bundle = await bridge.exportAll(bridge.createContext(), {
    itemType: 'agent',
    includeEmpty: false,
  });
  const withAssistant = bundle.conversations.filter(c => c.assistant);
  const sample = withAssistant[0];
  console.log(JSON.stringify({
    total: bundle.conversations.length,
    withAssistant: withAssistant.length,
    sample: sample && {
      title: sample.title,
      assistantName: sample.assistant.name,
      hasPrompt: typeof sample.assistant.systemPrompt === 'string',
    },
  }, null, 2));
})().catch(e => {
  console.error(e);
  process.exit(1);
});
'@ | node -
```

期望：

```text
withAssistant == total
sample.hasPrompt == true
```

## Android 无线调试连接

手机端操作：

1. 打开“开发者选项”。
2. 打开“无线调试”。
3. 进入“使用配对码配对设备”。
4. 记录配对码、IP、配对端口。

电脑端操作：

```powershell
$adb = "C:\Users\CHENXI\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb pair 192.168.6.121:36647
```

输入手机显示的 6 位配对码。例如：

```text
130057
```

配对成功后，回到手机无线调试页面，查看“IP 地址和端口”。这个连接端口通常和配对端口不同，再执行：

```powershell
& $adb connect 192.168.6.121:46127
& $adb devices
```

期望输出中出现：

```text
192.168.6.121:46127    device
```

后续命令统一设置：

```powershell
$serial = "192.168.6.121:46127"
```

如果设备断开：

```powershell
& $adb disconnect 192.168.6.121:46127
& $adb connect 192.168.6.121:46127
```

如果无线调试端口变了，需要在手机无线调试页面重新查看新端口。

## 手机端运行和日志

启动 UniVCP debug 包：

```powershell
$adb = "C:\Users\CHENXI\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.6.121:46127"

& $adb -s $serial shell am force-stop com.univcp.android.debug
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n com.univcp.android.debug/me.rerere.rikkahub.RouteActivity
```

过滤聊天同步日志：

```powershell
& $adb -s $serial logcat -d -v time |
  Select-String -Pattern 'ChatSyncManager|Imported assistant|Updated imported assistant|Moved remote conversation|Imported remote conversation|Skipped remote|Pulled|SQLiteConstraintException|FATAL EXCEPTION|AndroidRuntime'
```

常见健康日志：

```text
Imported assistant name=Nova
Moved remote conversation title=插件目录查找 to imported assistant
Skipped remote conversation title=插件目录查找, local is up-to-date
Pulled 24 remote conversations, imported=0
```

`observeRemote failed java.net.SocketException: Software caused connection abort` 偶发出现时，通常是 Firebase SSE 长连接被系统或网络中断；当前实现会循环重连。只要没有 `FATAL EXCEPTION`、`AndroidRuntime` 崩溃，且后续还能拉取/接收数据，一般不算同步失败。

## 手机端数据库验证

Debug 包可用 `run-as` 拉取 Room 数据库：

```powershell
$adb = "C:\Users\CHENXI\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.6.121:46127"
$tmp = "C:\VCP\Eric\UniVCP\build\codex-device-db"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

& $adb -s $serial exec-out run-as com.univcp.android.debug cat databases/rikka_hub > "$tmp\rikka_hub"
& $adb -s $serial exec-out run-as com.univcp.android.debug cat databases/rikka_hub-wal > "$tmp\rikka_hub-wal"
& $adb -s $serial exec-out run-as com.univcp.android.debug cat databases/rikka_hub-shm > "$tmp\rikka_hub-shm"
```

统计会话归属：

```powershell
sqlite3 "$tmp\rikka_hub" "SELECT assistant_id, COUNT(*) FROM conversationentity GROUP BY assistant_id ORDER BY COUNT(*) DESC;"
```

拉取 DataStore 并检查 Assistant 是否落盘：

```powershell
& $adb -s $serial exec-out run-as com.univcp.android.debug cat files/datastore/settings.preferences_pb > "$tmp\settings.preferences_pb"
rg -a -n "Nova|UIka|systemPrompt|assistants" "$tmp\settings.preferences_pb"
```

期望：

- `assistants` JSON 中存在从 VCPChat 导入的助手名称。
- 对应助手对象里有 `systemPrompt`。
- `conversationentity.assistant_id` 分布到导入的助手 ID 下。

## 实时同步烟测

验证 UniVCP 正在运行时能实时收到 Firebase 新会话：

1. 先启动 UniVCP 并清空 logcat。
2. 向 Firebase 当前 room 的 `conversations/{conversationKey}.json` 写入一条 `sourceDeviceId=vcpchat-desktop-dev` 的 envelope。
3. 观察 logcat 是否出现：

```text
Imported remote conversation title=..., messages=...
```

如果只想验证真实链路，优先使用 VCPChat 插件的 `SyncOnce direction=push`，避免手写 envelope。

## 排错清单

- `Firebase is not configured`：检查 UniVCP `local.properties` 和设置页是否写入了 Firebase URL。
- Firebase 返回 401：检查 RTDB rules 是否开放了具体 room 路径；根路径 401 不一定是问题。
- UniVCP 没有导入：看 logcat 中 `ChatSyncManager`，确认是否 `Pulled ... imported=...`。
- 重复插入或崩溃：看是否出现 `SQLiteConstraintException`；当前实现应先 initial pull，再启动 SSE，避免启动竞态。
- VCPChat 没推送：检查 `config.env`、`CHAT_SYNC_ENABLED=true`、`CHAT_SYNC_PROVIDER=firebase-rtdb`。
- 无线调试失效：重新 `adb connect`，必要时手机端重新关闭/开启无线调试，端口会变。
- 看不到 Assistant：确认 Firebase 中 conversation 是否包含 `conversation.assistant.name` 和 `conversation.assistant.systemPrompt`。
