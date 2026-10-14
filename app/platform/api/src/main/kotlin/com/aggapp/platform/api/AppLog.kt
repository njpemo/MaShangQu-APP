package com.aggapp.platform.api

/**
 * 应用内日志环缓冲（纯 Kotlin，供平台模块与 UI 层共用）。
 *
 * 目的：真机排查时无需 adb logcat——「我的」页底部日志板块直接展示/复制全部日志。
 * - [d]/[e]/[w] 记录到内存环（容量 [CAP] 行，超限丢弃最旧），同时可选镜像到 logcat；
 * - [dump] 按时间序导出全部日志（供复制发送）；
 * - [clear] 清空。
 */
object AppLog {

    /** 环缓冲容量（行） */
    const val CAP = 800

    private val lines = ArrayDeque<String>(CAP)

    /** logcat 镜像（app 模块 Application 启动时挂上；平台模块不依赖 android） */
    @Volatile
    var logcatSink: ((level: String, tag: String, msg: String) -> Unit)? = null

    /** 文件持久化 sink（logcat 在部分机型被 logd 丢弃，落盘保底；实现方自行处理异步） */
    @Volatile
    var fileSink: ((line: String) -> Unit)? = null

    /** 新日志回调（UI 实时刷新用，UI 侧自行节流） */
    @Volatile
    var onLog: ((String) -> Unit)? = null

    fun d(tag: String, msg: String) = record("D", tag, msg, null)

    fun w(tag: String, msg: String, err: Throwable? = null) = record("W", tag, msg, err)

    fun e(tag: String, msg: String, err: Throwable? = null) = record("E", tag, msg, err)

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    private fun record(level: String, tag: String, msg: String, err: Throwable?) {
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = buildString {
            append(ts).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            err?.let { append("  !! ").append(it.javaClass.simpleName).append(": ").append(it.message) }
        }
        synchronized(lines) {
            if (lines.size >= CAP) lines.removeFirst()
            lines.addLast(line)
        }
        logcatSink?.invoke(level, tag, line)
        fileSink?.invoke(line)
        onLog?.invoke(line)
    }
}
