package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aggapp.core.model.Credential
import com.aggapp.platform.api.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面 console 序列化：object 参数 JSON.stringify，便于从 logcat 看到页面拿到的挑战数据 */
private const val CONSOLE_SERIALIZE_HOOK =
    """(function(){['log','info','warn','error'].forEach(function(m){var b=console[m];console[m]=function(){var a=[];for(var i=0;i<arguments.length;i++){var x=arguments[i];try{a.push(typeof x==='object'?JSON.stringify(x):String(x))}catch(e){a.push(String(x))}}b.apply(console,a)}})})()"""

/** dump 页面题干：取 #main 文本、压缩空白、去掉尾部按钮文字"确认"，供外置显示 */
private const val DOM_TEXT_DUMP =
    """(function(){var el=document.querySelector('#main')||document.body;var t=(el.innerText||'').replace(/\s+/g,' ').trim().replace(/确认$/,'').replace(/\\/g,'');return JSON.stringify(t.slice(0,300))})()"""

/** 外置确认：多选/九宫格题型下页面确认按钮可能不渲染，JS 直接触发 .btn-submit 的 click */
private const val EXTERNAL_CONFIRM_JS =
    """(function(){var b=document.querySelector('.btn-submit')||document.querySelector('[class*="submit" i]');if(!b)return JSON.stringify({r:'NO_BTN'});var cs=getComputedStyle(b);b.click();return JSON.stringify({r:'CLICKED',cls:b.className,display:cs.display,text:(b.innerText||'').slice(0,20)})})()"""

/**
 * 通用 WebView 登录弹窗（拼多多短信登录首用）：
 * - 加载 [loginUrl]（平台登录页），用户在真实浏览器环境完成手机号+验证码/滑块；
 * - 轮询 CookieManager（HttpOnly cookie 也可读）+ onPageFinished 双路检测，把平铺
 *   cookie 解析成 map 交 [checkSuccess]（即 PlatformAdapter.detectLoginSuccess）判定；
 * - 命中 → 回调 [onCredential]（只回调一次）。
 * - [actionLabel]/[onAction]：可选手动兜底按钮（如 PDD 官方验证页过完但收尾接口未落 Cookie 时，
 *   由 App 端补发 auth/ticket）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginDialog(
    title: String,
    loginUrl: String,
    checkSuccess: (cookies: Map<String, String>) -> Credential?,
    onCredential: (Credential) -> Unit,
    onDismiss: () -> Unit,
    tip: String? = "在页面中完成手机号验证码登录，成功后自动记录登录态",
    desktopUa: Boolean = false,
    clearCookies: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var done by remember { mutableStateOf(false) }
    var wv by remember { mutableStateOf<WebView?>(null) }
    // 页面题目文字外置显示：部分题型题干在 WebView 里渲染不出来，定时提取 innerText 展示到原生 UI
    var pageText by remember { mutableStateOf("") }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }

    fun deliver(cred: Credential) {
        if (done) return
        done = true
        onCredential(cred)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            // 题干外置（tip 为 null 的验证场景）：固定前缀小字 + 题目主体大字（截到「（单选/多选）」为止，选项列表不重复显示）
            if (tip == null && pageText.isNotBlank()) {
                val body = pageText
                    .replace(Regex("为了您的资金安全，请[继进][续行]安全验证"), "")
                    .trim()
                val question = Regex("请选择您.*?[（(][单多]选[）)]").find(body)?.value ?: body
                Text(
                    "为了您的资金安全，请进行安全验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    question,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 外置确认：多选/九宫格题型页面确认按钮可能不渲染，JS 直接触发 .btn-submit click
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    wv?.evaluateJavascript(EXTERNAL_CONFIRM_JS) { result ->
                        AppLog.d("AggApp", "pdd external confirm: $result")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选好了但页面没有确认按钮？点此外置确认") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(if (desktopUa) 640.dp else 520.dp),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                if (clearCookies) {
                    // 清掉残留 cookie（如上次验证种下的失效 PDDAccessToken），否则弹窗打开即误判"已登录"秒退
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    AppLog.d("AggApp", "pdd verify cookies cleared before load")
                }
                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    if (desktopUa) {
                        // login.html 检测移动端 WebView 环境会跳 pinduoduo:// deep link（ERR_UNKNOWN_URL_SCHEME），
                        // 伪装 PC Chrome 后页面正常渲染（扫码登录 Tab 完整可用，已 PC Chrome 实测）
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                        // 桌面页面按自身 viewport 整体缩放渲染；否则窄视口下确认按钮等元素渲染不出来
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }
                    WebView.setWebContentsDebuggingEnabled(true) // CDP 远程调试抓页面网络请求

                    fun readCookies(): Map<String, String> =
                        cookieManager.getCookie(loginUrl)?.split("; ")
                            ?.mapNotNull { pair ->
                                val eq = pair.indexOf('=')
                                if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1) else null
                            }?.toMap() ?: emptyMap()

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean {
                            val u = request.url
                            // 拦截 pinduoduo:// 等非 http(s) 跳转（deep link 兜底），避免整页报错
                            if (u.scheme != "http" && u.scheme != "https") {
                                AppLog.d("AggApp", "pdd verify block scheme jump: $u")
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            // 序列化 console 对象参数：页面日志 [object Object] → JSON 文本
                            view.evaluateJavascript(CONSOLE_SERIALIZE_HOOK, null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            AppLog.d("AggApp", "pdd verify page finished: $url")
                            checkSuccess(readCookies())?.let(::deliver)
                            // 持续 dump 页面可见文本（30 次 × 2s），文本变化才记录，覆盖用户操作期间的状态变化
                            scope.launch {
                                var lastText = ""
                                for (i in 1..30) {
                                    delay(2000)
                                    view.evaluateJavascript(DOM_TEXT_DUMP) { text ->
                                        if (text != lastText) {
                                            lastText = text
                                            AppLog.d("AggApp", "pdd page dom[$i]: $text")
                                            pageText = text?.removeSurrounding("\"")?.take(400) ?: ""
                                        }
                                    }
                                }
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                            AppLog.d(
                                "AggApp",
                                "pdd console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                    "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                            )
                            return true
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            AppLog.d("AggApp", "pdd verify page title: $title")
                        }
                    }
                    loadUrl(loginUrl)
                }
                // 轮询兜底：部分 SPA 登录成功不触发新的 onPageFinished；cookie 集变化时打日志便于诊断
                var lastCookieKeys = ""
                scope.launch {
                    while (isActive) {
                        delay(1000)
                        val cookies = withContext(Dispatchers.IO) {
                            CookieManager.getInstance().getCookie(loginUrl)
                        }?.split("; ")?.mapNotNull { pair ->
                            val eq = pair.indexOf('=')
                            if (eq > 0) pair.substring(0, eq) to pair.substring(eq + 1) else null
                        }?.toMap() ?: emptyMap()
                        val keys = cookies.keys.sorted().toString()
                        if (keys != lastCookieKeys) {
                            lastCookieKeys = keys
                            AppLog.d("AggApp", "pdd verify cookies: $keys")
                        }
                        checkSuccess(cookies)?.let(::deliver)
                    }
                }
                webView
            },
            update = { wv = it },
        )
    }
}
