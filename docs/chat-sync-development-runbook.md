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

## 2026-05-30 稳定性与易用性改进

本轮目标是先降低同步链路在真实使用中的重复写入、半写入和“用户不知道哪里坏了”的概率。

改动范围：

- VCPChat `ChatSyncBridgeService.js`
  - 启动顺序改为：启动本地 watcher，先执行一次 `pullOnce()`，再打开 Firebase stream。
  - 增加 `appliedRemoteConversations` 内存表，按 conversation id 记录最近应用的远端 `updatedAt` 和内容签名。
  - 相同或更旧的 envelope 不再重复写入 history，并在状态统计中计入 `remoteSkippedUnchanged` 或 `remoteSkippedStale`。
  - 增加 `Diagnostics` 命令和 `GET /chat-sync/diagnostics`，检查服务启用、provider、room/device、AppData/UserData、默认导入目标和最近错误。
- VCPChat `ChatSyncBridge.js`
  - `history.json` 和 Agent/Group `config.json` 写入改为临时文件后移动到目标路径。
  - 远端内容与本地 merge 结果一致时跳过写入，并在导入结果中返回 `changed`、`historyChanged`、`topicChanged`。
- VCPChat `plugin-manifest.json` / `README.md`
  - 记录新增诊断命令、诊断 HTTP 端点和稳定性保护策略。
- UniVCP `ChatSyncTab.kt`
  - 手动拉取、推送、立即同步都增加 `runCatching`。
  - `ChatSyncRunResult.skippedReason` 不再显示成“成功 0 条”，而是提示“同步跳过：原因”。

## 2026-05-30 单向同步与 AI 回复漏同步修复

本轮继续围绕“电脑端作为主库，移动端只接收”的使用方式做保护。

问题背景：

- 实测中，电脑端发送用户消息后移动端能看到用户消息，但 AI 回复完成后移动端可能看不到回复。
- 用户希望支持单向模式，例如只允许 VCPChat/电脑端同步到 Firebase，再由 UniVCP/手机端拉取；手机端删除或修改不应反向影响电脑端。

改动范围：

- VCPChat `ChatSyncBridgeService.js`
  - 新增 `CHAT_SYNC_DIRECTION=both|push|pull`，兼容 `push-only`、`pull-only` 等别名。
  - `push` 模式只启动本地 `history.json` watcher 和推送逻辑，不执行 initial pull，不打开 Firebase stream，不启动 pull interval，手动 pull 也返回 skipped。
  - `pull` 模式只执行远端导入，不启动本地 watcher，不推送本机历史。
  - `Status` / `Diagnostics` 返回 `direction`、`allowPush`、`allowPull`，方便确认当前保护状态。
- VCPChat `config.env`
  - 当前开发机配置已加 `CHAT_SYNC_DIRECTION=push`，用于保护电脑端主库。
- UniVCP `ChatSyncConfig.kt` / `ChatSyncManager.kt` / `ChatSyncTab.kt`
  - 新增 `ChatSyncMode.BOTH/PUSH_ONLY/PULL_ONLY`。
  - 运行时按照模式决定是否启动本地推送监听、远端监听、启动导入、启动推送。
  - 手动“拉取/推送/立即同步”也受模式限制，并显示跳过原因。
  - 设置页新增“同步方向”分段选择；手机只接收电脑数据时选“仅拉取”。
- UniVCP `FirebaseRtdbChatSyncRemoteStore.kt`
  - 拉取和 SSE 接收到的会话会用 Firebase envelope 的 `updatedAt` 推进 `SyncConversation.updatedAt`。
  - 这解决了 VCPChat 追加 AI 回复但会话内部时间戳没有明显变大时，Android 端误判“本地已是最新”而跳过导入的问题。
- VCPChat `plugin-manifest.json` / `README.md` / `config.env.example`
  - 记录方向配置、命令行为和推荐单向配置。

推荐单向配置：

```text
VCPChat 桌面端: CHAT_SYNC_DIRECTION=push
UniVCP 手机端: 设置页 -> 备份 -> 聊天同步 -> 同步方向 -> 仅拉取
```

