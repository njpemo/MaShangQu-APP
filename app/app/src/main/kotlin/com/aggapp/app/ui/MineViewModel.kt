package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformLoginState
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
    val pddQuiz: com.aggapp.platform.pdd.PddQrClient.QuizQuestion? = null, // type=101 知识验证题（原生弹窗作答）
    val pddQuizOrder: Int = 1,            // challenge_verify 的 question_order_number，提交后 +1
    val pddQuizMsg: String? = null,       // 答题反馈（答错提示等）
    // ---- 如意邻里短信验证码登录（图形码 + 短信码，README §3.2）----
    val ryllVcodeBase64: String? = null,  // 图形验证码 PNG（base64 data URL，Compose 直接解码显示）
    val ryllSmsSent: Boolean = false,     // 短信验证码已发送（可输入验证码登录）
    // ---- 淘宝多账号选择（手机号绑定多个淘宝账号时，原生弹窗选择后收尾登录）----
    val tbAccounts: List<com.aggapp.platform.taobao.TbAccountOption> = emptyList(),
    // ---- 淘宝 iv 风控页（多账号收尾命中风控时，WebView 弹窗内手动过风控）----
    val tbRiskUrl: String? = null,
    val tbWebviewCookies: List<Pair<String, String>> = emptyList(), // (url, setCookie串) 预置注入
)

/**
 * 「我的」页 VM：
 * - 淘宝：短信协议登录（sendSmsCode → loginWithSmsCode）
 * - 京东：手机号+短信验证码协议直连登录（prepare→滑块 WebView 截 vt→sendSms→verify）；兜底粘贴 pt_key/pin
 * - 顺丰：短信验证码登录官方小程序链路（发码前过极验 v4 滑块拿 gt 四件套；登录态 suuid → 静态段永久本地出码，sf.md §2.5）；兜底粘贴 YJY-Auth
 * - 拼多多：扫码登录（AUTH_LOGIN 二维码存相册→拼多多 App 相册识别；54002 二次验证 Text 原生输码/其余 WebView 官方页；不踢 App 下线）
 * 启动时 restore 凭据并逐平台 checkAlive。
 */
