package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.PlatformAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IdCodeUiState(
    /** 各平台身份码 */
    val codes: Map<Platform, IdentityCode> = emptyMap(),
    /** 该平台身份码是真实拉取（true）还是 Mock 演示（false） */
    val isReal: Map<Platform, Boolean> = emptyMap(),
    val selected: Platform = Platform.SF,
    val loading: Boolean = false,
    /** 淘宝账号在菜鸟侧无档案（waybill-5026）→ 弹「激活菜鸟驿站」引导弹窗 */
    val needCainiaoActivate: Boolean = false,
)

// v1.0.10：countdownSec 从 UiState 拆出为独立 StateFlow —— 倒计时每秒跳动原本会触发整页
// 重组（与条码重画叠加成恶性循环），现在只有倒计时徽章一个小 composable 局部 collect。

@HiltViewModel
class MainViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val adapters: Map<Platform, PlatformAdapter> = registry.adapters

    /** 菜鸟激活引导「不再展示」等轻量 UI 偏好 */
    private val prefs = context.getSharedPreferences("idcode_prefs", Context.MODE_PRIVATE)

    /** 有真实登录态返回凭据，否则 null（UI 提示未登录，不做 Mock 兜底） */
    private fun credentialFor(p: Platform): Credential? = session.credentials.value[p]

    /** 上次全量刷新时间，ON_RESUME 自动刷新用 30s 节流 */
    private var lastRefreshAt = 0L

    /**
     * 全量刷新。force=true（下拉/单击刷新）强制执行并显示 loading；
     * 默认（ON_RESUME 自动刷新）30s 内节流跳过，已有缓存时静默刷新不闪 loading，
     * 避免切页转场期的重组风暴造成卡顿。
     */
    fun refreshAll(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now
        val silent = !force && _state.value.codes.isNotEmpty()
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(loading = true)
            val fetched = mutableMapOf<Platform, IdentityCode>()
            val real = mutableMapOf<Platform, Boolean>()
            // 五平台并行拉取（原串行 for 是换页/刷新等待长的主要原因）；
            // 各平台独立容错，结果在主线程汇总，合并语义与原串行实现逐字一致
            coroutineScope {
                for ((p, adapter) in adapters) {
                    launch {
                        val cred = credentialFor(p)
                        if (cred == null) {
                            real[p] = false // 未登录：不拉码
                            return@launch
                        }
                        try {
                            fetched[p] = adapter.fetchIdentityCode(cred)
                            real[p] = true
                        } catch (e: PlatformError.NeedRelogin) {
                            real[p] = false // 凭据失效：不展示码
                        } catch (e: PlatformError.NeedActivate) {
                            real[p] = false
                            com.aggapp.platform.api.AppLog.w("IdCode", "${p.label} fetchIdentityCode: ${e.detail}")
                            if (p == Platform.TAOBAO && !prefs.getBoolean(KEY_CAINIAO_HIDDEN, false)) {
                                _state.value = _state.value.copy(needCainiaoActivate = true)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            _state.value = if (silent) {
                // 静默刷新：按平台合并，拉取失败的平台保留旧码
                _state.value.copy(codes = _state.value.codes + fetched, isReal = real)
            } else {
                _state.value.copy(
                    codes = fetched,
                    isReal = real,
                    loading = false,
                    selected = _state.value.selected,
                )
            }
        }
    }

    private val _state = MutableStateFlow(IdCodeUiState())
    val state: StateFlow<IdCodeUiState> = _state.asStateFlow()

    /** 倒计时（秒）：独立流，每秒只更新这里，UI 徽章局部 collect，整页零重组 */
    private val _countdown = MutableStateFlow<Long?>(null)
    val countdown: StateFlow<Long?> = _countdown.asStateFlow()

    init {
        // restore 已移至 SessionRepository 构造（默认启动页为快递页，凭据须更早就绪）
        refreshAll(force = true)
        // 启动即全平台登录态存活探测（串行错峰，10 分钟节流；结果共享给我的页/快递页）
        session.verifyAll(adapters)
        // 每秒 tick 更新倒计时；到期自动重取该平台身份码
        viewModelScope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    fun select(platform: Platform) {
        _state.value = _state.value.copy(selected = platform)
    }

    /** 关闭「激活菜鸟驿站」引导弹窗 */
    fun dismissCainiaoActivate() {
        _state.value = _state.value.copy(needCainiaoActivate = false)
    }

    /** 「不再展示」菜鸟激活引导：持久化标记，此后淘宝身份码获取失败也不再弹窗 */
    fun neverShowCainiaoActivate() {
        prefs.edit().putBoolean(KEY_CAINIAO_HIDDEN, true).apply()
        _state.value = _state.value.copy(needCainiaoActivate = false)
    }

    private companion object {
        const val KEY_CAINIAO_HIDDEN = "cainiao_never_show"
    }

    private fun tick() {
        val s = _state.value
        val selected = s.codes[s.selected]
        if (selected == null) {
            _countdown.value = null
            return
        }
        val remain = selected.expireAt?.let { (it - System.currentTimeMillis()) / 1000 }
        _countdown.value = remain?.coerceAtLeast(0)
        if (remain != null && remain <= 0) {
            refreshOne(s.selected)
        }
    }

    private fun refreshOne(platform: Platform) {
        viewModelScope.launch {
            val cred = credentialFor(platform) ?: return@launch
            try {
                val code = adapters.getValue(platform).fetchIdentityCode(cred)
                _state.value = _state.value.copy(
                    codes = _state.value.codes + (platform to code),
                    isReal = _state.value.isReal + (platform to true),
                )
            } catch (e: PlatformError.NeedActivate) {
                com.aggapp.platform.api.AppLog.w("IdCode", "${platform.label} fetchIdentityCode: ${e.detail}")
                if (platform == Platform.TAOBAO && !prefs.getBoolean(KEY_CAINIAO_HIDDEN, false)) {
                    _state.value = _state.value.copy(needCainiaoActivate = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
