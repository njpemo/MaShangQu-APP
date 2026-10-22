package com.aggapp.platform.sf

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 顺丰 MIMP H5 独立登录客户端（2026-09-29 最终方案，sf.md §2.5；Node 参考 sf/sf-mimp-e2e.js，
 * 请求形状取自真实抓包对拍，浏览器全链实测出码 150610521ACS2853414300）。
 *
 * 链路（完全不依赖 ucmp 会话/YYB）：
 *   ① sendValidCode：POST /mcs-mimp/sendValidCode body {sendMobile, captcha_args}，
 *      captcha_args = 极验 v4 四件套（MIMP captchaId d6ee898c...，pass_token 一次性）；
 *   ② loginByMobile：POST /mcs-mimp/loginByMobile body {channel,mobile,validCode,from}
 *      → Set-Cookie 落 MIMP 会话（sessionId/JSESSIONID/_login_user_id_/_login_mobile_）；
 *   ③ createIdentityCode：固定密钥签名 + 全量 Cookie → 返回「时间段+ACS+静态段」，
 *      静态段与用户永久绑定，拿到后 App 永久本地出码。
 *
 * 请求头签名（固定密钥硬编码于 MIMP 前端）：signature = md5("token=<K>&timestamp=<ms>&sysCode=MCS-MIMP-CORE")
 */
internal class SfMimpClient(private val http: OkHttpClient) {

    private val json = Json.Default

    // ============ ① 发码（sf.md §2.5 ①） ============

