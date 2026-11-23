package com.devingo.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start acceptance: `startupCompilationNone` (fresh, no profile) vs
 * `startupCompilationPartial` (packaged baseline-prof.txt installed via
 * ProfileInstaller). Run against the release-shaped "profile" variant:
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkProfileAndroidTest
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun setup() = grantNotificationPermission()

    @Test
    fun startupCompilationNone() = startup(CompilationMode.None())

    @Test
    fun startupCompilationPartial() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Wait for the home list to render so the iteration ends on a real screen.
        device.wait(Until.hasObject(By.scrollable(true)), 10_000)
    }
}
