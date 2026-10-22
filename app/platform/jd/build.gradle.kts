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
    implementation(libs.okhttp)
    api(libs.javax.inject)
}

