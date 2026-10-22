package com.aggapp.app.ui

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
    val countdownSec: Long? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
) : ViewModel() {

    private val adapters: Map<Platform, PlatformAdapter> = registry.adapters

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
            for ((p, adapter) in adapters) {
                val cred = credentialFor(p)
                if (cred == null) {
                    real[p] = false // 未登录：不拉码
                    continue
                }
                try {
                    fetched[p] = adapter.fetchIdentityCode(cred)
                    real[p] = true
                } catch (e: PlatformError.NeedRelogin) {
                    real[p] = false // 凭据失效：不展示码
                } catch (e: Exception) {
                    e.printStackTrace()
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

    init {
        session.restore()
        refreshAll(force = true)
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

    private fun tick() {
        val s = _state.value
        val selected = s.codes[s.selected] ?: return
        val remain = selected.expireAt?.let { (it - System.currentTimeMillis()) / 1000 }
        _state.value = s.copy(countdownSec = remain?.coerceAtLeast(0))
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
