package com.aggapp.platform.api

/**
 * YYB 微信 code 源（窄接口，隔离 JVM 平台模块对 android 库 core:yyb 的依赖）。
 * 实现由 :app 经 Hilt 绑定（内部调 core:yyb 的 YybGo）。
 */
interface YybCodeSource {

    /** YYB 库是否可用（未初始化/初始化失败为 false） */
    val available: Boolean

    /**
     * 取第一个可用微信账号对目标小程序的登录 code。
     * 无可用账号或取码失败抛异常，由调用方决定降级策略。
     */
    suspend fun getCode(targetAppId: String): String
}
