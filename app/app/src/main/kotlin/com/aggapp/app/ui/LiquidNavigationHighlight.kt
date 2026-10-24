// SPDX-License-Identifier: Apache-2.0
// Adapted from Kyant0/AndroidLiquidGlass 1.0.1 InteractiveHighlight; see assets/licenses/.
package com.aggapp.app.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

@Composable
internal fun Modifier.liquidNavigationHighlight(
    progress: () -> Float,
    position: (Size) -> Offset,
): Modifier {
    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(
                """
                layout(color) uniform half4 color;
                uniform float radius;
                uniform float2 position;
                half4 main(float2 coord) {
                    float dist = distance(coord, position);
                    float intensity = smoothstep(radius, radius * 0.5, dist);
                    return color * intensity;
                }
                """.trimIndent(),
            )
        } else null
    }
    val brush = remember(shader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) ShaderBrush(shader)
        else null
    }
    return this.drawWithContent {
        val p = progress()
        if (p > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null && brush != null) {
                drawRect(Color.White.copy(alpha = 0.08f * p), blendMode = BlendMode.Plus)
                val center = position(size)
                shader.setColorUniform("color", Color.White.copy(alpha = 0.15f * p).toArgb())
                shader.setFloatUniform("radius", size.minDimension * 1.5f)
                shader.setFloatUniform(
                    "position",
                    center.x.coerceIn(0f, size.width),
                    center.y.coerceIn(0f, size.height),
                )
                drawRect(brush, blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(alpha = 0.25f * p), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }
}
