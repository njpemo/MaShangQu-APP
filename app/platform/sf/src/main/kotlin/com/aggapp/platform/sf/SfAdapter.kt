package com.aggapp.platform.sf

import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.model.PickupCode
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 顺丰适配器 —— 已迁移至 MIMP H5 独立短信登录链路（2026-09-29 最终方案，去 YYB 去 ucmp）。
 * 主链路（sf.md §2.5 登录 / §4.4 出码，Node 参考 sf/sf-mimp-e2e.js，浏览器全链实测出码）：
 *
 *   MIMP 发码（/mcs-mimp/sendValidCode，body 带 captcha_args 极验四件套，UI 先过滑块）
 *   → loginByMobile 验证码登录（Set-Cookie 落 MIMP 会话 sessionId/JSESSIONID/_login_user_id_）
 *   → createIdentityCode（固定密钥签名 + Cookie）取身份码静态段（10 位，永久有效）
 *   → 此后本地公式出码零请求（SfMimpClient 承载全部 MIMP 调用）
 *
 * 旧驿收发链路（YYB-GO → YJY-Auth JWT，sf.md §2.2）降级为兜底：粘贴 YJY-Auth token
 * （或 wx.login code 调 [loginWithWxCode]）仍可走 credentialPerson/快递列表/取件码，
 * 由 [SfSigner]（§9.1 签名）承载，保留不删。
 *
 * 身份码为【伪动态】（sf.md §4.3 已破解并实测回填）：
 *
 *   身份码 = {当前Unix秒 − 1640010000} + "ACS" + {用户静态段(10位)}
 *
 * - 时间段线性 +1/秒，本地可离线生成任意时刻码；
 * - 静态段与用户永久绑定，首次登录/出码时取得后由调用方缓存进 cred.extra["staticSeg"] 加密落盘；
 * - extra["staticSeg"] 已存在 → 本地公式生成不请求；未存在 → 按 MIMP Cookie 走 §4.4 出码
 *   （Cookie 失效 → NeedRelogin 重新短信登录），旧链路凭据则回退 credentialPerson。
 *   （§4.3 建议的 3 分钟周期服务端对齐由调用方按需触发，适配器内不强制）
 */
@Singleton
class SfAdapter @Inject constructor() : PlatformAdapter {

    override val platform = Platform.SF

    /** 短信验证码登录（官方小程序链路，去 YYB；淘宝同款 PHONE_CODE 模式） */
    override val loginMode = LoginMode.PHONE_CODE

    private val json = Json.Default

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** MIMP H5 独立登录直连客户端（新主链路，sf.md §2.5/§4.4） */
    private val mimp = SfMimpClient(http)

    // sf.md §9.3.2 频控：相邻业务请求最小间隔 ≥3s + 随机抖动（单例内全局串行）
    private val throttleMutex = Mutex()
    private var lastCallAt = 0L

    // ============ 登录（新主链路 sf.md §2.5；旧链路 §2.2 降级兜底） ============

    /** 契约方法：无极验发码（实际发码必须携带极验四件套，走下方重载） */
    override suspend fun sendSmsCode(phone: String) = sendSmsCode(phone, gt = null)

