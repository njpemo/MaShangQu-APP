package com.aggapp.app

import android.app.Application
import com.aggapp.platform.api.AppLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AggApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 应用内日志环同步镜像到 logcat（「我的」页日志板块展示 AppLog 环内容）
        AppLog.logcatSink = { level, tag, line ->
            when (level) {
                "E" -> android.util.Log.e(tag, line)
                "W" -> android.util.Log.w(tag, line)
                else -> android.util.Log.d(tag, line)
            }
        }
        // 落盘保底：部分机型 logd 间歇丢日志，AppLog 全量追加到 filesDir/aggapp.log
        // 轮转策略：当前文件超 2MB → 重命名为 aggapp.log.1（旧的 .1 被覆盖），保留上一代历史；
        // 导出时（MineViewModel）把 .log 与 .log.1 按时间序合并，覆盖自安装以来的完整日志。
        val logFile = java.io.File(getFilesDir(), "aggapp.log")
        val logOld = java.io.File(getFilesDir(), "aggapp.log.1")
        val logIo = java.util.concurrent.Executors.newSingleThreadExecutor()
        AppLog.fileSink = { line ->
            logIo.execute {
                try {
                    if (logFile.length() > 2_000_000) {
                        logOld.delete()
                        logFile.renameTo(logOld)
                    }
                    java.io.FileWriter(logFile, true).use { it.append(line).append('\n') }
                } catch (_: java.io.IOException) {
                }
            }
        }
        AppLog.d("AggApp", "app start")
        // debug 包开启全量请求日志（含 body/Set-Cookie 摘要）；release 包保持关闭
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        com.aggapp.platform.api.NetDebug.enabled = isDebug
        com.aggapp.platform.api.NetDebug.withBody = isDebug
        AppLog.d("AggApp", "NetDebug enabled=$isDebug")
    }
}
