package com.aggapp.platform.taobao

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.AppLog
import okhttp3.FormBody
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 淘宝 PC 短信协议登录（docs/platform/taobao/taobao.md §3.4，逐请求移植 taobao/js/protocol_login.js）。
 *
 * 链路：
 *   1. GET  havanaone/login/login.htm?bizName=taobao          → 种 Cookie + 从 HTML 解析 _csrf
 *   2. POST havanaone/loginLegacy/sms/sendSms.do              → {"smsToken":"sm2_xxx"}（验证码发到手机）
 *   3. POST havanaone/loginLegacy/sms/login.do                → {"loginResult":"success"}，
 *      Set-Cookie 落全套登录态（含 httpOnly cookie2/unb/skt/sg/_l_g_/tracknick）
 *
 * 实测约束（§3.4）：
 * - 接口无 baxia 反爬：ua / umidToken 传空串即可，无滑块/验证码图片；
 * - smsToken 分钟级时效，send 与 login 必须同一会话（Cookie 连续）；
 * - _csrf 会话级、从登录页 HTML 解析，禁止写死。
 */
/** 多账号候选项（multiAccountSelect 页 mutiAccountDOS 逐对象解析） */
data class TbAccountOption(
    val uniqId: String,
    val nick: String,
    val avatarUrl: String,
    val mostFrequent: Boolean,
    val lastActiveTime: String,
)

/** 短信登录结果：Cookie 直接落地（Done）/ 需选账号（NeedSelectAccount）/ 命中身份验证页需人工过（RiskCheck） */
internal sealed class SmsLoginOutcome {
    data class Done(val cookies: TaobaoCookieStore) : SmsLoginOutcome()
    data class NeedSelectAccount(val accounts: List<TbAccountOption>) : SmsLoginOutcome()
    data class RiskCheck(val url: String) : SmsLoginOutcome()
}

/** 选账号收尾结果：登录态落地（Done）或命中 iv 风控页需用户过验证（RiskCheck，浏览器实测
 *  风控放行后重试 continue.htm&uniqId 可直接 200 落登录态，2026-10-22 CDP 抓包） */
internal sealed class SelectOutcome {
    data class Done(val cookies: TaobaoCookieStore) : SelectOutcome()
    data class RiskCheck(val url: String) : SelectOutcome()
}

internal class TaobaoLoginClient(private val http: OkHttpClient) {

    /** 会话 Cookie（send → login 同会话）。含 httpOnly；Android OkHttp 直读无 WebView/Node 的限制 */
    val cookies = TaobaoCookieStore()

    /** 登录页 _csrf（会话级，openLoginPage 时解析） */
    private var csrf: String = ""

    /** sendSms.do 返回的 smsToken（sm2_ 前缀） */
    var smsToken: String = ""
        private set

    /** 调试导出：登录链路会话中间态全貌（_csrf / smsToken / 会话 Cookie） */
    fun debugDump(): String = buildString {
        appendLine("csrf=$csrf")
        appendLine("smsToken=$smsToken")
        appendLine("cookies=${cookies.header()}")
        if (pendingAccounts.isNotEmpty()) {
            appendLine("pendingAccounts=${pendingAccounts.map { "${it.nick}(${it.uniqId})" }}")
        }
    }

    /** 当前会话 Cookie 头（风控 WebView 预置注入用） */
    fun cookieHeader(): String = cookies.header()

    /** 合并外部（风控 WebView）Cookie 到会话存储并返回（WebView 内完成登录后收尾用） */
    fun mergeWebviewCookies(header: String): TaobaoCookieStore {
        header.split("; ").filter { it.contains("=") }.forEach {
            val i = it.indexOf('=')
            if (i > 0) cookies[it.substring(0, i).trim()] = it.substring(i + 1).trim()
        }
        return cookies
    }

    /** 步骤 1：GET 登录页，种 Cookie + 解析 _csrf（§3.4 步骤 1 / protocol_login.js Step 1） */
    suspend fun openLoginPage() {
        val resp = get(LOGIN_PAGE_URL)
        val html = resp.body
        csrf = Regex("\"csrfId\":\"([0-9a-f]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("_csrf[\"'=:\\s]+([0-9a-f]{32})").find(html)?.groupValues?.get(1)
            ?: throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "登录页未解析到 _csrf（会话级字段禁止写死，见文档 §3.4）",
            )
    }

