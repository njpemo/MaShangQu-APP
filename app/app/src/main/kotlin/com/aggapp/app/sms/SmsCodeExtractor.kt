package com.aggapp.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 短信取件码提取：用户主动点击时读取近几天收件箱短信，
 * 按内置规则提取取件码/快递公司/站点，全部数据仅存本地 SharedPreferences。
 */
object SmsCodeExtractor {

    /** 短信识别出的一条取件信息 */
    data class SmsPickup(
        val id: String,          // 去重键：code+station+日期
        val code: String,        // 取件码
        val carrier: String,     // 快递公司（可空，展示"未知快递"）
        val station: String,     // 站点/驿站
        val content: String,     // 短信原文（截断展示）
        val receivedAt: Long,    // 短信时间
    )

    private const val PREFS = "sms_pickups"
    private const val KEY_ITEMS = "items"
    private const val KEY_PICKED = "picked_ids"
    private const val DAYS = 3

    fun hasReadSmsPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * 读取系统收件箱近 [DAYS] 天短信，规则匹配提取取件码；
     * 与已有记录按 id 去重合并后持久化，返回本次新增条数。
     */
    suspend fun scanAndMerge(ctx: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!hasReadSmsPermission(ctx)) return@withContext Result.failure(SecurityException("缺少短信读取权限"))
        try {
            val start = System.currentTimeMillis() - DAYS * 24L * 3600_000
            val found = querySms(ctx, start)
                .mapNotNull { (body, date) -> extract(body, date) }
                .associateBy { it.id }
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = loadItems(prefs)
            val merged = (existing.associateBy { it.id } + found).values.sortedByDescending { it.receivedAt }
            val added = merged.size - existing.size
            prefs.edit()
                .putString(KEY_ITEMS, serialize(merged))
                .apply()
            Result.success(added)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadAll(ctx: Context): List<SmsPickup> = loadItems(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** 标记已取（移入已取分区，下次扫描不清除） */
    fun markPicked(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = prefs.getStringSet(KEY_PICKED, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_PICKED, s + id).apply()
    }

    fun pickedIds(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_PICKED, emptySet()) ?: emptySet()

    /** 删除单条记录 */
    fun delete(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rest = loadItems(prefs).filterNot { it.id == id }
        prefs.edit().putString(KEY_ITEMS, serialize(rest)).apply()
    }

    // ============ 内部实现 ============

    private fun querySms(ctx: Context, startAt: Long): List<Pair<String, Long>> {
        val cr = ctx.contentResolver
        val list = mutableListOf<Pair<String, Long>>()
        cr.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("body", "date"),
            "date > ? AND read = 1",
            arrayOf(startAt.toString()),
            "date DESC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val body = cur.getString(0) ?: continue
                val date = cur.getLong(1)
                list += body to date
            }
        }
        return list
    }

    // 快递公司关键词 → 名称
    private val carriers = linkedMapOf(
        "顺丰" to "顺丰速运", "圆通" to "圆通速递", "中通" to "中通快递",
        "申通" to "申通快递", "韵达" to "韵达快递", "百世" to "百世快递",
        "极兔" to "极兔速递", "邮政" to "中国邮政", "EMS" to "EMS",
        "京东" to "京东物流", "菜鸟" to "菜鸟驿站", "丰巢" to "丰巢",
        "德邦" to "德邦快递", "宅急送" to "宅急送",
    )

    /**
     * 从一条短信提取取件码。规则要点：
     * - 取件码常见形态：纯数字段（3-8 位）、字母+数字组合（如 3-2-1008、A12-3456）
     * - 优先匹配「取件码/凭码/提货码」等引导词后面的内容；否则取 1-3 位数字-数字 的柜机格式
     */
    fun extract(body: String, date: Long): SmsPickup? {
        if (body.length > 500) return null
        // 排除验证码类短信
        if (listOf("验证码", "校验码", "验证如下", "动态码").any { it in body }) return null

        val text = body.replace('\n', ' ')
        val codeRegexes = listOf(
            Regex("(?:取件码|提货码|凭码|出货码)[^0-9A-Za-z]{0,6}([0-9A-Za-z][0-9A-Za-z\\-]{1,11})"),
            Regex("\\b(\\d{1,2}-\\d{1,3}-\\d{2,6})\\b"),               // 丰巢/柜机格式 1-2-3456
            Regex("\\b([A-Z]-?\\d{2,3}-?\\d{2,6})\\b"),                // 字母前缀 A12-3456
        )
        val code = codeRegexes.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) } ?: return null
        // 纯数字兜底太易误伤（订单号/手机号），仅接受带引导词或分段格式的结果
        val guided = codeRegexes[0].find(text) != null
        if (!guided && !code.contains('-')) return null

        val carrier = carriers.entries.firstOrNull { text.contains(it.key) }?.value ?: ""
        val station = Regex("((?:菜鸟驿站|快递驿站|妈妈驿站|丰巢智能柜|[\\u4e00-\\u9fa5]{2,12}(?:驿站|快递点|代收点|便利店|智能柜)))")
            .find(text)?.groupValues?.get(1) ?: ""

        val id = "${code}|${station}|${date / 86_400_000}"
        return SmsPickup(id, code, carrier, station, text.take(120), date)
    }

    private fun loadItems(prefs: android.content.SharedPreferences): List<SmsPickup> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return raw.split("\u0001").filter { it.isNotBlank() }.mapNotNull { s ->
            val p = s.split("\u0002")
            if (p.size < 6) return@mapNotNull null
            SmsPickup(p[0], p[1], p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L)
        }
    }

    private fun serialize(items: List<SmsPickup>): String =
        items.joinToString("\u0001") { listOf(it.id, it.code, it.carrier, it.station, it.content, it.receivedAt).joinToString("\u0002") }
}
