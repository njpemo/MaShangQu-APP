package com.aggapp.platform.taobao

import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 淘宝（菜鸟驿站）适配器 —— 协议真实实现（docs/platform/taobao/taobao.md v1.1）。
 *
 * 登录（§3.4 纯协议短信登录，参考 taobao/js/protocol_login.js）：
 *   GET havanaone/login/login.htm（取 _csrf + 种 Cookie）→ POST sendSms.do（拿 smsToken）
 *   → POST sms/login.do → Set-Cookie 落全套登录态（含 httpOnly cookie2/unb/skt）。
 *   ua / umidToken 传空串即可，无 baxia 反爬；send 与 login 必须同会话（本单例内存暂存会话态）。
 *
 * mtop 直调（§5.2/§5.4/§7，参考 taobao/js/fetch_idcode.js）：
 *   sign = MD5(token & t & appKey & data)，token 取 _m_h5_tk 下划线前 32 位，appKey=12574478；
 *   首调 FAIL_SYS_TOKEN_EMPTY 换网关下发的新 token 重试一次（两段式）。
 *   Node 直调带全量 Cookie（含 httpOnly）+ Referer 已验证全链路可行；Android OkHttp 可直接读取
 *   httpOnly Cookie（Node/WebView 读不到的限制不存在），登录态存于 Credential.token 原样携带。
 *   若线上报 FAIL_SYS_ILLEGAL_ACCESS（网关要求页面上下文 lib.mtop），需降级 WebView 注入方案（§5.4.1）。
 */
@Singleton
class TaobaoAdapter @Inject constructor() : PlatformAdapter {
    override val platform = Platform.TAOBAO
    override val loginMode = LoginMode.PHONE_CODE

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mtop = TaobaoMtopClient(http)

    /** 短信登录会话（_csrf + smsToken + 会话 Cookie），sendSmsCode 建立后由 loginWithSmsCode 消费 */
    @Volatile
    private var loginSession: TaobaoLoginClient? = null

    /** 发送短信验证码：§3.4 步骤 1+2（GET login.htm 取 _csrf → POST sendSms.do），会话态暂存单例内存 */
    override suspend fun sendSmsCode(phone: String) {
        val client = TaobaoLoginClient(http)
        client.openLoginPage()
        client.sendSms(phone)
        loginSession = client
    }

    /** 短信验证码登录：§3.4 步骤 3（POST sms/login.do）。
     *  全部 Set-Cookie（含 httpOnly）拼成 Cookie 串存 Credential.token；
     *  extra 落 unb / _m_h5_tk / tracknick / mobile（§3.3 字段表）。 */
    override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential {
        val session = loginSession
            ?: throw PlatformError.ParseFailed(Platform.TAOBAO, "请先调用 sendSmsCode 发送验证码")
        val cookies = session.login(phone, smsCode)
        loginSession = null
        return Credential(
            platform = platform,
            token = cookies.header(), // 全量 Cookie 原样保存，mtop 直调必须带齐（§9.3）
            extra = mapOf(
                "unb" to (cookies["unb"] ?: ""),
                "_m_h5_tk" to (cookies["_m_h5_tk"] ?: ""), // 登录时可能尚未下发，mtop 两段式会自动补
                "tracknick" to (cookies["tracknick"] ?: ""), // URL 编码昵称，展示用
                "mobile" to phone,
            ),
            capturedAt = System.currentTimeMillis(),
        )
    }

    /** WebView 登录兜底入口（loginMode 实际为 PHONE_CODE，协议登录优先） */
    override fun loginUrl(): String = "https://login.m.taobao.com/login.htm"

    /** WebView 兜底登录判定（§3.3 幂等规则）：cookie2 与 unb 同时非空 → 登录成功 */
    override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? {
        val cookie2 = cookies["cookie2"]
        val unb = cookies["unb"]
        if (cookie2.isNullOrBlank() || unb.isNullOrBlank()) return null
        return Credential(
            platform = platform,
            token = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            extra = mapOf(
                "unb" to unb,
                "_m_h5_tk" to (cookies["_m_h5_tk"] ?: ""),
                "tracknick" to (cookies["tracknick"] ?: ""),
            ),
            capturedAt = System.currentTimeMillis(),
        )
    }

    /** 登录态校验（§4/§5.3）：mtop search.getTimeStamp 轻量探测，needLogin=true 下会话失效即
     *  FAIL_SYS_SESSION_EXPIRED → NeedRelogin → false；网络/解析类错误按 §4.4 原样抛给上层。 */
    override suspend fun checkAlive(cred: Credential): Boolean {
        return try {
            mtop.request(cred, API_ALIVE_PROBE, "1.0", "{}")
            true
        } catch (e: PlatformError.NeedRelogin) {
            false
        }
    }