    /** 步骤 2：POST sendSms.do 发送 4 位短信验证码（§3.4 步骤 2 / protocol_login.js Step 2） */
    suspend fun sendSms(phone: String) {
        if (csrf.isEmpty()) openLoginPage()
        val form = FormBody.Builder()
            .apply { commonFields().forEach { (k, v) -> add(k, v) } }
            .add("phoneCode", "86")
            .add("loginId", phone)
            .add("loginId2", "")
            .add("countryCode", "CN")
            .add("codeLength", "4")
            .build()
        val body = post(SEND_SMS_URL, form).body
        // 频率限制等业务拒绝（hasError=false 但 titleMsg 有内容，2026-10-22 真机实测：
        // {"titleMsg":"获取次数已达上限，请24小时后再试","resultCode":100}）→ 直接提示
        Regex("\"titleMsg\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let { msg ->
            throw PlatformError.ParseFailed(Platform.TAOBAO, "发送验证码失败：$msg")
        }
        // 与 Node 脚本同款正则提取，容忍响应结构差异
        smsToken = Regex("\"smsToken\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "sendSms.do 未返回 smsToken：${body.take(200)}",
            )
    }

    /** 步骤 3：POST sms/login.do 完成登录（§3.4 步骤 3 / protocol_login.js Step 4）。
     *  直接成功 → Done（Cookie 已落 store）；redirect 到 multiAccountSelect → NeedSelectAccount
     *  （手机号绑定多个淘宝账号，服务端要求先选账号才下发登录态）。 */
    suspend fun login(phone: String, smsCode: String): SmsLoginOutcome {
        check(smsToken.isNotEmpty()) { "smsToken 缺失：sendSmsCode 未执行或会话已丢失" }
        if (!Regex("^\\d{4}$").matches(smsCode)) {
            throw PlatformError.ParseFailed(Platform.TAOBAO, "验证码须为 4 位数字（codeLength=4）")
        }
        val form = FormBody.Builder()
            .apply { commonFields().forEach { (k, v) -> add(k, v) } }
            .add("loginId", phone)
            .add("loginId2", "")
            .add("phoneCode", "86")
            .add("countryCode", "CN")
            .add("smsCode", smsCode)
            .add("smsToken", smsToken)
            .add("keepLogin", "false")
            .add("contextToken", "")
            .build()
        val body = post(LOGIN_URL, form).body

        // 成功判定双格式兼容（2026-09-29 真机实测出现新版 havana 响应）：
        // - 旧格式 {"loginResult":"success",...}
        // - 新格式 {"hasError":false,"content":{...}}（无 loginResult 字段）
        // 最终以 cookie2/unb 登录态 Cookie 是否落地为准（下方加固判定）
        val loginResult = Regex("\"loginResult\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val hasError = Regex("\"hasError\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)
        if (loginResult != "success" && hasError != "false") {
            throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "登录被拒（loginResult=${loginResult ?: "-"} hasError=${hasError ?: "-"} body=${body.take(200)}）",
            )
        }
        // 异步收尾分支：
        // - iv 校验：redirectUrl=login_check.htm?havana_iv_token=... → GET 落地完整登录态（2026-09-29）
        // - 多账号：redirectUrl=multiAccountSelect.htm?contextToken=... → 解析候选账号，
        //   由 UI 弹原生选择弹窗，用户选中后 selectAccount(uniqId) 收尾（2026-10-22）
        val redirectUrl = Regex("\"redirectUrl\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.replace("\\/", "/")
        if (redirectUrl != null) {
            if (redirectUrl.contains("multiAccountSelect.htm")) {
                parseAccountSelectPage(redirectUrl)
                return SmsLoginOutcome.NeedSelectAccount(pendingAccounts)
            }
            // iv 校验：逐跳跟随收 Set-Cookie；若 200 落地身份验证页（ivModel，
            // 2026-10-22 真机实测风控触发）→ 返回 RiskCheck 交 WebView 人工过验证，
            // 放行后登录在 WebView 内完成，由 mergeWebviewCookies 合并收尾
            var ivCur: String = redirectUrl
            repeat(5) {
                val cur = ivCur
                val r = taobaoGetNoRedirect(http, cur, cookies.header(), LOGIN_PAGE_URL, LOGIN_UA)
                cookies.store(r.setCookies)
                val l = r.location
                if (r.code in 300..399 && !l.isNullOrBlank()) {
                    ivCur = java.net.URI(cur).resolve(l).toString()
                    return@repeat
                }
                if (r.body.contains("window.ivModel") || r.body.contains("list_strategy_tpl")) {
                    AppLog.d("TB", "smsLogin: iv verification page (200) url=$cur")
                    return SmsLoginOutcome.RiskCheck(cur)
                }
                return@repeat
            }
        }

        // 业务拒绝提示（hasError=false/success=true 但 titleMsg 有内容：校验码已失效、频率上限等）
        Regex("\"titleMsg\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let { msg ->
            throw PlatformError.ParseFailed(Platform.TAOBAO, "登录失败：$msg")
        }

        // 成功加固判定（文档 §3.3）：cookie2 与 unb 必须同时非空；
        // 失败时附完整响应体与已落地 Cookie（2026-09-29 新版 havana 异步登录排查）
        if (cookies["cookie2"].isNullOrBlank() || cookies["unb"].isNullOrBlank()) {
            throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "登录响应 success 但 Cookie 缺 cookie2/unb 登录态 | cookies=${cookies.header()} | body=$body",
            )
        }
        return SmsLoginOutcome.Done(cookies)
    }

