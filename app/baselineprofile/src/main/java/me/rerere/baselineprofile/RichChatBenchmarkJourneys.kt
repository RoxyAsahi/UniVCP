package me.rerere.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

private const val RichChatWaitTimeoutMs = 5_000L

internal fun MacrobenchmarkScope.scrollRichChatJourney(repetitions: Int = 5) {
    device.wait(Until.hasObject(By.scrollable(true)), RichChatWaitTimeoutMs)
    val feed = device.findObject(By.scrollable(true)) ?: return
    repeat(repetitions) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
        feed.fling(Direction.UP)
        device.waitForIdle()
    }
}