@HiltViewModel
class MineViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
    private val pddQrClient: com.aggapp.platform.pdd.PddQrClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
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
            // type=101 知识验证（选择题）：challengeCaptcha 拉题 → 原生弹窗作答 →
            // challengeVerify 提交（0=通过 3002=答错取下一题）→ authTicket(vat,1) 收尾。
            // 2026-10-21 CDP 抓包实测协议，全程 API 无 WebView。
            val quiz = try {
                pddQrClient.challengeCaptcha(vat, pddSalt)
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd challengeCaptcha failed", e)
                null
            }
            if (quiz != null) {
                _state.value = _state.value.copy(
                    pddQrHint = "安全验证：请在弹窗中选择正确答案",
                    pddQuiz = quiz,
                    pddQuizOrder = 1,
                    pddQuizMsg = null,
                )
                return
            }
            // 知识题拉取失败 → 兜底：换全新 vat 走 WebView 官方验证页
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

    /** type=101 原生答题：提交所选选项 → 0=通过收尾 / 3002=答错自动取下一题 */
    fun pddAnswerQuiz(optionKeys: List<String>) {
        val q = _state.value.pddQuiz ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, pddQuizMsg = null)
            try {
                val code = pddQrClient.challengeVerify(
                    vat = pddVat,
                    salt = pddSalt,
                    questionId = q.questionId,
                    optionKeys = optionKeys,
                    questionOrderNumber = _state.value.pddQuizOrder,
                )
                when (code) {
                    0 -> {
                        // 全部答对，收尾换登录态
                        when (val auth = pddQrClient.authTicket(pddTicket, pddSecretKey, pddVat, 1)) {
                            is com.aggapp.platform.pdd.PddQrClient.AuthResult.Ok -> onPddQrSuccess(auth.credential)
                            is com.aggapp.platform.pdd.PddQrClient.AuthResult.NeedVerify ->
                                _state.value = _state.value.copy(
                                    busy = false,
                                    pddQuiz = null,
                                    message = "验证通过但服务端仍要求验证，请重新扫码",
                                )
                        }
                    }
                    3002 -> {
                        // 答错：服务端自动下发下一题
                        val next = pddQrClient.challengeCaptcha(pddVat, pddSalt)
                        _state.value = if (next != null) {
                            _state.value.copy(
                                busy = false,
                                pddQuiz = next,
                                pddQuizOrder = _state.value.pddQuizOrder + 1,
                                pddQuizMsg = "答案不正确，请再试一次",
                            )
                        } else {
                            _state.value.copy(busy = false, pddQuiz = null, message = "验证异常，请关闭后重新扫码")
                        }
                    }
                    else -> _state.value = _state.value.copy(
                        busy = false,
                        pddQuizMsg = "提交失败（code=$code），请重试",
                    )
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "pdd answer quiz failed", e)
                _state.value = _state.value.copy(busy = false, pddQuizMsg = "提交失败：${e.message}")
            }
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
            busy = false, // 成功路径必须复位，否则全局按钮一直灰
            pddQrOpen = false, pddQrContent = null, pddQrHint = null,
            pddSmsVerifySent = false, pddQuiz = null, message = "拼多多登录成功",
        )
        refreshLoginStates()
    }

    /** 关闭扫码弹窗，取消轮询 */
    fun dismissPddQr() {
        pddQrJob?.cancel()
        pddQrJob = null
        _state.value = _state.value.copy(
            busy = false,
            pddQrOpen = false, pddQrContent = null, pddQrHint = null, pddSmsVerifySent = false, pddQuiz = null,
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
                val adapter = adapters.getValue(Platform.TAOBAO) as com.aggapp.platform.taobao.TaobaoAdapter
                when (val r = adapter.smsLogin(smsPhone, smsCode)) {
                    is com.aggapp.platform.taobao.TaobaoAdapter.SmsLoginResult.Done -> {
                        session.upsert(r.cred)
                        _state.value = _state.value.copy(busy = false, message = "淘宝登录成功")
                        refreshLoginStates()
                    }
                    is com.aggapp.platform.taobao.TaobaoAdapter.SmsLoginResult.NeedSelect -> {
                        // 手机号绑定多个淘宝账号：弹原生选择弹窗，选中后 tbSelectAccount 收尾
                        _state.value = _state.value.copy(busy = false, tbAccounts = r.accounts)
                    }
                    is com.aggapp.platform.taobao.TaobaoAdapter.SmsLoginResult.RiskCheck ->
                        enterTbRiskCheck(r.url)
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "taobao login failed", e)
                _state.value = _state.value.copy(busy = false, message = "登录失败：${e.message}")
            }
        }
    }

    /** 用户在弹窗中选中账号：continue.htm 收尾落登录态；命中 iv 风控页 → 弹 WebView 手动过 */
    private var lastTbUniqId: String = ""

    fun tbSelectAccount(uniqId: String) {
        lastTbUniqId = uniqId
        attemptSelectAccount()
    }

    private fun attemptSelectAccount() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.TAOBAO) as com.aggapp.platform.taobao.TaobaoAdapter
                when (val r = adapter.selectAccount(lastTbUniqId)) {
                    is com.aggapp.platform.taobao.TaobaoAdapter.SelectResult.Done -> {
                        session.upsert(r.cred)
                        _state.value = _state.value.copy(
                            busy = false, tbAccounts = emptyList(), tbRiskUrl = null,
                            tbWebviewCookies = emptyList(), message = "淘宝登录成功",
                        )
                        refreshLoginStates()
                    }
                    is com.aggapp.platform.taobao.TaobaoAdapter.SelectResult.RiskCheck ->
                        enterTbRiskCheck(r.url)
                }
            } catch (e: Exception) {
                AppLog.e("AggApp", "taobao selectAccount failed", e)
                _state.value = _state.value.copy(
                    busy = false, tbAccounts = emptyList(), tbRiskUrl = null,
                    message = "账号登录失败：${e.message}（可重新发送验证码）",
                )
            }
        }
    }

    /** 进入淘宝 iv 风控/身份验证流程：WebView 弹窗打开验证页，预置会话 Cookie；
     *  用户放行后（WebView 出现 cookie2/unb）tbRiskCheckPassed 合并 Cookie 收尾 */
    private fun enterTbRiskCheck(url: String) {
        val adapter = adapters.getValue(Platform.TAOBAO) as com.aggapp.platform.taobao.TaobaoAdapter
        val header = adapter.loginCookieHeader
        val cookies = header.split("; ").filter { it.contains("=") }
            .map { "https://www.taobao.com/" to "$it; Domain=.taobao.com; Path=/" }
        _state.value = _state.value.copy(busy = false, tbAccounts = emptyList(), tbRiskUrl = url, tbWebviewCookies = cookies)
    }

    /** 风控 WebView 检测到放行（出现 cookie2/unb 登录态 Cookie）→ 合并 Cookie 直接收尾
     *  （登录已在 WebView 内完成，contextToken 已消费，不能重试 selectAccount） */
    fun tbRiskCheckPassed(webviewCookieHeader: String) {
        if (_state.value.tbRiskUrl == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val adapter = adapters.getValue(Platform.TAOBAO) as com.aggapp.platform.taobao.TaobaoAdapter
                val cred = adapter.completeRiskCheck(webviewCookieHeader)
                session.upsert(cred)
                _state.value = _state.value.copy(
                    busy = false, tbRiskUrl = null, tbWebviewCookies = emptyList(), message = "淘宝登录成功",
                )
                refreshLoginStates()
            } catch (e: Exception) {
                AppLog.e("AggApp", "taobao risk check complete failed", e)
                _state.value = _state.value.copy(
                    busy = false, tbRiskUrl = null, tbWebviewCookies = emptyList(),
                    message = "淘宝登录失败：${e.message}（可重新发送验证码）",
                )
            }
        }
    }

    /** 关闭风控 WebView 弹窗（放弃本次登录） */
    fun tbDismissRiskCheck() {
        _state.value = _state.value.copy(tbRiskUrl = null, tbWebviewCookies = emptyList(), message = "已取消，可重新发送验证码")
    }

    /** 关闭多账号选择弹窗（取消登录） */
    fun tbDismissAccountSelect() {
        _state.value = _state.value.copy(tbAccounts = emptyList(), message = "已取消，可重新发送验证码")
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

    // ---------- 调试日志导出（debug 包「我的」页入口） ----------

    /**
     * 导出调试日志 txt 并弹出系统分享：
     * ① 全部登录态（含完整 token/Cookie 登录态，仅供 debug 排查）
     * ② 淘宝短信登录链路中间态（csrf/smsToken/会话 Cookie）
     * ③ AppLog 内存环（操作日志 + NetDebug 请求日志）
     * ④ aggapp.log 落盘文件全量（App 重启不丢）
     */
    fun exportDebugLog() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            try {
                val dir = java.io.File(appContext.cacheDir, "debug_export").apply { mkdirs() }
                val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val f = java.io.File(dir, "aggapp-debug-$ts.txt")
                f.writeText(buildDebugReport())
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.fileprovider", f,
                )
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                appContext.startActivity(
                    android.content.Intent.createChooser(send, "导出调试日志")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _state.value = _state.value.copy(busy = false, message = "调试日志已导出")
            } catch (e: Exception) {
                AppLog.e("AggApp", "export debug log failed", e)
                _state.value = _state.value.copy(busy = false, message = "导出失败：${e.message}")
            }
        }
    }

    private suspend fun buildDebugReport(): String = buildString {
        appendLine("===== 码上取 调试日志导出 =====")
        appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("package: ${appContext.packageName}")
        try {
            val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            appendLine("versionName: ${pi.versionName}  versionCode: ${pi.longVersionCode}")
            appendLine("debuggable: ${(appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
        } catch (_: Exception) {}
        appendLine("NetDebug.enabled=${com.aggapp.platform.api.NetDebug.enabled}")

        appendLine()
        appendLine("===== 1. 各平台登录态（含完整 token/Cookie） =====")
        val creds = session.credentials.value
        for (p in Platform.entries) {
            val cred = creds[p]
            appendLine("--- [${p.label}] ---")
            if (cred == null) {
                appendLine("未登录（无凭据）")
                continue
            }
            appendLine("capturedAt: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(cred.capturedAt))}")
            appendLine("token: ${cred.token}")
            if (cred.extra.isNotEmpty()) {
                appendLine("extra:")
                cred.extra.forEach { (k, v) -> appendLine("  $k = $v") }
            }
        }

        appendLine()
        appendLine("===== 2. 淘宝登录链路中间态（短信协议登录会话） =====")
        (adapters[Platform.TAOBAO] as? com.aggapp.platform.taobao.TaobaoAdapter)
            ?.let { appendLine(it.debugSessionDump()) }

        appendLine()
        appendLine("===== 3. AppLog 内存环（操作日志 + 请求日志） =====")
        appendLine(AppLog.dump())

        appendLine()
        appendLine("===== 4. aggapp.log 落盘（全量，超 2MB 截尾保留最新） =====")
        val logFile = java.io.File(appContext.filesDir, "aggapp.log")
        if (logFile.exists()) {
            val bytes = logFile.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            appendLine(if (text.length > 2_000_000) text.takeLast(2_000_000) else text)
        } else {
            appendLine("（aggapp.log 不存在）")
        }
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

