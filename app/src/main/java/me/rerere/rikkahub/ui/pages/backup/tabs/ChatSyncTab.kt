package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import java.time.Instant
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sync.chat.ChatSyncConfig
import me.rerere.rikkahub.data.sync.chat.ChatSyncDirection
import me.rerere.rikkahub.data.sync.chat.ChatSyncMode
import me.rerere.rikkahub.data.sync.chat.ChatSyncPeerStatus
import me.rerere.rikkahub.data.sync.chat.ChatSyncRunResult
import me.rerere.rikkahub.data.sync.chat.allowsPull
import me.rerere.rikkahub.data.sync.chat.allowsPush
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime

@Composable
fun ChatSyncTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncState by vm.chatSyncState.collectAsStateWithLifecycle()
    val config = settings.chatSyncConfig
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    fun updateConfig(newConfig: ChatSyncConfig) {
        vm.updateSettings(settings.copy(chatSyncConfig = newConfig))
    }

    fun showSyncResult(resultText: (pushed: Int, imported: Int) -> String, result: ChatSyncRunResult) {
        val skippedReason = result.skippedReason
        if (skippedReason != null && result.pushed == 0 && result.imported == 0) {
            toaster.show("同步跳过：$skippedReason", type = ToastType.Info)
        } else if (skippedReason != null) {
            toaster.show("${resultText(result.pushed, result.imported)}；$skippedReason", type = ToastType.Info)
        } else {
            toaster.show(resultText(result.pushed, result.imported), type = ToastType.Success)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = { Text("Firebase 实时聊天同步") },
                    supportingContent = {
                        val status = when {
                            syncState.lastError != null -> "异常：${syncState.lastError}"
                            syncState.running -> "运行中，房间：${syncState.roomId}"
                            config.enabled -> "已启用，等待配置生效"
                            else -> "未启用"
                        }
                        Text("VCP / VCPChat 专用配置 · $status · ${config.usageMode().label}")
                    },
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { updateConfig(config.copy(enabled = it)) }
                        )
                    }
                )
                item(
                    headlineContent = { Text("同步统计") },
                    supportingContent = {
                        Text(
                            listOf(
                                "已推送 ${syncState.pushedCount}",
                                "已导入 ${syncState.importedCount}",
                                "设备 ${syncState.deviceId.ifBlank { "-" }}"
                            ).joinToString(" · ")
                        )
                    }
                )
                if (syncState.lastPushAt != null || syncState.lastImportAt != null) {
                    item(
                        headlineContent = { Text("最近同步") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    syncState.lastPushAt?.let { "推送 ${Instant.ofEpochMilli(it).toLocalDateTime()}" },
                                    syncState.lastImportAt?.let { "导入 ${Instant.ofEpochMilli(it).toLocalDateTime()}" },
                                ).joinToString("\n")
                            )
                        }
                    )
                }
            }

            CardGroup(title = { Text("Firebase") }) {
                item(
                    headlineContent = { Text("Realtime Database URL") },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = config.firebaseDatabaseUrl,
                            onValueChange = { updateConfig(config.copy(firebaseDatabaseUrl = it.trim())) },
                            placeholder = { Text("https://your-project-default-rtdb.firebaseio.com") },
                            singleLine = true,
                        )
                    }
                )
                item(
                    headlineContent = { Text("Auth Token") },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = config.firebaseAuthToken,
                            onValueChange = { updateConfig(config.copy(firebaseAuthToken = it.trim())) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                )
                item(
                    headlineContent = { Text("Room ID") },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = config.roomId,
                            onValueChange = { updateConfig(config.copy(roomId = it.trim().ifBlank { "default" })) },
                            placeholder = { Text("default") },
                            singleLine = true,
                        )
                    }
                )
                item(
                    headlineContent = { Text("Device ID") },
                    supportingContent = {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = config.deviceId,
                            onValueChange = { updateConfig(config.copy(deviceId = it.trim())) },
                            placeholder = { Text("留空会在启用时自动生成") },
                            singleLine = true,
                        )
                    }
                )
            }

            CardGroup(title = { Text("使用方式") }) {
                item(
                    headlineContent = { Text("同步模式") },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                ChatSyncUsageMode.entries.forEachIndexed { index, usageMode ->
                                    SegmentedButton(
                                        selected = config.usageMode() == usageMode,
                                        onClick = { updateConfig(config.applyUsageMode(usageMode)) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = ChatSyncUsageMode.entries.size,
                                        ),
                                        modifier = Modifier.height(44.dp),
                                    ) {
                                        Text(usageMode.shortLabel)
                                    }
                                }
                            }
                            Text(
                                text = config.usageMode().description,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                )
                if (config.mode == ChatSyncMode.BOTH) {
                    item(
                        headlineContent = { Text("双向警告") },
                        supportingContent = {
                            Text("双向模式会让手机和电脑都参与写入。只有两端都可信、且你明确需要双向时才建议开启。")
                        }
                    )
                }
                item(
                    headlineContent = { Text("当前保护状态") },
                    supportingContent = { Text(guardStatus(config)) }
                )
                item(
                    headlineContent = { Text("VCPChat 状态") },
                    supportingContent = {
                        Text(vcpChatPresenceText(syncState.vcpChatPresence))
                    }
                )
                item(
                    headlineContent = { Text("另一端设置") },
                    supportingContent = {
                        Text(peerRequirement(config))
                    }
                )
                item(
                    headlineContent = { Text("修复模式") },
                    supportingContent = {
                        Text("重新扫描 Firebase，并按 Agent 与 Topic 归属修复手机端会话。同步只新增缺失内容，不会删除或截短本机记录。")
                    }
                )
            }

            CardGroup(title = { Text("Firebase 填写指南") }) {
                item(
                    headlineContent = { Text("1. 创建 Firebase 项目") },
                    supportingContent = {
                        Text("打开 console.firebase.google.com，新建项目；Google Analytics 可以先不启用。")
                    }
                )
                item(
                    headlineContent = { Text("2. 创建 Realtime Database") },
                    supportingContent = {
                        Text("在 Firebase 控制台进入 Realtime Database，点击创建数据库。地区选离你常用网络近的即可；新手测试可先用测试模式。默认全量拉取不需要索引；只有在高级选项开启“增量拉取”时，才需要在 rules 中给 conversations 加 .indexOn: [\"updatedAt\"]。")
                    }
                )
                item(
                    headlineContent = { Text("3. 填 Database URL") },
                    supportingContent = {
                        Text("创建后复制 Realtime Database 页面顶部的网址，形如 https://xxx-default-rtdb.firebaseio.com 或 https://xxx-default-rtdb.asia-southeast1.firebasedatabase.app。")
                    }
                )
                item(
                    headlineContent = { Text("4. Auth Token 可以先留空") },
                    supportingContent = {
                        Text("如果你的 Firebase 规则允许当前 room 读写，Auth Token 留空就能用。只有你配置了需要登录或服务端 token 的规则时，才需要填写 token。")
                    }
                )
                item(
                    headlineContent = { Text("5. Room ID 两端必须一致") },
                    supportingContent = {
                        Text("Room ID 是同步房间名，手机端和 VCPChat 端要填同一个值。建议用不容易猜到的英文名，例如 univcp-main-你的昵称。")
                    }
                )
            }

            CardGroup(title = { Text("高级选项") }) {
                item(
                    headlineContent = { Text("启动时自动拉取") },
                    supportingContent = { Text("打开应用后自动把远端新记录同步到手机。") },
                    trailingContent = {
                        Switch(
                            checked = config.importRemoteOnStart,
                            enabled = config.mode.allowsPull(),
                            onCheckedChange = { updateConfig(config.copy(importRemoteOnStart = it)) }
                        )
                    }
                )
                item(
                    headlineContent = { Text("增量拉取") },
                    supportingContent = {
                        Text(
                            if (config.incrementalPull) {
                                "只拉取上次同步后更新的记录。需要 Firebase rules 给 conversations 配置 .indexOn: [\"updatedAt\"]。"
                            } else {
                                "默认全量读取远端记录，再在本机只新增合并；不需要 Firebase 索引配置。"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = config.incrementalPull,
                            enabled = config.mode.allowsPull(),
                            onCheckedChange = { updateConfig(config.copy(incrementalPull = it)) }
                        )
                    }
                )
                item(
                    headlineContent = { Text("导出范围") },
                    supportingContent = {
                        Text(if (config.currentPathOnly) "推送时只导出当前选中的对话分支。" else "推送时导出全部分支。")
                    },
                    trailingContent = {
                        Switch(
                            checked = config.currentPathOnly,
                            enabled = config.mode.allowsPush(),
                            onCheckedChange = { updateConfig(config.copy(currentPathOnly = it)) }
                        )
                    }
                )
            }
        }

        HorizontalDivider()
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            vm.testChatSync()
                        }.onSuccess {
                            toaster.show("Firebase 连接成功", type = ToastType.Success)
                        }.onFailure { error ->
                            toaster.show("Firebase 连接失败：${error.message.orEmpty()}", type = ToastType.Error)
                        }
                    }
                }
            ) {
                Text("测试连接")
            }
            TextButton(
                enabled = config.mode.allowsPull(),
                onClick = {
                    scope.launch {
                        runCatching {
                            vm.repairChatSync()
                        }.onSuccess { result ->
                            val skippedReason = result.skippedReason
                            if (skippedReason != null) {
                                toaster.show("修复跳过：$skippedReason", type = ToastType.Info)
                            } else {
                                toaster.show(
                                    "修复完成：检查 ${result.checked}，修复 ${result.repaired}",
                                    type = ToastType.Success,
                                )
                            }
                        }.onFailure { error ->
                            toaster.show("修复失败：${error.message.orEmpty()}", type = ToastType.Error)
                        }
                    }
                }
            ) {
                Text("修复")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            vm.syncChatNow(config.mode.defaultDirection())
                        }.onSuccess { result ->
                            showSyncResult({ pushed, imported -> syncResultText(config, pushed, imported) }, result)
                        }.onFailure { error ->
                            toaster.show("同步失败：${error.message.orEmpty()}", type = ToastType.Error)
                        }
                    }
                }
            ) {
                Text("立即同步")
            }
        }
    }
}