    /**
     * 短信发码（sf.md §2.5 ①）：POST /mcs-mimp/sendValidCode，body 携带 captcha_args。
     * 极验 v4（缺失报 700001 未进行滑块验证）：UI 先弹滑块 WebView（SfGeetestDialog，
     * MIMP captchaId d6ee898c...），通过后传 gt 四件套 lot_number/captcha_output/
     * pass_token/gen_time（pass_token 一次性，失败需重新过滑块）。
     */
    suspend fun sendSmsCode(phone: String, gt: Map<String, String>?) {
        val g = gt?.filterValues { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: throw PlatformError.ParseFailed(platform, "缺少极验四件套，请先完成滑块验证")
        mimp.sendValidCode(phone, g)
    }

    /**
     * 短信验证码登录（sf.md §2.5 ②）：loginByMobile 成功后 Set-Cookie 落 MIMP 会话。
     * 凭据：token = MIMP Cookie 串（淘宝同款形态），extra 落 mobile（账号展示名）、
     * mimp="1"（MIMP 凭据标记）、staticSeg（拿到即永久）。登录成功后立即按 §4.4 取
     * 静态段，失败不阻断登录（fetchIdentityCode 会按 Cookie 重试）。
     */
    override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential {
        val cookieHeader = mimp.loginByMobile(phone, smsCode)
        val cred = Credential(
            platform,
            cookieHeader,
            buildMap {
                put("mobile", phone)
                put("mimp", "1")
            },
            System.currentTimeMillis(),
        )
        // 静态段一次获取、此后永久本地出码（§4.4）；此处失败不阻断登录
        return try {
            cred.copy(extra = cred.extra + ("staticSeg" to mintStaticSeg(cookieHeader)))
        } catch (_: Exception) {
            cred
        }
    }

    /**
     * MIMP 出静态段（契约外公开方法，sf.md §4.4）：Cookie → createIdentityCode，
     * 取 "ACS" 之后 10 位静态段（与用户永久绑定）。Cookie 失效 → NeedRelogin（重新短信登录）。
     */
    suspend fun mintStaticSeg(cookieHeader: String): String {
        val code = mimp.createIdentityCode(cookieHeader)
        // ACS 为固定分隔标识，其后即 10 位静态段（按分隔符解析，容忍时间段位数变化）
        val seg = code.substringAfter(ACS, "")
        if (seg.length != 10) throw PlatformError.ParseFailed(platform, "身份码结构异常：缺少 10 位静态段")
        return seg
    }

    /**
     * 旧链路：wx.login code 换 YJY-Auth（契约外公开方法，粘贴 code 兜底，sf.md §2.2；
     * 已不依赖 YYB-GO，由 UI 手动粘贴 code 触发）。
     * POST /e-mini/mini/login body {"code","appid"}——登录接口无需 YJY-Auth（token 传空串）。
     */
    suspend fun loginWithWxCode(code: String): Credential {
        val env = call("", "POST", PATH_LOGIN, buildJsonObject {
            put("code", code)
            put("appid", APPID)
        })
        val data = requireData(env) as? JsonObject
            ?: throw PlatformError.ParseFailed(platform, "登录响应 data 非 JSON 对象")
        val token = data.optStr("accessToken")
            ?: throw PlatformError.ParseFailed(platform, "登录响应缺 accessToken")
        val extra = buildMap {
            data.optStr("userId")?.let { put("userId", it) }
            data.optStr("mobile")?.let { put("mobile", it) } // §2.2：可用于判定是否已绑手机号
            SfCrypto.parseJwtPayload(token)?.optLong("exp")?.let { put("exp", it.toString()) }
        }
        return Credential(platform, token, extra, System.currentTimeMillis())
    }

    override fun loginUrl(): String = ""

    override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? = null

    // ============ 登录态校验（sf.md §4.1） ============

    override suspend fun checkAlive(cred: Credential): Boolean {
        // ① 新链路（§4.4）：静态段与用户永久绑定 → 本地判活零请求
        if (!cred.extra["staticSeg"].isNullOrBlank()) return true
        // ② 新链路 MIMP 凭据无静态段：交给 fetchIdentityCode 实测（Cookie 失效 → NeedRelogin），此处不误判
        if (cred.extra["mimp"] == "1") return true
        // ③ 旧链路（YJY-Auth 粘贴兜底）：JWT 本地解码 exp（HS256 payload base64url），已过期直接 false，省一次请求（§4.1）
        SfCrypto.parseJwtPayload(cred.token)?.optLong("exp")?.let { expSec ->
            if (System.currentTimeMillis() / 1000 >= expSec) return false
        }
        // ④ GET /e-uc/user/v2/loginUserInfo 轻量探测（无副作用，签名三件套 + YJY-Auth）
        return try {
            val env = call(cred.token, "GET", PATH_LOGIN_USER_INFO)
            env.optBool("success") == true || env.optStr("code") == "200"
        } catch (_: PlatformError.NeedRelogin) {
            false // HTTP 401 / 登录态失效业务码（http.js: e-mini_200_B_101）→ 失效
        }
        // 其余 PlatformError（网络/签名等）原样抛出：探测失败 ≠ 登录态失效（§2.3 不误杀有效凭据）
    }

    // ============ 身份码（新链路 §4.4 / 伪动态 §4.3 / 旧链路 §4.2） ============

    override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
        // ① 静态段已缓存 → 本地公式生成，不请求（§4.3 伪动态结论）
        cred.extra["staticSeg"]?.let { return localIdentityCode(it) }
        // ② 新链路：MIMP Cookie → createIdentityCode（§4.4）；Cookie 失效 → NeedRelogin
        if (cred.extra["mimp"] == "1" && cred.token.isNotBlank()) {
            return IdentityCode(
                platform = platform,
                type = CodeType.BARCODE,
                content = mimp.createIdentityCode(cred.token), // 形如 150610521ACS2853414300（服务端时间最准）
                expireAt = System.currentTimeMillis() + REFRESH_PERIOD_MS, // 刷新周期 3 分钟
            )
        }
        // ③ 旧链路兜底（YJY-Auth 粘贴）→ 真实请求 credentialPerson（§4.2），data 即身份码字符串
        val data = fetchIdCodeRaw(cred)
            ?: throw PlatformError.ParseFailed(platform, "credentialPerson 响应缺 data")
        return IdentityCode(
            platform = platform,
            type = CodeType.BARCODE, // §4.2：App 侧 CODE128 条形码渲染（H5 用 JsBarcode）
            content = data,          // 形如 150409083ACS2853414300
            expireAt = System.currentTimeMillis() + REFRESH_PERIOD_MS, // §4.2：刷新周期 3 分钟
        )
    }