这种配置下，手机端不会把删除、修改或手动推送写回 Firebase；桌面端也不会从 Firebase 导入远端内容。

## 2026-05-30 手机端新手引导与拉取鲁棒性

本轮继续提升移动端配置易用性，并针对时间戳不可靠做防护。

改动范围：

- UniVCP `ChatSyncTab.kt`
  - 新增“新手指南”区块。
  - 提供“电脑端到手机端”的推荐配置说明：电脑端 `CHAT_SYNC_DIRECTION=push`，手机端“仅拉取”。
  - 增加“一键安全模式”，会把手机端设置为 `PULL_ONLY`、开启启动时导入、关闭启动时推送。
  - 增加“当前保护状态”，明确提示手机端是否会反向影响电脑端。
  - 在“仅拉取”模式下禁用手动“推送”按钮；在“仅推送”模式下禁用手动“拉取”按钮。
  - “立即同步”若部分方向被模式跳过，但另一方向实际成功，会显示成功数量和跳过原因，不再让用户误以为整次同步都没做。
- UniVCP `ChatSyncManager.kt`
  - 在 `PULL_ONLY` 模式下，远端内容作为权威源。
  - 如果本地会话时间戳看起来更新，但远端内容与本地不同，仍会导入远端内容。
  - 这个兜底用于处理设备时钟漂移、VCPChat 消息时间戳未推进、或 Firebase envelope 与 conversation 内部时间不一致导致的漏同步。

## 2026-05-30 Agent 隔离与修复模式

本轮重点是保证不同 VCPChat Agent 的会话不会串到一起，并提供手机端数据库修复入口。

关键原则：

- VCPChat 来源的会话身份以 `source.agentId + source.topicId` 为准，而不是盲信远端 `conversation.id`。
- VCPChat 来源的 Assistant 身份以 `source.agentId` 为准，而不是盲信远端 `assistant.id` 或助手名称。
- 同名 Topic 在不同 Agent 下必须导入为不同 UniVCP 会话。
- 同名 Assistant 或损坏的 assistant metadata 不能让 A Agent 的聊天记录挂到 B Agent 下。

改动范围：

- UniVCP `ChatSyncManager.kt`
  - 导入 VCPChat 会话前会重建作用域身份：`vcpchat:{agentId}:{topicId}`。
  - 如果远端 `conversation.id` 与 `source.agentId/topicId` 不一致，会记录日志并使用修复后的 ID。
  - 如果 conversation source 与 message source 出现多个 Agent/Topic 混杂，会拒绝导入并记录错误，避免串库。
  - VCPChat Assistant 导入时使用 `source.agentId` 作为稳定身份，确保不同 Agent 创建不同 Assistant。
  - 新增 `repairNow()`，从 Firebase 全量扫描当前 room，并按 Agent/Topic 归属重新导入需要修复的会话。
- UniVCP `ChatSyncTab.kt` / `BackupVM.kt`
  - 新手指南中新增“修复模式”入口。
  - 点击“开始修复”会全量拉取远端，自动修复手机端会话归属和漏同步内容；修复只追加缺失内容，不删除或截短本机记录。
- UniVCP `ChatSyncMapperTest.kt`
  - 增加回归测试：不同 Agent 使用相同 topic id 时，导入后的会话 ID 和 Assistant ID 必须不同。

## 2026-05-30 同步页操作逻辑简化

本轮把手机端聊天同步页从“底层策略配置”整理成“使用方式优先”的操作逻辑。

新的主流程：

1. 选择使用方式：`手机只接收`、`双向`、`本机上传`。
2. 填 Firebase 连接信息。
3. 点底部 `立即同步`。

界面调整：

