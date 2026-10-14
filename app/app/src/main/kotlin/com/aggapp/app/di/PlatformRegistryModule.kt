package com.aggapp.app.di

import com.aggapp.core.model.Platform
import com.aggapp.platform.api.PlatformAdapter
import com.aggapp.platform.jd.JdAdapter
import com.aggapp.platform.pdd.PddAdapter
import com.aggapp.platform.ryll.RyllAdapter
import com.aggapp.platform.sf.SfAdapter
import com.aggapp.platform.taobao.TaobaoAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 平台注册表：:app 通过 Hilt 收集所有 PlatformAdapter 实现（README §3.1） */
class PlatformRegistry(val adapters: Map<Platform, PlatformAdapter>)

@Module
@InstallIn(SingletonComponent::class)
object PlatformRegistryModule {

    @Provides
    @Singleton
    fun provideRegistry(
        pdd: PddAdapter,
        taobao: TaobaoAdapter,
        jd: JdAdapter,
        sf: SfAdapter,
        ryll: RyllAdapter,
    ): PlatformRegistry = PlatformRegistry(
        mapOf(
            Platform.PDD to pdd,
            Platform.TAOBAO to taobao,
            Platform.JD to jd,
            Platform.SF to sf,
            Platform.RYLL to ryll,
        ),
    )
}