    /**
     * 取用户静态段（契约外公开方法，旧链路兜底）：credentialPerson 返回 data 尾部 10 位
     * （§4.3，与用户永久绑定）。新链路走 [mintStaticSeg]（§4.4 MIMP）。
     * 调用方拿到后写入 cred.extra["staticSeg"] 加密落盘，此后身份码本地生成零请求。
     */
    suspend fun fetchStaticSeg(cred: Credential): String {
        val data = fetchIdCodeRaw(cred)
            ?: throw PlatformError.ParseFailed(platform, "credentialPerson 响应缺 data")
        // ACS 为固定分隔标识，其后即 10 位静态段（按分隔符解析，容忍时间段位数变化）
        val seg = data.substringAfter(ACS, "")
        if (seg.length != 10) throw PlatformError.ParseFailed(platform, "身份码结构异常：缺少 10 位静态段")
        return seg
    }

    /** POST /e-mini/mini/credentialPerson body {}（§4.2），成功返回 data 字符串 */
    private suspend fun fetchIdCodeRaw(cred: Credential): String? {
        val env = call(cred.token, "POST", PATH_CREDENTIAL_PERSON, buildJsonObject { })
        return (requireData(env) as? JsonPrimitive)?.contentOrNull
    }

    /** 本地公式（§4.3，三样本实测精确吻合）：{Unix秒 − EPOCH_OFFSET} + ACS + 静态段 */
    private fun localIdentityCode(staticSeg: String) = IdentityCode(
        platform = platform,
        type = CodeType.BARCODE,
        content = "${System.currentTimeMillis() / 1000 - EPOCH_OFFSET}$ACS$staticSeg",
        expireAt = System.currentTimeMillis() + REFRESH_PERIOD_MS,
    )

    // ============ 快递列表 / 取件码（sf.md §5.1 / §5.2） ============

    /**
     * §5.3：MIMP 凭据走校园专享 queryStudentWaittingWaybill（单次全量，无分页；
     * 取件码 pickUpCode 列表直出，waitingPickType="1" 为丰巢柜扫码取件）。
     * 旧驿收发凭据仍走 §5.1 searchWaybill 分页。
     */
    override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> {
        if (cred.extra["mimp"] == "1" && cred.token.isNotBlank()) {
            if (page > 1) return emptyList() // 校园接口无分页：首页即全量
            return mimp.queryStudentWaittingWaybill(cred.token).mapNotNull { el ->
                (el as? JsonObject)?.let(::mapCampusExpress)
            }
        }
        val env = call(cred.token, "POST", PATH_SEARCH_WAYBILL, searchWaybillBody(page))
        val records = (requireData(env) as? JsonObject)?.optArr("records") ?: return emptyList()
        return records.mapNotNull { el -> (el as? JsonObject)?.let(::mapExpress) }
    }