- 原来的 `同步方向` 改成更口语化的 `同步模式`。
- `手机只接收` 会自动设置：`PULL_ONLY`、启动时自动拉取、禁止启动时推送、只导出当前分支。
- `双向` 会自动设置：`BOTH`、启动时自动拉取、禁止启动时推送、只导出当前分支。
- `本机上传` 会自动设置：`PUSH_ONLY`、关闭启动时自动拉取、禁止启动时推送、只导出当前分支。
- 底部操作简化为：`测试连接`、`修复`、`立即同步`。
- `立即同步` 会根据使用方式自动选择拉取、推送或双向，不再要求用户理解底层 direction。
- 原来的 `启动时推送本地记录` 从主界面移除，避免误开危险项。
- 原来的 `只同步当前分支` 改名为 `导出范围`，放到高级选项，并只在当前模式允许推送时可操作。

## 2026-05-30 删除保护与防截断策略

本轮把删除操作改成显式二次确认，并把同步后果写进确认弹窗，避免用户误以为手机端删除会安全地同步到所有端，或误删本机唯一副本。

改动范围：

- UniVCP `SyncDeleteConfirmDialog.kt`
  - 新增统一删除确认弹窗。
  - 弹窗会根据当前同步模式提示后果：
    - `手机只接收`：删除只影响手机本地副本；远端仍存在时，下次同步或修复可能恢复。
    - `本机上传`：删除不会向 Firebase 发送删除指令，也不会删除电脑端记录。
    - `双向`：当前版本仍不传播删除，但会立即移除本机内容，删除前应确认电脑端或备份里有需要保留的记录。
- UniVCP `ChatDrawer.kt`
  - 侧边栏长按菜单删除对话前必须确认。
- UniVCP `HistoryPage.kt`
  - 历史页滑动删除不再立即删除，而是弹出确认。
  - “删除全部历史”复用同步删除确认，并强化批量删除后果说明。
- UniVCP `ChatPage.kt`
  - 单条消息删除前必须确认。
- UniVCP `ChatSyncManager.kt`
  - Android 端本地删除仍然是 local-only：不会写 tombstone，不会向 Firebase 或电脑端传播删除。
  - 远端导入采用只新增策略：已有本地会话以本机内容为底，只追加远端缺失消息、补空标题、修正 Assistant 归属，不删除、不截短、不用更短远端覆盖本地。
  - 修复模式也遵守只新增策略：用于修复 Agent/Topic 归属和漏同步内容，不再按远端重建本地记录。

## 2026-05-30 VCPChat 在线状态

本轮增加 VCPChat 在线状态展示，避免用户误以为“VCPChat 离线时手机完全不能拉取”。

关键行为：

- VCPChat 在线时，插件定时写入 Firebase：

```text
chatSync/{base64url(roomId)}/presence/{base64url(deviceId)}
```

- UniVCP 每 15 秒读取一次 presence，取最新的 VCPChat 设备心跳。
- 90 秒内有在线心跳则显示“在线”；超过 90 秒或 VCPChat 显式写入 `offline` 则显示“离线”；没有任何 heartbeat 时显示“未知”。
- VCPChat 离线时，手机端仍能从 Firebase 拉取已经存在的聊天记录；只是电脑端新消息不会继续推送到 Firebase，因此手机看不到最新回复。

改动范围：

- VCPChat `ChatSyncRemoteProvider.js`
  - Firebase provider 新增 `updatePresence()`，写入 `app=vcpchat`、`deviceId`、`status`、`updatedAt`、`direction`。
- VCPChat `ChatSyncBridgeService.js`
  - 服务启动后按 `CHAT_SYNC_PRESENCE_INTERVAL_MS` 定时写在线心跳。
  - 服务停止时尽量写入离线状态。
- UniVCP `FirebaseRtdbChatSyncRemoteStore.kt`
  - 新增 `getPresence()` 读取 Firebase presence。
- UniVCP `ChatSyncManager.kt`
  - 新增 VCPChat presence 轮询和 `ChatSyncPeerStatus` 状态。
- UniVCP `ChatSyncTab.kt`
  - 在“使用方式”区域新增 `VCPChat 状态`，显示在线、离线或未知，并说明离线时仍可拉取 Firebase 已有记录。

## 2026-05-30 Firebase 新手填写指南

