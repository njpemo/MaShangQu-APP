package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformLoginState
import com.aggapp.core.yyb.YybNotConfigured
import com.aggapp.core.yyb.YybRemote
import com.aggapp.core.yyb.YybRescanRequired
import com.aggapp.core.yyb.YybSettings
import com.aggapp.platform.api.AppLog
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.PlatformAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MineUiState(
    val loginStates: Map<Platform, PlatformLoginState> = emptyMap(),
    val message: String? = null,          // 操作结果提示（Toast/内联）
    val smsSent: Boolean = false,         // 淘宝验证码已发送
    val busy: Boolean = false,
    // ---- YYB 远程服务（v1.3：HTTP 调用部署的 YYB-GO，扫码在服务端 Web 页 /scan 完成）----
    val yybBaseUrl: String = "",          // 配置态回显
    val yybRef: String = "",              // 账号 ref（openid 或数字 id）
    val yybAvailable: Boolean = false,    // 是否已配置（baseUrl+ref 齐全）
    val yybTestStatus: String? = null,    // 连通测试结果
    // ---- 京东短信验证码登录（prepare → 滑块 WebView 截 vt → sendSms → verify）----
    val jdCaptchaUrl: String? = null,     // 非空时弹出滑块验证 WebView
    val jdSmsSent: Boolean = false,       // 短信验证码已发送（可输入验证码登录）
    // ---- 顺丰短信验证码登录（官方小程序链路，去 YYB，sf.md §2.5）----
    val sfSmsSent: Boolean = false,       // 短信验证码已发送（可输入验证码登录）
    val sfGeetestOpen: Boolean = false,   // 顺丰极验滑块弹窗开启中（发码前先过验证，errorCode 70000）
    // ---- 拼多多扫码登录（AUTH_LOGIN 扫码不踢 App 下线；54002 二次验证 Text 原生输码/其余 WebView 官方页）----
    val pddQrOpen: Boolean = false,       // 扫码登录弹窗开启中
    val pddQrContent: String? = null,     // 二维码内容（down_scan.html 链接，Compose 渲染成 QR）
    val pddQrHint: String? = null,        // 弹窗内提示文案（轮询中/已过期重刷/验证类型等）
    val pddSmsVerifySent: Boolean = false,// 二次验证为短信码且已下发（可输码）
    val pddVerifyUrl: String? = null,     // 非 Text 类型：官方验证页 URL（WebView 手动过）
    // ---- 如意邻里短信验证码登录（图形码 + 短信码，README §3.2）----
    val ryllVcodeBase64: String? = null,  // 图形验证码 PNG（base64 data URL，Compose 直接解码显示）
    val ryllSmsSent: Boolean = false,     // 短信验证码已发送（可输入验证码登录）
)

/**
 * 「我的」页 VM：
 * - 淘宝：短信协议登录（sendSmsCode → loginWithSmsCode）
 * - 京东：手机号+短信验证码协议直连登录（prepare→滑块 WebView 截 vt→sendSms→verify）；兜底粘贴 pt_key/pin
 * - 顺丰：短信验证码登录官方小程序链路（发码前过极验 v4 滑块拿 gt 四件套；登录态 suuid → 静态段永久本地出码，sf.md §2.5）；兜底粘贴 YJY-Auth
 * - 拼多多：扫码登录（AUTH_LOGIN 二维码存相册→拼多多 App 相册识别；54002 二次验证 Text 原生输码/其余 WebView 官方页；不踢 App 下线）
 * - YYB：服务配置卡片（BASE URL + 账号 ref + 连通测试）；微信账号扫码在服务端 /scan 页完成
 * 启动时 restore 凭据并逐平台 checkAlive。
 */
