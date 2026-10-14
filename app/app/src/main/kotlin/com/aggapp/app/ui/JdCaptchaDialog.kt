package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
                "完成验证后将自动向该手机号发送短信验证码",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        @Suppress("LiftReturnOrAssignment")
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(520.dp),
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
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(INJECT_JS, null)
                        }
                    }
                    loadUrl(captchaUrl)
                }
            },
        )
    }
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
