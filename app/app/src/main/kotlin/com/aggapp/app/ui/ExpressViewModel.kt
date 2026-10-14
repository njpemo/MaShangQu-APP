package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 快递页 UI 状态 */
data class ExpressUiState(
    val loading: Boolean = false,
    val loggedInPlatforms: Set<Platform> = emptySet(),
    val stations: List<StationGroup> = emptyList(),   // 按驿站分组（顺丰+淘宝合并）
    val error: String? = null,
    // 运单号查询
    val searching: Boolean = false,
    val searchNotice: String? = null,                 // 查不到/未接入提示
    val searchResult: PickupCode? = null,             // 查到的取件码
    val searchDetail: com.aggapp.platform.ryll.RyllOrderDetail? = null, // 如意邻里查件详情+轨迹
    val needSuffixFor: String? = null,                // 顺丰单号不在列表 → 需输入手机后4位
)

/** 驿站分组 */
data class StationGroup(
    val stationName: String,
    val items: List<ExpressItem>,
)

/**
 * 快递页：
 * - 列表：各平台包裹按驿站分组；已签收/已取出不显示
 * - 跟踪单号：搜索查到的单自动加入跟踪（SharedPreferences 持久化），每次刷新
 *   按来源平台单独查状态；签收/查无自动移除
 */
