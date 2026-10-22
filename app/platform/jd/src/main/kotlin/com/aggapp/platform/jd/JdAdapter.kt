package com.aggapp.platform.jd

import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.model.PickupCode
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import com.aggapp.platform.api.YybCodeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 京东适配器 —— 真实网络实现。
 *
 * 身份码链路（已在 Node 直连实测打通，无需任何签名/anti-content）：
 * - POST https://lop-proxy.jd.com/generateIdentityCode
 * - Cookie 仅需 `pt_key=<pt_key>; pin=<pin>;`
 * - body：`[{"source":"ConvenientH5","jdPin":-1}]`（JSON 数组，原样字符串）
 * - 必需请求头：ClientInfo: {"client":"m"}、AppParams: {"appid":"2410","ticket_type":"m"}、
 *   LOP-DN: convenient-h5.jd.com、Content-Type: application/json
 * - 返回 JSON 含 identityCode 字段（JDM 前缀），有效期 1200 秒。
 *
 * 登录：主链路为短信验证码登录（prepareSmsLogin → WebView 滑块截 vt → sendSmsWithVt →
 * verifySmsCode，jd.md §2.2.2，Node 全链路实测打通）；YYB 微信扫码（remoteLogin）为辅助；
 * 兜底链路为 UI 手动粘贴 pt_key/pin（loginWithCookie）。
 */
