package com.aggapp.platform.taobao

import com.aggapp.core.model.Credential
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * MTOP H5 网关直调客户端（docs/platform/taobao/taobao.md §5.2/§5.4/§7，逐请求移植 taobao/js/fetch_idcode.js）。
 *
 * 请求形态（§5.4 Node 直调已验证全链路通过）：
 *   GET https://h5api.m.taobao.com/h5/{api小写}/{v小写}/
 *       ?jsv=2.6.1&appKey=12574478&t&sign&api&v&needLogin=true&type=originaljson&dataType=json&data={urlencoded}
 *   sign = MD5(token & t & appKey & data)（MtH5Signer，§7）
 *   头部：全量 Cookie（含 httpOnly）+ Referer: https://pages-fast.m.taobao.com/ + UA
 *
 * 两段式 token（§5.4.3 / §7 刷新机制）：
 *   首调可能 FAIL_SYS_TOKEN_EMPTY（或 EXOIRED），网关经 Set-Cookie 下发新 _m_h5_tk；
 *   用新 token 重算 sign 重试一次（最多重试 1 次）。
 *
 * 降级预案（§5.4.1）：若线上直调报 FAIL_SYS_ILLEGAL_ACCESS（网关要求页面上下文
 * window.lib.mtop.H5Request），需降级到 WebView 注入方案；当前按文档结论优先直调版本。
 */
internal class TaobaoMtopClient(private val http: OkHttpClient) {

    /** _m_h5_tk 最新值缓存：key = Credential.token 哈希（登录态更换自动失效重取），value = (tk, tkEnc) */
    private val tkCache = ConcurrentHashMap<Int, Pair<String, String?>>()

    /**
     * mtop 标准调用：ret[0] 以 SUCCESS 开头返回完整响应 JSON；失败按 §4.4 归一：
     * TOKEN_*（重试一次后仍失败）与 SESSION_EXPIRED → NeedRelogin；LIMIT → RateLimited；
     * 其余 FAIL_* → ParseFailed；HTTP/网络层失败 → NetworkError。
     */
    suspend fun request(cred: Credential, api: String, version: String, data: String): JsonObject {
        val cacheKey = cred.token.hashCode()
        var retriedToken = false
        while (true) {
            val cachedTk: String? = tkCache[cacheKey]?.first
            // token 优先取内存缓存的新 _m_h5_tk，其次回退 Credential.token 里的旧值（两段式刷新来源）
            val token: String = MtH5Signer.extractToken(
                cachedTk ?: TaobaoCookieStore.parse(cred.token)["_m_h5_tk"],
            )
            val t = System.currentTimeMillis().toString()
            val sign = MtH5Signer.sign(token, t, MtH5Signer.APP_KEY, data)

            // URL 参数顺序对齐 fetch_idcode.js：jsv/appKey/t/sign/api/v/needLogin/type/dataType/data
            val url = buildString {
                append(GATEWAY).append(api.lowercase()).append('/').append(version.lowercase()).append('/')
                append("?jsv=2.6.1&appKey=").append(MtH5Signer.APP_KEY)
                append("&t=").append(t).append("&sign=").append(sign)
                append("&api=").append(encode(api)).append("&v=").append(encode(version))
                append("&needLogin=true&type=originaljson&dataType=json&data=").append(encode(data))
            }

            val resp = taobaoGet(http, url, cookieHeader(cred, tkCache[cacheKey]), MTOP_REFERER)
            if (resp.code !in 200..299) {
                throw PlatformError.NetworkError(Platform.TAOBAO, IOException("mtop 网关 HTTP ${resp.code}"))
            }

            // §7：token 空/过期时网关 Set-Cookie 下发新 _m_h5_tk，先落缓存再判定 ret
            val newTk = setCookieValue(resp.setCookies, "_m_h5_tk")
            val newTkEnc = setCookieValue(resp.setCookies, "_m_h5_tk_enc")
            if (newTk != null) tkCache[cacheKey] = newTk to newTkEnc

            val json = try {
                Json.parseToJsonElement(resp.body).jsonObject
            } catch (e: Exception) {
                throw PlatformError.ParseFailed(Platform.TAOBAO, "mtop 响应非 JSON：${resp.body.take(200)}")
            }
            val ret = (json["ret"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonPrimitive }?.content
                .orEmpty()

            return when {
                ret.startsWith("SUCCESS") -> json
                // 两段式刷新（§7）：换新 token 重试一次；网关未下发新 tk 则清缓存回退 Cookie 串
                "TOKEN_EMPTY" in ret || "TOKEN_EXPIRED" in ret || "TOKEN_EXOIRED" in ret -> {
                    if (retriedToken) throw PlatformError.NeedRelogin(Platform.TAOBAO)
                    retriedToken = true
                    if (newTk == null) tkCache.remove(cacheKey)
                    continue
                }
                "SESSION_EXPIRED" in ret -> throw PlatformError.NeedRelogin(Platform.TAOBAO)
                "LIMIT" in ret -> throw PlatformError.RateLimited(Platform.TAOBAO)
                "FAIL_SYS_ILLEGAL_ACCESS" in ret -> throw PlatformError.ParseFailed(
                    Platform.TAOBAO,
                    "$ret（网关拒绝直调，需降级 WebView 注入 lib.mtop 方案，见文档 §5.4.1）",
                )
                else -> throw PlatformError.ParseFailed(Platform.TAOBAO, "mtop 失败：$ret")
            }
        }
    }

    /** Cookie 头 = Credential.token 全量 Cookie（含 httpOnly），叠加内存中最新的 _m_h5_tk/_m_h5_tk_enc */
    private fun cookieHeader(cred: Credential, cachedTk: Pair<String, String?>?): String {
        val store = TaobaoCookieStore.parse(cred.token)
        cachedTk?.let { (tk, enc) ->
            if (tk.isNotEmpty()) store["_m_h5_tk"] = tk
            enc?.let { store["_m_h5_tk_enc"] = it }
        }
        return store.header()
    }

    private fun encode(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    /** 从 Set-Cookie 头列表中取指定 cookie 的值（只看首对 k=v） */
    private fun setCookieValue(lines: List<String>, name: String): String? =
        lines.firstNotNullOfOrNull { line ->
            val pair = line.substringBefore(';').trim()
            if (pair.startsWith("$name=")) pair.substringAfter('=', "").takeIf { it.isNotEmpty() } else null
        }

    private companion object {
        /** §7 mtop H5 网关 */
        const val GATEWAY = "https://h5api.m.taobao.com/h5/"

        /** §5.4：直调必须带 pages-fast（身份码 TinyApp 所在域）Referer */
        const val MTOP_REFERER = "https://pages-fast.m.taobao.com/"
    }
}
