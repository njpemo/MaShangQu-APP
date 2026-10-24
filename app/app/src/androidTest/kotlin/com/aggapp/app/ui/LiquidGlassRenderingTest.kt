package com.aggapp.app.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Optical regression fixture; requires hardware rendering on Android 13+. */
class LiquidGlassRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lightGlassCapturesAndMagnifiesForeground() = verifyOptics(dark = false)

    @Test
    fun darkGlassCapturesAndMagnifiesForeground() = verifyOptics(dark = true)

    @Test
    fun draggingFromMineToIdentityLeavesNoDarkPressRippleAtTheSource() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val selection = mutableStateOf("idcode")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(360.dp, 160.dp)) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) { drawRect(Color.White) }
                    LiquidBottomNavigation(
                        listOf(
                            LiquidNavigationItem("idcode", "身份码", Icons.Filled.Home),
                            LiquidNavigationItem("express", "快递", Icons.Filled.LocalShipping),
                            LiquidNavigationItem("mine", "我的", Icons.Filled.Person),
                        ), selection.value, { selection.value = it }, backdrop,
                        Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }
        }
        val navigation = compose.onNodeWithTag("liquid-bottom-navigation")
        val baseline = navigation.captureToImage().toPixelMap()
        compose.runOnIdle { selection.value = "mine" }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            navigation.performTouchInput {
                down(Offset(width * 5f / 6f, height / 2f))
                moveTo(Offset(width / 6f, height / 2f), delayMillis = 16)
                up()
            }
            repeat(19) {
                compose.mainClock.advanceTimeByFrame()
                val frame = navigation.captureToImage().toPixelMap()
                var compared = 0
                var darkened = 0
                // Compare only original bright background pixels, excluding icon/text and capsule edges.
                for (y in frame.height / 5 until frame.height * 4 / 5) {
                    for (x in frame.width * 3 / 4 until frame.width * 11 / 12) {
                        val before = baseline[x, y]
                        if (before.red > 0.94f && before.green > 0.94f && before.blue > 0.94f) {
                            compared++
                            val now = frame[x, y]
                            if (now.red < before.red - 0.06f && now.green < before.green - 0.06f) darkened++
                        }
                    }
                }
                assertTrue("The source tab must retain its unpressed background at frame $it", compared > 0 && darkened < compared * 0.03f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun verifyOptics(dark: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val backdrop = rememberLayerBackdrop()
                val selection = androidx.compose.runtime.remember { mutableStateOf("express") }
                Box(Modifier.size(360.dp, 240.dp).testTag("glass-scene")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stripe = 18.dp.toPx()
                            repeat((size.width / stripe).toInt() + 1) { i ->
                                drawRect(
                                    if (i % 2 == 0) Color(0xFFF2AA34) else Color(0xFF36A36A),
                                    topLeft = Offset(i * stripe, 0f),
                                    size = Size(stripe, size.height),
                                )
                            }
                        }
                        Text(
                            "ABCDE 012345 取件码",
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                            color = Color.Black,
                        )
                    }
                    LiquidBottomNavigation(
                        items = listOf(
                            LiquidNavigationItem("idcode", "身份码", Icons.Filled.Home),
                            LiquidNavigationItem("express", "快递", Icons.Filled.LocalShipping),
                            LiquidNavigationItem("mine", "我的", Icons.Filled.Person),
                        ),
                        selectedRoute = selection.value,
                        onRouteSelected = { selection.value = it },
                        backdrop = backdrop,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }
        }
        val scene = compose.onNodeWithTag("glass-scene")
        val navigation = compose.onNodeWithTag("liquid-bottom-navigation")
        val normal = scene.captureToImage()
        save(normal, dark, "normal")

        compose.mainClock.autoAdvance = false
        navigation.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1000)
        val pressed = scene.captureToImage()
        save(pressed, dark, "pressed")
        try {
            val normalBlue = blueForegroundPixels(normal)
            val pressedBlue = blueForegroundPixels(pressed)
            assertTrue("The selected lens must sample the blue foreground", normalBlue > 30)
            assertTrue(
                "Pressing must enlarge the captured icon/text, not just tint a solid indicator",
                pressedBlue > normalBlue * 1.15f,
            )
            navigation.performTouchInput { moveTo(Offset(width * 0.82f, height / 2f), delayMillis = 160) }
            compose.mainClock.advanceTimeBy(500)
            save(scene.captureToImage(), dark, "drag")
        } finally {
            navigation.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun blueForegroundPixels(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        // The fixture's backdrop has no blue, isolating the captured tab foreground.
        for (y in image.height / 2 until image.height) {
            for (x in 0 until image.width) {
                val c = pixels[x, y]
                if (c.blue > 0.48f && c.green > 0.15f &&
                    c.blue > c.red * 1.35f && c.blue > c.green * 1.15f
                ) count++
            }
        }
        return count
    }

    private fun save(image: ImageBitmap, dark: Boolean, state: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "glass-rendering").apply { mkdirs() }
        val name = (if (dark) "dark" else "light") + "-" + state + ".png"
        File(directory, name).outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