    /** 多账号选择中间态（NeedSelectAccount 后非空，selectAccount 消费） */
    var pendingAccounts: List<TbAccountOption> = emptyList()
        private set

    /** 选账号收尾 URL（multiAccountSelect 页 viewData.returnUrl，含 contextToken） */
    private var selectReturnUrl: String = ""

    /**
     * 解析 multiAccountSelect 页：window.viewData.mutiAccountDOS → 候选账号列表 +
     * returnUrl（passport.taobao.com/havanaone/login/continue.htm?...contextToken=...）。
     * 选账号页（viewConfig={}）点击账号即 location.replace(returnUrl + "&uniqId=" + uniqId)。
     */
    private suspend fun parseAccountSelectPage(selectUrl: String) {
        val page = get(selectUrl).body
        // 账号对象含一层嵌套（extra:{}），正则容忍一层大括号
        val objs = Regex("\\{[^{}]*(?:\\{[^{}]*\\})?[^{}]*\\}").findAll(page)
            .filter { it.value.contains("\"uniqId\"") }
        val accounts = objs.mapNotNull { o ->
            fun f(k: String) =
                Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(o.value)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            val uniqId = f("uniqId")
            if (uniqId.isEmpty()) return@mapNotNull null
            TbAccountOption(
                uniqId = uniqId,
                nick = f("taobaoNick"),
                avatarUrl = f("imageUrl"),
                mostFrequent = o.value.contains("\"isMostFrequently\"\\s*:\\s*true".toRegex()) ||
                    o.value.contains("\"isMostFrequently\":true"),
                lastActiveTime = f("lastActiveTime"),
            )
        }.toList()
        if (accounts.isEmpty()) {
            throw PlatformError.ParseFailed(Platform.TAOBAO, "选账号页未解析到候选账号: ${page.take(300)}")
        }
        selectReturnUrl = Regex("\"returnUrl\"\\s*:\\s*\"([^\"]+)\"").find(page)
            ?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw PlatformError.ParseFailed(Platform.TAOBAO, "选账号页未解析到 returnUrl")
        pendingAccounts = accounts
        AppLog.d("TB", "multi-account: ${accounts.size} candidates, returnUrl=$selectReturnUrl")
    }