@HiltViewModel
class ExpressViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val sessionRepo: SessionRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val adapters = registry.adapters
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tracked = MutableStateFlow(loadTracked())

    private val _ui = MutableStateFlow(ExpressUiState())
    val ui: StateFlow<ExpressUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /** 拉取各平台包裹列表 + 跟踪单状态合并（顺丰+淘宝+拼多多+如意邻里，各自独立容错） */
    fun refresh() {
        val creds = sessionRepo.credentials.value
        val loggedIn = creds.keys.filter {
            it == Platform.SF || it == Platform.TAOBAO || it == Platform.PDD || it == Platform.RYLL
        }.toSet()
        if (loggedIn.isEmpty()) {
            _ui.value = ExpressUiState(error = "请先在「我的」页登录顺丰/淘宝（短信验证码）/拼多多/如意邻里")
            return
        }
        _ui.value = _ui.value.copy(loading = true, error = null, loggedInPlatforms = loggedIn)
        viewModelScope.launch {
            val errors = mutableListOf<String>()
            val allItems = mutableListOf<ExpressItem>()
            val byNo = mutableMapOf<String, ExpressItem>()
            for (platform in listOf(Platform.SF, Platform.TAOBAO, Platform.PDD, Platform.RYLL)) {
                val cred = creds[platform] ?: continue
                try {
                    val items = mutableListOf<ExpressItem>()
                    for (page in 1..MAX_PAGES) {
                        val batch = adapters.getValue(platform).fetchExpressList(cred, page)
                        items += batch
                        if (batch.size < PAGE_SIZE) break
                    }
                    for (item in items) {
                        val no = item.trackingNo
                        if (no != null) byNo[no] = item // 全量索引（含签收，供跟踪状态判断）
                    }
                    allItems += items
                } catch (e: Exception) {
                    errors += "${platformLabel(platform)}：${e.message}"
                }
            }

            // ① 跨平台运单号去重：同一单多平台各有一份 → 合并为一条（有码优先、字段互补）
            val deduped = dedupeByTrackingNo(allItems.filter { it.status == ExpressStatus.WAIT_PICKUP })
            // ② 无码待取件：用运单号调其他平台查件接口互补补码；查不到的原样展示
            val completed = backfillPickupCodes(deduped, creds)
            // ③ 按驿站分组
            val groups = linkedMapOf<String, MutableList<ExpressItem>>()
            fun add(item: ExpressItem) {
                val name = item.station.ifBlank { "未知驿站" }
                groups.getOrPut(name) { mutableListOf() } += item
            }
            for (item in completed) add(item)

            // ④ 跟踪单：列表没有的按来源平台单独查状态；签收/查无 → 移除跟踪
            // PDD 列表查询限频（54001）：多个跟踪单共享一次拉取结果
            var pddListCache: List<ExpressItem>? = null
            val tracked = _tracked.value.toMutableMap()
            for ((no, plat) in tracked.toList()) {
                val inList = byNo[no]
                if (inList != null && inList.status == ExpressStatus.PICKED) {
                    tracked.remove(no)
                    continue
                }
                if (inList != null) continue // 列表已有且未签收
                val cred = creds[plat] ?: continue // 来源平台未登录：保留跟踪不误删
                try {
                    when (plat) {
                        Platform.PDD -> {
                            // 快递助手列表一次拿状态（含取件码），多跟踪单复用缓存
                            val list = pddListCache
                                ?: adapters.getValue(Platform.PDD).fetchExpressList(cred, 1).also { pddListCache = it }
                            val it2 = list.firstOrNull { it.trackingNo == no }
                            when {
                                it2 == null || it2.status == ExpressStatus.PICKED -> tracked.remove(no)
                                else -> add(it2)
                            }
                        }
                        Platform.RYLL -> {
                            val d = lookupRyllDetail(no)
                            when {
                                d == null || d.statusName.contains("签收") -> tracked.remove(no)
                                else -> add(
                                    ExpressItem(
                                        id = no,
                                        platform = Platform.RYLL,
                                        station = d.statusName.ifBlank { "如意邻里" },
                                        stationAddress = null,
                                        trackingNo = no,
                                        pickupCode = d.pickupCode?.takeIf { it.isNotBlank() },
                                        summary = d.receiverName?.let { "收 $it" },
                                        status = if (d.atStation) ExpressStatus.WAIT_PICKUP else ExpressStatus.UNKNOWN,
                                        arrivedAt = null,
                                        fetchDeadline = null,
                                        raw = null,
                                    ),
                                )
                            }
                        }
                        else -> {
                            val code = adapters.getValue(plat).fetchPickupCode(cred, no) ?: continue
                            add(
                                ExpressItem(
                                    id = no,
                                    platform = plat,
                                    station = "跟踪单号",
                                    stationAddress = null,
                                    trackingNo = no,
                                    pickupCode = code.code,
                                    summary = null,
                                    status = ExpressStatus.WAIT_PICKUP,
                                    arrivedAt = null,
                                    fetchDeadline = null,
                                    raw = null,
                                ),
                            )
                        }
                    }
                } catch (_: Exception) {
                    // 单次状态查询失败：保留跟踪，下次刷新再查
                }
            }
            if (tracked != _tracked.value) {
                _tracked.value = tracked
                saveTracked(tracked)
            }
            _ui.value = _ui.value.copy(
                loading = false,
                stations = groups.map { (name, list) -> StationGroup(name, list.toList()) },
                error = if (groups.isEmpty() && errors.isNotEmpty()) errors.joinToString("\n") else null,
            )
        }
    }

    /** 跨平台运单号去重：同号多平台仅保留一条（保持首次出现顺序） */
    private fun dedupeByTrackingNo(items: List<ExpressItem>): List<ExpressItem> {
        val byNo = linkedMapOf<String, ExpressItem>()
        for (item in items) {
            val no = item.trackingNo ?: continue
            val exist = byNo[no]
            byNo[no] = if (exist == null) item else mergeDuplicate(exist, item)
        }
        return byNo.values.toList()
    }

    /** 同单合并：有取件码者优先为主体；站点/地址/码/描述互补，入仓时间取更早者 */
    private fun mergeDuplicate(a: ExpressItem, b: ExpressItem): ExpressItem {
        val main = if (a.pickupCode != null) a else b
        val other = if (main === a) b else a
        return main.copy(
            station = main.station.ifBlank { other.station },
            stationAddress = main.stationAddress ?: other.stationAddress,
            pickupCode = main.pickupCode ?: other.pickupCode,
            pickupCodeSource = main.pickupCodeSource ?: other.pickupCodeSource,
            summary = main.summary ?: other.summary,
            arrivedAt = listOfNotNull(main.arrivedAt, other.arrivedAt).minOrNull(),
        )
    }

    /**
     * 无码待取件互补查询（app 聚合核心逻辑）：
     * 有取件码的待取件直接展示；无码的按「上次命中渠道 → FALLBACK_ORDER」依次用运单号调
     * 其他已登录平台的查件接口（fetchPickupCode，各平台 ExpressItem.id 均为运单号）。
     * 查到 → 填码并记录来源渠道（持久化，下次优先）；查不到 → 原样展示。
     */
    private suspend fun backfillPickupCodes(
        items: List<ExpressItem>,
        creds: Map<Platform, Credential>,
    ): List<ExpressItem> {
        val channels = loadPickupChannels()
        var channelsDirty = false
        val out = mutableListOf<ExpressItem>()
        for (item in items) {
            val no = item.trackingNo
            if (no == null || !item.pickupCode.isNullOrBlank()) {
                out += item
                continue
            }
            val recorded = channels[no]
            val candidates = buildList {
                if (recorded != null && recorded != item.platform && creds.containsKey(recorded)) add(recorded)
                for (p in FALLBACK_ORDER) {
                    if (p != item.platform && p != recorded && creds.containsKey(p)) add(p)
                }
            }
            var filled: ExpressItem? = null
            for (plat in candidates) {
                val code = try {
                    adapters.getValue(plat).fetchPickupCode(creds.getValue(plat), no)
                } catch (_: Exception) {
                    null // 单渠道失败不阻断，继续下一个
                }
                if (code != null) {
                    filled = item.copy(pickupCode = code.code, pickupCodeSource = plat)
                    if (channels[no] != plat) {
                        channels[no] = plat
                        channelsDirty = true
                    }
                    break
                }
            }
            out += filled ?: item
        }
        if (channelsDirty) savePickupChannels(channels)
        return out
    }

    /** 搜索查到的单加入跟踪（已签收的不加） */
    private fun addTracked(no: String, plat: Platform) {
        if (_tracked.value[no] == plat) return
        val m = _tracked.value + (no to plat)
        _tracked.value = m
        saveTracked(m)
    }

    private fun loadTracked(): Map<String, Platform> =
        prefs.getStringSet(KEY_TRACKED, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap()

    private fun saveTracked(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_TRACKED, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /** 取件码来源渠道（互补查询命中时记录，`no|PLATFORM` StringSet），下次补码优先走该渠道 */
    private fun loadPickupChannels(): MutableMap<String, Platform> =
        prefs.getStringSet(KEY_PICKUP_CHANNELS, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap().toMutableMap()

    private fun savePickupChannels(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_PICKUP_CHANNELS, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /** 运单号查询：SF 直查顺丰；YT(圆通) 直查如意邻里；其他淘宝→拼多多→如意邻里兜底 */
    fun lookupByTrackingNo(waybill: String) {
        val no = waybill.trim().uppercase()
        if (no.isEmpty()) return
        _ui.value = _ui.value.copy(searchResult = null, searchNotice = null, searchDetail = null)
        val creds = sessionRepo.credentials.value
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true)
            try {
                // 1) 顺丰单号
                if (SF_WAYBILL_REGEX.matches(no)) {
                    val cred = creds[Platform.SF]
                    if (cred == null) {
                        _ui.value = _ui.value.copy(searching = false, searchNotice = "请先在「我的」页登录顺丰")
                        return@launch
                    }
                    val code = adapters.getValue(Platform.SF).fetchPickupCode(cred, no)
                    if (code != null) {
                        addTracked(no, Platform.SF)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                    } else {
                        // 不在列表 → 引导输入手机后4位（sf.md §5.2 防遍历验证）
                        _ui.value = _ui.value.copy(
                            searching = false,
                            needSuffixFor = no,
                            searchNotice = "该运单不在列表中，需验证收件手机后 4 位查询",
                        )
                    }
                    return@launch
                }
                // 2) 圆通单号（YT+13 数字）→ 如意邻里轨迹 + 拼多多取件码并行（互不阻断，合并展示）
                if (RYLL_WAYBILL_REGEX.matches(no)) {
                    coroutineScope {
                        val detailDef = async { lookupRyllDetail(no) }
                        val pddDef = async {
                            creds[Platform.PDD]?.let { c ->
                                try {
                                    adapters.getValue(Platform.PDD).fetchPickupCode(c, no)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        val detail = detailDef.await()
                        val code = pddDef.await()
                        if (detail != null || code != null) {
                            val signed = detail != null &&
                                (detail.status.contains("SIGN", true) || detail.statusName.contains("签收"))
                            if (!signed) addTracked(no, if (code != null) Platform.PDD else Platform.RYLL)
                            _ui.value = _ui.value.copy(
                                searching = false,
                                searchDetail = detail,
                                searchResult = code,
                            )
                            return@coroutineScope
                        }
                        // 两边都没查到（未登录/单不在驿站在架）→ 继续淘宝兜底
                        val tbCode = creds[Platform.TAOBAO]?.let { c ->
                            try {
                                adapters.getValue(Platform.TAOBAO).fetchPickupCode(c, no)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (tbCode != null) {
                            addTracked(no, Platform.TAOBAO)
                            _ui.value = _ui.value.copy(searching = false, searchResult = tbCode)
                        } else {
                            _ui.value = _ui.value.copy(
                                searching = false,
                                searchNotice = "未查到 $no（已查如意邻里/拼多多/淘宝；单可能已取出或不在驿站在架列表）",
                            )
                        }
                    }
                    return@launch
                }
                // 3) 其他单号 → 淘宝（菜鸟代收）→ 拼多多（多多驿站）→ 如意邻里兜底
                val tbCred = creds[Platform.TAOBAO]
                if (tbCred != null) {
                    val code = try {
                        adapters.getValue(Platform.TAOBAO).fetchPickupCode(tbCred, no)
                    } catch (e: Exception) {
                        null
                    }
                    if (code != null) {
                        addTracked(no, Platform.TAOBAO)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                        return@launch
                    }
                }
                val pddCred = creds[Platform.PDD]
                if (pddCred != null) {
                    val code = try {
                        adapters.getValue(Platform.PDD).fetchPickupCode(pddCred, no)
                    } catch (e: Exception) {
                        null
                    }
                    if (code != null) {
                        addTracked(no, Platform.PDD)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                        return@launch
                    }
                }
                if (creds[Platform.RYLL] != null) {
                    val detail = lookupRyllDetail(no)
                    if (detail != null) {
                        val signed = detail.status.contains("SIGN", true) || detail.statusName.contains("签收")
                        if (!signed) addTracked(no, Platform.RYLL)
                        _ui.value = _ui.value.copy(searching = false, searchDetail = detail)
                        return@launch
                    }
                }
                _ui.value = _ui.value.copy(
                    searching = false,
                    searchNotice = "未查到 $no 的取件码（已查淘宝/拼多多/如意邻里，未登录或查不到）",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(searching = false, searchNotice = "查询失败：${e.message}")
            }
        }
    }

    /** 如意邻里查件（纯查询不置 state，调用方合并展示；null=查无此单/网络异常） */
    private suspend fun lookupRyllDetail(no: String): com.aggapp.platform.ryll.RyllOrderDetail? {
        val adapter = adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter
        return try {
            adapter.queryOrderDetail(no)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun setResult(no: String, code: PickupCode?) {
        _ui.value = if (code != null) {
            _ui.value.copy(searching = false, searchResult = code)
        } else {
            _ui.value.copy(searching = false, searchNotice = "未查到 $no 的取件码（可能已取走/单号有误/不在驿站）")
        }
    }

    /** 顺丰单号 + 手机后4位验证查询（v2/getWaybillPickupCode，逐驿站尝试） */
    fun lookupWithSuffix(suffix: String) {
        val no = _ui.value.needSuffixFor ?: return
        val s = suffix.trim()
        if (s.length != 4 || !s.all { it.isDigit() }) {
            _ui.value = _ui.value.copy(searchNotice = "请输入 4 位数字手机尾号")
            return
        }
        val cred = sessionRepo.credentials.value[Platform.SF] ?: run {
            _ui.value = _ui.value.copy(searchNotice = "请先在「我的」页登录顺丰")
            return
        }
        _ui.value = _ui.value.copy(searching = true, searchNotice = null)
        viewModelScope.launch {
            try {
                val adapter = adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter
                val code = adapter.fetchPickupCodeWithSuffix(cred, no, s)
                _ui.value = if (code != null) {
                    _ui.value.copy(searching = false, searchResult = code, needSuffixFor = null)
                } else {
                    _ui.value.copy(
                        searching = false,
                        searchNotice = "尾号 $s 未查到取件码（尾号错误/已取走/运单号有误）",
                    )
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(searching = false, searchNotice = "查询失败：${e.message}")
            }
        }
    }

    fun clearSearch() {
        _ui.value = _ui.value.copy(searchResult = null, searchNotice = null, needSuffixFor = null, searchDetail = null)
    }

    private fun platformLabel(p: Platform) = when (p) {
        Platform.SF -> "顺丰"
        Platform.TAOBAO -> "淘宝"
        Platform.JD -> "京东"
        Platform.PDD -> "拼多多"
        Platform.RYLL -> "如意邻里"
    }

    private companion object {
        const val MAX_PAGES = 3
        const val PAGE_SIZE = 10
        const val PREFS_NAME = "express_tracked"
        const val KEY_TRACKED = "tracked_nos"
        const val KEY_PICKUP_CHANNELS = "pickup_channels"

        /** 无码待取件互补查询顺序：淘宝优先（multiOneSearch 轻量）、PDD 较重（54001 限频）靠后 */
        val FALLBACK_ORDER = listOf(Platform.TAOBAO, Platform.PDD, Platform.RYLL, Platform.SF)
        val SF_WAYBILL_REGEX = Regex("^SF\\d{12,15}$")
        val RYLL_WAYBILL_REGEX = Regex("^YT\\d{13}$") // 圆通单号（如意邻里=圆通妈妈驿站）
    }
}