    /** 身份码（§5.2 直调 mtop）：mtop.cainiao.pickup.identity.getIdentityMessage（v=1.0，data={}）。
     *  返回 XNST 前缀动态码（每次调用不同、无静态段），码内容为字符串 → 本地 ZXing 渲染 QR；
     *  响应无显式时效字段，按页面行为取 180s 级刷新周期。 */
    override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
        val json = mtop.request(cred, API_IDENTITY_MESSAGE, "1.0", "{}")
        // 响应结构（§5.2 脱敏样例）：data.data.{identityCode, accountName, phone}
        val content = (((json["data"] as? JsonObject)?.get("data") as? JsonObject)
            ?.get("identityCode") as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw PlatformError.ParseFailed(Platform.TAOBAO, "身份码响应缺 identityCode 字段")
        return IdentityCode(
            platform = platform,
            type = CodeType.QR,
            content = content,
            expireAt = System.currentTimeMillis() + IDENTITY_CODE_TTL_MILLIS,
        )
    }

    /**
     * 快递列表（taobao.md §6 实测）：mtop.cainiao.pickup.search.queryMultiStationPackages
     * data {stationType:"XY", pageNo, pageSize}；响应按站点聚合。**2026-09-27 实测真实结构**：
     * stationPackageAssembleList[].stationMessageDTO（站点信息）
     *   + groupList[].certainPickupWaybillList[]{waybillNo, pickupCode, stationId, upCpCode,
     *     receiverName, privacyNumber, collectionTime, pickedWaybill, status, ...}
     * （实测样例：运单号 79144370359463 直出 pickupCode "22-5-7603"）。
     */
    override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> {
        val json = mtop.request(
            cred,
            API_QUERY_MULTI_STATION_PACKAGES,
            "1.0",
            """{"stationType":"XY","pageNo":$page,"pageSize":$PAGE_SIZE}""",
        )
        val data = json["data"] as? JsonObject ?: return emptyList()
        val assemble = (data["data"] as? JsonObject)?.get("stationPackageAssembleList") as? JsonArray
            ?: return emptyList()
        val out = mutableListOf<ExpressItem>()
        for (stationEl in assemble) {
            val station = stationEl as? JsonObject ?: continue
            val meta = station["stationMessageDTO"] as? JsonObject ?: JsonObject(emptyMap())
            val stationName = meta.str("stationName") ?: meta.str("name") ?: "菜鸟驿站"
            val stationAddress = meta.str("stationAddress") ?: meta.str("address")
            for (pkgEl in collectPackages(station)) {
                val pkg = pkgEl as? JsonObject ?: continue
                val trackingNo = pkg.str("waybillNo") ?: continue
                val picked = pkg.str("pickedWaybill") == "true"
                out += ExpressItem(
                    id = trackingNo, // 菜鸟列表以运单号为主键（fetchPickupCode 入参）
                    platform = Platform.TAOBAO,
                    station = pkg.str("stationName") ?: stationName,
                    stationAddress = stationAddress,
                    trackingNo = trackingNo,
                    pickupCode = pkg.str("pickupCode"),
                    summary = pkg.str("upCpCode")?.let { "承运：$it" },
                    status = if (picked) ExpressStatus.PICKED else ExpressStatus.WAIT_PICKUP,
                    arrivedAt = pkg.str("collectionTime")?.parseTime(),
                    fetchDeadline = null,
                    raw = pkg.toString(),
                )
            }
        }
        return out
    }

    /** 收集站点下的包裹（真实路径 groupList[].certainPickupWaybillList[]；兼容旧候选键） */
    private fun collectPackages(station: JsonObject): List<JsonElement> {
        val groups = station["groupList"] as? JsonArray
        if (groups != null) {
            val out = mutableListOf<JsonElement>()
            for (g in groups) {
                val list = (g as? JsonObject)?.get("certainPickupWaybillList") as? JsonArray
                if (list != null) out.addAll(list)
            }
            return out
        }
        return PKG_LIST_KEYS.firstNotNullOfOrNull { key ->
            (station[key] as? JsonArray)?.takeIf { it.isNotEmpty() }
        } ?: emptyList<JsonElement>()
    }