    /**
     * 用户选中账号后收尾：GET returnUrl + "&uniqId="（continue.htm 落最终登录态）。
     * 逐跳跟随重定向并逐跳收 Set-Cookie（OkHttp 自动重定向会丢中间跳 Set-Cookie）。
     * 命中 iv 风控页（verify_modes.htm，风控触发、条件性出现）→ 返回 RiskCheck 交 UI
     * 弹 WebView 让用户手动过风控；放行后重试本方法即可直接落登录态（浏览器实测）。
     */
    suspend fun selectAccount(uniqId: String): SelectOutcome {
        check(selectReturnUrl.isNotEmpty()) { "无待选账号会话：请先完成短信登录" }
        var url = if (selectReturnUrl.contains("?")) "$selectReturnUrl&uniqId=$uniqId" else "$selectReturnUrl?uniqId=$uniqId"
        var lastCode = 0
        var lastBody = ""
        repeat(5) {
            val resp = taobaoGetNoRedirect(http, url, cookies.header(), LOGIN_PAGE_URL, LOGIN_UA)
            cookies.store(resp.setCookies)
            lastCode = resp.code
            lastBody = resp.body
            val loc = resp.location
            if (resp.code in 300..399 && !loc.isNullOrBlank()) {
                url = java.net.URI(url).resolve(loc).toString()
                // 302 直达风控页 → 不再跟随，交 WebView（注意：login_check.htm 是 iv 校验非风控，继续跟）
                if (url.contains("iv/verify_modes")) {
                    AppLog.d("TB", "selectAccount: risk check required (302) url=$url")
                    return SelectOutcome.RiskCheck(url)
                }
                return@repeat
            }
            // 200 落地页即风控页（页面自检/自动续登场景）→ 交 WebView
            if (url.contains("iv/verify_modes") || lastBody.contains("/iv/verify_modes")) {
                AppLog.d("TB", "selectAccount: risk check required (200) url=$url")
                return SelectOutcome.RiskCheck(url)
            }
            // 200 "Loading..." 落地页：JS 跳转到 iv 二次校验（login_check.htm?havana_iv_token=...，
            // 2026-10-22 真机实测多账号收尾必现）——unb/skt 登录态在该跳 Set-Cookie 落地，必须跟随
            val ivUrl = Regex("https://passport\\.taobao\\.com/iv/[^\"'\\s]+havana_iv_token=[A-Za-z0-9\\-_]+")
                .find(lastBody)?.value
            if (ivUrl != null) {
                AppLog.d("TB", "selectAccount: follow iv check: $ivUrl")
                var ivCur: String = ivUrl
                repeat(5) {
                    val cur = ivCur
                    val r = taobaoGetNoRedirect(http, cur, cookies.header(), LOGIN_PAGE_URL, LOGIN_UA)
                    cookies.store(r.setCookies)
                    lastCode = r.code
                    lastBody = r.body
                    val l = r.location
                    if (r.code in 300..399 && !l.isNullOrBlank()) {
                        ivCur = java.net.URI(ivCur).resolve(l).toString()
                        // iv 链中途被要求风控 → 交 WebView
                        if (ivCur.contains("iv/verify_modes")) {
                            AppLog.d("TB", "selectAccount: risk check required (in iv chain) url=$ivCur")
                            return SelectOutcome.RiskCheck(ivCur)
                        }
                        return@repeat
                    }
                    // iv 200 落地页含 ivModel（身份验证选择页，需用户操作，2026-10-22 真机实测）
                    // → 交 WebView 人工过验证；放行后登录在 WebView 内完成，由
                    // mergeWebviewCookies 合并收尾（contextToken 已消费，重试会报校验码失效）
                    if (lastBody.contains("window.ivModel") || lastBody.contains("list_strategy_tpl")) {
                        AppLog.d("TB", "selectAccount: iv verification page (200) url=$cur")
                        return SelectOutcome.RiskCheck(ivCur)
                    }
                    return@repeat
                }
            }
            AppLog.d("TB", "selectAccount done: uniqId=$uniqId HTTP=$lastCode cookies=${cookies.header()}")
            // 收尾成功判定：cookie2 与 unb 必须同时落地
            if (cookies["cookie2"].isNullOrBlank() || cookies["unb"].isNullOrBlank()) {
                throw PlatformError.ParseFailed(
                    Platform.TAOBAO,
                    "选账号收尾后 Cookie 仍缺 cookie2/unb | HTTP=$lastCode uniqId=$uniqId cookies=${cookies.header()} body=${lastBody.take(300)}",
                )
            }
            pendingAccounts = emptyList()
            selectReturnUrl = ""
            return SelectOutcome.Done(cookies)
        }
        throw PlatformError.ParseFailed(
            Platform.TAOBAO,
            "选账号收尾重定向超 5 跳 | uniqId=$uniqId cookies=${cookies.header()} body=${lastBody.take(300)}",
        )
    }