@HiltViewModel
class MineViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
    private val yybSettings: YybSettings,
    private val pddQrClient: com.aggapp.platform.pdd.PddQrClient,
) : ViewModel() {

    private val adapters: Map<Platform, PlatformAdapter> = registry.adapters

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    /** 淘宝协议登录的中间态（adapter 单例内暂存，这里只记录已发送） */
    private var smsPhone: String = ""

    /** 顺丰短信登录的中间态手机号（会话 suuid 由 adapter 单例内暂存） */
    private var sfPhone: String = ""

    init {
        session.restore()
        refreshLoginStates()
        _state.value = _state.value.copy(
            yybBaseUrl = yybSettings.baseUrl,
            yybRef = yybSettings.ref,
            yybAvailable = yybSettings.configured,
        )
    }

    fun refreshLoginStates() {
        viewModelScope.launch {
            val creds = session.credentials.value
            val states = Platform.entries.associateWith { p ->
                val cred = creds[p]
                if (cred == null) {
                    PlatformLoginState(p, loggedIn = false)
                } else {
                    val alive = try {
                        adapters.getValue(p).checkAlive(cred)
                    } catch (e: Exception) {
                        AppLog.e("AggApp", "$p checkAlive failed", e)
                        false
                    }
                    PlatformLoginState(
                        p,
                        loggedIn = alive,
                        needRelogin = !alive,
                        lastCheckAt = System.currentTimeMillis(),
                    )
                }
            }
            _state.value = _state.value.copy(loginStates = states)
        }
    }

    // ---------- WebView 短信登录（拼多多，去 YYB） ----------

    /** WEBVIEW 登录页地址（PDD：mobile.yangkeduo.com/login.html） */
    fun pddLoginUrl(): String = adapters.getValue(Platform.PDD).loginUrl()

    /** WebView cookie 判定（PDD：出现 PDDAccessToken 即成功） */
    fun pddDetectLogin(cookies: Map<String, String>): Credential? =
        adapters.getValue(Platform.PDD).detectLoginSuccess(CapturedRequest("", "GET", emptyMap()), cookies)

    /** WebView 登录成功：保存凭据 + 刷新登录态总览 + 提示 */
    fun saveWebViewCredential(cred: Credential) {
        viewModelScope.launch {
            session.upsert(cred)
            _state.value = _state.value.copy(message = "${cred.platform.label}登录成功（WebView）")
            refreshLoginStates()
        }
    }

    // ---------- YYB 远程服务：配置 + 三平台登录 ----------

    /**
     * 调对应平台 adapter.remoteLogin()（YYB 未配置返回 null）：
     * 成功 → 保存凭据 + 刷新登录态总览 + 提示；失败 → 提示异常 message
     */
    fun loginRemote(platform: Platform) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val cred = adapters.getValue(platform).remoteLogin()
                if (cred == null) {
                    _state.value = _state.value.copy(busy = false, message = "YYB 服务未配置，请先在下方填写服务地址与账号 ref")
                    return@launch
                }
                session.upsert(cred)
                _state.value = _state.value.copy(busy = false, message = "${platform.label}登录成功")
                refreshLoginStates()
            } catch (e: YybRescanRequired) {
                AppLog.e("AggApp", "loginRemote $platform 需重扫", e)
                _state.value = _state.value.copy(busy = false, message = "${platform.label}登录失败：${e.message}（在 YYB 服务 Web 页 /scan 重新扫码）")
            } catch (e: Exception) {
                AppLog.e("AggApp", "loginRemote $platform failed", e)
                _state.value = _state.value.copy(busy = false, message = "${platform.label}登录失败：${e.message}")
            }
        }
    }

    // ---------- YYB：服务配置 ----------

    /** 保存 YYB 服务配置（BASE URL + 账号 ref），保存即视为已配置 */
    fun saveYybConfig(baseUrl: String, ref: String) {
        val url = baseUrl.trim()
        val r = ref.trim()
        if (url.isBlank() || r.isBlank()) {
            _state.value = _state.value.copy(message = "服务地址与账号 ref 均必填")
            return
        }
        yybSettings.baseUrl = url
        yybSettings.ref = r
        _state.value = _state.value.copy(
            yybBaseUrl = url,
            yybRef = r,
            yybAvailable = true,
            yybTestStatus = null,
            message = "YYB 服务配置已保存",
        )
    }

    /** 连通测试：GET /health 探活 */
    fun testYybConnection() {
        val url = _state.value.yybBaseUrl
        if (url.isBlank()) {
            _state.value = _state.value.copy(yybTestStatus = "请先填写服务地址")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, yybTestStatus = "正在测试连通…")
            val ok = YybRemote.health(url)
            _state.value = _state.value.copy(
                busy = false,
                yybTestStatus = if (ok) "✓ 连接成功" else "✗ 连接失败（检查地址/网络，或服务未启动）",
            )
        }
    }

    // ---------- 淘宝：短信登录 ----------

    fun sendTaobaoSms(phone: String) {
        if (phone.length != 11) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        smsPhone = phone
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                adapters.getValue(Platform.TAOBAO).sendSmsCode(phone)
                _state.value = _state.value.copy(busy = false, smsSent = true, message = "验证码已发送")
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = "发送失败：${e.message}")
            }
        }
    }

    // ---------- 拼多多：扫码登录（2026-09-29 全链逆向，54002 二次验证自动分派；短信登录已移除——会踢 App 下线）----------

    /** 扫码会话中间态（轮询 job + auth/ticket 参数） */
    private var pddQrJob: kotlinx.coroutines.Job? = null
    private var pddSecretKey = ""
    private var pddTicket = ""
    private var pddVat = ""
    private var pddSalt = ""

    /** 打开扫码弹窗并启动 gen → 轮询 → authTicket → 二次验证分派 全流程 */
    fun startPddQrLogin() {
        pddQrJob?.cancel()
        _state.value = _state.value.copy(
            pddQrOpen = true, pddQrContent = null, pddQrHint = "正在生成二维码…",
            pddSmsVerifySent = false, pddVerifyUrl = null,
        )
        pddQrJob = viewModelScope.launch {
            try {
                genLoop@ while (kotlin.coroutines.coroutineContext.isActive) {
                    val qr = pddQrClient.genQr()
                    pddSecretKey = qr.secretKey
                    _state.value = _state.value.copy(
                        pddQrContent = qr.qrContent,
                        pddQrHint = "打开拼多多 App 扫一扫 → 相册，选择本二维码并确认登录",
                    )
                    // 轮询当前码（有效期实测 ~2 分钟；过期返回空串 → 重新 genQr）
                    val ticket = pollPddQr(qr.secretKey) ?: return@launch // 弹窗已关
                    if (ticket.isEmpty()) {
                        _state.value = _state.value.copy(pddQrHint = "二维码已过期，正在刷新…")
                        continue@genLoop
                    }
                    pddTicket = ticket
                    _state.value = _state.value.copy(pddQrHint = "已确认，正在换取登录态…")
                    when (val auth = pddQrClient.authTicket(pddTicket, qr.secretKey)) {
                        is com.aggapp.platform.pdd.PddQrClient.AuthResult.Ok -> {
                            onPddQrSuccess(auth.credential)
                            return@launch
                        }
                        is com.aggapp.platform.pdd.PddQrClient.AuthResult.NeedVerify -> {
                            handlePddVerify(auth.verifyAuthToken)
                            return@launch
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd qr login failed", e)
                _state.value = _state.value.copy(pddQrOpen = false, message = "扫码登录失败：${e.message}")
            }
        }
    }

    /** 轮询单个二维码；弹窗被关返回 null，拿到 ticket 返回非空 */
    private suspend fun pollPddQr(secretKey: String): String? {
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (!_state.value.pddQrOpen) return null
            when (val r = pddQrClient.checkQr(secretKey)) {
                is com.aggapp.platform.pdd.PddQrClient.CheckResult.Ticket -> return r.ticket
                is com.aggapp.platform.pdd.PddQrClient.CheckResult.Expired -> return "" // 触发重新 gen
                is com.aggapp.platform.pdd.PddQrClient.CheckResult.Waiting -> delay(3000)
            }
        }
        return null
    }

    /**
     * 54002 二次验证分派：vcPreCk → obtainCaptcha 拿服务端下发类型。
     * Text(1/2/3)：原生弹窗输码（不展示官方页面）；其他：WebView 官方验证页手动过。
     * obtainCaptcha 失败（如空 captcha_collect 被拒）→ 兜底转 WebView 官方页。
     */
    private suspend fun handlePddVerify(vat: String) {
        pddVat = vat
        var type = -1
        try {
            val pre = pddQrClient.vcPreCk(vat)
            pddSalt = pre.salt
            type = pddQrClient.obtainCaptcha(vat, pre.salt)
        } catch (e: Exception) {
            AppLog.e("AggApp", "pdd vc preCheck/obtain failed", e)
        }
        if (type in 1..3) {
            _state.value = _state.value.copy(
                pddSmsVerifySent = true,
                pddQrHint = "安全验证：验证码已发送到绑定手机号，请输入短信验证码",
            )
        } else {
            // App 端 preCheck/obtain 已占用本 vat 的验证会话；页面再对同一 vat preCheck 会拿到
            // 降级挑战（实测症状：点选无提示词、通过后无反应、收尾 110000）。
            // 因此非 Text 一律重新换取一份全新 vat，让官方页用干净会话跑完整流程。
            pddVat = try {
                when (val again = pddQrClient.authTicket(pddTicket, pddSecretKey)) {
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.NeedVerify -> again.verifyAuthToken
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.Ok -> {
                        // 意外直接成功（服务端免验证了）：直接落库
                        onPddQrSuccess(again.credential)
                        return
                    }
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd refresh vat failed, fallback to current vat", e)
                vat
            }
            _state.value = _state.value.copy(
                pddQrOpen = false,
                pddVerifyUrl = pddVerifyPageUrl(),
                pddQrHint = null,
            )
        }
    }

    /** 官方二次验证页（bundle：verifyLogin QR 分支从 location.search 读
     *  VerifyAuthToken/secret_key/ticket/from，isFromQRCodeLogin = from==="qr_code_login"）。
     *  参数必须 URL 编码——ticket 含特殊字符时未编码会导致页面读到坏 ticket，收尾失败。 */
    private fun pddVerifyPageUrl(): String {
        fun q(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val url = "https://mobile.yangkeduo.com/psnl_verification.html" +
            "?VerifyAuthToken=${q(pddVat)}&secret_key=${q(pddSecretKey)}" +
            "&ticket=${q(pddTicket)}&from=qr_code_login"
        AppLog.d("AggApp", "pdd verify page url: $url")
        return url
    }

    /** Text 二次验证：提交短信码 → userVerify → authTicket(status:1) 出 Cookie */
    fun pddVerifySms(code: String) {
        if (code.isBlank()) {
            _state.value = _state.value.copy(message = "请输入短信验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                if (!pddQrClient.userVerify(pddVat, pddSalt, code)) {
                    _state.value = _state.value.copy(busy = false, message = "验证码校验未通过，请重试")
                    return@launch
                }
                when (val auth = pddQrClient.authTicket(pddTicket, pddSecretKey, pddVat, 1)) {
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.Ok -> {
                        onPddQrSuccess(auth.credential)
                    }
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.NeedVerify -> {
                        _state.value = _state.value.copy(
                            busy = false, pddQrOpen = false, pddVerifyUrl = pddVerifyPageUrl(),
                        )
                    }
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd verifySms failed", e)
                _state.value = _state.value.copy(busy = false, message = "验证失败：${e.message}")
            }
        }
    }

    /** 非 Text 验证页通过后（WebView Cookie 出现 PDDAccessToken）的落库回调 */
    fun onPddWebViewCredential(cred: Credential) {
        viewModelScope.launch {
            session.upsert(cred)
            _state.value = _state.value.copy(pddVerifyUrl = null, message = "拼多多登录成功")
            refreshLoginStates()
        }
    }

    /**
     * 手动兜底：用户在官方验证页过完验证但页面收尾（auth/ticket status:1）未落 Cookie 时，
     * App 端按页面 bundle 同参补发（PddQrClient.authTicket）。
     */
    fun pddManualFinishVerify() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                when (val auth = pddQrClient.authTicket(pddTicket, pddSecretKey, pddVat, 1)) {
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.Ok -> onPddQrSuccess(auth.credential)
                    is com.aggapp.platform.pdd.PddQrClient.AuthResult.NeedVerify -> {
                        _state.value = _state.value.copy(
                            busy = false,
                            message = "服务端仍要求验证（可能已过期），请关闭后重新扫码",
                        )
                    }
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd manual finish failed", e)
                _state.value = _state.value.copy(busy = false, message = "收尾失败：${e.message}")
            }
        }
    }

    fun pddDismissVerifyUrl() {
        _state.value = _state.value.copy(pddVerifyUrl = null, message = "已取消验证，可重新扫码")
    }

    private suspend fun onPddQrSuccess(cred: Credential) {
        session.upsert(cred)
        _state.value = _state.value.copy(
            pddQrOpen = false, pddQrContent = null, pddQrHint = null,
            pddSmsVerifySent = false, message = "拼多多登录成功",
        )
        refreshLoginStates()
    }

    /** 关闭扫码弹窗，取消轮询 */
    fun dismissPddQr() {
        pddQrJob?.cancel()
        pddQrJob = null
        _state.value = _state.value.copy(
            pddQrOpen = false, pddQrContent = null, pddQrHint = null, pddSmsVerifySent = false,
        )
    }

    fun loginTaobao(smsCode: String) {
        if (smsPhone.isEmpty()) {
            _state.value = _state.value.copy(message = "请先获取验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val cred = adapters.getValue(Platform.TAOBAO).loginWithSmsCode(smsPhone, smsCode)
                session.upsert(cred)
                AppLog.d("AggApp", "taobao login ok: tokenLen=${cred.token.length} cookies=${cred.token.split(';').size} unb=${cred.extra["unb"]}")
                _state.value = _state.value.copy(busy = false, message = "淘宝登录成功")
                refreshLoginStates()
            } catch (e: Exception) {
                AppLog.e("AggApp", "taobao login failed", e)
                _state.value = _state.value.copy(busy = false, message = "登录失败：${e.message}")
            }
        }
    }

    // ---------- 京东：短信验证码登录（主链路，jd.md §2.2.2）----------

    /** 步骤1：准备短信登录 → 成功弹出滑块验证 WebView */
    fun jdPrepareSms(phone: String) {
        if (phone.length != 11) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, jdSmsSent = false)
            try {
                val adapter = adapters.getValue(Platform.JD) as com.aggapp.platform.jd.JdAdapter
                val url = adapter.prepareSmsLogin(phone)
                _state.value = _state.value.copy(busy = false, jdCaptchaUrl = url)
            } catch (e: Exception) {
                AppLog.e("AggApp", "jd prepareSms failed", e)
                _state.value = _state.value.copy(busy = false, message = "准备失败：${e.message}")
            }
        }
    }

    /** 步骤2：滑块验证成功回调 → 截获 vt → sendlogincode 发短信 */
    fun jdOnCaptchaResult(dataJson: String) {
        viewModelScope.launch {
            try {
                val root = org.json.JSONObject(dataJson)
                // 桥回传的是 postMessage 的 data 字段：{"type":"captcha_result","status":"success",
                // "data":{"success":true,...,"vt":"..."}}；层层兜底取 vt
                val vt = root.optString("vt").ifEmpty {
                    root.optJSONObject("data")?.optString("vt").orEmpty()
                }.ifEmpty {
                    root.optJSONObject("data")?.optJSONObject("data")?.optString("vt").orEmpty()
                }
                if (vt.isEmpty()) {
                    _state.value = _state.value.copy(jdCaptchaUrl = null, message = "验证未通过，请重试")
                    return@launch
                }
                _state.value = _state.value.copy(busy = true)
                val adapter = adapters.getValue(Platform.JD) as com.aggapp.platform.jd.JdAdapter
                adapter.sendSmsWithVt(vt)
                _state.value = _state.value.copy(
                    busy = false, jdCaptchaUrl = null, jdSmsSent = true, message = "验证码已发送",
                )
            } catch (e: Exception) {
                AppLog.e("AggApp", "jd sendSms failed", e)
                _state.value = _state.value.copy(
                    busy = false, jdCaptchaUrl = null, message = "发送验证码失败：${e.message}",
                )
            }
        }
    }

    fun jdDismissCaptcha() {
        _state.value = _state.value.copy(jdCaptchaUrl = null)
    }

    /** 步骤3：提交短信验证码，dophonelogin 拿 pt_key */
    fun jdVerifySms(smsCode: String) {
        if (smsCode.isBlank()) {
            _state.value = _state.value.copy(message = "请输入短信验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.JD) as com.aggapp.platform.jd.JdAdapter
                val cred = adapter.verifySmsCode(smsCode)
                session.upsert(cred)
                _state.value = _state.value.copy(busy = false, jdSmsSent = false, message = "京东登录成功")
                refreshLoginStates()
            } catch (e: Exception) {
                AppLog.e("AggApp", "jd verifySms failed", e)
                _state.value = _state.value.copy(busy = false, message = "登录失败：${e.message}")
            }
        }
    }

    // ---------- 京东：粘贴 Cookie ----------

    fun loginJd(ptKey: String, pin: String) {
        if (ptKey.isBlank() || pin.isBlank()) {
            _state.value = _state.value.copy(message = "pt_key 与 pin 均必填")
            return
        }
        viewModelScope.launch {
            try {
                val cred = adapters.getValue(Platform.JD).let { adapter ->
                    @Suppress("UNCHECKED_CAST")
                    (adapter as com.aggapp.platform.jd.JdAdapter).loginWithCookie(ptKey.trim(), pin.trim())
                }
                session.upsert(cred)
                _state.value = _state.value.copy(message = "京东凭据已保存")
                refreshLoginStates()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "保存失败：${e.message}")
            }
        }
    }

    // ---------- 顺丰：短信验证码登录（官方小程序链路，去 YYB，sf.md §2.5；发码前先过极验 v4）----------

    /** 步骤1：校验手机号并弹出极验滑块（服务端 errorCode 70000 = 发码必须携带 gt 四件套） */
    fun sfStartCaptcha(phone: String) {
        if (phone.length != 11) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        sfPhone = phone
        _state.value = _state.value.copy(message = null, sfSmsSent = false, sfGeetestOpen = true)
    }

    /**
     * 步骤2：极验成功回调（SfGeetestDialog 桥回传 gt 四件套 JSON）→ 携带 gt 头发码。
     * pass_token 一次性：任何失败（验证未过/发码失败）都关弹窗，重试需重新过滑块。
     */
    fun sfOnCaptchaResult(dataJson: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val r = org.json.JSONObject(dataJson)
                val gt = mapOf(
                    "lot_number" to r.optString("lot_number"),
                    "captcha_output" to r.optString("captcha_output"),
                    "pass_token" to r.optString("pass_token"),
                    "gen_time" to r.optString("gen_time"),
                )
                if (gt.values.any { it.isEmpty() }) {
                    _state.value = _state.value.copy(busy = false, sfGeetestOpen = false, message = "验证未通过，请重试")
                    return@launch
                }
                val adapter = adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter
                adapter.sendSmsCode(sfPhone, gt)
                _state.value = _state.value.copy(
                    busy = false, sfGeetestOpen = false, sfSmsSent = true, message = "验证码已发送",
                )
            } catch (e: Exception) {
                AppLog.e("AggApp", "sf sendSms failed", e)
                _state.value = _state.value.copy(
                    busy = false, sfGeetestOpen = false, message = "发送失败：${e.message}",
                )
            }
        }
    }

    fun sfDismissCaptcha() {
        _state.value = _state.value.copy(sfGeetestOpen = false)
    }

    /** 步骤3：提交验证码 bindMember；成功后凭据含 staticSeg（永久）即本地出码，否则按 suuid 自动重试 */
    fun loginSfSms(smsCode: String) {
        if (sfPhone.isEmpty()) {
            _state.value = _state.value.copy(message = "请先获取验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val cred = adapters.getValue(Platform.SF).loginWithSmsCode(sfPhone, smsCode)
                session.upsert(cred)
                _state.value = _state.value.copy(
                    busy = false,
                    sfSmsSent = false,
                    message = if (cred.extra.containsKey("staticSeg")) "顺丰登录成功（身份码已永久本地生成）"
                    else "顺丰登录成功（静态段未取到，稍后自动重试）",
                )
                refreshLoginStates()
            } catch (e: Exception) {
                AppLog.e("AggApp", "sf login failed", e)
                _state.value = _state.value.copy(busy = false, message = "登录失败：${e.message}")
            }
        }
    }

    // ---------- 顺丰：粘贴 YJY-Auth（旧驿收发链路兜底） ----------

    fun loginSf(token: String, onSuccess: suspend (Credential) -> Unit = {}) {
        val t = token.trim()
        if (t.isEmpty()) {
            _state.value = _state.value.copy(message = "请粘贴 YJY-Auth token")
            return
        }
        viewModelScope.launch {
            try {
                val exp = decodeJwtExp(t)
                val cred = Credential(
                    platform = Platform.SF,
                    token = t,
                    extra = if (exp != null) mapOf("exp" to exp.toString()) else emptyMap(),
                    capturedAt = System.currentTimeMillis(),
                )
                // 登录后立即取静态段并缓存（此后身份码本地生成）
                try {
                    val adapter = adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter
                    val staticSeg = adapter.fetchStaticSeg(cred)
                    session.upsert(cred.copy(extra = cred.extra + ("staticSeg" to staticSeg)))
                } catch (e: Exception) {
                    // token 无效/接口失败：仍保存凭据，checkAlive 会标红
                    session.upsert(cred)
                }
                _state.value = _state.value.copy(message = "顺丰凭据已保存")
                refreshLoginStates()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "保存失败：${e.message}")
            }
        }
    }

    // ---------- 如意邻里：图形码 + 短信验证码登录（README §3.2，三步全在 RyllAdapter）----------

    /** 步骤①：取图形验证码（randValue 存 adapter 单例内，UI 显示图片并让用户输 4 位图形码） */
    fun ryllFetchVcode() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter
                val v = adapter.fetchVcode()
                _state.value = _state.value.copy(busy = false, ryllVcodeBase64 = v.base64Png)
            } catch (e: Exception) {
                AppLog.e("AggApp", "ryll vcode failed", e)
                _state.value = _state.value.copy(busy = false, message = "获取图形码失败：${e.message}")
            }
        }
    }

    /** 步骤②：图形码 + 手机号发短信；图形码错误时自动刷新图形码让用户重试 */
    fun ryllSendSms(phone: String, captchaCode: String) {
        if (phone.length != 11) {
            _state.value = _state.value.copy(message = "请输入 11 位手机号")
            return
        }
        if (captchaCode.isBlank()) {
            _state.value = _state.value.copy(message = "请输入图形验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter
                adapter.sendSms(phone, captchaCode)
                _state.value = _state.value.copy(busy = false, ryllSmsSent = true, message = "验证码已发送")
            } catch (e: Exception) {
                AppLog.e("AggApp", "ryll sendSms failed", e)
                // 图形码错误 → 自动换一张，清空已输图形码
                _state.value = _state.value.copy(busy = false, message = "发送失败：${e.message}（已刷新图形码）")
                ryllFetchVcode()
            }
        }
    }

    /** 步骤③：短信码登录；成功后凭据含静态 PIN（永久本地出码） */
    fun ryllLogin(smsCode: String) {
        if (smsCode.isBlank()) {
            _state.value = _state.value.copy(message = "请输入短信验证码")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter
                val cred = adapter.loginWithSms(smsCode)
                session.upsert(cred)
                _state.value = _state.value.copy(
                    busy = false,
                    ryllSmsSent = false,
                    message = if (cred.extra.containsKey("pin")) "如意邻里登录成功（静态码已本地缓存）"
                    else "如意邻里登录成功",
                )
                refreshLoginStates()
            } catch (e: Exception) {
                AppLog.e("AggApp", "ryll login failed", e)
                _state.value = _state.value.copy(busy = false, message = "登录失败：${e.message}")
            }
        }
    }

    fun logout(platform: Platform) {
        session.clear(platform)
        refreshLoginStates()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** HS256 JWT payload exp 解码；非 JWT 返回 null */
    private fun decodeJwtExp(token: String): Long? = try {
        val payload = token.split(".")[1]
        val json = String(java.util.Base64.getUrlDecoder().decode(padBase64(payload)))
        val m = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json) ?: return null
        m.groupValues[1].toLong()
    } catch (e: Exception) {
        null
    }

    private fun padBase64(s: String): String {
        val b = s.replace('-', '+').replace('_', '/')
        return b + "=".repeat((4 - b.length % 4) % 4)
    }
}