本轮在 UniVCP 聊天同步页新增 `Firebase 填写指南`，把最容易卡住的配置点直接写进 App：

- 新手从 Firebase Console 创建项目，Google Analytics 可以先不启用。
- 在 Firebase 控制台创建 Realtime Database，测试阶段可以先用测试模式确认链路。
- `Realtime Database URL` 填控制台 Realtime Database 页面顶部的网址，常见形态：

```text
https://xxx-default-rtdb.firebaseio.com
https://xxx-default-rtdb.asia-southeast1.firebasedatabase.app
```

- `Auth Token` 默认可以留空：只要 Firebase rules 允许当前 room 读写，UniVCP 和 VCPChat 的 REST 请求不需要额外 token。
- 只有用户把 rules 改成需要认证或服务端 token 时，才需要填写 Auth Token。
- `Room ID` 是同步房间名，UniVCP 和 VCPChat 必须一致；建议使用不容易猜到的英文名。

## 2026-05-30 同步默认值与入口调整

本轮把聊天同步的默认值和入口改成更适合新用户的安全路径。

默认配置：

- `enabled=false`：默认不自动开启实时同步，必须用户主动打开。
- `mode=PULL_ONLY`：新用户默认是“接收”，即手机只从 Firebase 拉取，不向远端推送本地变更。
- `importRemoteOnStart=true`：用户开启同步后，启动时会自动拉取远端新记录。
- `pushOnStart=false`：默认不开启启动时推送，避免第一次配置时把手机端本地数据误推到远端。
- `currentPathOnly=true`：允许推送时默认只导出当前分支，减少分支记录在另一端展开造成的理解成本。
- `firebaseAuthToken=""`：默认留空。只有 Firebase rules 要求认证或 token 时才填写。

界面调整：

- 设置页 `数据设置` 下新增 `VCP 数据同步` 入口，直接打开备份页中的 VCP 数据同步标签。
- 备份页原 `聊天同步` 标签改名为 `VCP 数据同步`，强调这是 VCP/VCPChat 专用功能。
- VCP 数据同步页顺序调整为：
  1. 状态开关与同步统计。
  2. Firebase URL / Auth Token / Room ID / Device ID。
  3. 使用方式、VCPChat 在线状态、保护状态、另一端设置。
  4. Firebase 填写指南。
  5. 高级选项。
- 选择 `双向` 时显示双向警告，提示手机和电脑都会参与写入。

行为说明：

- 同步开启时切换 `接收`、`双向`、`上传` 不会直接删除或改写已有记录；它会重启同步运行时，并让后续拉取/推送按新模式执行。
- 真正需要提示的风险是 `双向`：该模式允许手机端变更写回远端，因此只建议在两端都可信、且用户明确需要双向时开启。

## 2026-05-30 Firebase 增量拉取高级选项

本轮把 Firebase RTDB 的增量拉取改为高级选项。默认仍使用全量拉取，避免新用户因为 Firebase rules 没有 `.indexOn` 而连接失败。

拉取规则：

- 默认模式：请求整个 `conversations` 节点，再在本机按只新增策略合并；不需要 Firebase 索引。
- 增量模式：开启 UniVCP 高级选项“增量拉取”或 VCPChat `CHAT_SYNC_INCREMENTAL_PULL=true` 后启用。
- 增量模式下，首次同步、修复模式或游标为 0 时仍会全量扫描当前 room。
- 每个 Firebase envelope 顶层都有 `updatedAt`。
- 客户端记录已经处理过的最大 `updatedAt`，下一次请求只查：

```text
conversations.json?orderBy="updatedAt"&startAt=lastUpdatedAt+1
```

- 增量模式下 UniVCP 和 VCPChat 都会在处理完成后推进游标；重启后会从持久化游标继续增量拉取。
- UniVCP 游标保存在 DataStore 的独立键中，不会触发同步配置重启。
- VCPChat 插件游标保存在 `AppData/ChatSyncBridge/remote-cursors.json`。
- 只有增量模式需要给 Firebase rules 的 `conversations` 加 `.indexOn: ["updatedAt"]`。

