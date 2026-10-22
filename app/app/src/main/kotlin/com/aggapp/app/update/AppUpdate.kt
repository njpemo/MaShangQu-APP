package com.aggapp.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新（JSON 更新源方案，零密钥）：
 * 版本信息发布在仓库 update/latest.json，App 经 jsDelivr CDN（国内可直连）读取，
 * 失败降级 GitHub raw。全程不携带任何 API Key，APK 内无密钥可提取。
 *
 * 发版流程（Key 只在开发机 local.properties，仅蒲公英上传用）：
 * 1. 修改 update/latest.json 的 versionCode/versionName/apkUrl/changelog
 * 2. gradlew assembleRelease（versionCode/versionName 自动取自 latest.json）
 * 3. APK 上传蒲公英 → 把蒲公英下载短链填入 apkUrl（或用 GitHub Release 直链）→ 提交推送
 */
object AppUpdate {

    /** 更新源：jsDelivr 优先（国内直连），GitHub raw 兜底；@main 指向 main 分支 */
    private val JSON_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/njpemo/mashangqu@main/update/latest.json",
        "https://raw.githubusercontent.com/njpemo/mashangqu/main/update/latest.json",
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 检测结果：有新版本时非空 */
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
    )

    /**
     * 拉取更新源并与本地 versionCode 比对。
     * 网络失败 / 无新版本均返回 null，静默跳过。
     */
    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        for (url in JSON_URLS) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val root = runCatching { org.json.JSONObject(resp.body?.string() ?: "") }
                        .getOrNull() ?: return@use null
                    val remoteCode = root.optInt("versionCode", 0)
                    val apkUrl = root.optString("apkUrl").orEmpty()
                    if (remoteCode <= currentVersionCode || apkUrl.isBlank()) return@use null
                    UpdateInfo(
                        versionCode = remoteCode,
                        versionName = root.optString("versionName"),
                        apkUrl = apkUrl,
                        changelog = root.optString("changelog").orEmpty(),
                    )
                }
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    /**
     * 应用内下载 APK 到 cacheDir/updates/，onProgress 回调 0f..1f。
     * 成功返回 APK 文件；失败返回 null。
     */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                // 每次下载都覆盖旧文件，避免占用空间
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "update_${System.currentTimeMillis()}.apk")

                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            var written = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                written += read
                                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                            output.flush()
                        }
                    }
                }
                target
            }.getOrNull()
        }

    /** 是否已授予「安装未知应用」权限（Android 8+ 必需） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 跳系统设置页引导用户开启「允许安装未知应用」 */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 通过 FileProvider 拉起系统安装器 */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * 挂在 Activity 根部的启动检查 + 更新弹窗（应用内下载 + 自动拉起安装）。
 * 用法：在 setContent 的主题内调用一次。
 */
@Composable
fun AppUpdateEffect() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<AppUpdate.UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }   // null=未在下载；非空=下载中
    var downloadError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val currentCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        } catch (_: Exception) {
            0
        }
        info = AppUpdate.check(currentCode)
    }

    info?.let { u ->
        AlertDialog(
            onDismissRequest = { if (progress == null) info = null },
            title = { Text("发现新版本 v${u.versionName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (progress != null) {
                        Text("正在下载更新…")
                        LinearProgressIndicator(
                            progress = { progress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (downloadError) {
                        Text("下载失败，请检查网络后重试。")
                    } else if (u.changelog.isNotBlank()) {
                        Text(u.changelog)
                    }
                }
            },
            confirmButton = {
                if (progress == null) {
                    TextButton(onClick = {
                        downloadError = false
                        // 未授予「安装未知应用」权限时先引导去设置，回来后再点即可下载安装
                        if (!AppUpdate.canInstall(context)) {
                            AppUpdate.requestInstallPermission(context)
                            return@TextButton
                        }
                        progress = 0f
                        scope.launch {
                            val apk = AppUpdate.downloadApk(context, u.apkUrl) { p ->
                                progress = p
                            }
                            progress = null
                            if (apk != null) {
                                info = null
                                AppUpdate.installApk(context, apk)
                            } else {
                                downloadError = true
                            }
                        }
                    }) { Text(if (downloadError) "重试" else "立即更新") }
                }
            },
            dismissButton = {
                if (progress == null) {
                    TextButton(onClick = { info = null }) { Text("稍后再说") }
                }
            },
        )
    }
}