@Singleton
class JdAdapter @Inject constructor(
    private val yyb: YybCodeSource, // YYB 微信 code 源（:app 绑定，内部调 core:yyb）
) : PlatformAdapter {

    override val platform = Platform.JD
    override val loginMode = LoginMode.REMOTE_SERVICE

    /** 纯 JVM 模块直连京东 LOP 网关，单一 OkHttpClient 复用连接池 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        /** 身份码接口（实测无签名要求） */
        const val IDENTITY_URL = "https://lop-proxy.jd.com/generateIdentityCode"

        /** YYB 目标小程序：京东快递微信 appid（jd.md §2.2） */
        const val APPID_WX = "wx73247c7819d61796"

        /** 京东快递小程序插件 appid / 登录网关数字 appid / 客户端版本（jd.md §2.2 标准封装参数） */
        const val PAPPID = "wxefe655223916819e"
        const val APPID_NUM = "599"
        const val CLIENT_VER = "2.0.2"

        /** v2/login（全新登录，cmd=34 sub_cmd=5，jd.md §2.2.2） */
        const val LOGIN_URL = "https://wxapplogin.m.jd.com/cgi-bin/v2/login"
        const val CMD_LOGIN = "34"
        const val SUB_CMD_LOGIN = "5"

        /**
         * 短信验证码登录（jd.md §2.2.2，2026-09-28 Node 全链路实测打通，jd/js/test_sms_*.js）：
         * prepare（v2/login → Jcap fp）→ WebView 滑块截 vt → sendlogincode(sub10) → dophonelogin(sub11)。
         * 两接口均无需 h5st；Cookie 必须带 guid=<v2/login 响应的 guid>，否则报 264。
         */
        const val SEND_SMS_URL = "https://wxapplogin.m.jd.com/cgi-bin/v2/sendlogincode"
        const val DO_PHONE_LOGIN_URL = "https://wxapplogin.m.jd.com/cgi-bin/v2/dophonelogin"
        const val JCAP_FP_URL = "https://wxapplogin.m.jd.com/jcap/cgi-bin/wxapp/fp"
        const val JCAP_CAPTCHA_PAGE = "https://wxapplogin.m.jd.com/jcap/jcap/html/wx-captcha-v1.html"
        const val SUB_CMD_SEND_SMS = "10"
        const val SUB_CMD_DO_PHONE_LOGIN = "11"

        /** v2/login 签名盐（jd.md §2.2.2，抓包固定值） */
        const val GSALT = "sb2cwlYyaCSN1KUv5RHG3tmqxfEb8NKN"

        /** 微信内置浏览器 UA（模拟小程序 webview 环境） */
        const val WX_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49"

        /** PC 微信小程序宿主 UA（v2/login 实测成功环境，jd/js/test_login.js） */
        const val MINI_PROGRAM_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/144.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) " +
                "MiniProgramEnv/Windows miniProgram/wx73247c7819d61796"

        /** 请求体：JSON 数组原样字符串 */
        const val IDENTITY_BODY = """[{"source":"ConvenientH5","jdPin":-1}]"""

        /** 必需请求头（实测值，缺失会被网关拒绝） */
        const val HEADER_CLIENT_INFO = """{"client":"m"}"""
        const val HEADER_APP_PARAMS = """{"appid":"2410","ticket_type":"m"}"""
        const val HEADER_LOP_DN = "convenient-h5.jd.com"

        /** 身份码有效期 1200 秒 */
        const val CODE_TTL_MS = 1200_000L

        /** 从响应 JSON 中提取 identityCode */
        val IDENTITY_CODE_REGEX = Regex("\"identityCode\"\\s*:\\s*\"([^\"]+)\"")

        /** YYB getCode 返回 JSON 里的 code 字段 */
        val WX_CODE_REGEX = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"")

        /** v2/login 响应 err_code / err_msg / pt_key / (pt_)pin 提取 */
        val ERR_CODE_REGEX = Regex("\"err_code\"\\s*:\\s*(-?\\d+)")
        val ERR_MSG_REGEX = Regex("\"err_msg\"\\s*:\\s*\"([^\"]*)\"")
        val PT_KEY_REGEX = Regex("\"pt_key\"\\s*:\\s*\"([^\"]+)\"")
        val PT_PIN_REGEX = Regex("\"(?:pt_pin|pin)\"\\s*:\\s*\"([^\"]+)\"")

        /** 短信登录：v2/login 预发会话字段 + Jcap fp 响应字段 */
        val NUM_CODE_REGEX = Regex("\"code\"\\s*:\\s*(-?\\d+)")
        val JCAP_SID_REGEX = Regex("\"phonelogin_jcap_sid\"\\s*:\\s*\"([^\"]+)\"")
        val JCAP_SID_FALLBACK_REGEX = Regex("\"login_jcap_sid\"\\s*:\\s*\"([^\"]+)\"")
        val SALT_REGEX = Regex("\"salt\"\\s*:\\s*\"([^\"]+)\"")
        val WX_TOKEN_REGEX = Regex("\"wx_token\"\\s*:\\s*\"([^\"]+)\"")
        val GUID_REGEX = Regex("\"guid\"\\s*:\\s*\"([^\"]+)\"")
        val ST_REGEX = Regex("\"st\"\\s*:\\s*\"([^\"]+)\"")
        val FP_REGEX = Regex("\"fp\"\\s*:\\s*\"([^\"]+)\"")

        /** 登录态失效的响应文案/错误码关键词（失效时网关返回未登录类错误） */
        val LOGIN_FAIL_KEYWORDS = listOf(
            "未登录", "请登录", "重新登录", "登录失效", "登录已过期",
            "notlogin", "needlogin", "nologin",
        )
    }

    // ============ 契约实现 ============

    /**
     * YYB 微信扫码登录（jd.md §2.2.2 code-only 试验版）：
     * YYB 不可用（未初始化/无微信账号库）→ null（UI 走「粘贴 pt_key/pin」兜底）；
     * 可用 → 取京东快递小程序 js_code → POST wxapplogin v2/login（cmd=34 sub_cmd=5，
     * 仅 code 不带 encryptedData/iv）→ 解析 Set-Cookie（兜底响应体）里的 pt_key / pt_pin。
     *
     * 待验证点：Node 实测曾需 wx.getUserInfo 的 encryptedData/iv，纯 code 会失败——
     * 按「目标 appid 正确即可兑换」假设先接通；失败时把网关 err_code / err_msg 带进
     * PlatformError message 便于真机联调定位（83=code 无效/已消费，264=参数异常，19=页面超时）。
     */
    override suspend fun remoteLogin(): Credential? {
        if (!yyb.available) return null
        // v1.3 远程 YYB：getCode 直接返回 code 字符串
        return loginWithCode(yyb.getCode(APPID_WX))
    }

    /**
     * code-only v2/login 兑换（契约外公开方法，便于联调复用）。
     * sign = md5(["appid","wxappid","client_ver","ts","cmd","sub_cmd","gsalt"].map(值).join(""))（jd.md §2.2.2）。
     * 成功 → Set-Cookie 取 pt_key/pt_pin（兜底响应体字段）组 Credential；
     * err_code != 0 或未取得 pt_key/pt_pin → ParseFailed（附 err_code/err_msg/响应摘录）。
     */
    suspend fun loginWithCode(code: String): Credential {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = md5(APPID_NUM + APPID_WX + CLIENT_VER + ts + CMD_LOGIN + SUB_CMD_LOGIN + GSALT)
        // 完整字段复刻 jd/js/test_login.js（PC 实测 err_code:0；缺 user_data/returnurl/gsalt 等字段报 264）
        val form = FormBody.Builder()
            .add("code", code)
            .add("user_data", "")
            .add("user_iv", "")
            .add("eid_token", "")
            .add("returnurl", "/pages/login/web-view/web-view")
            .add("cmd", CMD_LOGIN)
            .add("sub_cmd", SUB_CMD_LOGIN)
            .add("gsalt", GSALT)
            .add("wxappid", APPID_WX)
            .add("pappid", PAPPID)
            .add("appid", APPID_NUM)
            .add("client_ver", CLIENT_VER)
            .add("ts", ts)
            .add("sign", sign)
            .build()
        val request = Request.Builder()
            .url(LOGIN_URL)
            .header("User-Agent", MINI_PROGRAM_UA)
            .header("Cookie", "guid=; pt_pin=; pt_key=; pt_token=")
            .post(form)
            .build()
        val (setCookies, httpCode, body) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    Triple(resp.headers("Set-Cookie"), resp.code, resp.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
        val errCode = ERR_CODE_REGEX.find(body)?.groupValues?.get(1)
        val errMsg = ERR_MSG_REGEX.find(body)?.groupValues?.get(1)
        if (errCode != null && errCode != "0") {
            throw PlatformError.ParseFailed(platform, "v2/login 失败 err_code=$errCode err_msg=$errMsg")
        }
        // wx_bind:0 = 该微信未绑定京东 pin，服务端不下发 pt_key，只能走 Jcap 短信/密码或粘贴 pt_key
        if (body.contains("\"wx_bind\":0")) {
            throw PlatformError.ParseFailed(
                platform,
                "该微信账号未绑定京东账号（wx_bind=0）。请先在京东小程序/京东App内绑定，或改用粘贴 pt_key 登录",
            )
        }
        // pt_key/pt_pin 优先取 Set-Cookie（HTTPONLY），兜底响应体 JSON 字段
        val cookieMap = setCookies.mapNotNull(::cookiePair).toMap()
        val ptKey = cookieMap["pt_key"] ?: PT_KEY_REGEX.find(body)?.groupValues?.get(1)
        val ptPin = cookieMap["pt_pin"] ?: cookieMap["pin"] ?: PT_PIN_REGEX.find(body)?.groupValues?.get(1)
        if (ptKey.isNullOrBlank() || ptPin.isNullOrBlank()) {
            throw PlatformError.ParseFailed(
                platform,
                "v2/login 未取得 pt_key/pt_pin：http=$httpCode err_code=$errCode err_msg=$errMsg " +
                    "body=${body.take(300)}",
            )
        }
        return Credential(
            platform = platform,
            token = "pt_key=$ptKey; pin=$ptPin", // 与 parseCookie/detectLoginSuccess 的 token 约定一致
            extra = mapOf("pin" to ptPin),
            capturedAt = System.currentTimeMillis(),
        )
    }

    // ============ 短信验证码登录（jd.md §2.2.2，2026-09-28 Node 全链路实测打通） ============

    /** 短信登录中间会话（adapter 单例内暂存：prepare → WebView 滑块截 vt → sendSms → verify） */
    private class SmsSession(
        val phone: String,
        val sid: String,
        val salt: String,
        val wxToken: String,
        val guid: String,
        val st: String,
        val fp: String,
    )

    @Volatile private var smsSession: SmsSession? = null

    /**
     * 步骤1：准备短信登录。v2/login（cmd34 sub5）**空 code 即可**（实测 2026-09-28，err_code=0
     * 且完整下发 phonelogin_jcap_sid/salt/wx_token/guid，无需微信 code/YYB 服务）
     * → Jcap fp 指纹上报（XXTEA 变体 ENC，见 JcapCodec）→ 返回滑块页 URL 供 WebView 加载。
     * 滑块完成后调 [sendSmsWithVt]。
     */
    suspend fun prepareSmsLogin(phone: String): String {
        // v2/login：空 code 直接拿预发会话（jd/js/test_login_nocode.js 实测）
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = md5(APPID_NUM + APPID_WX + CLIENT_VER + ts + CMD_LOGIN + SUB_CMD_LOGIN + GSALT)
        val (_, loginBody) = postForm(
            LOGIN_URL,
            listOf(
                "code" to "",
                "user_data" to "",
                "user_iv" to "",
                "eid_token" to "",
                "returnurl" to "/pages/login/web-view/web-view",
                "cmd" to CMD_LOGIN,
                "sub_cmd" to SUB_CMD_LOGIN,
                "gsalt" to GSALT,
                "wxappid" to APPID_WX,
                "pappid" to PAPPID,
                "appid" to APPID_NUM,
                "client_ver" to CLIENT_VER,
                "ts" to ts,
                "sign" to sign,
            ),
            guid = "",
        )
        val errCode = ERR_CODE_REGEX.find(loginBody)?.groupValues?.get(1)
        val errMsg = ERR_MSG_REGEX.find(loginBody)?.groupValues?.get(1)
        if (errCode != "0") {
            throw PlatformError.ParseFailed(platform, "v2/login 失败 err_code=$errCode err_msg=$errMsg")
        }
        val sid = JCAP_SID_REGEX.find(loginBody)?.groupValues?.get(1)
            ?: JCAP_SID_FALLBACK_REGEX.find(loginBody)?.groupValues?.get(1).orEmpty()
        val salt = SALT_REGEX.find(loginBody)?.groupValues?.get(1).orEmpty()
        val wxToken = WX_TOKEN_REGEX.find(loginBody)?.groupValues?.get(1).orEmpty()
        val guid = GUID_REGEX.find(loginBody)?.groupValues?.get(1).orEmpty()
        if (sid.isEmpty() || salt.isEmpty()) {
            throw PlatformError.ParseFailed(platform, "v2/login 响应缺少 jcap_sid/salt：${loginBody.take(200)}")
        }

        // Jcap fp 指纹上报（payload 结构见 jd.md §2.2.6①，与 test_sms_prepare.js 一致；
        // 纯 JVM 模块无 org.json，字段值均为安全字面量，phone 已校验为 11 位数字，直接拼接）
        val tsMs = System.currentTimeMillis()
        val devInfo = "{\"account\":\"$phone\",\"ccode\":\"86\",\"pr\":3," +
            "\"scr\":\"393x873,393x836\",\"pt\":\"android\",\"lan\":\"zh_CN\"," +
            "\"cpu\":\"\",\"dnm\":\"Xiaomi\",\"dbn\":\"Xiaomi\",\"dmd\":\"23127PN0CC\"," +
            "\"anm\":\"$APPID_WX\",\"avs\":\"8.0.60\",\"os\":\"Android\",\"osv\":\"14\"," +
            "\"capfp\":\"\",\"mem\":\"12GB\",\"sdv\":\"\"}"
        val fpPayload = JcapCodec.random((tsMs % 19).toInt()) +
            JcapCodec.pad(sid.length.toString(), 4) + sid + devInfo + tsMs
        val (_, fpBody) = postForm(
            JCAP_FP_URL,
            listOf(
                "client" to "wxapp",
                "si" to sid,
                "ct" to JcapCodec.encrypt(fpPayload),
                "version" to "2",
                "lang" to "1",
            ),
            guid = guid,
        )
        val fpCode = NUM_CODE_REGEX.find(fpBody)?.groupValues?.get(1)
        val st = ST_REGEX.find(fpBody)?.groupValues?.get(1).orEmpty()
        val fp = FP_REGEX.find(fpBody)?.groupValues?.get(1).orEmpty()
        if (fpCode != "0" || st.isEmpty() || fp.isEmpty()) {
            throw PlatformError.ParseFailed(platform, "Jcap fp 失败 code=$fpCode body=${fpBody.take(200)}")
        }
        smsSession = SmsSession(phone, sid, salt, wxToken, guid, st, fp)
        // 滑块页 URL（任意浏览器/WebView 可加载，无微信环境校验；成功后 postMessage 出 vt）
        val q = listOf(
            "baseUrl" to "https://wxapplogin.m.jd.com/jcap",
            "si" to sid, "lang" to "1", "client" to "wxapp",
            "st" to st, "fp" to fp, "appid" to APPID_WX,
        ).joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        return JCAP_CAPTCHA_PAGE + "?" + q
    }

    /**
     * 步骤2：滑块验证通过后（UI 从 WebView 截获 vt），调 sendlogincode（cmd34 sub10）发短信。
     * 无需 h5st；Cookie 必须带 guid（实测空 guid 报 264）。
     */
    suspend fun sendSmsWithVt(vt: String) {
        val s = smsSession ?: throw PlatformError.ParseFailed(platform, "登录会话已失效，请重新获取验证码")
        val ts = (System.currentTimeMillis() / 1000).toString()
        // sign keyArr: ["country_code","phone","cmd","sub_cmd","salt"]（cmd=34, sub_cmd=10）
        val sign = md5(
            APPID_NUM + APPID_WX + CLIENT_VER + ts + "86" + s.phone +
                CMD_LOGIN + SUB_CMD_SEND_SMS + s.salt,
        )
        val (_, body) = postForm(
            SEND_SMS_URL,
            listOf(
                "jcap_sid" to s.sid,
                "verify_token" to vt,
                "phone" to s.phone,
                "country_code" to "86",
                "wx_token" to s.wxToken,
                "wxappid" to APPID_WX,
                "pappid" to PAPPID,
                "appid" to APPID_NUM,
                "client_ver" to CLIENT_VER,
                "ts" to ts,
                "sign" to sign,
            ),
            guid = s.guid,
        )
        val errCode = ERR_CODE_REGEX.find(body)?.groupValues?.get(1)
        if (errCode != "0") {
            val errMsg = ERR_MSG_REGEX.find(body)?.groupValues?.get(1)
            throw PlatformError.ParseFailed(platform, "发送验证码失败 err_code=$errCode err_msg=$errMsg")
        }
    }

    /**
     * 步骤3：提交短信验证码，dophonelogin（cmd34 sub11）拿 pt_key/pt_pin。
     * 实测无需 h5st（siginAppId 84a79 服务端弱校验，见 jd.md §2.2.2）。
     */
    suspend fun verifySmsCode(smsCode: String): Credential {
        val s = smsSession ?: throw PlatformError.ParseFailed(platform, "登录会话已失效，请重新获取验证码")
        val ts = (System.currentTimeMillis() / 1000).toString()
        // sign keyArr: ["country_code","phone","cmd","sub_cmd","salt"]（cmd=34, sub_cmd=11）
        val sign = md5(
            APPID_NUM + APPID_WX + CLIENT_VER + ts + "86" + s.phone +
                CMD_LOGIN + SUB_CMD_DO_PHONE_LOGIN + s.salt,
        )
        val (_, body) = postForm(
            DO_PHONE_LOGIN_URL,
            listOf(
                "auth_code" to smsCode.trim(),
                "phone" to s.phone,
                "country_code" to "86",
                "agreement" to "",
                "wx_token" to s.wxToken,
                "returnurl" to "/pages/login/web-view/web-view",
                "wxappid" to APPID_WX,
                "pappid" to PAPPID,
                "appid" to APPID_NUM,
                "client_ver" to CLIENT_VER,
                "ts" to ts,
                "sign" to sign,
            ),
            guid = s.guid,
        )
        val errCode = ERR_CODE_REGEX.find(body)?.groupValues?.get(1)
        val ptKey = PT_KEY_REGEX.find(body)?.groupValues?.get(1)
        val ptPin = PT_PIN_REGEX.find(body)?.groupValues?.get(1)
        if (errCode == "0" && !ptKey.isNullOrBlank() && !ptPin.isNullOrBlank()) {
            return Credential(
                platform = platform,
                token = "pt_key=$ptKey; pin=$ptPin",
                extra = mapOf("pin" to ptPin),
                capturedAt = System.currentTimeMillis(),
            )
        }
        val errMsg = ERR_MSG_REGEX.find(body)?.groupValues?.get(1)
        throw PlatformError.ParseFailed(platform, "验证码登录失败 err_code=$errCode err_msg=$errMsg")
    }

    /** 表单 POST 统一封装（wxapplogin 网关）；返回 (HTTP 状态码, 响应体) */
    private suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        guid: String,
    ): Pair<Int, String> {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MINI_PROGRAM_UA)
            // guid 必须取 v2/login 响应体下发的值；空 guid 报 264（实测踩坑，jd.md §2.2.2）
            .header("Cookie", "guid=$guid; pt_pin=; pt_key=; pt_token=")
            .post(form)
            .build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
    }

    /** JD 不使用短信登录契约方法（短信链路走 prepareSmsLogin/sendSmsWithVt/verifySmsCode 三步） */
    override suspend fun sendSmsCode(phone: String) =
        throw UnsupportedOperationException("京东短信登录请走 prepareSmsLogin 三步流程")

    /** JD 不使用短信登录契约方法 */
    override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential =
        throw UnsupportedOperationException("京东短信登录请走 prepareSmsLogin 三步流程")

    /** WEBVIEW 兜底暂不启用 */
    override fun loginUrl(): String = ""

    /** WebView 拦截：宿主 H5 链路 Cookie 命中 pt_key 即视为登录成功 */
    override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? {
        val ptKey = cookies["pt_key"] ?: return null
        if (ptKey.isBlank()) return null
        return Credential(
            platform = platform,
            token = "pt_key=$ptKey; pin=${cookies["pin"] ?: ""}",
            extra = mapOf("pin" to (cookies["pin"] ?: "")),
            capturedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 登录态探测：generateIdentityCode 本身即是最轻量的探测接口——
     * pt_key 有效则正常返回 identityCode，失效则返回未登录类错误。
     */
    override suspend fun checkAlive(cred: Credential): Boolean {
        val (httpCode, body) = callIdentityCode(cred)
        if (IDENTITY_CODE_REGEX.find(body) != null) return true
        if (isNotLoggedIn(httpCode, body)) return false
        // 响应既无身份码也无法判定为未登录，按解析失败归一，交由上层区分处理
        throw PlatformError.ParseFailed(platform, "checkAlive 响应异常：http=$httpCode body=${body.take(200)}")
    }

    /** 真实调用 generateIdentityCode 获取 JDM 身份码 */
    override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
        val (httpCode, body) = callIdentityCode(cred)
        val code = IDENTITY_CODE_REGEX.find(body)?.groupValues?.get(1)
        if (!code.isNullOrBlank()) {
            return IdentityCode(
                platform = platform,
                type = CodeType.BARCODE, // JDM 前缀身份码按条码渲染
                content = code,
                expireAt = System.currentTimeMillis() + CODE_TTL_MS,
            )
        }
        if (isNotLoggedIn(httpCode, body)) throw PlatformError.NeedRelogin(platform)
        throw PlatformError.ParseFailed(
            platform,
            "generateIdentityCode 响应缺少 identityCode：http=$httpCode body=${body.take(200)}",
        )
    }

    /** 待逆向：京东快递列表接口（LOP 通道）需抓包逆向后接入 */
    override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> = emptyList()

    /** 待设计：取件码查询依赖快递列表接口逆向结果 */
    override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? = null

    // ============ 手动登录兜底 ============

    /**
     * 手动登录：YYB 未接入前，UI 提供「粘贴 pt_key/pin」入口。
     * 不做网络校验，直接构造凭据；有效性由首次 checkAlive/fetchIdentityCode 判定。
     */
    suspend fun loginWithCookie(ptKey: String, pin: String): Credential {
        val key = ptKey.trim()
        val userPin = pin.trim()
        require(key.isNotEmpty() && userPin.isNotEmpty()) { "pt_key / pin 不能为空" }
        return Credential(
            platform = platform,
            token = "pt_key=$key; pin=$userPin",
            extra = mapOf("pin" to userPin),
            capturedAt = System.currentTimeMillis(),
        )
    }

    // ============ 内部工具 ============

    /**
     * 从 Credential 提取 pt_key/pin。
     * token 约定为 "pt_key=xxx; pin=yyy" 格式，extra["pin"] 为备份来源；缺失即判定需重登。
     */
    private fun parseCookie(cred: Credential): Pair<String, String> {
        var ptKey = ""
        var pin = ""
        for (raw in cred.token.split(';')) {
            val part = raw.trim()
            when {
                part.startsWith("pt_key=") -> ptKey = part.removePrefix("pt_key=").trim()
                part.startsWith("pin=") -> pin = part.removePrefix("pin=").trim()
            }
        }
        if (pin.isEmpty()) pin = cred.extra["pin"]?.trim().orEmpty()
        if (ptKey.isEmpty() || pin.isEmpty()) throw PlatformError.NeedRelogin(platform)
        return ptKey to pin
    }

    /**
     * 统一发起 generateIdentityCode 调用，返回 (HTTP 状态码, 响应体)。
     * 网络 IO 异常归一为 PlatformError.NetworkError。
     */
    private suspend fun callIdentityCode(cred: Credential): Pair<Int, String> {
        val (ptKey, pin) = parseCookie(cred)
        val request = Request.Builder()
            .url(IDENTITY_URL)
            .header("Cookie", "pt_key=$ptKey; pin=$pin;") // 实测仅需这两个 Cookie，注意尾部分号
            .header("ClientInfo", HEADER_CLIENT_INFO)
            .header("AppParams", HEADER_APP_PARAMS)
            .header("LOP-DN", HEADER_LOP_DN)
            .post(IDENTITY_BODY.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
    }

    /** 判定响应是否属于「登录态失效」：401/403 或响应体命中未登录类关键词 */
    private fun isNotLoggedIn(httpCode: Int, body: String): Boolean {
        if (httpCode == 401 || httpCode == 403) return true
        val lower = body.lowercase()
        return LOGIN_FAIL_KEYWORDS.any { lower.contains(it.lowercase()) }
    }

    /** MD5 小写十六进制（v2/login sign 用） */
    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** "name=value; Path=/; ..." → (name, value)：取首个分号段，value 内含 '=' 时按首个 '=' 切分 */
    private fun cookiePair(raw: String): Pair<String, String>? {
        val first = raw.substringBefore(';').trim()
        val idx = first.indexOf('=')
        if (idx <= 0) return null
        return first.substring(0, idx).trim() to first.substring(idx + 1).trim()
    }
}
