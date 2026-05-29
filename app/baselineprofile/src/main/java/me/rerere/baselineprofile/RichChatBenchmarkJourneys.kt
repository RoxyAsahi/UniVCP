package me.rerere.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry

private const val RichChatWaitTimeoutMs = 5_000L
private const val RichChatFeedResourceId = "rich-chat-feed"

internal fun MacrobenchmarkScope.scrollRichChatJourney(repetitions: Int = 5) {
    val feed = findRichChatFeed() ?: return
    repeat(repetitions) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
        feed.fling(Direction.UP)
        device.waitForIdle()
    }
}

internal fun MacrobenchmarkScope.scrollRichChatHistoryJourney(repetitions: Int = 8) {
    val feed = findRichChatFeed() ?: return
    repeat(2) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(repetitions) {
        feed.fling(Direction.UP)
        device.waitForIdle()
    }
}

private fun MacrobenchmarkScope.findRichChatFeed(): androidx.test.uiautomator.UiObject2? {
    val targetPackage = InstrumentationRegistry.getArguments().getString("targetAppId").orEmpty()
    val taggedSelector = By.res(targetPackage, RichChatFeedResourceId)
    if (targetPackage.isNotBlank() && device.wait(Until.hasObject(taggedSelector), RichChatWaitTimeoutMs)) {
        return device.findObject(taggedSelector)
    }
    device.wait(Until.hasObject(By.scrollable(true)), RichChatWaitTimeoutMs)
    return device.findObject(By.scrollable(true))
}
