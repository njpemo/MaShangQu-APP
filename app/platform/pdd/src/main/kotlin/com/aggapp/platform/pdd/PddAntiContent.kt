package com.aggapp.platform.pdd

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PDD anti-content 生成器（pdd.md §3）：App 端无 jsdom，改用**隐藏 WebView** 跑逆向出的
 * fbeZ 模块（assets/pdd/module_fbeZ.js + module_8oxB.js，与 PC Node 实测脚本同源）。
 *
 * 链路：GET api.pinduoduo.com/api/server/_stm 取 server_time → WebView 内
 * `new C({serverTime}).messagePackSync()` → ~330 字符 anti-content（实测服务端不校验完整指纹，
 * 330 字符可通过，pdd.md §3.3）。
 *
 * 线程：WebView 须在主线程创建与 evaluate；结果经 suspendCancellableCoroutine 回调。
 * 由 :app AppModule 提供（传 applicationContext），本模块保持无 Hilt 依赖。
 */
class PddAntiContent(private val context: Context) {

    private val started = AtomicBoolean(false)
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (!started.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val shim = ctx.assets.open("pdd/module_8oxB.js").bufferedReader().use { it.readText() }
        val fbeZ = ctx.assets.open("pdd/module_fbeZ.js").bufferedReader().use {
            it.readText().trimStart().removePrefix("fbeZ:")
        }
        // 与 pdd/js/test_live.js 的 webpack 迷你注册表一致；genAntiContent 暴露到 window
        val html = buildString {
            append("<html><body><script>\n")
            append("var registry={};\n")
            append("function req(key){\n")
            append("  if(registry[key]) return registry[key].exports;\n")
            append("  var mod=registry[key]={i:key,l:false,exports:{}};\n")
            append("  if(key==='8oxB'){ new Function('e','t',").append(q(shim)).append(")(mod.exports,mod); }\n")
            append("  else { var make=new Function('return ('+").append(q(fbeZ)).append("+');'); make()(mod,mod.exports,req); }\n")
            append("  mod.l=true; return mod.exports;\n")
            append("}\n")
            append("window.__ac=null; window.__acErr=null;\n")
            append("window.startAnti = function(serverTime){\n")
            append("  window.__ac=null; window.__acErr=null;\n")
            append("  try{ var C=req('fbeZ'); new C({serverTime:String(serverTime)}).messagePackSync()\n")
            append("    .then(function(v){window.__ac=String(v)},\n")
            append("          function(e){window.__acErr=String(e)}); }catch(e){window.__acErr=String(e)}\n")
            append("};\n")
            append("</script></body></html>")
        }
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
        webView = wv
    }

    /** 生成一个 anti-content（~330 字符）。每次调用独立生成，失败抛 IllegalStateException。 */
    suspend fun generate(): String = withTimeout(20_000) {
        withContext(Dispatchers.Main) { ensureWebView() }
        val serverTime = fetchServerTime()
        val wv = webView ?: error("WebView 未初始化")
        withContext(Dispatchers.Main) {
            // evaluateJavascript 不等待 Promise：先启动异步生成，再轮询 window.__ac
            wv.evaluateJavascript(
                "window.startAnti(" + JSONObject.quote(serverTime) + ")", null,
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val r = suspendCancellableCoroutine<Pair<String?, String?>> { cont ->
                    wv.evaluateJavascript("window.__ac+'|'+(window.__acErr||'')") { v ->
                        val s = v?.trim()?.removeSurrounding("\"")?.unescapeJs().orEmpty()
                        val parts = s.split('|', limit = 2)
                        cont.resume(
                            parts[0].takeIf { it.isNotEmpty() && it != "null" && it.length > 50 } to
                                parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                        )
                    }
                }
                val (ac, err) = r
                if (ac != null) return@withContext ac
                if (!err.isNullOrBlank()) throw IllegalStateException("anti-content 生成失败: $err")
                delay(150)
            }
            throw IllegalStateException("anti-content 生成超时")
        }
    }

    /** GET api.pinduoduo.com/api/server/_stm → server_time（test_live.js 同款，失败退 Date.now()） */
    private suspend fun fetchServerTime(): String = withContext(Dispatchers.IO) {
        try {
            java.net.URL("https://api.pinduoduo.com/api/server/_stm")
                .openStream().bufferedReader().use { JSONObject(it.readText()).optString("server_time") }
        } catch (_: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private fun String.unescapeJs(): String = try {
        JSONObject("\"" + this + "\"").toString()
    } catch (_: Exception) {
        this
    }

    private companion object {
        const val BASE_URL = "https://mdkd.pinduoduo.com/weixin/package" // pdd.md §3.3：URL 上下文必须一致
    }
}
