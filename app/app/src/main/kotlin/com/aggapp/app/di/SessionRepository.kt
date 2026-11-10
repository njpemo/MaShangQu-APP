package com.aggapp.app.di

import android.content.Context
import com.aggapp.core.model.Platform
import com.aggapp.core.model.Credential
import com.aggapp.core.model.PlatformLoginState
import com.aggapp.core.storage.CredentialStore
import com.aggapp.core.storage.SavedCredential
import com.aggapp.platform.api.PlatformAdapter
import com.aggapp.platform.api.AppLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 登录会话：内存 StateFlow + 加密落盘（CredentialStore），App 重启恢复 */
@Singleton
class SessionRepository @Inject constructor(private val store: CredentialStore) {

    private val _credentials = MutableStateFlow<Map<Platform, Credential>>(emptyMap())
    val credentials: StateFlow<Map<Platform, Credential>> = _credentials.asStateFlow()

    /** 各平台登录态（启动时 verifyAll 串行探测写入，我的页/快递页共享消费） */
    private val _loginStates = MutableStateFlow<Map<Platform, PlatformLoginState>>(emptyMap())
    val loginStates: StateFlow<Map<Platform, PlatformLoginState>> = _loginStates.asStateFlow()

    /** verifyAll 去重：启动探测与我的页手动刷新可能并发 */
    private val verifyMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 单例构造即恢复凭据：默认启动页是快递页，ExpressViewModel 拿凭据时必须已就绪
        // （此前依赖 MainViewModel.init 恢复，而 MainViewModel 只在身份码页创建，导致
        //  默认打开快递页时凭据为空，需先进「我的」页再返回才有数据）
        // 注意：init 必须放在 _credentials 等属性声明之后（Kotlin 按顺序初始化，
        //  否则 restore() 写入未初始化的 StateFlow 会 NPE 闪退）
        restore()
    }

    /** 上次全量探测时间（10 分钟内不重复探测，避免频繁请求触发风控） */
    @Volatile
    private var lastVerifyAt = 0L

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

    /**
     * 全平台登录态存活探测（串行错峰，降低风控/限频压力）。
     * force=true（我的页手动刷新）忽略节流；启动自动探测 10 分钟节流。
     * adapters 传入 null 时只做"有无凭据"判定，不发起网络请求。
     */
    fun verifyAll(adapters: Map<Platform, PlatformAdapter>? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastVerifyAt < 10 * 60_000) return
        lastVerifyAt = now
        scope.launch {
            verifyMutex.withLock {
                // 先用有无凭据占位，让 UI 立即知道"未登录"的平台
                val creds = _credentials.value
                val states = Platform.entries.associateWith { p ->
                    _loginStates.value[p]
                        ?: if (creds[p] == null) PlatformLoginState(p, loggedIn = false) else null
                    ?: PlatformLoginState(p, loggedIn = false, needRelogin = false, lastCheckAt = 0L)
                }
                _loginStates.value = states
                for (p in Platform.entries) {
                    val cred = creds[p] ?: continue // 无凭据：占位已标未登录，不探测
                    val adapter = adapters?.get(p) ?: continue
                    val alive = try {
                        adapter.checkAlive(cred)
                    } catch (e: Exception) {
                        AppLog.w("Session", "${p.label} checkAlive: ${e.message}")
                        false
                    }
                    _loginStates.value = _loginStates.value + (p to PlatformLoginState(
                        p,
                        loggedIn = alive,
                        needRelogin = !alive,
                        lastCheckAt = System.currentTimeMillis(),
                    ))
                    delay(200) // 错峰：降低多平台并发探测的风控/限频压力
                }
            }
        }
    }

    /** 凭据变更后使单个平台登录态失效，下次 verifyAll 重新探测 */
    fun invalidateLoginState(platform: Platform) {
        _loginStates.value = _loginStates.value - platform
    }

    fun upsert(cred: Credential) {
        _credentials.value = _credentials.value + (cred.platform to cred)
        store.save(
            SavedCredential(cred.platform.name, cred.token, cred.extra, cred.capturedAt),
        )
        invalidateLoginState(cred.platform)
        lastVerifyAt = 0L // 新凭据落库后，紧随其后的 verifyAll 必须真实探测（不受 10 分钟节流影响）
    }

    fun clear(platform: Platform) {
        _credentials.value = _credentials.value - platform
        store.clear(platform.name)
        invalidateLoginState(platform)
        lastVerifyAt = 0L // 登出后同样立即重查，"未登录"状态即时上屏
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
