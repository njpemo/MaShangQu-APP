package com.aggapp.platform.pdd

import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.model.PickupCode
import com.aggapp.platform.api.AppLog
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拼多多（多多驿站）适配器。
 *
 * 登录链路（2026-09-29 全链 Node 直调实测，彻底去 YYB）：PHONE_CODE 协议短信登录——
 * 发码 POST /proxy/api/api/sigerus/mobile/code/request → 登录 POST /proxy/api/api/sigerus/login_mobile
 * （body 带 anti_content，App 隐藏 WebView 生成）→ 响应 access_token（有效期约 1 年，
 * 与微信 OAuth 链路同一套 token，station/package 实测 200）。无滑块、Cookie 非必需。
 * YYB 微信 code 兑换链路（pdd.md §1/§4）保留为兜底（remoteLogin/exchangeLoginCookie 不删）。
 *
 * 业务链路不变：accesstoken 头 + anti-content 头（App 隐藏 WebView 生成）+
 * POST proxy/api/api/express_trackbox/station/package。
 *
 * 身份码链路（暂不接）：POST proxy/api/.../generate_identification_code 除 accesstoken 外还需
 * anti-content 头（~330 字符，jsdom 生成，pdd.md §3）——fetchIdentityCode 保持 Mock。
 */
@Singleton
class PddAdapter @Inject constructor(
    private val antiContent: PddAntiContent, // 隐藏 WebView 生成 anti-content（:app 提供）
) : PlatformAdapter {

    override val platform = Platform.PDD

    /** 主链路已改为 PddQrClient 扫码登录（不踢 App 下线）；WEBVIEW 保留供二次验证官方页兜底检测 */
    override val loginMode = LoginMode.WEBVIEW

    /** 登录兑换需拦截 302 自取 Set-Cookie，故关闭自动重定向 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(com.aggapp.platform.api.NetDebug.interceptor)
            .build()
    }

    /**
     * code 兑换登录态（契约外公开方法，便于联调复用）：
     * GET /weixin/login?redirect_url=%2Fweixin%2Fpackage&code=<code>&state=false，
     * 请求头微信 UA + Referer open.weixin.qq.com（pdd.md §4.1/§4.2.4）。
     * 成功标志 = 302/200 响应的 Set-Cookie 含 accesstoken；
     * 失败（110000 = code 已被消费/上游异常，无 Set-Cookie）→ ParseFailed 带响应摘录。
     */
    suspend fun exchangeLoginCookie(code: String): Credential {
        val url = LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("redirect_url", "/weixin/package") // 必须原样携带（§4.2.4）
            .addQueryParameter("code", code)
            .addQueryParameter("state", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WX_UA)
            .header("Referer", "https://open.weixin.qq.com/")
            .get()
            .build()
        val snapshot = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    RespSnapshot(
                        setCookies = resp.headers("Set-Cookie"),
                        httpCode = resp.code,
                        location = resp.header("Location"),
                        body = resp.body?.string().orEmpty(),
                    )
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
        val cookies = snapshot.setCookies.mapNotNull(::parseCookiePair).toMap()
        val accessToken = cookies["accesstoken"]
        if (accessToken.isNullOrBlank()) {
            throw PlatformError.ParseFailed(
                platform,
                "weixin/login 兑换失败：http=${snapshot.httpCode} location=${snapshot.location} " +
                    "cookieKeys=${cookies.keys} body=${snapshot.body.take(200)}",
            )
        }
        val extra = buildMap {
            cookies["pdd_uin"]?.let { put("pdd_uin", it) }
            cookies["pdd_user_id"]?.let { put("pdd_user_id", it) }
        }
        return Credential(platform, accessToken, extra, System.currentTimeMillis())
    }

    /**
     * 短信发码（2026-09-29 Node 直调实测 200 {"mobile_des":"132****7330"}）：
     * POST /proxy/api/api/sigerus/mobile/code/request?pdduid=0，
     * body {mobile, anti_content, add_cookie, support_enhance_type:6, login_app_id:155,
     * country_id:1, tel_code:86}；anti_content 为 App 隐藏 WebView 生成的 330 字符串
     * （服务端只验形态不验新鲜度，Node 复用旧值实测通过）。无滑块。
     * 业务错误走 HTTP 403 + error_code（如 40012 发码过频），Cookie 非必需（裸调实测）。
     */
    override suspend fun sendSmsCode(phone: String) {
        postLogin("/api/sigerus/mobile/code/request", phone, null)
    }

    /**
     * 短信登录（Node 直调实测 200）：POST /proxy/api/api/sigerus/login_mobile?pdduid=0，
     * 响应 JSON {uid, uin, access_token, acid, mobile_id, mobile_des}，
     * token 同时经 Set-Cookie 下发 PDDAccessToken（同一套，业务接口 accesstoken 头直用）。
     */
    override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential {
        val env = postLogin("/api/sigerus/login_mobile", phone, smsCode)
        val token = env.str("access_token")
            ?: throw PlatformError.ParseFailed(platform, "登录响应缺 access_token：$env")
        val extra = buildMap {
            env.str("uid")?.let { put("pdd_user_id", it) }
            env.str("uin")?.let { put("pdd_uin", it) }
        }
        return Credential(platform, token, extra, System.currentTimeMillis())
    }

    /**
     * 登录类请求（区别于 postApi：错误以 HTTP 403 + {error_code,error_msg} 返回，
     * 不能按非 2xx 直接抛 NetworkError）。anti_content 双份上送：请求头 anti-content +
     * body.anti_content（Node 实测均可过，双份与浏览器行为一致）。
     */
    private suspend fun postLogin(api: String, phone: String, code: String?): JsonObject {
        val ac = try {
            antiContent.generate()
        } catch (e: Exception) {
            throw PlatformError.ParseFailed(platform, "anti-content 生成失败：${e.message}")
        }
        val body = JsonObject(
            buildMap {
                put("mobile", JsonPrimitive(phone))
                code?.let { put("code", JsonPrimitive(it)) }
                put("anti_content", JsonPrimitive(ac))
                put("add_cookie", JsonPrimitive(true))
                put("support_enhance_type", JsonPrimitive(6))
                put("login_app_id", JsonPrimitive(155))
                put("country_id", JsonPrimitive(1))
                put("tel_code", JsonPrimitive(86))
            },
        )
        val request = Request.Builder()
            .url("$PROXY_BASE$api?pdduid=0")
            .header("anti-content", ac)
            .header("Referer", "https://mobile.yangkeduo.com/login.html")
            .post(body.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .build()
        val text = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
        val env = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw PlatformError.ParseFailed(platform, "响应非 JSON：${text.take(120)}")
        }
        val errCode = env.str("error_code")
        if (errCode != null && errCode != "0") {
            throw PlatformError.ParseFailed(platform, "${env.str("error_msg")} ($errCode)")
        }
        return env
    }

    /** WEBVIEW 短信登录页（手机号+验证码，桌面/移动 UA 均可） */
    override fun loginUrl(): String = "https://mobile.yangkeduo.com/login.html"

    /**
     * WebView 登录成功检测：Cookie 出现 PDDAccessToken 即成功（HttpOnly，
     * android.webkit.CookieManager.getCookie 可读）。token 形态与微信链路一致。
     */
    override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? {
        val token = cookies["PDDAccessToken"]?.takeIf { it.isNotBlank() } ?: return null
        val extra = buildMap {
            cookies["pdd_user_id"]?.let { put("pdd_user_id", it) }
            cookies["pdd_user_uin"]?.let { put("pdd_uin", it) }
        }
        return Credential(platform, token, extra, System.currentTimeMillis())
    }

    override suspend fun checkAlive(cred: Credential): Boolean {
        // 业务接口探测（pdd.md §0）：error_code 40001 = session 超时
        return try {
            postApi(cred, API_STATION_PACKAGE, "{}").str("error_code") != "40001"
        } catch (_: Exception) {
            false
        }
    }

    /** 身份码（pdd.md §2 已实测）：entry_source:"WX" → wx 前缀码（expire ~587s），每次调用生成新码 */
    override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
        val env = postApi(cred, API_IDENTIFICATION_CODE, """{"entry_source":"WX"}""")
        val result = env.obj("result")
        val code = result?.str("identification_code")
            ?: throw PlatformError.ParseFailed(platform, "身份码响应缺 identification_code：$env")
        return IdentityCode(
            platform = platform,
            type = CodeType.BARCODE,
            content = code,
            expireAt = result.optLong("expire_mills")?.takeIf { it > 0 },
        )
    }

    /**
     * 快递列表 = 双源合并（2026-09-29 用户实测：两源各有对方缺的取件码，必须都拉）：
     * ① 快递助手 express_trackbox/list（手机号聚合全驿站，带 goods_name/status_type/pick_code）
     *    POST {offset,count:20,channel:"express",entry_source:"assistant_search",status_type:0,query_type:0}
     * ② 多多驿站 station/package（门店在架实况，company_name/address/operation_time/pick_code）
     * 按 trackingNumber 去重，重复单字段互补：码谁有用谁的、驿站名/地址/入库时间用驿站的、
     * summary 用快递助手商品名、在架(WAIT_PICKUP)状态优先于运输中。
     */
    override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> {
        if (page > 1) return emptyList()
        val out = mutableListOf<ExpressItem>()
        // ① 快递助手（全驿站聚合）
        runCatching {
            var offset = 0
            var done = false
            repeat(5) {
                if (done) return@repeat
                val env = postApi(
                    cred,
                    API_ASSISTANT_LIST,
                    """{"offset":$offset,"count":20,"channel":"express","entry_source":"assistant_search","status_type":0,"query_type":0}""",
                )
                val list = env.obj("result")?.arr("track_info_list").orEmpty()
                for (el in list) {
                    val t = el as? JsonObject ?: continue
                    val trackingNo = t.str("tracking_number") ?: continue
                    if (out.any { it.trackingNo == trackingNo }) continue
                    val status = when (t.optLong("status_type")) {
                        400L -> ExpressStatus.WAIT_PICKUP
                        500L -> ExpressStatus.PICKED
                        else -> ExpressStatus.UNKNOWN // 200 运输中/其他
                    }
                    out += ExpressItem(
                        id = trackingNo,
                        platform = Platform.PDD,
                        station = t.str("track_desc").extractStation() ?: t.str("status_type_desc") ?: "拼多多快递",
                        stationAddress = null,
                        trackingNo = trackingNo,
                        pickupCode = t.str("pick_code")?.removePrefix(PICK_CODE_PREFIX)?.trim()?.takeIf { it.isNotBlank() },
                        summary = t.str("goods_name") ?: t.str("shipping_name"),
                        status = status,
                        arrivedAt = null, // 列表无入库时间
                        fetchDeadline = null,
                        raw = t.toString(),
                    )
                }
                if (list.size < 20) {
                    done = true
                } else {
                    offset += 20
                    delay(PAGE_DELAY_MS) // 54001 查询频率太快：翻页间隔 2s
                }
            }
        }.onFailure { AppLog.w(TAG, "快递助手列表失败（继续驿站源）", it) }
        delay(SOURCE_DELAY_MS) // 两源之间也留间隔，降低单平台请求频率
        // ② 多多驿站（门店在架实况）：重复单字段互补，新单追加
        runCatching {
            val env = postApi(cred, API_STATION_PACKAGE, """{"entry_source":"WX"}""")
            val result = env.obj("result") ?: return@runCatching
            for (el in result.arr("combine_list")) {
                val station = el as? JsonObject ?: continue
                val stationName = station.str("company_name") ?: station.str("address") ?: "多多驿站"
                val stationAddress = station.str("address")
                for (trackEl in station.arr("combine_track_list")) {
                    val t = trackEl as? JsonObject ?: continue
                    val trackingNo = t.str("tracking_number") ?: continue
                    val stCode = t.str("pick_code")?.removePrefix(PICK_CODE_PREFIX)?.trim()?.takeIf { it.isNotBlank() }
                    val exist = out.firstOrNull { it.trackingNo == trackingNo }
                    if (exist != null) {
                        val idx = out.indexOf(exist)
                        out[idx] = exist.copy(
                            pickupCode = exist.pickupCode?.takeIf { it.isNotBlank() } ?: stCode,
                            station = stationName, // 驿站官方名比 track_desc 提取准
                            stationAddress = exist.stationAddress ?: stationAddress,
                            arrivedAt = exist.arrivedAt ?: t.str("operation_time")?.parseTime(),
                            // 驿站在架实况优先：运输中的单若已在架则视为待取
                            status = if (exist.status != ExpressStatus.PICKED) ExpressStatus.WAIT_PICKUP else exist.status,
                            summary = exist.summary ?: t.str("shipping_name"),
                        )
                        continue
                    }
                    out += ExpressItem(
                        id = trackingNo,
                        platform = Platform.PDD,
                        station = stationName,
                        stationAddress = stationAddress,
                        trackingNo = trackingNo,
                        pickupCode = stCode,
                        summary = t.str("shipping_name"),
                        status = ExpressStatus.WAIT_PICKUP,
                        arrivedAt = t.str("operation_time")?.parseTime(),
                        fetchDeadline = null,
                        raw = t.toString(),
                    )
                }
            }
        }.onFailure { AppLog.w(TAG, "多多驿站列表失败（保留快递助手数据）", it) }
        return out
    }

    /**
     * 取件码：①快递助手列表直出（全驿站覆盖，2026-09-29 实测 YT 妈妈驿站单出码
     * 442-2-809），命中且有码直接返回、无码落驿站兜底；②驿站 station/package 直查。
     * 注意：兜底绝不能调 fetchExpressList（会把快递助手重复拉一遍 → 54001 限频）。
     */
    override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? {
        if (expressId.isBlank()) return null
        var hitAny = false
        // ① 快递助手
        runCatching {
            var done = false
            var offset = 0
            repeat(5) {
                if (hitAny || done) return@repeat
                val env = postApi(
                    cred,
                    API_ASSISTANT_LIST,
                    """{"offset":$offset,"count":20,"channel":"express","entry_source":"assistant_search","status_type":0,"query_type":0}""",
                )
                val list = env.obj("result")?.arr("track_info_list").orEmpty()
                val hit = list.firstOrNull { (it as? JsonObject)?.str("tracking_number") == expressId } as? JsonObject
                if (hit != null) {
                    val code = hit.str("pick_code")?.removePrefix(PICK_CODE_PREFIX)?.trim()?.takeIf { it.isNotBlank() }
                    if (code != null) return PickupCode(expressId, Platform.PDD, code)
                    hitAny = true // 命中但无码 → 驿站兜底补码
                    return@repeat
                }
                if (list.size < 20) {
                    done = true
                } else {
                    offset += 20
                    delay(PAGE_DELAY_MS) // 54001 查询频率太快：翻页间隔 2s
                }
            }
        }
        if (hitAny) delay(SOURCE_DELAY_MS)
        // ② 驿站门店直查兜底
        runCatching {
            val env = postApi(cred, API_STATION_PACKAGE, """{"entry_source":"WX"}""")
            val result = env.obj("result") ?: return@runCatching
            for (el in result.arr("combine_list")) {
                val station = el as? JsonObject ?: continue
                for (trackEl in station.arr("combine_track_list")) {
                    val t = trackEl as? JsonObject ?: continue
                    if (t.str("tracking_number") != expressId) continue
                    val code = t.str("pick_code")?.removePrefix(PICK_CODE_PREFIX)?.trim()?.takeIf { it.isNotBlank() }
                    if (code != null) return PickupCode(expressId, Platform.PDD, code)
                }
            }
        }
        return null
    }

    /** 从 track_desc 提取驿站/代收点名：「已到达[妈妈驿站]xxx」「已投递...在【xxx】」「已派送至【xxx】」 */
    private fun String?.extractStation(): String? {
        val s = this ?: return null
        STATION_ARRIVE_REGEX.find(s)?.let { return (it.groupValues[1] + it.groupValues[2]).trim() }
        STATION_BRACKET_REGEX.find(s)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** YYB getCode 返回 JSON {"code","errMsg"} → 取 code；缺 code 抛 ParseFailed（附原文便于定位） */
    private fun extractWxCode(raw: String): String {
        val code = WX_CODE_REGEX.find(raw)?.groupValues?.get(1).orEmpty()
        if (code.isBlank()) throw PlatformError.ParseFailed(platform, "YYB 取 code 失败：${raw.take(120)}")
        return code
    }

    /**
     * 业务请求（pdd.md §2）：POST https://mobile.yangkeduo.com/proxy/api/<api>，
     * 必需头仅 anti-content（每次新生成）+ accesstoken（§1.4：Origin/Referer/UA 非必需）。
     * error_code 40001 → NeedRelogin；其余非 0 → ParseFailed。
     */
    private suspend fun postApi(cred: Credential, api: String, body: String): JsonObject {
        val ac = try {
            antiContent.generate()
        } catch (e: Exception) {
            throw PlatformError.ParseFailed(platform, "anti-content 生成失败：${e.message}")
        }
        val request = Request.Builder()
            .url("$PROXY_BASE$api")
            .header("anti-content", ac)
            .header("accesstoken", cred.token)
            .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .build()
        val text = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw PlatformError.NetworkError(platform, IOException("HTTP ${resp.code}"))
                    resp.body?.string().orEmpty()
                }
            }
        } catch (e: PlatformError) {
            throw e
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
        val env = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw PlatformError.ParseFailed(platform, "响应非 JSON：${text.take(120)}")
        }
        val errCode = env.str("error_code")
        if (errCode == "40001") throw PlatformError.NeedRelogin(platform)
        if (errCode != null && errCode != "0") {
            throw PlatformError.ParseFailed(platform, "${env.str("error_msg")} ($errCode)")
        }
        return env
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.optLong(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()

    /** "2026-09-26 10:45:00" → epoch 毫秒（本地时区）；解析失败返回 null */
    private fun String.parseTime(): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(this)?.time
    } catch (_: Exception) {
        null
    }

    /** "name=value; Path=/; ..." → (name, value)：取首个分号段，value 内含 '=' 时按首个 '=' 切分 */
    private fun parseCookiePair(raw: String): Pair<String, String>? {
        val first = raw.substringBefore(';').trim()
        val idx = first.indexOf('=')
        if (idx <= 0) return null
        return first.substring(0, idx).trim() to first.substring(idx + 1).trim()
    }

    /** 302 响应快照（withContext(IO) 内采集，外部解析） */
    private data class RespSnapshot(
        val setCookies: List<String>,
        val httpCode: Int,
        val location: String?,
        val body: String,
    )

    private companion object {
        const val TAG = "AggApp"

        /** 快递助手翻页间隔（54001 查询频率太快，2026-09-29 实测） */
        const val PAGE_DELAY_MS = 2000L

        /** 两个数据源之间的间隔 */
        const val SOURCE_DELAY_MS = 800L

        /** 多多驿站微信 appid（pdd.md §1） */
        const val APPID = "wx913449bbda3b9f9a"

        /** 登录兑换端点（pdd.md §4.2.4 实测域名；YYB js_code 直接可换） */
        const val LOGIN_URL = "https://mdkd.pinduoduo.com/weixin/login"

        /** 业务网关（pdd.md §1.3：mdkd 直连被 WAF 405，须走 mobile proxy） */
        const val PROXY_BASE = "https://mobile.yangkeduo.com/proxy/api"

        /** 包裹列表（实测：combine_list 待取件按驿站分组 + package_out_list 已取出） */
        const val API_STATION_PACKAGE = "/api/express_trackbox/station/package"

        /** 快递助手列表（主站按手机号聚合全驿站快递，2026-09-29 实测：妈妈驿站 YT 单出取件码） */
        const val API_ASSISTANT_LIST = "/api/express_trackbox/list"

        /** track_desc 驿站名提取：「已投递...在【xxx】」/「已派送至【xxx】」 */
        val STATION_BRACKET_REGEX = Regex("【([^】]+)】")

        /** track_desc 驿站名提取：「已到达[妈妈驿站]珠海科技学院快递中心」 */
        val STATION_ARRIVE_REGEX = Regex("到达\\[([^\\]]+)\\]([^，。\\s]{2,25})")

        /** 身份码（实测：entry_source:"WX" → wx 前缀码） */
        const val API_IDENTIFICATION_CODE = "/api/express_trackbox/generate_identification_code"

        /** pick_code 前缀（"取件码 152-2-6010" → "152-2-6010"） */
        const val PICK_CODE_PREFIX = "取件码"

        /** 微信内置浏览器 UA（pdd.md §4.2.4：PC/手机微信 UA 均可） */
        const val WX_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49"

        /** getCode 返回 JSON 里的 code 字段 */
        val WX_CODE_REGEX = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"")
    }
}
