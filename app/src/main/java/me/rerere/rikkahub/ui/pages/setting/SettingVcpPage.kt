package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingVcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("VCP 服务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text("VCP 资源与日志") },
                ) {
                    item(
                        headlineContent = { Text("VCP WebSocket 服务器 URL") },
                        supportingContent = {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    value = settings.vcpLogUrl,
                                    onValueChange = { value ->
                                        vm.updateSettings(settings.copy(vcpLogUrl = value.trim()))
                                    },
                                    placeholder = { Text("ws://localhost:6005") },
                                    singleLine = true,
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("VCP 文件/图床密码") },
                        supportingContent = {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    value = settings.vcpFileKey,
                                    onValueChange = { value ->
                                        vm.updateSettings(settings.copy(vcpFileKey = value.trim()))
                                    },
                                    placeholder = { Text("用于拼接 /pw=密码/images/...") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                )
                                Text("用于修复 VCPChat 表情包与文件图片地址中的 /pw=... 段。")
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("VCP WebSocket 鉴权 Key") },
                        supportingContent = {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    value = settings.vcpLogKey,
                                    onValueChange = { value ->
                                        vm.updateSettings(settings.copy(vcpLogKey = value.trim()))
                                    },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
