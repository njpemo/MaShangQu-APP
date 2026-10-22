package com.aggapp.platform.ryll

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 如意邻里（圆通 wx814446d567063840）独立登录客户端
 * （docs/platform/ryll/README.md §3.2/§4.2；Node 参考 ryll/js/ryll_login.js、ryll_verify.js，全链实测）。
 *
 * 链路（完全不依赖 YYB）：
 *   ① vcode：GET /api/user/vcode → {base64Img(图形码PNG), randValue}
 *   ② sendsms：POST /api/loginsendsms?randValue=&code=<图形码>&username=<手机号>
 *   ③ login：POST /api/user/login?username=<手机号>&flag=2&smsCode=<短信码>
 *      → data.token 即 jwt-token（响应头 jwt-token 同值）
 *   ④ getPinCode：GET /api/user/getPinCode（jwt-token）
 *      → data = "data:image/png;base64,..."（Code128 条码图，919×223）
 *      → zxing 本地解码出 16 位静态 PIN（与账号永久绑定，实测两次调用码值一致）
 *
 * 请求头：source: TB_WX_APPLET（所有请求）+ jwt-token（业务接口）；
 * 401 → NeedRelogin（README §2：登录态失效统一语义）。
 */
internal class RyllClient(private val http: OkHttpClient) {

    private val json = Json.Default

    // ============ ① 图形验证码 ============

    /** GET /api/user/vcode → 图形码 PNG（base64，可直接喂给 Compose 显示）+ randValue */
    suspend fun vcode(): RyllVcode = withContext(Dispatchers.IO) {
        val env = exec(request("GET", "$BASE/api/user/vcode", token = null))
        // 实测（2026-09-29 Node 对拍）：响应无信封壳，顶层直接 {base64Img, randValue}；
        // 兼容 data 壳形态兜底
        val obj = (env["data"] as? JsonObject)?.takeUnless { it.isEmpty() } ?: env
        val img = obj.optStr("base64Img")
        val rand = obj.optStr("randValue")
        if (img.isNullOrBlank() || rand.isNullOrBlank()) {
            throw PlatformError.ParseFailed(PLATFORM, "vcode 响应缺 base64Img/randValue")
        }
        RyllVcode(img, rand)
    }
    // ============ ② 发短信 ============

    /** POST /api/loginsendsms?randValue=&code=<图形码>&username=<手机号>（qsStringify 拼 query，README §3.2） */
    suspend fun sendSms(phone: String, randValue: String, captchaCode: String) =
        withContext(Dispatchers.IO) {
            val url = "$BASE/api/loginsendsms" +
                "?randValue=${urlEnc(randValue)}&code=${urlEnc(captchaCode)}&username=${urlEnc(phone)}"
            val env = exec(request("POST", url, token = null))
            requireData(env) // 失败（图形码错等）→ ParseFailed 附 msg
            Unit
        }

    // ============ ③ 短信码登录 ============

    /**
     * POST /api/user/login?username=&flag=2&smsCode= → token 即 jwt-token。
     * token 提取四路兜底（信封形态实测不完全可控）：data.token（对象）→ data 本身（字符串）
     * → 顶层 token → 响应头 jwt-token；全缺时先过 requireData（业务失败抛具体 msg），
     * 仍无 → 抛错附响应体片段便于诊断。
     */
    suspend fun login(phone: String, smsCode: String): String = withContext(Dispatchers.IO) {
        val url = "$BASE/api/user/login?username=${urlEnc(phone)}&flag=2&smsCode=${urlEnc(smsCode)}"
        val req = request("POST", url, token = null)
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw PlatformError.NetworkError(PLATFORM, e)
        }
        resp.use { r ->
            if (r.code == 401) throw PlatformError.NeedRelogin(PLATFORM)
            val text = if (r.isSuccessful) r.body?.string().orEmpty()
            else throw PlatformError.NetworkError(PLATFORM, IOException("HTTP ${r.code}"))
            val env = parseJson(text)
            val data = env["data"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            val token = when (data) {
                is JsonObject -> data.optStr("token")
                is JsonPrimitive -> data.contentOrNull
                else -> null
            }?.takeIf { !it.isNullOrBlank() }
                ?: env.optStr("token")?.takeIf { it.isNotBlank() }
                ?: r.header("jwt-token")?.takeIf { it.isNotBlank() }
            if (token != null) return@use token
            requireData(env) // 明确业务失败（如验证码错误）→ 抛具体 msg
            throw PlatformError.ParseFailed(PLATFORM, "登录响应缺 token：${text.take(150)}")
        }
    }

