# ===== 码上取 v1.0.10 R8 keep 规则 =====
# 原则：AGP 默认规则（proguard-android-optimize.txt）+ 各库 AAR 自带 consumer 规则已覆盖
# Compose/Hilt/OkHttp/Okio/Coil；此处只补「反射/注解驱动」风险点，避免过度 keep 阻碍瘦身。

# ---- WebView JS 桥：JS 按方法名反射调用（AGP 默认规则已 keep @JavascriptInterface 成员，此处显式兜底） ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- kotlinx-serialization：仅用 Json 树解析（JsonObject/JsonElement，无 @Serializable 反射实例化），
#      官方 consumer 规则已随 AAR 下发，这里只压掉无关注解的告警 ----
-dontwarn kotlinx.serialization.**

# ---- zxing core：条码/二维码生成，无反射；整包 keep（体积小，稳妥优先，避免真机回归翻车） ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- OkHttp / Okio：AAR 自带 consumer 规则；补压告警 ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ---- 协程：无反射，压告警 ----
-dontwarn kotlinx.coroutines.**

# ---- Tink（androidx.security:security-crypto 传递依赖）：编译期注解不在运行时 classpath，R8 建议直接压掉 ----
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