    /** 登录页公共字段（H5 入口形态，对拍 taobao/js/test_h5_login.js；ua/umidToken 可为空） */
    private fun commonFields(): List<Pair<String, String>> = listOf(
        "isIframe" to "false",
        "banThirdPartyCookie" to "false",
        "documentReferer" to "",
        "defaultView" to "sms",
        "ua" to "",
        "umidGetStatusVal" to "",
        "screenPixel" to "399x683",
        "navlanguage" to "zh-CN",
        "navUserAgent" to LOGIN_UA,
        "navPlatform" to "Linux armv8l",
        "hitRSA2048Gray" to "true",
        "bizEntrance" to "taobao_h5",
        "bizName" to "taobao",
        "_csrf" to csrf,
        "lang" to "zh_CN",
        "umidToken" to "",
        "umidTag" to "NOT_INIT",
        "weiBoMpBridge" to "",
        "jsVersion" to "0.10.37",
        "deviceId" to (cookies["cna"] ?: ""),
        "pageTraceId" to "215046e1${System.currentTimeMillis()}a324ad",
    )

    /** GET + 收 Set-Cookie + 非 2xx 归一 NetworkError（登录链路统一手机 UA） */
    private suspend fun get(url: String): TaobaoHttpResponse {
        val resp = taobaoGet(http, url, cookies.header(), LOGIN_PAGE_URL, LOGIN_UA)
        cookies.store(resp.setCookies)
        if (resp.code !in 200..299) {
            throw PlatformError.NetworkError(Platform.TAOBAO, IOException("登录页 HTTP ${resp.code}"))
        }
        return resp
    }

    /** POST + 收 Set-Cookie + 非 2xx 归一 NetworkError（登录链路统一手机 UA） */
    private suspend fun post(url: String, form: FormBody): TaobaoHttpResponse {
        val resp = taobaoPost(http, url, form, cookies.header(), LOGIN_PAGE_URL, "https://login.m.taobao.com", LOGIN_UA)
        cookies.store(resp.setCookies)
        if (resp.code !in 200..299) {
            throw PlatformError.NetworkError(Platform.TAOBAO, IOException("登录接口 HTTP ${resp.code}"))
        }
        return resp
    }

    private companion object {
        /**
         * 2026-09-29 真机实测：PC 入口（login.taobao.com + bizEntrance=taobao_pc）被 havana 风控
         * 判定为无指纹客户端 → resultCode 100 强制 iv 二次校验（纯协议无法过）。
         * H5 入口（login.m.taobao.com + taobao_h5 + 手机 UA）实测 login.do 一次直落完整登录态
         * （unb/cookie2/skt 等全 Set-Cookie），无 iv。Node 对拍脚本 taobao/js/test_h5_login.js。
         */
        const val LOGIN_UA: String =
            "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        /** §3.4 步骤 1 登录页（H5 入口） */
        const val LOGIN_PAGE_URL =
            "https://login.m.taobao.com/havanaone/login/login.htm?bizEntrance=taobao_h5&bizName=taobao&loginFrom=wap_tb"

        /** §3.4 步骤 2 发验证码（H5 入口） */
        const val SEND_SMS_URL =
            "https://login.m.taobao.com/havanaone/loginLegacy/sms/sendSms.do?bizEntrance=taobao_h5&bizName=taobao"

        /** §3.4 步骤 3 短信登录（H5 入口） */
        const val LOGIN_URL =
            "https://login.m.taobao.com/havanaone/loginLegacy/sms/login.do?bizEntrance=taobao_h5&bizName=taobao"
    }
}
