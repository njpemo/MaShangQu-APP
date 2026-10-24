package com.aggapp.app.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class Code128BitmapCacheTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun concurrentConsumersShareContentAndSizeButDifferentSizesRenderSeparately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val image = ImageBitmap(10, 3)
        val cache = Code128BitmapCache(scope) {
            calls.incrementAndGet()
            gate.await()
            image
        }
        try {
            val key = Code128Key("123456", 1000, 300)
            val first = async { cache.image(key) }
            val second = async { cache.image(key) }
            gate.complete(Unit)
            assertSame(first.await(), second.await())
            assertEquals(1, calls.get())
            cache.image(key.copy(width = 1400, height = 420))
            assertEquals(2, calls.get())
        } finally { scope.cancel() }
    }

    @Test
    fun countdownRecompositionReusesBitmapAndNewContentHidesTheOldBitmap() {
        val calls = AtomicInteger()
        val content = mutableStateOf("123456")
        val countdown = mutableIntStateOf(10)
        val newImageGate = CompletableDeferred<Unit>()
        var displayed: Code128Render? = null
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val cache = androidx.compose.runtime.remember {
                Code128BitmapCache(scope) { key ->
                    calls.incrementAndGet()
                    if (key.content == "654321") newImageGate.await()
                    ImageBitmap(10, 3)
                }
            }
            countdown.intValue
            displayed = rememberCode128Bitmap(content.value, cache = cache)
        }
        compose.waitUntil { displayed?.key?.content == "123456" }
        repeat(10) { compose.runOnIdle { countdown.intValue-- } }
        compose.runOnIdle {
            assertEquals(1, calls.get())
            content.value = "654321"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(displayed) }
        newImageGate.complete(Unit)
        compose.waitUntil { displayed?.key?.content == "654321" }
        compose.runOnIdle { assertEquals(2, calls.get()) }
    }

    @Test
    fun lateOldResultCannotReplaceTheCurrentCode() {
        val content = mutableStateOf("old")
        val oldGate = CompletableDeferred<Unit>()
        var displayed: Code128Render? = null
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val cache = androidx.compose.runtime.remember {
                Code128BitmapCache(scope) { key ->
                    if (key.content == "old") oldGate.await()
                    ImageBitmap(10, 3)
                }
            }
            displayed = rememberCode128Bitmap(content.value, cache = cache)
        }
        compose.runOnIdle { content.value = "new" }
        compose.waitUntil { displayed?.key?.content == "new" }
        oldGate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("new", displayed?.key?.content) }
    }
}
