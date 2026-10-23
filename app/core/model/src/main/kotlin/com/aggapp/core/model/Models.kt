package com.aggapp.core.model

/** 平台枚举（README §4.3 逐字对齐） */
enum class Platform(val label: String) {
    PDD("拼多多"), TAOBAO("淘宝"), JD("京东"), SF("顺丰"), RYLL("如意邻里")
}

/** 平台登录凭据（落盘前由 CredentialStore 加密） */
data class Credential(
    val platform: Platform,
    val token: String,                            // Cookie 串或 Token 主体
    val extra: Map<String, String> = emptyMap(),  // 辅助字段：userId、设备号、h5_tk 等
    val capturedAt: Long,
)

/** 身份码 */
data class IdentityCode(
    val platform: Platform,
    val type: CodeType,          // QR / BARCODE / IMAGE_URL
    val content: String,         // 码内容；IMAGE_URL 时为图片地址
    val expireAt: Long? = null,  // 有效期，可空
)

enum class CodeType { QR, BARCODE, IMAGE_URL }

/** 快递条目（列表卡片数据源） */
data class ExpressItem(
    val id: String,              // 平台内唯一 ID（fetchPickupCode 入参）
    val platform: Platform,
    val station: String,         // 站点/驿站名
    val stationAddress: String? = null,
    val trackingNo: String? = null,
    val pickupCode: String? = null,   // 列表直接携带的取件码，可空
    val pickupCodeSource: Platform? = null, // 取件码来源渠道（跨平台互补查询命中时记录；列表直出为 null）
    val summary: String? = null,      // 物品/商家描述
    val status: ExpressStatus = ExpressStatus.WAIT_PICKUP,
    val arrivedAt: Long? = null,
    val fetchDeadline: Long? = null,  // 最迟取件时间
    val raw: String? = null,          // 原始 JSON，仅调试用，UI 不读
)

enum class ExpressStatus { WAIT_PICKUP, PICKED, TIMEOUT, UNKNOWN }

/** 取件码详情 */
data class PickupCode(
    val expressId: String,
    val platform: Platform,
    val code: String,
    val qrContent: String? = null,   // 若支持扫码取件
)

/** 平台登录态（「我的」页数据源） */
data class PlatformLoginState(
    val platform: Platform,
    val loggedIn: Boolean,
    val needRelogin: Boolean = false,
    val lastCheckAt: Long? = null,
)

// ============ §4.4 统一错误模型 ============

sealed class PlatformError(message: String) : Exception(message) {
    class NeedRelogin(val platform: Platform) : PlatformError("登录态失效")   // → UI 标记需重登
    class NeedActivate(val platform: Platform, val detail: String) : PlatformError("需激活：$detail") // → UI 弹引导（如淘宝/菜鸟档案未建立）
    class SignFailed(val platform: Platform, val detail: String) : PlatformError("签名失败：$detail")
    class RateLimited(val platform: Platform) : PlatformError("请求过于频繁")
    class ParseFailed(val platform: Platform, val detail: String) : PlatformError("解析失败：$detail")
    class NetworkError(val platform: Platform, override val cause: Throwable) :
        PlatformError("网络错误：${cause.message}")
}
