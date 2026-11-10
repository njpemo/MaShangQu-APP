package com.aggapp.app.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CODE128 条码渲染管线（v1.0.10 性能重构，视觉不变）：
 * 1. 批量写像素：BitMatrix → IntArray → setPixels 单次调用（替代旧实现逐像素 setPixel 的
 *    30 万次 JNI，1000×300 卡主线程数百毫秒；全屏 3000×1100 达 330 万次）
 * 2. LruCache 按「内容+尺寸」缓存：同码绝不重画，倒计时每秒重组零渲染成本
 * 3. BarcodeImage 组合期只读缓存；未命中显示同尺寸占位，Dispatchers.Default 渲染完成后
 *    自动上屏（主线程零阻塞，全屏亮码/五码弹窗打开瞬间不再卡死）
 */
object BarcodeRenderer {

    /** 按字节计费缓存：5 码 1000×300 约 3MB + 全屏 3000×1100 约 6.6MB，24MB 上限足够 */
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 同步渲染（仅后台线程调用），结果入缓存；编码失败返回 null */
    fun render(content: String, width: Int = 1000, height: Int = 300): Bitmap? {
        val key = "$content|$width|$height"
        cache.get(key)?.let { return it }
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 10)
            val matrix: BitMatrix = Code128Writer().encode(content, BarcodeFormat.CODE_128, width, height, hints)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val row = BitArray(width)
            val pixels = IntArray(width * height)
            var idx = 0
            for (y in 0 until height) {
                matrix.getRow(y, row)
                for (x in 0 until width) {
                    pixels[idx++] = if (row.get(x)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            cache.put(key, bmp)
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 组合期取缓存（零渲染成本）；未命中返回 null */
    fun cached(content: String, width: Int, height: Int): Bitmap? = cache.get("$content|$width|$height")
}

/**
 * 条码图组件（三处调用点统一入口：页内大卡 / 全部平台码弹窗 / 全屏亮码）。
 * 命中缓存组合期立即出图；未命中先占位（调用方传入的 modifier 决定占位尺寸），
 * 后台渲染完成后自动上屏，主线程全程零阻塞。
 */
@Composable
fun BarcodeImage(
    content: String,
    modifier: Modifier = Modifier,
    width: Int = 1000,
    height: Int = 300,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val key = "$content|$width|$height"
    var bmp by remember(key) { mutableStateOf(BarcodeRenderer.cached(content, width, height)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val rendered = withContext(Dispatchers.Default) { BarcodeRenderer.render(content, width, height) }
            if (rendered != null) {
                bmp = rendered
            } else {
                failed = true
            }
        }
    }
    val image = bmp?.asImageBitmap()
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (failed) {
                Text(
                    "条码渲染失败",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
