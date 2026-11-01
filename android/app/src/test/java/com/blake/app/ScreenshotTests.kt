package com.blake.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.blake.app.ui.theme.BlakeTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for every top-level screen, light and dark (ROADMAP-NEXTGEN.md P1 rule 6).
 *
 * These capture the screen immediately after first composition, without waiting for any
 * network call its ViewModel kicks off (Today/Timeline/Settings all fetch on launch) — so
 * what's captured is the initial/loading shell, not server-dependent content. That's
 * intentional: it keeps these tests fast, deterministic, and independent of the real backend
 * (CI has no BLAKE_API_KEY configured for the Android job, and hitting the live Render/Neon
 * stack from a unit test on every push would be slow and flaky on cold starts). It still
 * fully validates what this phase cares about — theme, edge-to-edge, nav shell, dark mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ScreenshotTests {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun capture(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            // dynamicColor off: Robolectric has no real wallpaper, so dynamic color would be
            // non-deterministic noise rather than a meaningful screenshot difference.
            BlakeTheme(darkTheme = dark, dynamicColor = false) { content() }
        }
        composeTestRule.onRoot().captureRoboImage("${name}_${if (dark) "dark" else "light"}.png")
    }

    @Test
    fun today_light() = capture("today", dark = false) { TodayScreen() }

    @Test
    fun today_dark() = capture("today", dark = true) { TodayScreen() }

    @Test
    fun timeline_light() = capture("timeline", dark = false) { TimelineScreen() }

    @Test
    fun timeline_dark() = capture("timeline", dark = true) { TimelineScreen() }

    @Test
    fun insights_light() = capture("insights", dark = false) { InsightsScreen() }

    @Test
    fun insights_dark() = capture("insights", dark = true) { InsightsScreen() }

    @Test
    fun settings_light() = capture("settings", dark = false) { SettingsScreen(onBack = {}) }

    @Test
    fun settings_dark() = capture("settings", dark = true) { SettingsScreen(onBack = {}) }
}
