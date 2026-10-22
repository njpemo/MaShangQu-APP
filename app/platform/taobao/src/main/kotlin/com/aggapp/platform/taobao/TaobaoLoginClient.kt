package com.aggapp.platform.taobao

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
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
internal class TaobaoLoginClient(private val http: OkHttpClient) {

    /** 会话 Cookie（send → login 同会话）。含 httpOnly；Android OkHttp 直读无 WebView/Node 的限制 */
    val cookies = TaobaoCookieStore()

    /** 登录页 _csrf（会话级，openLoginPage 时解析） */
    private var csrf: String = ""

    /** sendSms.do 返回的 smsToken（sm2_ 前缀） */
    var smsToken: String = ""
        private set

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
        // 与 Node 脚本同款正则提取，容忍响应结构差异
        smsToken = Regex("\"smsToken\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "sendSms.do 未返回 smsToken：${body.take(200)}",
            )
    }

    /** 步骤 3：POST sms/login.do 完成登录（§3.4 步骤 3 / protocol_login.js Step 4），返回最终 Cookie 存储 */
    suspend fun login(phone: String, smsCode: String): TaobaoCookieStore {
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
        // 新版异步登录（2026-09-29 真机实测）：响应 {"hasError":false,"content":{"status":0,
        // "data":{"redirect":true,"redirectUrl":"...login_check.htm?havana_iv_token=...","resultCode":100}}}
        // → 须再 GET redirectUrl（havana iv 二次校验）落地完整登录态 Cookie（补 unb/skt 等）
        val redirectUrl = Regex("\"redirectUrl\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        var ivDebug = "no-redirect"
        if (redirectUrl != null) {
            try {
                val iv = get(redirectUrl.replace("\\/", "/"))
                ivDebug = "ivHTTP=${iv.code} ivBody=${iv.body.take(600)} ivCookies=${cookies.header()}"
            } catch (e: Exception) {
                ivDebug = "iv fail: $e | cookies=${cookies.header()}"
            }
        }

        // 成功加固判定（文档 §3.3）：cookie2 与 unb 必须同时非空；
        // 失败时附完整响应体与已落地 Cookie（2026-09-29 新版 havana 异步登录排查）
        if (cookies["cookie2"].isNullOrBlank() || cookies["unb"].isNullOrBlank()) {
            throw PlatformError.ParseFailed(
                Platform.TAOBAO,
                "登录响应 success 但 Cookie 缺 cookie2/unb 登录态 | $ivDebug | cookies=${cookies.header()} | body=$body",
            )
        }
        return cookies
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
