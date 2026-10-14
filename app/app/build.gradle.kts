plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aggapp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aggapp.app"
        minSdk = 26
        targetSdk = 34
        // 每次构建自动递增（epoch 分钟），确保 adb install -r 同名包也能命中更新
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "0.1." + (System.currentTimeMillis() / 60000L % 100000)
    }
    buildToolsVersion = "34.0.0"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(project(":core:yyb"))
    implementation(project(":platform:api"))
    implementation(project(":platform:pdd"))
    implementation(project(":platform:taobao"))
    implementation(project(":platform:jd"))
    implementation(project(":platform:sf"))
    implementation(project(":platform:ryll"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation("androidx.webkit:webkit:1.11.0") // WebViewAssetLoader：极验页需 https origin（file:// origin 为 null 会被 gt4.js 拒绝）
}

