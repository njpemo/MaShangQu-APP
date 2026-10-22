package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.aggapp.platform.api.AppLog

/**
 * 京东滑块验证原生 Activity（2026-10-21）。
 * 背景：本设备上 Compose 容器（BottomSheet/Dialog + AndroidView）内的 WebView DOM 渲染
 * 完整但渲染进程不出帧（涂红实验截图仍白）。改用纯 View 体系 Activity 承载 WebView
 * ——与老版本及普通 App 的 WebView 环境一致。
 * 结果通过静态回调 [onResult] 回传：null=用户关闭；非 null=AndroidCaptcha 桥 JSON。
 */
class CaptchaActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        /** true=本地 assets 页（顺丰极验 sf_geetest.html，需 AssetLoader + 极验 JSONP 代理） */
        const val EXTRA_ASSET = "asset"
        var onResult: ((String?) -> Unit)? = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val ctx = this
        val dm = resources.displayMetrics
        val dp = dm.density

        // 半透明遮罩全屏，居中一张圆角卡片（对话框式）
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16 * dp
            }
        }
        val header = TextView(this).apply {
            text = "安全验证"
            setTextColor(Color.parseColor("#1C1B1F"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
        }
        val subtitle = TextView(this).apply {
            text = "完成验证后将自动向该手机号发送短信验证码"
            setTextColor(Color.parseColor("#5A5A5F"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (8 * dp).toInt())
        }
        val isAsset = intent.getBooleanExtra(EXTRA_ASSET, false)
        // 顺丰极验：gt4.js 要求正常 https origin（file:// 跨域被拒），assets 映射为 https
        val assetLoader = if (isAsset) {
            androidx.webkit.WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
                .build()
        } else {
            null
        }
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onCaptcha(dataJson: String) {
                        runOnUiThread {
                            AppLog.d("AggApp", "jd captcha result: ${dataJson.take(200)}")
                            onResult?.invoke(dataJson)
                            onResult = null
                            finish()
                        }
                    }
                },
                "AndroidCaptcha",
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    assetLoader?.shouldInterceptRequest(request.url)?.let { return it }
                    // 极验 register/verify 走 JSONP 脚本，本 WebView 环境下响应被 ORB 拦截且
                    // gt4 不回调 onError → 页面永久空白。原生代发请求绕过 ORB（顺丰资产页模式）。
                    val host = request.url.host ?: return null
                    if (request.method == "GET" &&
                        (host.endsWith("gcaptcha4.geetest.com") || host.endsWith("gstatic.geetest.com"))
                    ) {
                        return try {
                            val u = request.url.toString()
                            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.requestMethod = request.method
                            val code = conn.responseCode
                            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()
                            AppLog.w("SfGeetest", "proxy $code ${u.take(90)}")
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

                override fun onPageFinished(view: WebView, u: String) {
                    view.evaluateJavascript(INJECT_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    AppLog.d("AggApp", "jd captcha console[${msg.messageLevel()}] ${msg.message()}")
                    return true
                }
            }
            if (isAsset) {
                // cache-buster：确保 assets/sf_geetest.html 更新后 WebView 不用旧缓存
                loadUrl("https://appassets.androidplatform.net/assets/sf_geetest.html?v=${System.currentTimeMillis()}")
            } else {
                loadUrl(url)
            }
        }
        card.addView(header)
        card.addView(subtitle)
        card.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.42f).toInt(),
            ).apply { setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), (16 * dp).toInt()) },
        )
        root.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                setMargins((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            },
        )
        setContentView(root)
    }

    override fun finish() {
        // 用户直接关闭时也通知空结果，避免上层状态卡住
        onResult?.let { cb -> cb(null); onResult = null }
        super.finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun finishWithNull() {
        onResult?.invoke(null)
        onResult = null
    }

    /** 防止 onResult 与 finish 重复回调：finish 里已处理 */
    @Deprecated("Deprecated in Java")
    override fun onDestroy() {
        super.onDestroy()
    }
}

private const val INJECT_JS =
    "try{window.__CAP_VT=null;" +
        "window.wx={miniProgram:{postMessage:function(m){" +
        "try{window.__CAP_MSG=m;" +
        "if(m&&m.data&&m.data.data&&m.data.data.vt)window.__CAP_VT=m.data.data.vt;" +
        "window.AndroidCaptcha&&window.AndroidCaptcha.onCaptcha(JSON.stringify(m&&m.data))" +
        "}catch(e){}}," +
        "navigateBack:function(){}}};}catch(e){}"
