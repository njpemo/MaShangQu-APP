package com.aggapp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppWindowLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val keyboardTargetHeight = mutableIntStateOf(0)
    private val keyboardHeight = mutableIntStateOf(0)
    private val navigationHeight = mutableIntStateOf(64)
    private lateinit var padding: MutableState<androidx.compose.ui.unit.Dp>
    private var pixelsPerDp = 1f
    private var navigationCreations = 0
    private var navigationDisposals = 0
    private var navigationClicks = 0

    @Test
    fun statusInsetIsAppliedOnceAndGestureRegionUsesPageBackground() {
        showLayout()
        val root = bounds("app-window-layout")
        val header = bounds("fixture-header")
        assertEquals(30 * pixelsPerDp, header.top - root.top, 1f)
        assertEquals(24 * pixelsPerDp + 12 * pixelsPerDp, root.bottom - bounds("fixture-navigation").bottom, 1f)
        val pixels = compose.onNodeWithTag("app-window-layout").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[pixels.width / 2, 1])
        assertEquals(Color(0xFFF6F5FB), pixels[pixels.width / 2, pixels.height - 1])
    }

    @Test
    fun threeButtonInsetsAndLargeNavigationLeaveTheLastItemAboveTheBar() {
        showLayout(navigationInset = 48, densityOverride = 2.625f)
        compose.runOnIdle { navigationHeight.intValue = 96 }
        compose.onNodeWithTag("fixture-scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 100000f)
        }
        val bar = bounds("fixture-navigation")
        val root = bounds("app-window-layout")
        assertEquals((48 + 12) * pixelsPerDp, root.bottom - bar.bottom, 1f)
        assertEquals(12 * pixelsPerDp, bar.top - bounds("fixture-last").bottom, 1f)
        assertAvoidanceMatchesMeasuredNavigation()
    }

    @Test
    fun keyboardHidesNavigationForOpenTargetAndRestoresWhenClosingStarts() {
        showLayout()
        compose.runOnIdle { keyboardTargetHeight.intValue = 300 }
        compose.onNodeWithTag("fixture-navigation").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(0.dp, padding.value); keyboardHeight.intValue = 300 }
        val root = bounds("app-window-layout")
        assertEquals(300 * pixelsPerDp, root.bottom - bounds("fixture-scroll").bottom, 1f)
        compose.runOnIdle { keyboardTargetHeight.intValue = 0 }
        compose.onNodeWithTag("fixture-navigation").assertIsDisplayed()
        compose.runOnIdle { keyboardHeight.intValue = 0 }
        compose.onNodeWithTag("fixture-navigation").assertExists()
        assertAvoidanceMatchesMeasuredNavigation()
    }

    @Test
    fun closingTargetRestoresOnNextFrameWithoutWaitingForStaleHeight() {
        showLayout()
        compose.runOnIdle { keyboardTargetHeight.intValue = 300; keyboardHeight.intValue = 300 }
        compose.onNodeWithTag("fixture-navigation").assertIsNotDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { keyboardTargetHeight.intValue = 0 }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("fixture-navigation").assertIsDisplayed()
            // A delayed OEM progress/end callback must not add a one-second hidden interval.
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("fixture-navigation").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(300, keyboardHeight.intValue)
                assertEquals(1, navigationCreations)
                assertEquals(0, navigationDisposals)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun repeatedKeyboardCyclesRetainNavigationAndDisableHiddenInputAndSemantics() {
        showLayout()
        val root = bounds("app-window-layout")
        val location = bounds("fixture-navigation").center - root.topLeft
        repeat(3) {
            compose.runOnIdle { keyboardTargetHeight.intValue = 300; keyboardHeight.intValue = 300 }
            compose.onNodeWithTag("fixture-navigation").assertIsNotDisplayed()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
            compose.onNodeWithTag("app-window-layout").performTouchInput { click(location) }
            compose.runOnIdle {
                assertEquals(1, navigationCreations)
                assertEquals(0, navigationDisposals)
                assertEquals(0, navigationClicks)
                keyboardTargetHeight.intValue = 0
                keyboardHeight.intValue = 0
            }
            compose.onNodeWithTag("fixture-navigation").assertExists()
        }
        compose.onNodeWithTag("fixture-navigation").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, navigationCreations); assertEquals(1, navigationClicks) }
    }

    @Test
    fun navigationIsMeasuredWhileHiddenAndRestoresWithoutASecondPaddingFrame() {
        showLayout()
        compose.runOnIdle { keyboardTargetHeight.intValue = 300; keyboardHeight.intValue = 300 }
        compose.runOnIdle { navigationHeight.intValue = 96 }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { keyboardTargetHeight.intValue = 0; keyboardHeight.intValue = 0 }
            compose.mainClock.advanceTimeByFrame()
            assertAvoidanceMatchesMeasuredNavigation()
            compose.runOnIdle { assertEquals(1, navigationCreations); assertEquals(0, navigationDisposals) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun darkThemeHasMatchingStatusAndNavigationBackgrounds() {
        showLayout(dark = true)
        val pixels = compose.onNodeWithTag("app-window-layout").captureToImage().toPixelMap()
        assertEquals(Color(0xFF1A1B23), pixels[pixels.width / 2, 1])
        assertEquals(Color(0xFF12131A), pixels[pixels.width / 2, pixels.height - 1])
    }

    private fun showLayout(navigationInset: Int = 24, dark: Boolean = false, densityOverride: Float? = null) {
        padding = mutableStateOf(0.dp)
        compose.setContent {
            val deviceDensity = LocalDensity.current
            val density = Density(densityOverride ?: deviceDensity.density, deviceDensity.fontScale)
            pixelsPerDp = density.density
            val palette = if (dark) darkColorScheme(surface = Color(0xFF1A1B23), background = Color(0xFF12131A))
            else lightColorScheme(surface = Color.White, background = Color(0xFFF6F5FB))
            CompositionLocalProvider(LocalDensity provides density) {
              MaterialTheme(colorScheme = palette) {
                AppWindowLayout(
                    modifier = Modifier.size(320.dp, 640.dp),
                    safeInsets = WindowInsets(0, with(density) { 30.dp.roundToPx() }, 0, 0),
                    navigationInsets = WindowInsets(0, 0, 0, with(density) { navigationInset.dp.roundToPx() }),
                    keyboardInsets = WindowInsets(0, 0, 0, with(density) { keyboardHeight.intValue.dp.roundToPx() }),
                    keyboardTargetInsets = WindowInsets(0, 0, 0, with(density) { keyboardTargetHeight.intValue.dp.roundToPx() }),
                    content = { bottomPadding ->
                        androidx.compose.runtime.SideEffect { padding.value = bottomPadding }
                        Column(Modifier.fillMaxSize().testTag("fixture-scroll")
                            .verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) {
                            Text("页头", Modifier.fillMaxWidth().height(48.dp).testTag("fixture-header"))
                            repeat(20) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                            Text("最后一项", Modifier.fillMaxWidth().height(48.dp).testTag("fixture-last"))
                        }
                    },
                    navigation = { _, interactive ->
                        DisposableEffect(Unit) {
                            navigationCreations++
                            onDispose { navigationDisposals++ }
                        }
                        Box(Modifier.fillMaxWidth().height(navigationHeight.intValue.dp)
                            .clickable(enabled = interactive, role = Role.Tab) { navigationClicks++ }
                            .background(Color.Blue).testTag("fixture-navigation"))
                    },
                )
              }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertAvoidanceMatchesMeasuredNavigation() {
        val occupiedPixels = (bounds("app-window-layout").bottom - bounds("fixture-navigation").top).roundToInt() +
            (12 * pixelsPerDp).roundToInt()
        compose.runOnIdle { assertEquals(occupiedPixels, (padding.value.value * pixelsPerDp).roundToInt()) }
    }
}
