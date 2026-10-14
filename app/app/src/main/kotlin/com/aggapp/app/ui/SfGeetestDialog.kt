package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aggapp.platform.api.AppLog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 顺丰极验 v4 滑块弹窗（sf.md §2.5：MIMP 独立登录链路；发码缺 captcha_args 报 700001）：
 * - 加载本地 assets/sf_geetest.html（极验官方 gt4.js，MIMP captchaId d6ee898c...，与
 *   小程序的 fadae... 不同且四件套绑定 captcha_id 不可混用）；
 * - product=bind：onReady 后自动 verify() 弹出滑块；
 * - 验证成功页面经 AndroidCaptcha JS 桥回传 {lot_number, captcha_output, pass_token, gen_time}
 *   JSON 给 [onCaptcha]（pass_token 一次性：一次滑块验证 = 一次发码尝试，失败需重新过滑块）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SfGeetestDialog(
    onCaptcha: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("安全验证")
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Text(
                "完成滑块验证后将自动向该手机号发送短信验证码",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onCaptcha(dataJson: String) {
                                // 回调在 JS 线程，post 到主线程外交付
                                android.os.Handler(ctx.mainLooper).post { onCaptcha(dataJson) }
                            }
                        },
                        "AndroidCaptcha",
                    )
                    // 极验 gt4.js 要求正常 https origin（file:// origin=null 跨域被拒），
                    // 用 WebViewAssetLoader 把 assets 映射为 https://appassets.androidplatform.net/
                    val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): android.webkit.WebResourceResponse? {
                            assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                            // 极验 register/verify 走 JSONP 脚本，本 WebView 环境下响应被 ORB
                            // (ERR_BLOCKED_BY_ORB) 拦截且 gt4 不回调 onError → 页面永久空白。
                            // 原生代发请求绕过 ORB：同源响应喂回 WebView，极验正常初始化。
                            val host = request.url.host ?: return null
                            if (request.method == "GET" &&
                                (host.endsWith("gcaptcha4.geetest.com") || host.endsWith("gstatic.geetest.com"))
                            ) {
                                return try {
                                    val url = request.url.toString()
                                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 8000
                                    conn.readTimeout = 8000
                                    conn.requestMethod = request.method
                                    val code = conn.responseCode
                                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()
                                    AppLog.w("SfGeetest", "proxy $code ${url.take(90)} " +
                                        "head=${String(body ?: ByteArray(0), 0, minOf(120, body?.size ?: 0))}")
                                    if (body == null) return null
                                    android.webkit.WebResourceResponse(
                                        conn.contentType?.substringBefore(';') ?: "text/javascript",
                                        "utf-8", java.io.ByteArrayInputStream(body),
                                    )
                                } catch (e: Exception) {
                                    AppLog.w("SfGeetest", "proxy fail: $e")
                                    null
                                }
                            }
                            return null
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            AppLog.w("SfGeetest", "load error ${request.url} ${error.description}")
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                            AppLog.w("SfGeetest", "console: ${consoleMessage.message()}")
                            return true
                        }
                    }
                    // cache-buster：确保 assets/sf_geetest.html 更新后 WebView 不用旧缓存
                    loadUrl("https://appassets.androidplatform.net/assets/sf_geetest.html?v=${System.currentTimeMillis()}")
                }
            },
        )
    }
}
