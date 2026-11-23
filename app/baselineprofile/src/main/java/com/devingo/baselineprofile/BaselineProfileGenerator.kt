package com.devingo.baselineprofile

import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val PACKAGE_NAME = "com.devingo.app"

/**
 * Grant POST_NOTIFICATIONS up front: MainActivity requests it on first launch
 * (API 33+), and the runtime dialog would shadow the measured journey.
 */
internal fun grantNotificationPermission() {
    val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand("pm grant $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
    ParcelFileDescriptor.AutoCloseInputStream(fd)
        .use { it.bufferedReader().readText() }
}

/**
 * Generates the Baseline Profile for the critical user journey: cold start ->
 * home session list -> open a session -> scroll the transcript -> expand a
 * work-log row.
 *
 * Everything past the cold start needs a paired bridge, so each step is
 * best-effort: on a fresh install with no sessions the selectors simply time
 * out and the step is skipped, still producing a cold-start profile.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun setup() = grantNotificationPermission()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        driveCriticalJourney()
    }
}

private const val CONTENT_TIMEOUT_MS = 10_000L
private const val SESSION_TIMEOUT_MS = 5_000L

/** Best-effort CUJ: every step no-ops when its target isn't on screen. */
private fun MacrobenchmarkScope.driveCriticalJourney() {
    // Home session list.
    val list = device.wait(Until.findObject(By.scrollable(true)), CONTENT_TIMEOUT_MS)
        ?: return
    list.setGestureMargin(device.displayWidth / 5)
    list.fling(Direction.DOWN)

    // Session rows carry a trailing "会话选项" overflow button; without a
    // bridge the list is empty and there is nothing to open. Tap the row at
    // a third of the screen width — safely off the overflow button.
    val overflow = device.wait(
        Until.findObject(By.desc("会话选项")),
        SESSION_TIMEOUT_MS,
    ) ?: return
    device.click(device.displayWidth / 3, overflow.visibleBounds.centerY())
    device.waitForIdle()

    // Scroll the transcript.
    device.wait(Until.findObject(By.scrollable(true)), CONTENT_TIMEOUT_MS)?.let { chat ->
        chat.setGestureMargin(device.displayWidth / 5)
        chat.fling(Direction.DOWN)
        chat.fling(Direction.UP)
    }

    // Expand a collapsed work-log / diff row ("展开 · N 行"). The transcript
    // may still be rendering right after navigation, so wait a little.
    device.wait(Until.findObject(By.textStartsWith("展开")), 3_000)?.click()
    device.waitForIdle()
}
