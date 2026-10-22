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
    implementation(libs.okhttp)                      // mec.yto.net.cn / yizhan.mamayz.com 直连（ryll README §1）
    implementation(libs.kotlinx.serialization.json)  // 响应信封 JSON（JsonElement 无需插件）
    api(libs.javax.inject)
}
