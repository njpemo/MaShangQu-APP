plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp) // NetDebug 请求日志拦截器（OkHttpClient 直供各平台模块挂载）
}
