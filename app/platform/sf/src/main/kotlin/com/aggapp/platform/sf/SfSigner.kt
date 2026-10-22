package com.aggapp.platform.sf

import java.security.MessageDigest
import java.util.UUID

/**
 * 驿收发网关签名器（sf.md §9.1，逐行移植自小程序 utils/sign.js，并经 yshoufa-client.js 实测 100% 吻合）。
 *
 * 请求头三件套 + 签名（sf.md §9.1）：
 * ```
 * X-Ca-Sign-Type: MD5
 * X-Ca-Nonce: <UUID v4>
 * X-Ca-Timestamp: <毫秒时间戳>
 * X-Ca-Signature: <MD5 签名>
 * YJY-Auth: <JWT；登录接口传空串（空值不参与签名，但请求头照发）>
 * ```
 *
 * 签名串构造（sf.md §9.1 / utils/sign.js）：
 * 1. 参与字段：METHOD / X-Ca-Sign-Type / X-Ca-Nonce / X-Ca-Timestamp / YJY-Auth；
 *    POST 追加 `BODY = MD5(body原文).lowercase()`；GET 的 query 参数平铺进参与字段（sign.js: Object.assign）；
 * 2. 过滤 null/空串值，按 key 字典序排序拼 k=v（TreeMap 天然等价于 Object.keys(u).sort()，key 均为 ASCII）；
 * 3. 末尾追加 `key=<secretKey>`；
 * 4. 用 & 连接后取 MD5 小写。
 */
internal object SfSigner {

    /** 生产环境 secretKey（源自解包 utils/env.js，yshoufa-client.js 同值实测通过）。
     *  TODO(sf.md §9.3.4)：后续由构建期注入 / YYB-GO 下发，避免硬编码入库 */
    private const val SECRET_KEY = "VMnINXNTHW9ij3Il"

    const val HEADER_AUTH = "YJY-Auth"
    const val HEADER_SIGN_TYPE = "X-Ca-Sign-Type"
    const val HEADER_NONCE = "X-Ca-Nonce"
    const val HEADER_TIMESTAMP = "X-Ca-Timestamp"
    const val HEADER_SIGNATURE = "X-Ca-Signature"

    /** MD5 小写（与小程序 utils/md5.js 一致） */
    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .toHexLower()

    /**
     * 生成带签名的请求头（对应 yshoufa-client.js 的 signHeaders）。
     *
     * @param method HTTP 方法（GET/POST，统一转大写）
     * @param token  YJY-Auth JWT；登录接口传空串
     * @param body   请求体原文（实际发送的 JSON 串）；GET 传 null（无 BODY 参与签名）
     * @param query  GET query 参数（平铺进参与字段，sf.md §9.1）；POST 场景留空
     */
    fun signedHeaders(
        method: String,
        token: String,
        body: String?,
        query: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val nonce = UUID.randomUUID().toString() // UUID v4，小写带连字符，与 crypto.randomUUID 一致
        val ts = System.currentTimeMillis()
        // TreeMap 按 key 字典序排序 == sign.js 的 Object.keys(u).sort()
        val fields = sortedMapOf(
            "METHOD" to method.uppercase(),
            "X-Ca-Sign-Type" to "MD5",
            "X-Ca-Nonce" to nonce,
            "X-Ca-Timestamp" to ts.toString(),
            "YJY-Auth" to token,
        )
        query.forEach { (k, v) -> fields[k] = v } // GET query 参数平铺参与签名（§9.1）
        if (!body.isNullOrEmpty()) fields["BODY"] = md5Hex(body) // body 原文的 MD5 参与签名
        // 签名串：k=v 按序 & 连接，末尾追加 key=secretKey（sign.js 过滤 null/空串值）
        val toSign = buildString {
            fields.forEach { (k, v) -> if (v.isNotEmpty()) append(k).append('=').append(v).append('&') }
            append("key=").append(SECRET_KEY)
        }
        val signature = md5Hex(toSign)
        return mapOf(
            HEADER_SIGN_TYPE to "MD5",
            HEADER_NONCE to nonce,
            HEADER_TIMESTAMP to ts.toString(),
            HEADER_SIGNATURE to signature,
            HEADER_AUTH to token,
        )
    }

    /** 字节转小写十六进制（手写避免 Locale/format 差异） */
    private fun ByteArray.toHexLower(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xFF
            sb.append(hex[i ushr 4]).append(hex[i and 0x0F])
        }
        return sb.toString()
    }
}