    /**
     * §5.3 校园专享列表项映射：接口不回运单号（前端也只展示驿站/取件码/寄件收件人），
     * id 用「驿站+取件码/类型」构造稳定去重键；取件码 pickUpCode 直出；
     * waitingPickType="1" → 丰巢柜（stationName 标注提示）。全部按 WAIT_PICKUP 展示。
     */
    private fun mapCampusExpress(o: JsonObject): ExpressItem {
        val store = o.optStr("storeName").orEmpty()
        val pickUpCode = o.optStr("pickUpCode")
        val fengchao = "".plus(o.optStr("waitingPickType").orEmpty()) == "1"
        val id = listOf(store, pickUpCode ?: "", o.optStr("waitingPickType").orEmpty())
            .joinToString("|").ifBlank { "campus" }
        return ExpressItem(
            id = id,
            platform = Platform.SF,
            station = if (fengchao) "$store（丰巢柜）" else store,
            stationAddress = null,               // 接口不提供
            trackingNo = null,                   // 接口不回运单号（§5.3）
            pickupCode = pickUpCode,             // 列表直出（§5.3）
            summary = listOfNotNull(
                o.optStr("originateContacts")?.let { "寄 $it" },
                o.optStr("destinationContacts")?.let { "收 $it" },
            ).joinToString(" · ").ifBlank { null },
            status = ExpressStatus.WAIT_PICKUP,
            arrivedAt = null,                    // 接口不提供入仓时间
            fetchDeadline = null,
            raw = o.toString(),                  // 原始 JSON，仅调试用
        )
    }

    /** §5.1 searchWaybill 请求体：{"pageNum":1,"pageSize":10,"scanFlag":false} */
    private fun searchWaybillBody(page: Int) = buildJsonObject {
        put("pageNum", page)
        put("pageSize", PAGE_SIZE)
        put("scanFlag", false)
    }

    /**
     * 取件码（sf.md §5.2）：
     * MIMP 凭据——校园接口按运单号精查无对应端点（§5.3 无运单号字段），返回 null（不误抛错）；
     * 旧驿收发凭据——主路径 searchWaybill 列表直出 pickupCode（按页查找运单号）；
     * 兜底——列表里出现过的驿站逐个试 POST /e-mini/warehouse/getWaybillInfo {billCode, stationId}
     * （stationId 必填；实测同一账号下驿站命中即返回 pickupCode，无需尾号）。
     * 查不到 / 已取走 / 运单号有误（§5.2 业务码 B_100/B_101/B_102）→ null。
     */
    override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? {
        if (expressId.isBlank()) return null
        if (cred.extra["mimp"] == "1") return null // 校园链路无按运单号查取件码端点（§5.3）
        val stationIds = linkedSetOf<String>()
        for (page in 1..PICKUP_PROBE_PAGES) {
            val env = call(cred.token, "POST", PATH_SEARCH_WAYBILL, searchWaybillBody(page))
            val records = (requireData(env) as? JsonObject)?.optArr("records")
                ?.takeUnless { it.isEmpty() } ?: break
            for (rec in records) {
                val o = rec as? JsonObject ?: continue
                o.optStr("stationId")?.let(stationIds::add)
                if (o.optStr("billCode") == expressId) {
                    o.optStr("pickupCode")?.let { code -> return PickupCode(expressId, Platform.SF, code) } // 列表直出
                }
            }
        }
        // 精查兜底：逐驿站 getWaybillInfo（实测无需尾号；他人代查可能 B_100 → 由 v2+尾号兜底）
        for (sid in stationIds) {
            val env = call(cred.token, "POST", PATH_GET_WAYBILL_INFO, buildJsonObject {
                put("billCode", expressId)
                put("stationId", sid)
            })
            val bizCode = env.optStr("code")
            if (bizCode != null && bizCode in WAYBILL_MISS_CODES) continue
            val code = (requireData(env) as? JsonObject)?.optStr("pickupCode")
            if (!code.isNullOrBlank()) return PickupCode(expressId, Platform.SF, code)
        }
        return null
    }

