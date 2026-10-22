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

    /**
     * Mock 凭据：无真实登录态时兜底演示（PDD 需微信 OAuth 暂无凭据入口；
     * 其余平台一旦「我的」页登录成功即切换真实凭据）。
     */
    private val mockCreds: Map<Platform, Credential> = mapOf(
        Platform.PDD to Credential(
            Platform.PDD, "mock",
            extra = mapOf("staticSeg" to "0000000000"),
            capturedAt = 0,
        ),
        Platform.TAOBAO to Credential(
            Platform.TAOBAO, "mock",
            extra = mapOf("mobile" to "13800000000"), // mock 占位号
            capturedAt = 0,
        ),
        Platform.JD to Credential(Platform.JD, "mock", capturedAt = 0),
        Platform.SF to Credential(
            Platform.SF, "mock",
            extra = mapOf("staticSeg" to "2853414300"), // sf.md §4.3 实测样本
            capturedAt = 0,
        ),
        Platform.RYLL to Credential(
            Platform.RYLL, "mock",
            extra = mapOf("pin" to "5Q394MCKDBENH9XX"), // ryll README §4.2 实测静态样本
            capturedAt = 0,
        ),
    )

    private val _state = MutableStateFlow(IdCodeUiState())
    val state: StateFlow<IdCodeUiState> = _state.asStateFlow()

    init {
        session.restore()
        refreshAll()
        // 每秒 tick 更新倒计时；到期自动重取该平台身份码
        viewModelScope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    /** 每个平台用真实凭据（有则用之），否则 Mock 兜底 */
    private fun credentialFor(p: Platform): Pair<Credential, Boolean> {
        val cred = session.credentials.value[p]
        return if (cred != null) cred to true else mockCreds.getValue(p) to false
    }

    fun refreshAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val fetched = mutableMapOf<Platform, IdentityCode>()
            val real = mutableMapOf<Platform, Boolean>()
            for ((p, adapter) in adapters) {
                val (cred, isReal) = credentialFor(p)
                try {
                    fetched[p] = adapter.fetchIdentityCode(cred)
                    real[p] = isReal
                } catch (e: PlatformError.NeedRelogin) {
                    real[p] = isReal // 凭据失效：不展示码
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            _state.value = _state.value.copy(
                codes = fetched,
                isReal = real,
                loading = false,
                selected = _state.value.selected,
            )
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
            val (cred, isReal) = credentialFor(platform)
            try {
                val code = adapters.getValue(platform).fetchIdentityCode(cred)
                _state.value = _state.value.copy(
                    codes = _state.value.codes + (platform to code),
                    isReal = _state.value.isReal + (platform to isReal),
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
