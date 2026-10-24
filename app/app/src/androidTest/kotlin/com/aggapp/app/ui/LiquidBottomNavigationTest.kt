package com.aggapp.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aggapp.app.ui.theme.AggTheme
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiquidBottomNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val selections = mutableListOf<String>()
    private lateinit var selectedRoute: MutableState<String>
    private lateinit var fontScale: MutableState<Float>
    private val navigationEnabled = mutableStateOf(true)
    private var focusColor = Color.Unspecified

    @Test
    fun touchClickNavigatesOnceAndSelectedTabDoesNotNavigateAgain() {
        showNavigation()

        tab("mine").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("mine"), selections) }
        tab("mine").assertIsSelected()

        tab("mine").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("mine"), selections) }

        tab("idcode").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("mine", "idcode"), selections) }
    }

    @Test
    fun dragPreviewsAcrossTabsAndCommitsOnlyOnRelease() {
        showNavigation(initialRoute = "idcode")
        val start = tabCenter("idcode")
        val middle = tabCenter("express")
        val end = tabCenter("mine")
        val initialIndicatorX = indicatorCenterX()

        navigation().performTouchInput {
            down(start)
            moveTo(middle, delayMillis = 160)
            moveTo(end, delayMillis = 160)
        }

        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        tab("idcode").assertIsSelected()
        assertTrue(indicatorCenterX() > initialIndicatorX)

        navigation().performTouchInput { up() }

        compose.runOnIdle { assertEquals(listOf("mine"), selections) }
        tab("mine").assertIsSelected()
        tab("idcode").assertIsNotSelected()
    }

    @Test
    fun releaseKeepsTheLensAtTheDestinationWhileNavigationCatchesUp() {
        showNavigation(initialRoute = "mine", updateSelectionImmediately = false)
        val start = tabCenter("mine")
        val end = tabCenter("idcode")
        navigation().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 16)
        }
        compose.mainClock.autoAdvance = false
        try {
            navigation().performTouchInput { up() }
            repeat(19) {
                compose.mainClock.advanceTimeByFrame()
                assertTrue("The released lens must not jump back to mine", indicatorCenterX() < start.x / 2)
            }
            compose.runOnIdle { selectedRoute.value = "idcode" }
            compose.mainClock.advanceTimeBy(500)
            tab("idcode").assertIsSelected()
            compose.runOnIdle { assertEquals(listOf("idcode"), selections) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun cancelledDragDoesNotNavigateAndReturnsIndicatorToSelectedTab() {
        showNavigation()
        val start = tabCenter("express")
        val end = tabCenter("mine")
        val initialIndicatorX = indicatorCenterX()

        navigation().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 160)
        }
        assertTrue(indicatorCenterX() > initialIndicatorX)
        navigation().performTouchInput { cancel() }

        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        tab("express").assertIsSelected()
        tab("mine").assertIsNotSelected()
        assertEquals(initialIndicatorX, indicatorCenterX(), 1f)
    }

    @Test
    fun dragReleasedOnCurrentTabDoesNotNavigate() {
        showNavigation()
        val start = tabCenter("express")
        val end = tabCenter("mine")

        navigation().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 160)
            moveTo(start, delayMillis = 160)
            up()
        }

        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        tab("express").assertIsSelected()
    }

    @Test
    fun externalSelectionUpdatesSemanticsAndIndicatorWithoutNavigationCallback() {
        showNavigation()
        val initialIndicatorX = indicatorCenterX()

        compose.runOnIdle { selectedRoute.value = "mine" }
        tab("mine").assertIsSelected()
        tab("express").assertIsNotSelected()
        assertTrue(indicatorCenterX() > initialIndicatorX)

        compose.runOnIdle { selectedRoute.value = "express" }
        tab("express").assertIsSelected()
        assertEquals(initialIndicatorX, indicatorCenterX(), 1f)
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
    }

    @Test
    fun externalSelectionCancelsAnActiveDragWithoutCommittingItOnRelease() {
        showNavigation()
        val start = tabCenter("express")
        val end = tabCenter("mine")

        navigation().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 160)
        }
        compose.runOnIdle { selectedRoute.value = "idcode" }
        tab("idcode").assertIsSelected()
        tab("express").assertIsNotSelected()
        navigation().performTouchInput { up() }

        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        assertEquals(
            tab("idcode").fetchSemanticsNode().boundsInRoot.center.x,
            indicatorCenterX(),
            1f,
        )
    }

    @Test
    fun navigationHeightGrowsAndShrinksWithFontScale() {
        showNavigation()
        val initialHeight = navigation().fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle { fontScale.value = 2f }
        assertTrue(navigation().fetchSemanticsNode().boundsInRoot.height > initialHeight)
        listOf("idcode", "express", "mine").forEach { route ->
            tab(route).assertHeightIsAtLeast(48.dp)
        }

        compose.runOnIdle { fontScale.value = 1f }
        assertEquals(initialHeight, navigation().fetchSemanticsNode().boundsInRoot.height, 1f)
        listOf("idcode", "express", "mine").forEach { route ->
            tab(route).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun disablingAnActiveDragCancelsItAndBlocksHiddenClicks() {
        showNavigation()
        val start = tabCenter("express")
        val end = tabCenter("mine")
        navigation().performTouchInput { down(start); moveTo(end, delayMillis = 160) }
        compose.runOnIdle { navigationEnabled.value = false }
        navigation().performTouchInput { up() }
        tab("mine").assertIsNotEnabled().performClick()
        tab("mine").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        assertEquals(tab("express").fetchSemanticsNode().boundsInRoot.center.x, indicatorCenterX(), 1f)
        compose.runOnIdle { navigationEnabled.value = true }
        tab("mine").performClick()
        compose.runOnIdle { assertEquals(listOf("mine"), selections) }
    }

    @Test
    fun keyboardFocusOutlineIsVisibleAboveTheSelectedLens() {
        showNavigation()
        val selected = tab("express")
        selected.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        selected.assertIsFocused()
        val container = navigation().fetchSemanticsNode().boundsInRoot
        val bounds = selected.fetchSemanticsNode().boundsInRoot
        val pixels = navigation().captureToImage().toPixelMap()
        val color = pixels[(bounds.center.x - container.left).toInt(), (bounds.top - container.top).toInt() + 1]
        assertEquals(focusColor.red, color.red, 0.12f)
        assertEquals(focusColor.green, color.green, 0.12f)
        assertEquals(focusColor.blue, color.blue, 0.12f)
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
    }

    @Test
    fun accessibilityExposesOneLabelPerTabWithSelectionAndLargeTouchTargets() {
        showNavigation()

        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).assertCountEquals(3)
        listOf("身份码", "快递", "我的").forEach { label ->
            compose.onAllNodesWithText(label).assertCountEquals(1)
        }
        listOf("idcode", "express", "mine").forEach { route ->
            tab(route)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        tab("express").assertIsSelected()
        tab("idcode").assertIsNotSelected()
        tab("mine").assertIsNotSelected()

        tab("mine").performClick()
        tab("mine").assertIsSelected()
        tab("mine").performClick()
        compose.runOnIdle { assertEquals(listOf("mine"), selections) }
    }

    @Test
    fun rightToLeftDragFollowsVisualTabOrder() {
        showNavigation(layoutDirection = LayoutDirection.Rtl)
        val start = tabCenter("express")
        val end = tabCenter("mine")
        assertTrue(end.x < start.x)
        assertEquals(tab("express").fetchSemanticsNode().boundsInRoot.center.x, indicatorCenterX(), 1f)

        navigation().performTouchInput {
            down(start)
            moveTo(end, delayMillis = 160)
        }
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        navigation().performTouchInput { up() }

        compose.runOnIdle { assertEquals(listOf("mine"), selections) }
        tab("mine").assertIsSelected()
        assertEquals(tab("mine").fetchSemanticsNode().boundsInRoot.center.x, indicatorCenterX(), 1f)
    }

    private fun showNavigation(
        initialRoute: String = "express",
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        updateSelectionImmediately: Boolean = true,
    ) {
        selectedRoute = mutableStateOf(initialRoute)
        fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            AggTheme {
                focusColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection,
                    LocalDensity provides Density(density.density, fontScale.value),
                ) {
                    Box(Modifier.width(320.dp)) {
                        LiquidBottomNavigation(
                            items = listOf(
                                LiquidNavigationItem("idcode", "身份码", Icons.Filled.Home),
                                LiquidNavigationItem("express", "快递", Icons.Filled.LocalShipping),
                                LiquidNavigationItem("mine", "我的", Icons.Filled.Person),
                            ),
                            selectedRoute = selectedRoute.value,
                            onRouteSelected = { route ->
                                selections += route
                                if (updateSelectionImmediately) selectedRoute.value = route
                            },
                            backdrop = emptyBackdrop(),
                            enabled = navigationEnabled.value,
                        )
                    }
                }
            }
        }
    }

    private fun navigation() = compose.onNodeWithTag("liquid-bottom-navigation")

    private fun tab(route: String) = compose.onNodeWithTag("liquid-tab-$route")

    private fun tabCenter(route: String): Offset {
        val container = navigation().fetchSemanticsNode().boundsInRoot
        return tab(route).fetchSemanticsNode().boundsInRoot.center - container.topLeft
    }

    private fun indicatorCenterX(): Float = compose.onNodeWithTag(
        "liquid-indicator",
        useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot.center.x
}
