package com.aggapp.core.yyb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** YYB 远程调用失败基类 */
open class YybRemoteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 账号 login_buffer 过期（HTTP 409）：需在服务端 Web 页 /scan 重新扫码 */
class YybRescanRequired(message: String) : YybRemoteException(message)

/** 服务未配置（BASE URL 为空） */
class YybNotConfigured : YybRemoteException("YYB 服务未配置，请在「我的」页填写服务地址")

/**
 * YYB-GO 远程服务配置（BASE URL + 账号 ref）。
 * ref 形态见服务端 db.ResolveAccount：纯数字=账号 id/uin，否则=openid。
 */
class YybSettings(context: Context) {

    private val prefs = context.getSharedPreferences("yyb_remote", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var ref: String
        get() = prefs.getString(KEY_REF, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_REF, value.trim()).apply()

    val configured: Boolean get() = baseUrl.isNotBlank() && ref.isNotBlank()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_REF = "ref"
    }
}

/**
 * YYB-GO 部署版 HTTP 客户端（README §3.4 契约，字段名以服务端源码为准）：
 * - GET  /health            → {"ok":true}（探活）
 * - POST /wxapp/getCode     body {"ref","app_id"} → {"openid","account":{...},"result":{"code","errMsg"}}
 * - POST /wx/oauth          body {"ref","appid","redirect_uri",...} → {"url","full_url",...}
 * 409 = login_buffer 过期（需重扫）；502 = 微信调用失败。
 */
object YybRemote {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 探活：/health 返回 ok=true 视为连通 */
    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        runCatching {
            val resp = client.newCall(Request.Builder().url(normalize(baseUrl) + "/health").build()).execute()
            resp.use { it.isSuccessful && unwrap(JSONObject(it.body?.string() ?: "")).optBoolean("ok") }
        }.getOrDefault(false)
    }

    /** 取目标小程序登录 code（result.code）。ref/appId 必填。 */
    suspend fun getCode(baseUrl: String, ref: String, appId: String): String {
        val body = JSONObject().put("ref", ref).put("app_id", appId)
        val root = post(normalize(baseUrl), "/wxapp/getCode", body)
        val code = root.optJSONObject("result")?.optString("code").orEmpty()
        if (code.isBlank()) throw YybRemoteException("getCode 未返回 code（检查 app_id 是否正确/账号会话状态）：$root")
        return code
    }

    /** 构造网页 OAuth 授权 URL（PDD 等用）；返回 full_url */
    suspend fun getOAuthUrl(baseUrl: String, ref: String, appId: String, redirectUri: String): String {
        val body = JSONObject()
            .put("ref", ref)
            .put("appid", appId)
            .put("redirect_uri", redirectUri)
        val root = post(normalize(baseUrl), "/wx/oauth", body)
        val url = root.optString("full_url").ifBlank { root.optString("url") }
        if (url.isBlank()) throw YybRemoteException("oauth 响应缺 url：$root")
        return url
    }

    /**
     * 服务端响应统一包了信封 {"code":0,"msg":"success","data":{...}}（实测 yyb.guangyun.baby），
     * 兼容两种形态：有 data 字段取 data，否则原样返回。
     */
    private fun unwrap(root: JSONObject): JSONObject =
        root.optJSONObject("data") ?: root

    /** POST JSON；非 2xx 抛异常（409 → YybRescanRequired），成功返回解包后的 JSON 对象 */
    private suspend fun post(base: String, path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(normalize(base) + path)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errRoot = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    val msg = errRoot.optString("error").ifBlank {
                        errRoot.optString("msg").ifBlank { text.take(200) }
                    }
                    if (resp.code == 409) throw YybRescanRequired(msg.ifBlank { "账号 login_buffer 已过期，请重新扫码" })
                    throw YybRemoteException("HTTP ${resp.code} $path：$msg")
                }
                unwrap(runCatching { JSONObject(text) }.getOrElse { JSONObject() })
            }
        }

    /** 补协议头 + 去尾斜杠 */
    private fun normalize(base: String): String {
        var u = base.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.removeSuffix("/")
    }
}
