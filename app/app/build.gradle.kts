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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 每次构建自动递增（epoch 分钟），确保 adb install -r 同名包也能命中更新
        versionCode = 9
        versionName = "1.0.8"
    }
    buildToolsVersion = "34.0.0"

    buildTypes {
        release {
            isMinifyEnabled = false
            // 蒲公英分发：用 debug 签名保证可安装（与已分发版本签名一致）
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // 调试包独立包名，与 release 包共存（调试日志导出/全量请求日志仅 debug 包开启）
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // APK 文件名：码上取全拼 + 版本号，如 mashangqu-v1.0.1.apk；debug 包 mashangqu-debug-v1.0.1.apk
    applicationVariants.all {
        val debugSuffix = if (buildType.name == "debug") "-debug" else ""
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "mashangqu${debugSuffix}-v${versionName}.apk"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
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
    implementation(libs.backdrop)
    implementation(libs.capsule)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation("androidx.webkit:webkit:1.11.0") // WebViewAssetLoader：极验页需 https origin（file:// origin 为 null 会被 gt4.js 拒绝）
    implementation("io.coil-kt:coil-compose:2.6.0") // 验证题图片选项（收货地址截图等）异步加载
    implementation("androidx.browser:browser:1.8.0") // Chrome Custom Tabs：PDD 验证页反 WebView 渲染隐藏，必须用真 Chrome 管线显示
}

