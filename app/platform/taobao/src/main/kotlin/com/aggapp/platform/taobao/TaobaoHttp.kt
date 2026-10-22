package com.aggapp.platform.taobao

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 与实测脚本一致的 UA（taobao/js 目录下 Node 脚本的 Chrome/142 桌面 UA，为当前唯一验证可用值）。
 * 文档 §9.1 建议对齐手机淘宝/淘宝 H5 UA 但未给出具体串；若线上触发风控/降级，
 * 在此处切换为移动端 H5 UA 即可（登录表单的 navUserAgent 字段同步取本常量，保持一致）。
 */
internal const val TB_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

/** 统一 HTTP 结果（body 已按 UTF-8 解码；setCookies 为原始 Set-Cookie 头列表） */
internal class TaobaoHttpResponse(
    val code: Int,
    val body: String,
    val setCookies: List<String>,
)

/** GET（带 UA/Referer/Cookie；网络失败抛 NetworkError；ua 缺省桌面 TB_UA） */
internal suspend fun taobaoGet(
    http: OkHttpClient,
    url: String,
    cookie: String,
    referer: String,
    ua: String = TB_UA,
): TaobaoHttpResponse = requestAndRead(
    http,
    Request.Builder()
        .url(url)
        .header("User-Agent", ua)
        .header("Referer", referer)
        .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
        .get()
        .build(),
)

/** POST 表单（带 UA/Referer/Origin/Cookie；网络失败抛 NetworkError） */
internal suspend fun taobaoPost(
    http: OkHttpClient,
    url: String,
    form: FormBody,
    cookie: String,
    referer: String,
    origin: String?,
    ua: String = TB_UA,
): TaobaoHttpResponse = requestAndRead(
    http,
    Request.Builder()
        .url(url)
        .header("User-Agent", ua)
        .header("Referer", referer)
        .apply { if (origin != null) header("Origin", origin) }
        .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
        .post(form)
        .build(),
)

private suspend fun requestAndRead(http: OkHttpClient, request: Request): TaobaoHttpResponse =
    withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { resp ->
                TaobaoHttpResponse(resp.code, resp.body?.string().orEmpty(), resp.headers("Set-Cookie"))
            }
        } catch (e: IOException) {
            throw PlatformError.NetworkError(Platform.TAOBAO, e)
        }
    }

/**
 * 扁平 Cookie 存储 —— 逐行移植自 taobao/js/protocol_login.js 的 JAR：
 * 只取 Set-Cookie 首个分号前的键值对，值为 deleted/空 时删除；
 * 不区分域/路径（与实测脚本一致：登录链路 Cookie 全落 .taobao.com 主域）。
 */
internal class TaobaoCookieStore(initial: Map<String, String> = emptyMap()) {

    private val map = LinkedHashMap<String, String>(initial)

    /** 解析 Set-Cookie 头列表（OkHttp response.headers("Set-Cookie") 产物） */
    fun store(setCookieHeaders: List<String>) {
        for (line in setCookieHeaders) {
            val pair = line.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1)
            if (value == "deleted" || value.isEmpty()) map.remove(name) else map[name] = value
        }
    }

    /** 拼 Cookie 请求头（k=v; k2=v2，与 Node 脚本 cookieHeader() 同格式） */
    fun header(): String = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 取 Cookie 值，缺失返回 null（供 cookies["unb"] 等下标访问） */
    operator fun get(name: String): String? = map[name]

    /** 写入/覆盖 Cookie 值（供 cookies["_m_h5_tk"] = tk 等下标赋值） */
    operator fun set(name: String, value: String) {
        map[name] = value
    }

    companion object {
        /** 把 Credential.token 形态的 Cookie 串还原为存储（loginWithSmsCode 落盘格式的逆操作） */
        fun parse(cookieString: String): TaobaoCookieStore {
            val store = TaobaoCookieStore()
            for (seg in cookieString.split(';')) {
                val pair = seg.trim()
                val eq = pair.indexOf('=')
                if (eq > 0) store[pair.substring(0, eq).trim()] = pair.substring(eq + 1)
            }
            return store
        }
    }
}
