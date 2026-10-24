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
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
    keyboardVisible: Boolean = WindowInsets.isImeVisible,
    content: @Composable (bottomContentPadding: Dp) -> Unit,
    navigation: @Composable (Backdrop) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background
    val density = LocalDensity.current
    val keyboardOccluding by remember(keyboardInsets, keyboardVisible, density) {
        // Visibility may change before the closing animation reaches zero height.
        derivedStateOf { keyboardVisible || keyboardInsets.getBottom(density) > 0 }
    }
    var navigationHeightPx by remember { mutableIntStateOf(0) }
    val bottomContentPadding = if (keyboardOccluding) 0.dp else with(density) { navigationHeightPx.toDp() }
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
                    // There is no sampler while the keyboard hides the floating navigation.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !keyboardOccluding) Modifier.layerBackdrop(backdrop)
                    else Modifier,
                ),
        ) {
            content(bottomContentPadding)
        }
        if (!keyboardOccluding) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { navigationHeightPx = it.height }
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal))
                    .windowInsetsPadding(navigationInsets)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                navigation(backdrop)
            }
        }
    }
}