    /** §5.1 字段映射（全部确认）：id/trackingNo=billCode、station=stationName、pickupCode 列表直出 */
    private fun mapExpress(o: JsonObject): ExpressItem {
        val billCode = o.optStr("billCode").orEmpty()
        val state = o.optStr("state")
        val outTime = o.optLong("outTime")
        val status = when {
            state == "wait_out_warehouse" -> ExpressStatus.WAIT_PICKUP // 待取件
            state == "already_out_warehouse" || outTime != null -> ExpressStatus.PICKED // 已出库（辅以 outTime 判定）
            else -> ExpressStatus.UNKNOWN
        }
        return ExpressItem(
            id = billCode,                       // fetchPickupCode 入参
            platform = Platform.SF,
            station = o.optStr("stationName").orEmpty(),
            stationAddress = null,               // 接口不提供（§5.1）
            trackingNo = billCode,
            pickupCode = o.optStr("pickupCode"), // 列表直出，无需二次查询（§5.1）
            summary = null,                      // 接口不提供物品描述（§5.1）
            status = status,
            arrivedAt = o.optLong("inTime"),
            fetchDeadline = null,                // 驿站无最迟取件时间概念（§5.1）
            raw = o.toString(),                  // 原始 JSON，仅调试用
        )
    }

    /**
     * 手机尾号验证查取件码（sf.md §5.2，他人代查场景）：运单不在列表时，
     * POST /e-mini/warehouse/v2/getWaybillPickupCode {billCode, mobileSuffix, stationId}。
     * stationId 必填——用 searchWaybill 列表里出现过的驿站逐个尝试（防遍历设计：尾号错误 → B_100）。
     * 正确尾号 + 待取件 → pickupCode；B_100/101/102 → null。
     */
    suspend fun fetchPickupCodeWithSuffix(cred: Credential, billCode: String, mobileSuffix: String): PickupCode? {
        if (billCode.isBlank() || mobileSuffix.length != 4) return null
        // 收集列表里出现过的 stationId（2 页足够）
        val stationIds = linkedSetOf<String>()
        for (page in 1..2) {
            val env = call(cred.token, "POST", PATH_SEARCH_WAYBILL, searchWaybillBody(page))
            val records = (requireData(env) as? JsonObject)?.optArr("records") ?: break
            for (rec in records) {
                (rec as? JsonObject)?.optStr("stationId")?.let(stationIds::add)
            }
            if (records.isEmpty()) break
        }
        for (sid in stationIds) {
            val env = call(cred.token, "POST", PATH_GET_WAYBILL_PICKUP_CODE_V2, buildJsonObject {
                put("billCode", billCode)
                put("mobileSuffix", mobileSuffix)
                put("stationId", sid)
            })
            val bizCode = env.optStr("code")
            if (bizCode != null && bizCode in WAYBILL_MISS_CODES) continue // 该驿站无此单/尾号不符 → 换下一家
            val data = requireData(env) as? JsonObject
            val code = data?.optStr("pickupCode") ?: data?.optStr("code")
            if (!code.isNullOrBlank()) return PickupCode(billCode, Platform.SF, code)
        }
        return null
    }

    // ============ 网关请求核心（sf.md §9.1 签名 + §1.3 AES 解密） ============