    // ============ 登录态探测 / 出 PIN ============

    /** GET /api/user/getuserinfo（轻量、无副作用）→ status=0 即存活 */
    suspend fun checkAlive(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            requireData(exec(request("GET", "$BASE/api/user/getuserinfo", token)))
            true
        } catch (e: PlatformError.NeedRelogin) {
            false
        }
        // 其余异常（网络等）原样抛：探测失败 ≠ 登录态失效
    }

    /**
     * GET /api/user/getPinCode → data = "data:image/png;base64,..."（Code128 条码图）。
     * 服务端只下发成品图不下发码值 → zxing 本地解码出 16 位静态 PIN。
     * （README §4.2 实测：静态码，与账号永久绑定，拿到即永久缓存。）
     */
    suspend fun fetchPinPng(token: String): String = withContext(Dispatchers.IO) {
        val env = exec(request("GET", "$BASE/api/user/getPinCode", token))
        val data = (requireData(env) as? JsonPrimitive)?.contentOrNull
            ?: throw PlatformError.ParseFailed(PLATFORM, "getPinCode 响应缺 data")
        if (!data.startsWith("data:image")) {
            throw PlatformError.ParseFailed(PLATFORM, "getPinCode data 非图片：${data.take(60)}")
        }
        data
    }

    // ============ 快递列表（README §5） ============

    // ============ 手机后四位验证（2026-10-22 全链实测） ============

    /**
     * POST /api/order/mobileCheck {lastFourMobile, waybillNo} → body 字面 true/false。
     * 实测（YT7651323338983）：0000→false、7330→false、5220(正确)→true；
     * 已签收单恒 true（不设防）；假单号 false；必须 JSON body（form 表单 → -57029）。
     * 验证通过后服务端把验证态绑定在 jwt-token 会话上，随后的详情查询才会带出取件码。
     */
    suspend fun mobileCheck(token: String, lastFourMobile: String, waybillNo: String): Boolean =
        withContext(Dispatchers.IO) {
            val req = request(
                "POST",
                "$BASE/api/order/mobileCheck",
                token,
                body = """{"lastFourMobile":"$lastFourMobile","waybillNo":"$waybillNo"}""",
            )
            val resp = try {
                http.newCall(req).execute()
            } catch (e: IOException) {
                throw PlatformError.NetworkError(PLATFORM, e)
            }
            resp.use { r ->
                if (!r.isSuccessful) throw PlatformError.NetworkError(PLATFORM, IOException("HTTP ${r.code}"))
                // 实测响应体就是裸布尔 true/false（无信封壳），宽容解析
                val text = r.body?.string().orEmpty().trim()
                text == "true" || runCatching {
                    when (val el = json.parseToJsonElement(text)) {
                        is JsonPrimitive -> el.booleanOrNull == true
                        is JsonObject -> el.optStr("data") == "true"
                        else -> false
                    }
                }.getOrDefault(false)
            }
        }

    /**
     * GET /api/order/orderDetailByWayBillNo?wayBillNo=<单号> → 查件详情+轨迹
     * （2026-09-29 实测 YT0711336342128 打通：baseOrderInfo.statusName=已到代收点，
     * 轨迹在 waybillInfoResultResponses[0].waybillProcessInfo[]，最新在前；
     * 驿站名/站点电话在入柜条目 description 内；非本人单 userIdentity=visitor 无取件码；
     * 2026-10-22 实测：mobileCheck 尾号验证通过后（验证态绑定 token 会话），
     * 同接口响应多出 recieveOrderDetailResponse.value.stationCode = 取件码（如 "439-1-495"），
     * ocOrderDetailResponse.stationCode 同值，storageTakingCode = 驿站取件凭证号）
     */
    suspend fun queryOrderDetail(waybillNo: String, token: String? = null): RyllOrderDetail? =
        withContext(Dispatchers.IO) {
            val url = "$BASE/api/order/orderDetailByWayBillNo?wayBillNo=${urlEnc(waybillNo)}"
            // token 必须带：mobileCheck 验证态绑定在 token 会话上，无 token 永远出不了取件码段
            val env = exec(request("GET", url, token))
        if (env.optStr("status") == "-57029") return@withContext null // 单号不存在实测返回该码
        val base = (env["baseOrderInfo"] as? JsonObject) ?: return@withContext null
        val tracks = mutableListOf<RyllTrack>()
        val firstWaybill = (env["waybillInfoResultResponses"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? JsonObject
        val trackArr: kotlinx.serialization.json.JsonArray? = when (val inner = firstWaybill?.get("waybillProcessInfo")) {
            is kotlinx.serialization.json.JsonArray -> inner
            is JsonObject -> inner["waybillProcessInfo"] as? kotlinx.serialization.json.JsonArray
            else -> null
        }
        trackArr?.forEach { el ->
            (el as? JsonObject)?.let { o ->
                tracks += RyllTrack(
                    opTime = o.optStr("opTime"),
                    opName = o.optStr("opName"),
                    description = o.optStr("description") ?: o.optStr("orderInfo"),
                    orgName = o.optStr("opOrgName"),
                )
            }
        }
        RyllOrderDetail(
            mailNo = base.optStr("mailNo") ?: waybillNo,
            status = base.optStr("status") ?: "",
            statusName = base.optStr("statusName") ?: "",
            senderName = base.optStr("senderName"),
            receiverName = base.optStr("receiverName") ?: base.optStr("recipientName"),
            fromCity = listOfNotNull(base.optStr("senderProvinceName"), base.optStr("senderCityName"))
                .joinToString(" ").ifBlank { null },
            toCity = listOfNotNull(base.optStr("recipientProvinceName"), base.optStr("recipientCityName"))
                .joinToString(" ").ifBlank { null },
            // owner 单防御式提取；visitor 单需 mobileCheck 通过后从 recieveOrderDetailResponse 出码（2026-10-22 实测）
            pickupCode = base.optStr("pickCode")
                ?: base.optStr("pickupCode")
                ?: ((env["recieveOrderDetailResponse"] as? JsonObject)?.get("value") as? JsonObject)
                    ?.optStr("stationCode")
                ?: (env["ocOrderDetailResponse"] as? JsonObject)?.optStr("stationCode"),
            tracks = tracks,
        )
    }

    /** POST /station-api/rypackage/waitAllOrderList {flag:1} → 原始信封（stationUrl 路由，防御式解析交上层） */
    suspend fun waitAllOrderList(token: String, flag: Int): JsonObject = withContext(Dispatchers.IO) {
        val env = exec(
            request(
                "POST",
                STATION_BASE + "rypackage/waitAllOrderList",
                token,
                body = """{"flag":$flag}""",
            ),
        )
        requireData(env) as? JsonObject ?: JsonObject(emptyMap())
    }

    /** POST /station-api/rypackage/queryCabinetByPhone {} → 柜机/货架包裹信封 */
    suspend fun queryCabinetByPhone(token: String): JsonObject = withContext(Dispatchers.IO) {
        val env = exec(
            request("POST", STATION_BASE + "rypackage/queryCabinetByPhone", token, body = "{}"),
        )
        requireData(env) as? JsonObject ?: JsonObject(emptyMap())
    }

    // ============ 内部工具 ============

    /** 统一请求：source 必带头 + jwt-token（token 非空时）；JSON body 或空 POST */
    private fun request(method: String, url: String, token: String?, body: String? = null): Request {
        val b = Request.Builder().url(url)
            .header("source", SOURCE)
            .header("Accept", "application/json, text/plain, */*")
        if (!token.isNullOrBlank()) b.header("jwt-token", token)
        if (method == "POST") b.post((body ?: "").toRequestBody(FORM_MEDIA)) else b.get()
        return b.build()
    }

    /** 统一执行：IO/非2xx → NetworkError；401 → NeedRelogin；响应须为 JSON 对象 */
    private fun exec(request: Request): JsonObject {
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PlatformError.NetworkError(PLATFORM, e)
        }
        resp.use { r ->
            if (r.code == 401) throw PlatformError.NeedRelogin(PLATFORM)
            if (!r.isSuccessful) throw PlatformError.NetworkError(PLATFORM, IOException("HTTP ${r.code}"))
            return parseJson(r.body?.string().orEmpty())
        }
    }

    /**
     * 信封校验（宽容模式，实测各接口信封不统一：getPinCode 是 {status:0,data}，
     * login 是 {code,data:{token}}，vcode 顶层无壳）→ 有 data 取 data；
     * 无 data 时仅存在明确错误标志（status 非 0 / code 非 0、"200"）才抛 ParseFailed。
     */
    private fun requireData(env: JsonObject): kotlinx.serialization.json.JsonElement? {
        env["data"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.let { return it }
        val status = env.optInt("status")
        if (status != null && status != 0) {
            throw PlatformError.ParseFailed(
                PLATFORM,
                "业务失败 $status ${env.optStr("msg") ?: env.optStr("message") ?: ""}".trim(),
            )
        }
        val code = env.optStr("code")
        if (code != null && code != "0" && code != "200") {
            throw PlatformError.ParseFailed(
                PLATFORM,
                "业务失败 $code ${env.optStr("msg") ?: env.optStr("message") ?: ""}".trim(),
            )
        }
        // 无 data 也无 status/code 但带 msg/message → 明确业务失败（如发码图形码错误）
        val msg = env.optStr("msg") ?: env.optStr("message")
        if (status == null && code == null && !msg.isNullOrBlank()) {
            throw PlatformError.ParseFailed(PLATFORM, "业务失败 $msg")
        }
        return null
    }

    private fun parseJson(text: String): JsonObject {
        if (text.isBlank()) throw PlatformError.ParseFailed(PLATFORM, "空响应体")
        return json.parseToJsonElement(text) as? JsonObject
            ?: throw PlatformError.ParseFailed(PLATFORM, "响应非 JSON 对象：${text.take(120)}")
    }

    companion object {
        private val PLATFORM = Platform.RYLL
        const val SOURCE = "TB_WX_APPLET"

        /** 主网关（README §1：所有 /api/ 路径） */
        const val BASE = "https://mec.yto.net.cn"

        /** 驿站网关（/station-api/ 前缀路由至此，README §1） */
        const val STATION_BASE = "https://yizhan.mamayz.com:49197/opYZAppPackage/"

        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        private fun urlEnc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
    }
}

/** 图形验证码：PNG base64（data URL，Compose 可直接解码显示）+ randValue（发短信时回传） */
data class RyllVcode(val base64Png: String, val randValue: String)

/** 查件轨迹条目（waybillProcessInfo[]，最新在前） */
data class RyllTrack(
    val opTime: String?,     // "2026-09-28 09:51:13"
    val opName: String?,     // "入柜|入库" / "派件扫描"
    val description: String?, // 含驿站名/站点电话的完整文案
    val orgName: String?,    // 操作网点
)

/** 查件详情（orderDetailByWayBillNo，visitor/owner 通用；取件码仅 owner 单可能有） */
data class RyllOrderDetail(
    val mailNo: String,
    val status: String,       // STATION / SIGNED ...
    val statusName: String,   // "已到代收点"
    val senderName: String?,
    val receiverName: String?,
    val fromCity: String?,
    val toCity: String?,
    val pickupCode: String?,  // owner 单防御式提取，visitor 为 null
    val tracks: List<RyllTrack>,
) {
    /** 已到驿站待取（STATION） */
    val atStation: Boolean get() = status.equals("STATION", ignoreCase = true)
}

/**
 * Code128 条码图（data:image PNG）→ 码值解码器。
 * platform:ryll 为纯 JVM 模块无 Android 图形 API，由 :app 注入实现（android.graphics + zxing）。
 */
fun interface PinDecoder {
    fun decode(dataUrl: String): String?
}

// ============ kotlinx JSON 安全取值（缺字段/类型不符一律 null，不抛） ============

internal fun JsonObject.optStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.optBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.optInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.optLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.optArr(key: String): kotlinx.serialization.json.JsonArray? =
    this[key] as? kotlinx.serialization.json.JsonArray
