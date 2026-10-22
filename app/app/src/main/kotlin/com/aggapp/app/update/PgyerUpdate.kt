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
import com.aggapp.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 蒲公英应用内更新（API 2.0：POST /apiv2/app/check + 应用内下载 APK + 拉起安装）。
 * 文档：https://www.pgyer.com/doc/view/api_version
 *
 * 接入步骤：
 * 1. 登录蒲公英后台 https://www.pgyer.com/account/api 获取 API Key，填入 [API_KEY]
 * 2. 在蒲公英「我的应用」里找到本应用的 appKey，填入 [APP_KEY]
 * 3. 每次发新版后，App 启动即会提示更新；点「立即更新」在应用内下载并自动拉起安装
 */
object PgyerUpdate {

    // API Key 经 BuildConfig 注入（app/local.properties 配置 pgyerApiKey=xxx），不硬编码入库
    private val API_KEY = BuildConfig.PGYER_API_KEY
    private const val APP_KEY = BuildConfig.PGYER_APP_KEY

    private const val CHECK_URL = "https://www.pgyer.com/apiv2/app/check"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 检测结果：有新版本时非空 */
    data class UpdateInfo(
        val versionName: String,      // 新版本号，如 1.1
        val versionNo: String,        // 上传包的版本编号
        val updateDescription: String,// 更新说明
        val downloadURL: String,      // 安装地址（含 _api_key，勿写入日志/公开分享）
    )

    /**
     * 调用蒲公英 check 接口。当前版本号由调用方传入（versionName）。
     * 网络失败 / 无新版本 / 未配置 key 均返回 null，静默跳过。
     */
    suspend fun check(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (API_KEY.startsWith("YOUR_") || APP_KEY.startsWith("YOUR_")) return@withContext null
        runCatching {
            val body = FormBody.Builder()
                .add("_api_key", API_KEY)
                .add("appKey", APP_KEY)
                .add("buildVersion", currentVersionName)
                .build()
            val resp = client.newCall(Request.Builder().url(CHECK_URL).post(body).build()).execute()
            resp.use {
                if (!it.isSuccessful) return@withContext null
                val root = JSONObject(it.body?.string() ?: "")
                if (root.optInt("code", -1) != 0) return@withContext null
                val data = root.optJSONObject("data") ?: return@withContext null
                if (!data.optBoolean("buildHaveNewVersion")) return@withContext null
                val url = data.optString("downloadURL").orEmpty()
                if (url.isBlank()) return@withContext null
                UpdateInfo(
                    versionName = data.optString("buildVersion"),
                    versionNo = data.optString("buildVersionNo"),
                    updateDescription = data.optString("buildUpdateDescription"),
                    downloadURL = url,
                )
            }
        }.getOrNull()
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
fun PgyerUpdateEffect() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PgyerUpdate.UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }   // null=未在下载；非空=下载中
    var downloadError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        info = current?.let { PgyerUpdate.check(it) }
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
                    } else if (u.updateDescription.isNotBlank()) {
                        Text(u.updateDescription)
                    }
                }
            },
            confirmButton = {
                if (progress == null) {
                    TextButton(onClick = {
                        downloadError = false
                        // 未授予「安装未知应用」权限时先引导去设置，回来后再点即可下载安装
                        if (!PgyerUpdate.canInstall(context)) {
                            PgyerUpdate.requestInstallPermission(context)
                            return@TextButton
                        }
                        progress = 0f
                        scope.launch {
                            val apk = PgyerUpdate.downloadApk(context, u.downloadURL) { p ->
                                progress = p
                            }
                            progress = null
                            if (apk != null) {
                                info = null
                                PgyerUpdate.installApk(context, apk)
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