private enum class ChatSyncUsageMode(
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    MOBILE_READ_ONLY(
        label = "手机只接收",
        shortLabel = "接收",
        description = "推荐：电脑端作为主库，手机端只拉取 VCPChat 记录。",
    ),
    TWO_WAY(
        label = "双向",
        shortLabel = "双向",
        description = "手机端和电脑端都会收发变更，请确认两端都可信。",
    ),
    MOBILE_UPLOAD(
        label = "本机上传",
        shortLabel = "上传",
        description = "手机端作为源端，把本机记录上传给另一端。",
    ),
}

private fun ChatSyncConfig.usageMode(): ChatSyncUsageMode {
    return when (mode) {
        ChatSyncMode.PULL_ONLY -> ChatSyncUsageMode.MOBILE_READ_ONLY
        ChatSyncMode.PUSH_ONLY -> ChatSyncUsageMode.MOBILE_UPLOAD
        ChatSyncMode.BOTH -> ChatSyncUsageMode.TWO_WAY
    }
}

private fun ChatSyncConfig.applyUsageMode(mode: ChatSyncUsageMode): ChatSyncConfig {
    return when (mode) {
        ChatSyncUsageMode.MOBILE_READ_ONLY -> copy(
            mode = ChatSyncMode.PULL_ONLY,
            importRemoteOnStart = true,
            pushOnStart = false,
            currentPathOnly = true,
        )

        ChatSyncUsageMode.TWO_WAY -> copy(
            mode = ChatSyncMode.BOTH,
            importRemoteOnStart = true,
            pushOnStart = false,
            currentPathOnly = true,
        )

        ChatSyncUsageMode.MOBILE_UPLOAD -> copy(
            mode = ChatSyncMode.PUSH_ONLY,
            importRemoteOnStart = false,
            pushOnStart = false,
            currentPathOnly = true,
        )
    }
}

