package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 京东滑块/点选验证码弹窗（jd.md §2.2.6②⑤）：
 * - 加载 wx-captcha-v1.html（实测无微信环境校验，任意 WebView 可开）；
 * - 页面 JS 只在验证成功时检查 typeof wx!=="undefined"，页面加载后注入假
 *   wx.miniProgram.postMessage 即可截获 captcha_result 的 vt；
 * - vt 经 AndroidCaptcha JS 桥回传 [onCaptcha]（payload 为 postMessage 的 data JSON）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JdCaptchaDialog(
    captchaUrl: String,
    onCaptcha: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 2026-10-21：WebView 嵌在 ModalBottomSheet 内时 DOM 渲染完整但整个 View 不上屏
    //（合成失效，软/硬件层均无效）。改用独立全屏 Dialog 窗口承载 WebView —— WebView
    // 独占 window 后走正常合成路径，这是 Android 上 BottomSheet+WebView 白屏的标准解法。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("安全验证", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text(
                    "完成验证后将自动向该手机号发送短信验证码",
                    style = MaterialTheme.typography.bodySmall,
                )
                CaptchaWebView(captchaUrl, onCaptcha)
            }
        }
    }
}

/** 独立 WebView 组件（供 Dialog 承载） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaWebView(captchaUrl: String, onCaptcha: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(520.dp).padding(top = 8.dp),
        factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 2026-10-21：滑块空白修复——WebView UA 的 "; wv" 标记被京东 jcap 风控识别后
                    // 不渲染滑块（与 PDD 验证页同款问题），去掉标记伪装成移动端 Chrome
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    settings.mediaPlaybackRequiresUserGesture = false
                    // 2026-10-21：DOM 渲染正常（300x307 visible）但屏幕空白——
                    // WebView 嵌在 ModalBottomSheet 的 AndroidView 里时的硬件合成失效，
                    // 强制软件渲染绕过（与 PDD 验证页同款处理）
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    // 诊断：页面 console 落 logcat，便于排查渲染失败
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                            com.aggapp.platform.api.AppLog.d(
                                "AggApp",
                                "jd captcha console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()?.takeLast(40)}:${msg.lineNumber()}",
                            )
                            return true
                        }
                    }
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
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(INJECT_JS, null)
                            // 诊断：延迟多次检测滑块容器（jcap 异步渲染，可能落在 iframe 里）
                            val probe =
                                "(function(){var m=document.querySelector('#captcha_modal');" +
                                    "if(!m)return JSON.stringify({none:true});" +
                                    "var cs=getComputedStyle(m);" +
                                    "return JSON.stringify({cls:m.className," +
                                    "size:m.offsetWidth+'x'+m.offsetHeight," +
                                    "rect:(function(){var r=m.getBoundingClientRect();return Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height)})()," +
                                    "disp:cs.display+' '+cs.visibility+' '+cs.opacity," +
                                    "children:m.children.length," +
                                    "inner:m.innerHTML.slice(0,600)," +
                                    "imgs:m.querySelectorAll('img,canvas').length})})()"
                            for (delay in longArrayOf(800, 2500, 5000, 8000)) {
                                view.postDelayed({ view.evaluateJavascript(probe) { v -> com.aggapp.platform.api.AppLog.d("AggApp", "jd captcha dom: $v") } }, delay)
                            }
                            // 决定性判别：5 秒时把页面涂红——若截图为红，WebView 上屏正常（问题在内容）；
                            // 若截图仍白，WebView 渲染进程没出帧（设备/组件级问题）
                            view.postDelayed({
                                view.evaluateJavascript("document.documentElement.style.background='#ff0000';document.body&&(document.body.style.background='#ff0000');'ok'", null)
                                view.postDelayed({
                                    try {
                                        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                                        val c = android.graphics.Canvas(bmp)
                                        view.draw(c)
                                        val vals = ctx.contentResolver
                                        val name = "jd_captcha_${System.currentTimeMillis()}.png"
                                        val uri = android.provider.MediaStore.Images.Media.insertImage(vals, bmp, name, "jd captcha diag")
                                        com.aggapp.platform.api.AppLog.d("AggApp", "jd webview snapshot saved: $uri ${view.width}x${view.height}")
                                    } catch (e: Exception) {
                                        com.aggapp.platform.api.AppLog.e("AggApp", "jd snapshot failed", e)
                                    }
                                }, 500)
                            }, 5000)
                        }
                    }
                    loadUrl(captchaUrl)
                }
        },
    )
}

/** 验证成功时页面才检测 wx 对象，onPageFinished 注入来得及；vt 同时存 window.__CAP_VT 兜底 */
private const val INJECT_JS =
    "try{window.__CAP_VT=null;" +
        "window.wx={miniProgram:{postMessage:function(m){" +
        "try{window.__CAP_MSG=m;" +
        "if(m&&m.data&&m.data.data&&m.data.data.vt)window.__CAP_VT=m.data.data.vt;" +
        "window.AndroidCaptcha&&window.AndroidCaptcha.onCaptcha(JSON.stringify(m&&m.data))" +
        "}catch(e){}}," +
        "navigateBack:function(){}}};}catch(e){}"
