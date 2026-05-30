package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.sync.chat.ChatSyncMode

@Composable
fun SyncDeleteConfirmDialog(
    show: Boolean,
    title: String,
    body: String,
    syncMode: ChatSyncMode,
    targetName: String? = null,
    confirmText: String = "删除",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                targetName?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(body)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "同步影响",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = syncMode.deleteWarningText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}

private fun ChatSyncMode.deleteWarningText(): String {
    return when (this) {
        ChatSyncMode.PULL_ONLY ->
            "当前是「手机只接收」。删除只会移除手机本地副本，不会删除电脑端或 Firebase 中仍存在的记录；下次同步或修复可能会把它恢复。"

        ChatSyncMode.PUSH_ONLY ->
            "当前是「本机上传」。本次删除不会向 Firebase 发送删除指令，也不会删除电脑端记录；删除后本机将不再保留这份内容。"

        ChatSyncMode.BOTH ->
            "当前是「双向」。本次删除不会主动向 Firebase 发送删除指令，但本机内容会立刻消失；请先确认电脑端或备份里仍有需要保留的记录。"
    }
}
