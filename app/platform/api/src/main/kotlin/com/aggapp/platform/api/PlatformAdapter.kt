package com.aggapp.platform.api

import com.aggapp.core.model.Credential
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.PickupCode

/** 登录方式（README §4.1） */
enum class LoginMode {
    /** 京东/顺丰/拼多多——内嵌 YYB-GO 库扫码登录微信 → GetCode → code 换平台登录态 */
    REMOTE_SERVICE,

    /** 淘宝——App 内输入手机号+短信验证码，协议直调 */
    PHONE_CODE,

    /** 兜底保留 */
    WEBVIEW,
}

/** WebView 请求拦截上下文（README §4.2） */
data class CapturedRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
)

/**
 * 平台适配器契约（README §4.1 逐字对齐）。
 * 接口签名与数据模型字段名不得擅自修改。
 */
interface PlatformAdapter {
    val platform: Platform

    val loginMode: LoginMode get() = LoginMode.REMOTE_SERVICE

    /** loginMode == REMOTE_SERVICE 时生效：调用内嵌 YYB-GO 库（core:yyb）获取/刷新凭据 */
    suspend fun remoteLogin(): Credential? = null

    /** loginMode == PHONE_CODE 时生效：发送短信验证码 */
    suspend fun sendSmsCode(phone: String)

    /** loginMode == PHONE_CODE 时生效：验证码 + 手机号协议登录 */
    suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential

    /** WEBVIEW 登录时加载的登录页地址；REMOTE_SERVICE 时可不实现 */
    fun loginUrl(): String

    /** WebView 请求拦截回调；命中登录成功返回凭据，否则 null */
    fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential?

    /** 登录态有效性检查（轻量接口调用），true = 仍有效 */
    suspend fun checkAlive(cred: Credential): Boolean

    /** 获取身份码内容（App 负责渲染） */
    suspend fun fetchIdentityCode(cred: Credential): IdentityCode

    /** 快递列表（按页） */
    suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem>

    /** 单件快递取件码查询；查不到返回 null */
    suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode?
}
