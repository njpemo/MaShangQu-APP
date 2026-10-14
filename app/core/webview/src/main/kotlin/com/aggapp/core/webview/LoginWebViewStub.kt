package com.aggapp.core.webview

/**
 * LoginWebView 通用登录组件（README §3.2 :core:webview）。
 *
 * 目标能力：
 * - WebViewClient.shouldInterceptRequest 逐请求回调 PlatformAdapter.detectLoginSuccess
 * - CookieManager 全量 Cookie 读取（含跨域）
 * - JS 注入钩子 hook fetch/XHR（取响应体）
 *
 * 本轮无平台走 WEBVIEW 登录，仅保留占位；淘宝协议登录接入后此组件仍作降级路径。
 * 真实 Composable 实现随首个 WEBVIEW 平台需求一并落地。
 */
object LoginWebViewStub {
    const val NOT_IMPLEMENTED = "LoginWebView 组件占位：当前无平台使用 WEBVIEW 登录"
}
