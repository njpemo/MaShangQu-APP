package com.aggapp.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class Code128Key(val content: String, val width: Int, val height: Int)
internal data class Code128Render(val key: Code128Key, val bitmap: ImageBitmap?)

/** Screen-scoped, bounded cache; concurrent normal/fullscreen consumers share the same job. */
internal class Code128BitmapCache(
    private val scope: CoroutineScope,
    private val render: suspend (Code128Key) -> ImageBitmap? = { key ->
        renderCode128(key.content, key.width, key.height)?.asImageBitmap()
    },
) {
    private val images = LinkedHashMap<Code128Key, Deferred<ImageBitmap?>>(16, 0.75f, true)

    suspend fun image(key: Code128Key): ImageBitmap? {
        val job = synchronized(images) {
            images.getOrPut(key) { scope.async(Dispatchers.Default) { render(key) } }.also {
                // Eviction releases only the cache reference; an active view may still await it.
                if (images.size > 12) images.remove(images.keys.first())
            }
        }
        return job.await()
    }
}

@Composable
internal fun rememberCode128Bitmap(
    content: String,
    width: Int = 1000,
    height: Int = 300,
    cache: Code128BitmapCache,
): Code128Render? {
    val key = Code128Key(content, width, height)
    val rendered by produceState<Code128Render?>(null, key, cache) {
        value = Code128Render(key, cache.image(key))
    }
    // produceState retains its previous value until the new producer runs. Never show that old code.
    return rendered?.takeIf { it.key == key }
}

@Composable
internal fun Code128Image(
    content: String,
    cache: Code128BitmapCache,
    contentDescription: String,
    modifier: Modifier,
    width: Int = 1000,
    height: Int = 300,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val result = rememberCode128Bitmap(content, width, height, cache)
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val image = result?.bitmap
        when {
            image != null -> Image(image, contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
            result == null -> Box(
                Modifier.fillMaxSize().semantics { this.contentDescription = "$contentDescription，正在生成" },
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            else -> Text("条码渲染失败", color = Color.DarkGray)
        }
    }
}
