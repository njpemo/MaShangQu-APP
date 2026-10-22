plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":platform:api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)                     // 网关直连（sf.md §1.2 ant.yshoufa.com）
    implementation(libs.kotlinx.serialization.json) // 响应信封/请求体 JSON（JsonElement 无需插件）
    api(libs.javax.inject)
}

