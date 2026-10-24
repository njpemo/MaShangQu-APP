package com.aggapp.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Owns system insets once. Backgrounds extend to window edges; interactive content stays safe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppWindowLayout(
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    navigationInsets: WindowInsets = WindowInsets.navigationBars,
    keyboardInsets: WindowInsets = WindowInsets.ime,
    keyboardTargetInsets: WindowInsets = WindowInsets.imeAnimationTarget,
    content: @Composable (bottomContentPadding: Dp) -> Unit,
    navigation: @Composable (Backdrop, interactive: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val density = LocalDensity.current
    val keyboardTargetVisible by remember(keyboardTargetInsets, density) {
        // Restore when closing starts, behind the moving IME window. Current height/visibility
        // can lag behind the animation target and must not postpone the bar's return.
        derivedStateOf { keyboardTargetInsets.getBottom(density) > 0 }
    }
    var navigationHeightPx by remember { mutableIntStateOf(0) }
    val bottomContentPadding = if (keyboardTargetVisible) 0.dp else with(density) { navigationHeightPx.toDp() }
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

    Box(modifier.fillMaxSize().background(background).testTag("app-window-layout")) {
        Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(safeInsets).background(colors.surface))
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(safeInsets)
                .windowInsetsPadding(keyboardInsets)
                .clipToBounds()
                .then(
                    // Keep the capture node attached so IME dismissal does not rebuild its layer.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.layerBackdrop(backdrop)
                    else Modifier,
                ),
        ) {
            content(bottomContentPadding)
        }
        Box(
            Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { navigationHeightPx = it.height }
                .focusProperties { canFocus = !keyboardTargetVisible }
                .then(if (keyboardTargetVisible) Modifier.clearAndSetSemantics {} else Modifier)
                .drawWithContent { if (!keyboardTargetVisible) drawContent() }
                .layout { measurable, constraints ->
                    val bar = measurable.measure(constraints)
                    layout(bar.width, bar.height) {
                        // Keep owned graphics layers placed; off-window content cannot intercept touches.
                        bar.placeRelative(0, if (keyboardTargetVisible) constraints.maxHeight + bar.height else 0)
                    }
                }
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(navigationInsets)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            navigation(backdrop, !keyboardTargetVisible)
        }
    }
}
