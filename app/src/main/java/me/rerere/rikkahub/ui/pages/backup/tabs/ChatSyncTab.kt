package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
                        Text(status)
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

            CardGroup(title = { Text("策略") }) {
                item(
                    headlineContent = { Text("启动时导入远端更新") },
                    supportingContent = { Text("开启后，应用启动会先拉取 Firebase 中的聊天记录。") },
                    trailingContent = {
                        Switch(
                            checked = config.importRemoteOnStart,
                            onCheckedChange = { updateConfig(config.copy(importRemoteOnStart = it)) }
                        )
                    }
                )
                item(
                    headlineContent = { Text("启动时推送本地记录") },
                    supportingContent = { Text("首次配置或换房间时可手动开启一次，平时建议关闭。") },
                    trailingContent = {
                        Switch(
                            checked = config.pushOnStart,
                            onCheckedChange = { updateConfig(config.copy(pushOnStart = it)) }
                        )
                    }
                )
                item(
                    headlineContent = { Text("只同步当前分支") },
                    supportingContent = { Text("开启后只同步每个消息节点当前选中的分支。") },
                    trailingContent = {
                        Switch(
                            checked = config.currentPathOnly,
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
                onClick = {
                    scope.launch {
                        val result = vm.syncChatNow(ChatSyncDirection.PULL)
                        toaster.show("已导入 ${result.imported} 个会话", type = ToastType.Success)
                    }
                }
            ) {
                Text("拉取")
            }
            TextButton(
                onClick = {
                    scope.launch {
                        val result = vm.syncChatNow(ChatSyncDirection.PUSH)
                        toaster.show("已推送 ${result.pushed} 个会话", type = ToastType.Success)
                    }
                }
            ) {
                Text("推送")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = vm.syncChatNow(ChatSyncDirection.BOTH)
                        toaster.show("同步完成：推送 ${result.pushed}，导入 ${result.imported}", type = ToastType.Success)
                    }
                }
            ) {
                Text("立即同步")
            }
        }
    }
}
