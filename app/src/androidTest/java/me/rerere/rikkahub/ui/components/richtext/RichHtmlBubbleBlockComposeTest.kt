package me.rerere.rikkahub.ui.components.richtext

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichHtmlBubbleBlockComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun richHtmlBubbleRendersInteractiveContent() {
        var sentInput: String? = null

        composeRule.setContent {
            MaterialTheme {
                RichHtmlBubbleBlock(
                    html = InteractiveCardHtml,
                    modifier = Modifier.padding(12.dp),
                    onSendInput = { sentInput = it },
                )
            }
        }

        composeRule.waitUntilTextExists("学习计划卡片")
        composeRule.onNodeWithText("学习计划卡片").assertIsDisplayed()
        composeRule.onNodeWithText("今日重点：Compose 原生富文本。").assertIsDisplayed()

        composeRule.onAllNodesWithTag("rich-html-details").onFirst().performClick()
        composeRule.waitUntilTextExists("展开内容：列表滚动后仍然可见。")
        composeRule.onNodeWithText("展开内容：列表滚动后仍然可见。").assertIsDisplayed()

        composeRule.onNodeWithTag("rich-html-button").performClick()
        composeRule.runOnIdle {
            assertEquals("继续学习", sentInput)
        }
    }

    @Test
    fun unsafeRichHtmlUsesRenderFallback() {
        composeRule.setContent {
            MaterialTheme {
                RichHtmlBubbleBlock(
                    html = """<div id="vcp-root"><iframe src="https://example.com"></iframe></div>""",
                    modifier = Modifier.padding(12.dp),
                    renderFallback = {
                        Text("动态预览兜底")
                    },
                )
            }
        }

        composeRule.waitUntilTextExists("动态预览兜底")
        composeRule.onNodeWithText("动态预览兜底").assertIsDisplayed()
    }

    @Test
    fun lazyColumnScrollsToOffscreenRichHtmlBubble() {
        var sentInput: String? = null

        composeRule.setContent {
            MaterialTheme {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("rich-html-scroll-list")
                        .padding(horizontal = 12.dp),
                ) {
                    items(
                        count = 72,
                        key = { index -> "render-row-$index" },
                    ) { index ->
                        RenderScrollRow(
                            index = index,
                            richHtml = if (index == 58) ScrollTargetHtml else null,
                            onSendInput = { sentInput = it },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("rich-html-scroll-list")
            .performScrollToNode(hasText("消息项 58"))
        composeRule.onNodeWithText("消息项 58").assertIsDisplayed()

        composeRule.waitUntilTextExists("滚动目标卡片")
        composeRule.onNodeWithText("滚动目标卡片").assertIsDisplayed()
        composeRule.onNodeWithTag("rich-html-button").performClick()
        composeRule.runOnIdle {
            assertEquals("滚动目标", sentInput)
        }
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            runCatching {
                onNodeWithText(text).assertExists()
            }.isSuccess
        }
    }
}

@Composable
private fun RenderScrollRow(
    index: Int,
    richHtml: String?,
    onSendInput: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text("消息项 $index", style = MaterialTheme.typography.labelLarge)
        if (richHtml == null) {
            Text("普通内容 $index", style = MaterialTheme.typography.bodyMedium)
        } else {
            RichHtmlBubbleBlock(
                html = richHtml,
                modifier = Modifier.padding(top = 8.dp),
                onSendInput = onSendInput,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

private val InteractiveCardHtml = """
    <div id="vcp-root" style="padding:12px;border:1px solid #cbd5e1;border-radius:12px;background:#f8fafc;color:#0f172a;">
      <h2>学习计划卡片</h2>
      <p>今日重点：Compose 原生富文本。</p>
      <details>
        <summary>摘要</summary>
        <p>展开内容：列表滚动后仍然可见。</p>
      </details>
      <button data-send="继续学习">继续</button>
    </div>
""".trimIndent()

private val ScrollTargetHtml = """
    <div id="vcp-root" style="padding:12px;border-radius:12px;background:#ecfeff;color:#164e63;">
      <h2>滚动目标卡片</h2>
      <p>这个富 HTML 气泡位于长列表深处，用来验证自动滚动和延迟编译。</p>
      <button data-send="滚动目标">提交滚动动作</button>
    </div>
""".trimIndent()