private fun ChatSyncMode.defaultDirection(): ChatSyncDirection {
    return when (this) {
        ChatSyncMode.PULL_ONLY -> ChatSyncDirection.PULL
        ChatSyncMode.PUSH_ONLY -> ChatSyncDirection.PUSH
        ChatSyncMode.BOTH -> ChatSyncDirection.BOTH
    }
}

private fun guardStatus(config: ChatSyncConfig): String {
    return when {
        config.mode == ChatSyncMode.PULL_ONLY && !config.pushOnStart -> {
            "手机端不会推送本地变更；删除或修改不会反向影响电脑端。"
        }

        config.mode == ChatSyncMode.PULL_ONLY -> {
            "当前为仅拉取，但启动时推送仍开启；建议关闭启动时推送。"
        }

        config.mode == ChatSyncMode.BOTH -> {
            "当前为双向同步；手机端变更可能写回远端。"
        }

        else -> {
            "当前为仅推送；适合把手机端作为源端，不适合保护电脑端主库。"
        }
    }
}

private fun peerRequirement(config: ChatSyncConfig): String {
    return when (config.usageMode()) {
        ChatSyncUsageMode.MOBILE_READ_ONLY -> {
            "电脑端作为主库：VCPChat 设置 CHAT_SYNC_DIRECTION=push。"
        }

        ChatSyncUsageMode.TWO_WAY -> {
            "两端都会收发：VCPChat 设置 CHAT_SYNC_DIRECTION=both。"
        }

        ChatSyncUsageMode.MOBILE_UPLOAD -> {
            "手机端作为源端：另一端应使用仅拉取或 CHAT_SYNC_DIRECTION=pull。"
        }
    }
}

private fun vcpChatPresenceText(status: ChatSyncPeerStatus): String {
    return when (status) {
        ChatSyncPeerStatus.Unknown ->
            "未知：还没有收到 VCPChat 在线心跳。可以拉取 Firebase 已有记录，但无法确认电脑端是否正在推送新消息。"

        is ChatSyncPeerStatus.Online -> {
            val direction = status.direction?.let { "，方向 $it" }.orEmpty()
            "在线：${status.deviceId}$direction，最近心跳 ${Instant.ofEpochMilli(status.updatedAt).toLocalDateTime()}。"
        }

        is ChatSyncPeerStatus.Offline ->
            "离线：${status.deviceId} 最近心跳 ${Instant.ofEpochMilli(status.updatedAt).toLocalDateTime()}。仍可拉取 Firebase 已有记录，但电脑端新回复不会继续同步到手机。"
    }
}

private fun syncResultText(config: ChatSyncConfig, pushed: Int, imported: Int): String {
    return when (config.mode) {
        ChatSyncMode.PULL_ONLY -> "同步完成：导入 $imported"
        ChatSyncMode.PUSH_ONLY -> "同步完成：推送 $pushed"
        ChatSyncMode.BOTH -> "同步完成：推送 $pushed，导入 $imported"
    }
}
