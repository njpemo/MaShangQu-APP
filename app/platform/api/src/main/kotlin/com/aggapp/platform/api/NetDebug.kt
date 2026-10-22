package com.aggapp.platform.api

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug 构建专用的全量请求日志拦截器（纯 OkHttp，各平台模块 OkHttpClient 统一挂载）。
 *
 * - [enabled] 总开关：仅 debug 包（Application 按 FLAG_DEBUGGABLE 打开）；
 * - [withBody] 请求/响应体摘要（淘宝短信登录链路排查重点：sendSms.do 的 smsToken、
 *   login.do 的 Set-Cookie 与 success 判定，body 截断到 4000 字符防日志爆炸）；
 * - 日志统一走 [AppLog]（内存环 + logcat + aggapp.log 落盘），导出调试日志时一并带出。
 */
object NetDebug {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var withBody: Boolean = false

    /** 单条 body 摘要最大长度（字符） */
    private const val BODY_SNIPPET = 4000

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!enabled) return@Interceptor chain.proceed(req)

        val t0 = System.currentTimeMillis()
        AppLog.d("HTTP", "==> ${req.method} ${req.url}")
        if (withBody) {
            val body = req.body
            when (body) {
                is FormBody -> {
                    val fields = (0 until body.size).joinToString("&") { i ->
                        "${body.encodedName(i)}=${body.encodedValue(i)}"
                    }
                    AppLog.d("HTTP", "--> form: $fields")
                }
                else -> body?.let { AppLog.d("HTTP", "--> body: ${it.contentType()} (${it.contentLength()}B)") }
            }
        }
        try {
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - t0
            AppLog.d("HTTP", "<== ${resp.code} ${req.method} ${req.url} (${ms}ms)")
            if (withBody) {
                val setCookie = resp.headers.values("Set-Cookie")
                if (setCookie.isNotEmpty()) {
                    AppLog.d("HTTP", "<-- set-cookie: ${setCookie.joinToString(" | ")}")
                }
                val peek = runCatching { resp.peekBody((64 * 1024).toLong()).string() }.getOrNull()
                if (!peek.isNullOrEmpty()) {
                    AppLog.d("HTTP", "<-- body: ${peek.take(BODY_SNIPPET)}")
                }
            }
            resp
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            AppLog.e("HTTP", "<!! ${req.method} ${req.url} (${ms}ms) ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}