    /**
     * 统一网关调用：签名三件套 + YJY-Auth 请求 → k/v 响应解密 → 返回响应信封 JsonObject。
     * 错误归一（http.js 语义）：
     * - HTTP 401 → NeedRelogin；
     * - HTTP 400 → SignFailed（网关签名/系统时间拦截，http.js 提示「检查系统时间」）；
     * - IO/5xx/其他 HTTP → NetworkError；
     * - 业务失败（success=false）→ requireData 内归一（NeedRelogin / ParseFailed）。
     */
    private suspend fun call(
        token: String,
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        throttle()
        // kotlinx JsonObject 串行化 key 保序；签名与实际传输使用同一 body 字符串
        val bodyStr = body?.toString()
        val headers = SfSigner.signedHeaders(method, token, bodyStr, query)
        val url = "$BASE_URL$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (bodyStr != null) post(bodyStr.toRequestBody(JSON_MEDIA)) else get()
        }.build()
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PlatformError.NetworkError(platform, e)
        }
        resp.use { r ->
            if (r.code == 401) throw PlatformError.NeedRelogin(platform)
            if (r.code == 400) {
                throw PlatformError.SignFailed(platform, "网关 400：请检查系统时间/签名（sf.md §9.2）")
            }
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw PlatformError.NetworkError(platform, e)
            }
            if (!r.isSuccessful) throw PlatformError.NetworkError(platform, IOException("HTTP ${r.code}"))
            if (text.isBlank()) throw PlatformError.ParseFailed(platform, "空响应体")
            val raw = try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw PlatformError.ParseFailed(platform, "响应非 JSON")
            }
            decryptKvEnvelope(raw as? JsonObject)
        }
    }

    /** §1.3：响应可能为 {..., k, v} AES 加密形态 → 解密得真实信封；非加密形态原样返回 */
    private fun decryptKvEnvelope(env: JsonObject?): JsonObject {
        if (env == null) throw PlatformError.ParseFailed(platform, "响应信封非 JSON 对象")
        val k = env.optStr("k") ?: return env
        val v = env.optStr("v") ?: return env
        val plain = try {
            SfCrypto.decryptKv(k, v)
        } catch (_: Exception) {
            throw PlatformError.ParseFailed(platform, "响应 AES 解密失败")
        }
        return try {
            json.parseToJsonElement(plain).jsonObject
        } catch (_: Exception) {
            throw PlatformError.ParseFailed(platform, "解密后响应非 JSON 对象")
        }
    }

    /**
     * 信封校验（http.js 语义：success===true 或 code=="200" 视为成功）→ 返回 data 字段（可空）。
     * 失败：登录态失效码 e-mini_200_B_101 → NeedRelogin；其余业务失败 → ParseFailed（附 code/msg）。
     */
    private fun requireData(env: JsonObject): JsonElement? {
        if (env.optBool("success") == true || env.optStr("code") == "200") {
            return env["data"]?.takeUnless { it is JsonNull }
        }
        val code = env.optStr("code").orEmpty()
        if (code == "e-mini_200_B_101") throw PlatformError.NeedRelogin(platform)
        val msg = env.optStr("msg").orEmpty()
        throw PlatformError.ParseFailed(
            platform,
            if (msg.isEmpty()) "业务失败 $code" else "业务失败 $code $msg",
        )
    }

    /** §9.3.2：相邻业务请求 ≥3s + 随机抖动（静态段缓存后请求频率极低） */
    private suspend fun throttle() = throttleMutex.withLock {
        val gap = MIN_CALL_INTERVAL_MS + Random.nextLong(JITTER_MS)
        val wait = lastCallAt + gap - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCallAt = System.currentTimeMillis()
    }

    companion object {
        const val EPOCH_OFFSET = 1640010000L
        const val ACS = "ACS"

        /** 由时间秒反推校验：本地公式 = 服务端下发的码（三样本实测精确吻合，§4.3） */
        fun build(timeSec: Long, staticSeg: String): String =
            "${timeSec - EPOCH_OFFSET}$ACS$staticSeg"

        // —— 网关与接口常量（sf.md §1.2 / §2.2 / §4.2 / §5） ——
        private const val BASE_URL = "https://ant.yshoufa.com"
        private const val APPID = "wx9aa8425de154698d" // 顺丰速运小程序（yjy-c-wchat，驿收发）
        private const val PATH_LOGIN = "/e-mini/mini/login"
        private const val PATH_LOGIN_USER_INFO = "/e-uc/user/v2/loginUserInfo"
        private const val PATH_CREDENTIAL_PERSON = "/e-mini/mini/credentialPerson"
        private const val PATH_SEARCH_WAYBILL = "/e-mini/mini/searchWaybill"
        private const val PATH_GET_WAYBILL_INFO = "/e-mini/warehouse/getWaybillInfo"
        private const val PATH_GET_WAYBILL_PICKUP_CODE_V2 = "/e-mini/warehouse/v2/getWaybillPickupCode"

        private const val PAGE_SIZE = 10
        private const val PICKUP_PROBE_PAGES = 5        // 取件码查找最多翻 5 页（50 条）
        private const val REFRESH_PERIOD_MS = 180_000L  // §4.2 身份码刷新周期 3 分钟
        private const val MIN_CALL_INTERVAL_MS = 3_000L // §9.3.2 最小请求间隔
        private const val JITTER_MS = 1_000L            // §9.3.2 随机抖动上限

        private val JSON_MEDIA = "application/json;charset=UTF-8".toMediaType()

        /** §5.2 业务码：查询不到取件码 / 已取走 / 运单号有误 → fetchPickupCode 返回 null */
        private val WAYBILL_MISS_CODES = setOf(
            "e-mini_1000_B_100",
            "e-mini_1000_B_101",
            "e-mini_1000_B_102",
        )
    }
}

// ============ kotlinx JSON 安全取值（缺字段/类型不符一律 null，不抛） ============

private fun JsonObject.optStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.optLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.optBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.optArr(key: String): JsonArray? =
    this[key] as? JsonArray