    /**
     * POST /mcs-mimp/sendValidCode body {"sendMobile":..,"captcha_args":{极验四件套}}。
     * captcha_args 必带（实测缺失报 {"errorCode":"700001","errorMessage":"未进行滑块验证"}）；
     * pass_token 一次性：一次滑块 = 一次发码尝试，失败需重新过滑块。
     */
    suspend fun sendValidCode(phone: String, gt: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("sendMobile", phone)
                put("captcha_args", buildJsonObject {
                    put("lot_number", gt["lot_number"].orEmpty())
                    put("captcha_output", gt["captcha_output"].orEmpty())
                    put("pass_token", gt["pass_token"].orEmpty())
                    put("gen_time", gt["gen_time"].orEmpty())
                })
            }.toString()
            val env = exec(mimpRequest("/mcs-mimp/sendValidCode", body))
            mimpBizCheck(env, "发送验证码失败")
            env
        }

    // ============ ② 登录（sf.md §2.5 ②） ============

    /**
     * POST /mcs-mimp/loginByMobile body {"channel":"WEIXIN","mobile":..,"validCode":..,"from":"weixin"}。
     * 成功后从 Set-Cookie 收集 MIMP 会话并拼成 Cookie 串（Credential.token 形态，淘宝同款）。
     * 未见会话 Cookie（验证码错误/过期）→ ParseFailed 透传 errorMessage。
     */
    suspend fun loginByMobile(phone: String, smsCode: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("channel", "WEIXIN")
            put("mobile", phone)
            put("validCode", smsCode)
            put("from", "weixin")
        }.toString()
        val req = mimpRequest("/mcs-mimp/loginByMobile", body)
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw PlatformError.NetworkError(Platform.SF, e)
        }
        resp.use { r ->
            if (!r.isSuccessful) throw PlatformError.NetworkError(Platform.SF, IOException("HTTP ${r.code}"))
            val text = r.body?.string().orEmpty()
            val env = parseJson(text)
            mimpBizCheck(env, "登录失败")
            // 逐条收 Set-Cookie（首段 kv），与浏览器 Cookie 形态对齐
            val cookies = linkedMapOf<String, String>()
            for (sc in r.headers("Set-Cookie")) {
                val kv = sc.substringBefore(';')
                val eq = kv.indexOf('=')
                if (eq > 0) cookies[kv.substring(0, eq).trim()] = kv.substring(eq + 1).trim()
            }
            val hasSession = cookies.keys.any {
                it == "sessionId" || it == "JSESSIONID" || it == "_login_user_id_"
            }
            if (!hasSession) throw PlatformError.ParseFailed(Platform.SF, "登录失败：响应未携带 MIMP 会话 Cookie")
            cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    // ============ ③ 出码（sf.md §4.4） ============

    /**
     * POST MIMP createIdentityCode（固定密钥签名 + 全量 Cookie），
     * 成功返回 obj 即身份码字符串；success=false（MIMP 会话失效为唯一鉴权失败面）→ NeedRelogin。
     */
    suspend fun createIdentityCode(cookieHeader: String): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val signature = SfSigner.md5Hex("token=$MIMP_SECRET&timestamp=$ts&sysCode=$MIMP_SYS_CODE")
        val req = Request.Builder()
            .url(MIMP_CREATE_IDENTITY_CODE)
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("timestamp", ts.toString())
            .header("signature", signature)
            .header("sysCode", MIMP_SYS_CODE)
            .header("channel", MIMP_CHANNEL)
            .header("platform", MIMP_PLATFORM)
            .header("Origin", MIMP_ORIGIN)
            .header("Referer", MIMP_REFERER)
            .header("Cookie", cookieHeader)
            .build()
        val env = exec(req)
        val code = (env["obj"] as? JsonPrimitive)?.contentOrNull
        if (env.optBool("success") == true && !code.isNullOrBlank()) code
        else throw PlatformError.NeedRelogin(Platform.SF)
    }

    // ============ ④ 待取快递列表（sf.md §5.3，2026-09-30 实测） ============

    /**
     * POST MIMP queryStudentWaittingWaybill（固定密钥签名 + 全量 Cookie），body {}。
     * 成功返回 obj 数组（空态无 obj 字段 → 空列表）；列表项含 pickUpCode（取件码直出）、
     * waitingPickType（"1"=丰巢柜扫码取件）、storeName 等，字段见 sf.md §5.3。
     * success=false（MIMP 会话失效为唯一鉴权失败面）→ NeedRelogin。
     */
    suspend fun queryStudentWaittingWaybill(cookieHeader: String): JsonArray = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val signature = SfSigner.md5Hex("token=$MIMP_SECRET&timestamp=$ts&sysCode=$MIMP_SYS_CODE")
        val req = Request.Builder()
            .url(MIMP_QUERY_WAITTING_WAYBILL)
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("timestamp", ts.toString())
            .header("signature", signature)
            .header("sysCode", MIMP_SYS_CODE)
            .header("channel", MIMP_CHANNEL)
            .header("platform", MIMP_PLATFORM)
            .header("Origin", MIMP_ORIGIN)
            .header("Referer", MIMP_REFERER)
            .header("Cookie", cookieHeader)
            .build()
        val env = exec(req)
        if (env.optBool("success") == true) env["obj"] as? JsonArray ?: JsonArray(emptyList())
        else throw PlatformError.NeedRelogin(Platform.SF)
    }

    // ============ 内部工具 ============

    /** MIMP 通用请求（签名头 + channel/platform，形状与真实抓包对拍）：body 非空 POST */
    private fun mimpRequest(apiPath: String, body: String): Request {
        val ts = System.currentTimeMillis()
        val signature = SfSigner.md5Hex("token=$MIMP_SECRET&timestamp=$ts&sysCode=$MIMP_SYS_CODE")
        return Request.Builder()
            .url("$MIMP_BASE$apiPath")
            .post(body.toRequestBody(JSON_MEDIA))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("timestamp", ts.toString())
            .header("signature", signature)
            .header("sysCode", MIMP_SYS_CODE)
            .header("channel", MIMP_CHANNEL)
            .header("platform", MIMP_PLATFORM)
            .header("Origin", MIMP_ORIGIN)
            .header("Referer", "$MIMP_BASE/login")
            .build()
    }

    /**
     * MIMP 业务失败归一：errorCode 字段非空（如 700001 未滑块验证）或 success=false → ParseFailed。
     * 错误信封字段为 errorCode/errorMessage（区别于 ucmp 的 code/msg）。
     */
    private fun mimpBizCheck(env: JsonObject, what: String) {
        val errCode = env.optStr("errorCode")
        if (!errCode.isNullOrBlank() && errCode != "null") {
            throw PlatformError.ParseFailed(
                Platform.SF,
                "$what：${env.optStr("errorMessage") ?: env.optStr("message") ?: errCode}",
            )
        }
        if (env.optBool("success") == false) {
            throw PlatformError.ParseFailed(
                Platform.SF,
                "$what：${env.optStr("errorMessage") ?: env.optStr("message") ?: "未知错误"}",
            )
        }
    }

    /** 统一执行：IO 异常 → NetworkError；非 2xx → NetworkError；响应须为 JSON 对象 */
    private fun exec(request: Request): JsonObject {
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PlatformError.NetworkError(Platform.SF, e)
        }
        resp.use { r ->
            if (!r.isSuccessful) throw PlatformError.NetworkError(Platform.SF, IOException("HTTP ${r.code}"))
            return parseJson(r.body?.string().orEmpty())
        }
    }

    private fun parseJson(text: String): JsonObject {
        if (text.isBlank()) throw PlatformError.ParseFailed(Platform.SF, "空响应体")
        return json.parseToJsonElement(text) as? JsonObject
            ?: throw PlatformError.ParseFailed(Platform.SF, "响应非 JSON 对象：${text.take(120)}")
    }

    companion object {
        private const val MIMP_BASE = "https://mcs-mimp-web.sf-express.com"
        private const val MIMP_CREATE_IDENTITY_CODE =
            "$MIMP_BASE/mcs-mimp/commonPost/~memberManage~studentService~createIdentityCode"
        private const val MIMP_QUERY_WAITTING_WAYBILL =
            "$MIMP_BASE/mcs-mimp/commonPost/~memberManage~studentService~queryStudentWaittingWaybill"

        // —— MIMP 固定密钥签名（sf.md §4.4，前端 app.js 硬编码，Node 三样本对拍吻合） ——
        private const val MIMP_SECRET = "wwesldfs29aniversaryvdld29"
        private const val MIMP_SYS_CODE = "MCS-MIMP-CORE"

        /** channel/platform 取值与真实抓包一致（Node sf-mimp-e2e.js 对拍形态） */
        private const val MIMP_CHANNEL = "weixin"
        private const val MIMP_PLATFORM = "OTHER"

        private const val MIMP_ORIGIN = "https://mcs-mimp-web.sf-express.com"
        private const val MIMP_REFERER =
            "https://mcs-mimp-web.sf-express.com/origin/a/mimp-member-right/campusZoneId"

        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

        private val JSON_MEDIA = "application/json;charset=UTF-8".toMediaType()
    }
}

// ============ kotlinx JSON 安全取值（缺字段/类型不符一律 null，不抛） ============

private fun JsonObject.optStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