当前仍然遵守只新增策略：增量拉取只减少“读哪些远端 envelope”，不会引入远端删除传播，也不会用更短的远端记录截断手机本地记录。

验证命令：

```powershell
cd C:\VCP\VCPChat
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncBridgeService.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncBridge.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\ChatSyncRemoteProvider.js
node --check VCPDistributedServer\Plugin\ChatSyncBridge\chatSyncMapper.js

cd C:\VCP\Eric\UniVCP
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.sync.chat.ChatSyncMapperTest" --console=plain
```

运行时诊断：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:5974/chat-sync/diagnostics |
  ConvertTo-Json -Depth 10
```

期望结果：

- `ok` 为 `true`。
- `checks` 中 `service_enabled`、`service_started`、`provider_enabled`、`app_data_dir`、`user_data_dir` 通过。
- `last_error` 为 `none`。

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
CHAT_SYNC_DIRECTION=push
CHAT_SYNC_FIREBASE_DATABASE_URL=https://your-project-default-rtdb.region.firebasedatabase.app
CHAT_SYNC_FIREBASE_AUTH_TOKEN=
CHAT_SYNC_ROOM_ID=univcp-main
CHAT_SYNC_DEVICE_ID=vcpchat-desktop-dev
CHAT_SYNC_DEBOUNCE_MS=800
CHAT_SYNC_PRESENCE_INTERVAL_MS=15000
CHAT_SYNC_DEFAULT_AGENT_ID=_Agent_xxx
CHAT_SYNC_DEFAULT_ITEM_TYPE=agent
```

说明：

- `CHAT_SYNC_DEFAULT_AGENT_ID` 用于 VCPChat 接收 UniVCP 新建会话时的默认写入 Agent。
- VCPChat -> UniVCP 时，每条会话会携带对应 Agent 的 `name` 和 `systemPrompt`。
- UniVCP -> VCPChat 时，如果同步包带有 `assistant`，VCPChat 会用它更新 Agent 的 `name/systemPrompt`。

## VCPChat 插件化现状

`C:\VCP\VCPChat\VCPDistributedServer\Plugin\ChatSyncBridge` 当前已经按 VCPChat 分布式服务器插件形态组织：

- `plugin-manifest.json` 声明 `pluginType=hybridservice`，入口为 `ChatSyncBridgeService.js`。
- VCPDistributedServer 启动时会扫描 `VCPDistributedServer/Plugin/*/plugin-manifest.json`，自动读取插件目录的 `config.env`，并直接加载 `hybridservice` 服务模块。
- 插件提供 `Status`、`Diagnostics`、`SyncOnce`、`Start`、`Stop`、`ListTargets`、`ExportTopic`、`ExportAll`、`ImportTopic`、`ImportBundle` 等命令。
- 插件目录包含 `config.env.example`、`README.md` 和 `package.json`。当前 VCPChat 主项目已有运行依赖；单独分发给旧版本时，可在插件目录执行 `npm install --omit=dev` 安装 `axios`、`chokidar`、`fs-extra`。

给普通用户的安装路径是复制整个 `ChatSyncBridge` 文件夹到 VCPChat 的 `VCPDistributedServer/Plugin/` 下，然后把 `config.env.example` 复制为 `config.env` 并填写 Firebase URL、Room ID。推荐桌面端默认使用 `CHAT_SYNC_DIRECTION=push`，手机端 UniVCP 使用“手机只接收”，这样手机删除或修改不会反向影响电脑端。

## Firebase RTDB

数据路径：

```text
chatSync/{base64url(roomId)}/conversations/{base64url(conversationId)}
```

开发时可临时使用按房间开放的规则。默认全量拉取不需要索引：

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
如果开启高级选项“增量拉取”，再给 `conversations` 增加索引：

```json
"conversations": {
  ".indexOn": ["updatedAt"]
}
```

`.indexOn` 用于支持 `orderBy="updatedAt"&startAt=...` 增量拉取，避免每次同步都下载整个房间。

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
