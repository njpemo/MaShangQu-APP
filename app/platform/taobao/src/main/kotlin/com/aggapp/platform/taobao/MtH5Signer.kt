package com.aggapp.platform.taobao

import java.security.MessageDigest

/**
 * MTOP H5 签名器（docs/platform/taobao/taobao.md §7）。
 *
 * 公式（已实测确认，test_live.js 验证通过）：
 *   sign = MD5(token + "&" + t + "&" + appKey + "&" + data) → 32 位小写 hex
 *   token = Cookie "_m_h5_tk" 值下划线前的 32 位 hex（如 "abc..._1700000000000" 取 "abc..."）
 *   appKey 固定 12574478（lib-mtop 源码 + 线上请求双确认，§7）
 */
internal object MtH5Signer {

    /** H5 网关公共 appKey（§7 已实测固定值） */
    const val APP_KEY = "12574478"

    /** MD5 → 32 位小写 hex（单测：MtH5SignerTest） */
    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** H5 签名（§7 公式，字段拼接顺序固定为 token & t & appKey & data） */
    fun sign(token: String, t: String, appKey: String, data: String): String =
        md5Hex("$token&$t&$appKey&$data")

    /** 从 _m_h5_tk cookie 值取签名 token（下划线前段；空/缺失返回空串，走两段式刷新） */
    fun extractToken(mh5TkCookieValue: String?): String =
        mh5TkCookieValue.orEmpty().substringBefore('_')
}