    /** "2026-09-27 16:45:14" → epoch 毫秒（本地时区）；解析失败返回 null */
    private fun String.parseTime(): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(this)?.time
    } catch (_: Exception) {
        null
    }

    /**
     * 取件码（taobao.md §6/§6.3 v1.3 实测链路）：
     * ① 列表直出：queryMultiStationPackages 主路径带完整 pickupCode，命中即返回、无需额外请求；
     * ② 按运单号查兜底：multiOneSearch（实测关键字段 searchValue）确认包裹在站待取
     *    （status="3"；"4"=已取不出列表；查无此单走业务码）→ 再调①列表拿完整码。
     *    ⚠️ multiOneSearch 响应的 pickupCode 脱敏为 ***，绝不可直接采用，必须回列表获取。
     */
    override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? {
        if (expressId.isBlank()) return null
        // ① 列表内查找（直出完整 pickupCode）
        listPickupCode(cred, expressId)?.let { return it }
        // ② 按运单号查（multiOneSearch，§6.3 实测入参）：在站待取才回列表补码
        return try {
            val ts = System.currentTimeMillis()
            val data = """{"searchValue":"$expressId","stationType":"XN","source":"DEFAULT",""" +
                """"traceId":"$ts-${randomTrace()}","stationCode":"POINT_0",""" +
                """"searchKey":"ST_${ts}_POINT_0_${(0..999).random()}"}"""
            val json = mtop.request(cred, API_MULTI_ONE_SEARCH, "1.0", data)
            // 实测响应路径 data.data.pkgResultResps[].certainPickupWaybillList[]
            val pkgs = (((json["data"] as? JsonObject)?.get("data") as? JsonObject)
                ?.get("pkgResultResps") as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("certainPickupWaybillList") as? JsonArray) }
                .flatten()
                .mapNotNull { it as? JsonObject }
            if (pkgs.none { it.str("status") == STATUS_WAIT_PICKUP }) return null // 已取/查无 → 列表必无
            listPickupCode(cred, expressId)
        } catch (e: PlatformError.ParseFailed) {
            null // waybill-5047 等业务码 → 查不到
        }
    }

    /** 列表内按运单号查找完整取件码（queryMultiStationPackages 翻页；脱敏 *** 视为无码） */
    private suspend fun listPickupCode(cred: Credential, expressId: String): PickupCode? {
        for (page in 1..PICKUP_PROBE_PAGES) {
            val json = mtop.request(
                cred,
                API_QUERY_MULTI_STATION_PACKAGES,
                "1.0",
                """{"stationType":"XY","pageNo":$page,"pageSize":$PAGE_SIZE}""",
            )
            val assemble = (((json["data"] as? JsonObject)?.get("data") as? JsonObject)
                ?.get("stationPackageAssembleList")) as? JsonArray ?: break
            if (assemble.isEmpty()) break
            for (stationEl in assemble) {
                val station = stationEl as? JsonObject ?: continue
                for (pkgEl in collectPackages(station)) {
                    val pkg = pkgEl as? JsonObject ?: continue
                    if (pkg.str("waybillNo") == expressId) {
                        val code = pkg.str("pickupCode")
                        if (code != null && code != MASKED_PICKUP_CODE) {
                            return PickupCode(expressId, Platform.TAOBAO, code)
                        }
                    }
                }
            }
        }
        return null
    }

    /** multiOneSearch traceId 随机段（Node 实测格式 {ts}-{随机小写串}） */
    private fun randomTrace(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String): Long? =
        str(key)?.toLongOrNull() ?: (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    private companion object {
        /** §5.2 身份码接口 */
        private const val API_IDENTITY_MESSAGE = "mtop.cainiao.pickup.identity.getIdentityMessage"

        /** §6 快递列表（实测 SUCCESS）：按站点聚合 */
        private const val API_QUERY_MULTI_STATION_PACKAGES =
            "mtop.cainiao.pickup.search.queryMultiStationPackages"

        /** §6.3 按运单号查/添加（2026-09-29 实测 SUCCESS，关键字段 searchValue） */
        private const val API_MULTI_ONE_SEARCH = "mtop.cainiao.pickup.search.multiOneSearch"

        /** status 枚举（§6.3 双样本实测）："3"=待取件（在站） */
        private const val STATUS_WAIT_PICKUP = "3"

        /** multiOneSearch 响应取件码脱敏标记（***），不可直接采用 */
        private const val MASKED_PICKUP_CODE = "***"

        /** 快递列表每页条数 / 取件码查找最多翻页数 */
        private const val PAGE_SIZE = 10
        private const val PICKUP_PROBE_PAGES = 3

        /** 包裹数组候选键（collectPackages 仍在用） */
        private val PKG_LIST_KEYS = arrayOf(
            "packageList", "packages", "pkgList", "waybillList", "cabinetPackages",
            "packageDTOList", "mailNoList", "list",
        )

        /** §5.3 checkAlive 候选：服务器时间戳（同页面实测 SUCCESS） */
        private const val API_ALIVE_PROBE = "mtop.cainiao.pickup.search.getTimeStamp"

        /** §5.2：动态码按 180s 级周期缓存 */
        private const val IDENTITY_CODE_TTL_MILLIS = 180_000L
    }
}
