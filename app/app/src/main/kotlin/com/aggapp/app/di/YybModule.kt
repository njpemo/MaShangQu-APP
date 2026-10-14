package com.aggapp.app.di

import com.aggapp.core.yyb.YybNotConfigured
import com.aggapp.core.yyb.YybRemote
import com.aggapp.core.yyb.YybRemoteException
import com.aggapp.core.yyb.YybRescanRequired
import com.aggapp.core.yyb.YybSettings
import com.aggapp.platform.api.YybCodeSource
import android.content.Context
import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YybCodeSource 默认实现（v1.3 远程版）：HTTP 调用部署的 YYB-GO 服务。
 * - available = 已配置 BASE URL + 账号 ref（不再依赖本地库初始化）
 * - getCode = POST /wxapp/getCode {ref, app_id}
 * - 409（login_buffer 过期）抛 YybRescanRequired，调用方提示需重扫
 */
@Singleton
class DefaultYybCodeSource @Inject constructor(
    private val settings: YybSettings,
) : YybCodeSource {

    override val available: Boolean get() = settings.configured

    override suspend fun getCode(targetAppId: String): String {
        if (!settings.configured) throw YybNotConfigured()
        return try {
            YybRemote.getCode(settings.baseUrl, settings.ref, targetAppId)
        } catch (e: YybRemoteException) {
            throw e // 409 需重扫等语义化错误原样上抛
        } catch (e: Exception) {
            throw YybRemoteException("YYB 服务连接失败：${e.message}", e)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class YybModule {

    @Binds
    @Singleton
    abstract fun bindYybCodeSource(impl: DefaultYybCodeSource): YybCodeSource

    companion object {
        @Provides
        @Singleton
        fun provideYybSettings(@ApplicationContext context: Context): YybSettings = YybSettings(context)
    }
}
