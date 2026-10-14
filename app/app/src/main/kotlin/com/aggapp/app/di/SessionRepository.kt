package com.aggapp.app.di

import android.content.Context
import com.aggapp.core.model.Platform
import com.aggapp.core.model.Credential
import com.aggapp.core.storage.CredentialStore
import com.aggapp.core.storage.SavedCredential
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 登录会话：内存 StateFlow + 加密落盘（CredentialStore），App 重启恢复 */
@Singleton
class SessionRepository @Inject constructor(private val store: CredentialStore) {

    private val _credentials = MutableStateFlow<Map<Platform, Credential>>(emptyMap())
    val credentials: StateFlow<Map<Platform, Credential>> = _credentials.asStateFlow()

    /** 启动时从加密存储恢复 */
    fun restore() {
        val restored = mutableMapOf<Platform, Credential>()
        for (p in Platform.entries) {
            store.load(p.name)?.let { s ->
                restored[p] = Credential(p, s.token, s.extra, s.capturedAt)
            }
        }
        _credentials.value = restored
    }

    fun upsert(cred: Credential) {
        _credentials.value = _credentials.value + (cred.platform to cred)
        store.save(
            SavedCredential(cred.platform.name, cred.token, cred.extra, cred.capturedAt),
        )
    }

    fun clear(platform: Platform) {
        _credentials.value = _credentials.value - platform
        store.clear(platform.name)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore =
        CredentialStore(context)

    @Provides
    @Singleton
    fun providePddAntiContent(@ApplicationContext context: Context): com.aggapp.platform.pdd.PddAntiContent =
        com.aggapp.platform.pdd.PddAntiContent(context)

    /**
     * 如意邻里静态 PIN 解码器：getPinCode 返回 Code128 条码 PNG（data:image base64），
     * :app（Android 模块）用 zxing 本地解码出 16 位 PIN（docs/platform/ryll/README.md §4.2）。
     */
    @Provides
    @Singleton
    fun provideRyllPinDecoder(): com.aggapp.platform.ryll.PinDecoder =
        com.aggapp.platform.ryll.PinDecoder { dataUrl ->
            runCatching {
                val b64 = dataUrl.substringAfter("base64,", "")
                if (b64.isEmpty()) return@runCatching null
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runCatching null
                val w = bmp.width
                val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()
                val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
                val reader = com.google.zxing.MultiFormatReader()
                val hints = mapOf(
                    com.google.zxing.DecodeHintType.TRY_HARDER to true,
                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(
                        com.google.zxing.BarcodeFormat.CODE_128,
                    ),
                )
                try {
                    reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)), hints).text
                } catch (_: Exception) {
                    reader.reset()
                    reader.decode(
                        com.google.zxing.BinaryBitmap(
                            com.google.zxing.common.GlobalHistogramBinarizer(source),
                        ),
                        hints,
                    ).text // 换 binarizer 兜底
                }
            }.getOrNull()
        }
}
