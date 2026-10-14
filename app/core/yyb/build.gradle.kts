plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aggapp.core.yyb"
    compileSdk = 36
    buildToolsVersion = "34.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // v1.3：内嵌 aar（yyblib.aar / yyblib-classes.jar）已废弃，改为远程 YYB-GO 服务 HTTP 客户端
    implementation(libs.okhttp)
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
