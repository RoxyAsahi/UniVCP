package me.rerere.rikkahub.ui.pages.history;

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.SyncDeleteConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<Conversation?>(null) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.history_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.MessageSearch)
                        }
                    ) {
                        Icon(
                            HugeIcons.GlobalSearch,
                            contentDescription = stringResource(R.string.history_page_search_messages)
                        )
                    }
                    IconButton(
                        onClick = {
                            showDeleteAllDialog = true
                        }
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                SwipeableConversationItem(
                    conversation = conversation,
                    onClick = {
                        navigateToChatPage(navController, conversation.id)
                    },
                    onDelete = {
                        conversationToDelete = conversation
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    conversationToDelete?.let { target ->
        SyncDeleteConfirmDialog(
            show = true,
            title = "删除这段历史？",
            targetName = target.title.ifBlank { stringResource(R.string.history_page_new_conversation) },
            body = "确认后会删除手机本机中的这段对话。删除后可通过底部提示短暂撤销。",
            syncMode = vm.chatSyncMode,
            onConfirm = {
                conversationToDelete = null
                scope.launch {
                    // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                    val fullConversation = vm.getFullConversation(target.id) ?: target
                    vm.deleteConversation(target)
                    val result = snackbarHostState.showSnackbar(
                        message = snackMessageDeleted,
                        actionLabel = snackMessageUndo,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        vm.restoreConversation(fullConversation)
                    }
                }
            },
            onDismiss = {
                conversationToDelete = null
            },
        )
    }

    if (showDeleteAllDialog) {
        SyncDeleteConfirmDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_conversations),
            targetName = assistant?.name?.takeIf { it.isNotBlank() },
            body = "确认后会删除当前助手下手机本机保存的全部历史对话。这个操作范围很大，请先确认电脑端或备份里仍有需要保留的记录。",
            syncMode = vm.chatSyncMode,
            confirmText = stringResource(R.string.history_page_delete),
            dismissText = stringResource(R.string.history_page_cancel),
            onConfirm = {
                vm.deleteAllConversations()
                showDeleteAllDialog = false
            },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        positionalThreshold = positionThreshold,
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false
            } else {
                true
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier
    ) {
        ListItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        )
                    )
                }
            }
        )
    }
}
