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
        // （debug 包可用 `adb exec-out run-as com.aggapp.app cat files/aggapp.log` 拉取）
        val logFile = java.io.File(getFilesDir(), "aggapp.log")
        val logIo = java.util.concurrent.Executors.newSingleThreadExecutor()
        AppLog.fileSink = { line ->
            logIo.execute {
                try {
                    if (logFile.length() > 1_000_000) logFile.delete() // 超 1MB 重建，防无限增长
                    java.io.FileWriter(logFile, true).use { it.append(line).append('\n') }
                } catch (_: java.io.IOException) {
                }
            }
        }
        AppLog.d("AggApp", "app start")
    }
}
