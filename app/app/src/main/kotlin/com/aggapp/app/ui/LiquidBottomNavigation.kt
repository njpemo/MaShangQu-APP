// SPDX-License-Identifier: Apache-2.0
// Adapted from Kyant0/AndroidLiquidGlass 1.0.1 catalog; see assets/licenses/.
package com.aggapp.app.ui

import android.os.Build
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

internal data class LiquidNavigationItem(val route: String, val label: String, val icon: ImageVector)

@Composable
internal fun LiquidBottomNavigation(
    items: List<LiquidNavigationItem>,
    selectedRoute: String,
    onRouteSelected: (String) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isLight = colors.surface.luminance() > 0.5f
    val canCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val accent = if (isLight) Color(0xFF0088FF) else Color(0xFF0091FF)
    val container = (if (isLight) Color(0xFFFAFAFA) else Color(0xFF121212)).copy(alpha = 0.4f)
    val routes = items.map { it.route }
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val selectedPosition = if (isRtl) items.lastIndex - selectedIndex else selectedIndex
    val interactionSources = remember(routes) { items.map { MutableInteractionSource() } }
    val isPressed = interactionSources.map { it.collectIsPressedAsState().value }.any { it }
    val focusedIndex = interactionSources.map { it.collectIsFocusedAsState().value }.indexOfFirst { it }
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelectedPosition by rememberUpdatedState(selectedPosition)
    val currentOnRouteSelected by rememberUpdatedState(onRouteSelected)
    var lastRequestedRoute by remember(selectedRoute) { mutableStateOf<String?>(null) }
    var navigationSize by remember { mutableStateOf(IntSize.Zero) }
    var pointerHeld by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = remember(routes, isRtl) { LiquidNavigationMotion(scope, selectedPosition.toFloat()) }
    val velocityTracker = remember { VelocityTracker() }
    val tabsBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

    fun selectRoute(route: String) {
        if (currentEnabled && route != currentSelectedRoute && route != lastRequestedRoute) {
            lastRequestedRoute = route
            currentOnRouteSelected(route)
        }
    }

    LaunchedEffect(selectedPosition, motion) {
        motion.followSelection(selectedPosition.toFloat())
    }
    val engaged = pointerHeld || isPressed || motion.dragging || motion.settling
    val pressProgress by animateFloatAsState(
        if (engaged) 1f else 0f, spring(1f, 1000f, 0.001f), label = "liquid press",
    )
    val lensScaleX by animateFloatAsState(
        if (engaged) 78f / 56f else 1f, spring(0.6f, 250f, 0.001f), label = "liquid scale x",
    )
    val lensScaleY by animateFloatAsState(
        if (engaged) 78f / 56f else 1f, spring(0.7f, 250f, 0.001f), label = "liquid scale y",
    )
    val velocity = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        snapshotFlow { motion.requestedVelocity }.collectLatest {
            velocity.animateTo(it, spring(0.5f, 300f, 0.01f))
        }
    }
    val position = { motion.position }
    val progress = { pressProgress.coerceIn(0f, 1f) }
    val panelOffset = {
        val fraction = (motion.translation / max(navigationSize.width, 1)).coerceIn(-1f, 1f)
        with(density) { 4.dp.toPx() } * fraction.sign * EaseOut.transform(abs(fraction))
    }
    val interactiveHighlight = Modifier.liquidNavigationHighlight(
        progress = progress,
        position = { size ->
            val inset = with(density) { 4.dp.toPx() }
            Offset(inset + (position() + 0.5f) * (size.width - 2f * inset) / items.size, size.height / 2f)
        },
    )
    val surfaceModifier = if (canCapture) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { ContinuousCapsule },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lens(24.dp.toPx(), 24.dp.toPx())
            },
            layerBlock = {
                val scale = 1f + 16.dp.toPx() / size.width * progress()
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = { drawRect(container) },
        )
    } else Modifier.background(colors.surface, ContinuousCapsule)

    Box(
        modifier
            .testTag("liquid-bottom-navigation")
            .widthIn(min = (items.size * 48 + 8).dp)
            .heightIn(min = 64.dp)
            .graphicsLayer { translationX = panelOffset() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { navigationSize = it }
                .pointerInput(routes, selectedRoute, navigationSize, isRtl, enabled) {
                    if (!enabled || navigationSize.width <= 0) return@pointerInput
                    val inset = with(density) { 4.dp.toPx() }
                    val slotWidth = (navigationSize.width - 2f * inset) / items.size
                    if (slotWidth <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main,
                        )
                        val selectedLeft = inset + selectedPosition * slotWidth
                        if (down.position.x < selectedLeft || down.position.x >= selectedLeft + slotWidth) {
                            return@awaitEachGesture
                        }
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        pointerHeld = true
                        var claimed = false
                        var released = false
                        try {
                            // Taps stay with the accessible tab; only claim horizontal drags.
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                change.consume()
                                claimed = true
                                motion.beginDrag(
                                    (selectedPosition + overSlop / slotWidth)
                                        .coerceIn(0f, items.lastIndex.toFloat()),
                                    overSlop,
                                )
                            } ?: return@awaitEachGesture
                            velocityTracker.addPosition(start.uptimeMillis, start.position)
                            val completed = horizontalDrag(start.id) { change ->
                                val delta = change.positionChange().x
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                motion.dragBy(
                                    delta, slotWidth, items.lastIndex,
                                    velocityTracker.calculateVelocity().x / slotWidth / max(items.lastIndex, 1),
                                )
                                change.consume()
                            }
                            if (completed) {
                                val physicalIndex = motion.position.roundToInt().coerceIn(items.indices)
                                val index = if (isRtl) items.lastIndex - physicalIndex else physicalIndex
                                released = true
                                motion.release(physicalIndex.toFloat())
                                selectRoute(items[index].route)
                            }
                        } finally {
                            // Cancel/route/size changes only restore the visual state.
                            pointerHeld = false
                            if (claimed && !released) motion.cancel(currentSelectedPosition.toFloat())
                        }
                    }
                },
        ) {
            // Only this row owns measurement, semantics and input.
            Row(
                Modifier.fillMaxWidth()
                    .then(surfaceModifier)
                    .then(interactiveHighlight)
                    .padding(4.dp)
                    .selectableGroup(),
            ) {
                items.forEachIndexed { index, item ->
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .testTag("liquid-tab-" + item.route)
                            .clip(ContinuousCapsule)
                            .selectable(
                                selected = index == selectedIndex,
                                interactionSource = interactionSources[index],
                                indication = null,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { selectRoute(item.route) },
                            )
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LiquidNavigationLabel(
                            item, if (!canCapture && index == selectedIndex) accent else colors.onSurface,
                        )
                    }
                }
            }

            if (canCapture) {
                // Alpha is OUTSIDE layerBackdrop: the recorded foreground remains available
                // to the lens. This copy contains no click handlers or selectable nodes.
                Row(
                    Modifier
                        .matchParentSize()
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { ContinuousCapsule },
                            effects = {
                                val p = progress()
                                vibrancy()
                                blur(8.dp.toPx())
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                                }
                            },
                            highlight = { Highlight.Default.copy(alpha = progress()) },
                            onDrawSurface = { drawRect(container) },
                        )
                        .then(interactiveHighlight)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accent))
                        .padding(4.dp),
                ) {
                    items.forEach { item ->
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer {
                                    val scale = lerp(1f, 1.2f, progress())
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LiquidNavigationLabel(item, colors.onSurface)
                        }
                    }
                }
            }

            if (navigationSize.width > 0 && navigationSize.height > 0) {
                val slotWidth = (navigationSize.width - with(density) { 8.dp.toPx() }) / items.size
                val indicatorSurface = if (canCapture) {
                    Modifier.drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            val p = progress()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                            }
                        },
                        highlight = { Highlight.Default.copy(alpha = progress()) },
                        shadow = { Shadow(alpha = progress()) },
                        innerShadow = { InnerShadow(radius = 8.dp * progress(), alpha = progress()) },
                        layerBlock = {
                            val v = velocity.value / 10f
                            scaleX = lensScaleX / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
                            scaleY = lensScaleY * (1f - (v * 0.25f).coerceIn(-0.2f, 0.2f))
                        },
                        onDrawSurface = {
                            val p = progress()
                            drawRect((if (isLight) Color.Black else Color.White).copy(alpha = 0.1f * (1f - p)))
                            drawRect(Color.Black.copy(alpha = 0.03f * p))
                        },
                    )
                } else {
                    Modifier.graphicsLayer {
                        scaleX = lensScaleX
                        scaleY = lensScaleY
                    }.background(
                        (if (isLight) Color.Black else Color.White).copy(alpha = 0.1f), ContinuousCapsule,
                    )
                }
                // Overlays never contribute the previous font height to parent measurement.
                Box(Modifier.matchParentSize().padding(4.dp), contentAlignment = AbsoluteAlignment.TopLeft) {
                    Box(
                        Modifier
                            .testTag("liquid-indicator")
                            .absoluteOffset { IntOffset((position() * slotWidth).roundToInt(), 0) }
                            .width(with(density) { slotWidth.toDp() })
                            .fillMaxHeight()
                            .then(indicatorSurface),
                    )
                    if (focusedIndex >= 0) {
                        val physicalFocus = if (isRtl) items.lastIndex - focusedIndex else focusedIndex
                        Box(Modifier.absoluteOffset { IntOffset((physicalFocus * slotWidth).roundToInt(), 0) }
                            .width(with(density) { slotWidth.toDp() }).fillMaxHeight()
                            .border(2.dp, colors.primary, ContinuousCapsule))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidNavigationLabel(item: LiquidNavigationItem, color: Color) {
    Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
    Spacer(Modifier.height(2.dp))
    Text(item.label, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
}
