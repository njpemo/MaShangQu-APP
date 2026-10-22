package com.aggapp.platform.ryll

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 如意邻里（圆通）适配器 —— 独立短信登录链路（docs/platform/ryll/README.md §3.2/§4.2；
 * Node ryll/js/ryll_login.js 全链实测，走 RyllClient）：
 *
 *   图形码 vcode → 发短信 loginsendsms（需 4 位图形码）→ login（flag=2）拿 jwt-token
 *   → getPinCode 取静态 PIN（Code128 图，zxing 解码一次后永久缓存 extra["pin"]，本地出码零请求）
 *
 * 登录流程比淘宝/顺丰多一步图形验证码，走 adapter 特有方法（UI 层 cast 调用，京东同款）：
 *   [fetchVcode] → [sendSms] → [loginWithSms]
 */
@Singleton
class RyllAdapter @Inject constructor(
    private val pinDecoder: PinDecoder,
) : PlatformAdapter {

    override val platform = Platform.RYLL

    /** 短信验证码登录（图形码 + 短信码，README §3.2） */
    override val loginMode = LoginMode.PHONE_CODE

    private val client = RyllClient(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    /** 登录中间态（VM 单例生命周期内有效，与淘宝 smsPhone 同模式） */
    private var pendingPhone = ""
    private var pendingRandValue = ""

    // ============ 登录（README §3.2 三步，adapter 特有方法） ============

    /** 步骤①：取图形验证码（UI 显示 base64 PNG，用户输 4 位图形码） */
    suspend fun fetchVcode(): RyllVcode {
        val v = client.vcode()
        pendingRandValue = v.randValue
        return v
    }

    /** 步骤②：图形码 + 手机号发短信（图形码错误会抛 ParseFailed，UI 提示后重新取码重试） */
    suspend fun sendSms(phone: String, captchaCode: String) {
        if (phone.length != 11) throw PlatformError.ParseFailed(platform, "手机号须 11 位")
        if (captchaCode.isBlank()) throw PlatformError.ParseFailed(platform, "请输入图形验证码")
        if (pendingRandValue.isBlank()) throw PlatformError.ParseFailed(platform, "请先获取图形验证码")
        pendingPhone = phone
        client.sendSms(phone, pendingRandValue, captchaCode)
    }

    /**
     * 步骤③：短信码登录 → jwt-token 凭据。
     * 登录成功立即取静态 PIN（README §4.2：一次获取、永久缓存）；失败不阻断登录
     * （fetchIdentityCode 会按 token 重试取码）。
     */
    suspend fun loginWithSms(smsCode: String): Credential {
        if (pendingPhone.isEmpty()) throw PlatformError.ParseFailed(platform, "请先获取验证码")
        val token = client.login(pendingPhone, smsCode)
        val cred = Credential(
            platform,
            token,
            buildMap {
                put("mobile", pendingPhone)
                try {
                    pin(token)?.let { put("pin", it) }
                } catch (_: Exception) {
                }
            },
            System.currentTimeMillis(),
        )
        pendingPhone = ""
        pendingRandValue = ""
        return cred
    }

    // ---- PlatformAdapter 契约：RYLL 登录不走通用 sendSmsCode/loginWithSmsCode 形状 ----

    override suspend fun sendSmsCode(phone: String) {
        throw PlatformError.ParseFailed(platform, "如意邻里需图形验证码，请使用专属登录表单")
    }

    override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential {
        throw PlatformError.ParseFailed(platform, "如意邻里需图形验证码，请使用专属登录表单")
    }

    override fun loginUrl(): String = ""

    override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? = null

    // ============ 登录态校验 ============

    override suspend fun checkAlive(cred: Credential): Boolean {
        // 静态 PIN 与账号永久绑定（README §4.2）→ 本地判活零请求；无 PIN 才轻量探测
        if (!cred.extra["pin"].isNullOrBlank()) return true
        if (cred.token.isBlank()) return false
        return client.checkAlive(cred.token)
    }

    // ============ 身份码（非纯静态：每次调用重新向服务端 getPinCode 拉取，拉取失败回退本地缓存） ============

    override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
        // ① 有 token → 服务端实时拉取（2026-10-08：用户确认非纯静态码，需每次刷新）
        if (cred.token.isNotBlank()) {
            try {
                pin(cred.token)?.let { return localCode(it) }
            } catch (e: PlatformError) {
                throw e // token 失效等平台错误直接上抛，不回退旧码
            } catch (e: Exception) {
                e.printStackTrace() // 网络/解码失败 → 回退本地缓存
            }
        }
        // ② PIN 已缓存 → 本地兜底出码
        cred.extra["pin"]?.takeIf { it.isNotBlank() }?.let { return localCode(it) }
        // ③ 无 token 且无缓存 → 让上层走 Mock/登录引导
        throw PlatformError.NeedRelogin(platform)
    }

    /** token → getPinCode 条码图 → 解码静态 PIN */
    private suspend fun pin(token: String): String? {
        val dataUrl = client.fetchPinPng(token)
        return pinDecoder.decode(dataUrl)
    }

    private fun localCode(pin: String) = IdentityCode(
        platform = platform,
        type = CodeType.BARCODE, // Code128 一维条码，IdCodeScreen 统一渲染
        content = pin,
        expireAt = null,         // 静态码永不过期
    )

    // ============ 快递列表（README §5.1：驿站 + 柜机双数据源，防御式解析） ============

    override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> {
        if (cred.token.isBlank()) throw PlatformError.NeedRelogin(platform)
        val items = LinkedHashMap<String, ExpressItem>()
        // 驿站在架（tab0 主数据源；分页字段服务端未证实，一次全量取回）
        runCatching {
            val data = client.waitAllOrderList(cred.token, flag = 1)
            data.optArr("waitTakeOrderResponseList")?.forEach { el ->
                (el as? JsonObject)?.let { mapExpress(it, ExpressStatus.WAIT_PICKUP) }
                    ?.let { if (it.id.isNotBlank()) items[it.id] = it }
            }
        }
        // 柜机/货架包裹（queryCabinetByPhone，README §5.2 shelfPackageList）
        runCatching {
            val data = client.queryCabinetByPhone(cred.token)
            for (key in listOf("waitTakeOrderResponseList", "takeOrderResponseList")) {
                data.optArr(key)?.forEach { el ->
                    (el as? JsonObject)?.let { o ->
                        mapExpress(o, if (key.startsWith("wait")) ExpressStatus.WAIT_PICKUP else ExpressStatus.PICKED)
                    }?.let { if (it.id.isNotBlank()) items[it.id] = it }
                }
            }
        }
        return items.values.toList()
    }

    /**
     * 字段名服务端未在解包产物中固化（README §8：防御式解析），多候选键兜底：
     * 运单号 waybillNo/mailNo/wayBillCode/orderNo；驿站 stationName/stationTitle；
     * 取件码 pickupCode/takeCode/取件码；入仓时间 inTime/createTime/inputTime。
     */
    private fun mapExpress(o: JsonObject, status: ExpressStatus): ExpressItem? {
        val no = o.firstStr("waybillNo", "mailNo", "wayBillCode", "expressNo", "orderNo") ?: return null
        return ExpressItem(
            id = no,
            platform = Platform.RYLL,
            station = o.firstStr("stationName", "stationTitle", "station") ?: "",
            stationAddress = o.firstStr("stationAddress", "address"),
            trackingNo = no,
            pickupCode = o.firstStr("pickupCode", "takeCode", "pickCode"),
            summary = o.firstStr("productName", "goodsName", "remark"),
            status = status,
            arrivedAt = o.firstLong("inTime", "createTime", "inputTime"),
            fetchDeadline = null,
            raw = o.toString(),
        )
    }

    // ============ 取件码（列表直出 → 查件详情兜底） ============

    /** 查件详情（orderDetailByWayBillNo 实测打通，visitor 单也可查；null=查无此单） */
    suspend fun queryOrderDetail(trackingNo: String): RyllOrderDetail? =
        client.queryOrderDetail(trackingNo.trim().uppercase())

    override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? {
        // ① 列表内直接取（station 网关未打通时为空，不阻断）
        val fromList = runCatching {
            fetchExpressList(cred, 1).firstOrNull { it.id == expressId }?.pickupCode
        }.getOrNull()
        if (fromList != null) return PickupCode(expressId, Platform.RYLL, fromList)
        // ② 查件详情兜底：已到驿站且 owner 单带取件码才返回（visitor 单无码，UI 走轨迹卡片）
        val detail = client.queryOrderDetail(expressId) ?: return null
        return detail.pickupCode
            ?.takeIf { it.isNotBlank() }
            ?.let { PickupCode(expressId, Platform.RYLL, it) }
    }

    companion object {
        private fun JsonObject.firstStr(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

        private fun JsonObject.firstLong(vararg keys: String): Long? =
            keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.longOrNull }
    }
}
