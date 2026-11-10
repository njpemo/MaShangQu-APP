package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressDetail
import com.aggapp.core.model.ExpressItem
import com.aggapp.platform.api.AppLog
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

/** 快递页分段（v1.0.10 三分区） */
enum class ExpressTab(val label: String) {
    WAIT("待取件"),
    TRANSIT("运输中"),
    PICKED("已签收"),
}

/** 快递页 UI 状态 */
data class ExpressUiState(
    val loading: Boolean = false,
    val loggedInPlatforms: Set<Platform> = emptySet(),
    val stations: List<StationGroup> = emptyList(),   // 待取件（按驿站分组）
    val inTransit: List<StationGroup> = emptyList(),  // 运输中（IN_TRANSIT/UNKNOWN）
    val picked: List<StationGroup> = emptyList(),     // 已签收（PICKED/TIMEOUT + 手动标记「已取出」）
    val tab: ExpressTab = ExpressTab.WAIT,
    val error: String? = null,
    // 详情弹窗（点击卡片懒加载）
    val detailFor: ExpressItem? = null,
    val detail: ExpressDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    // 运单号查询
    val searching: Boolean = false,
    val searchNotice: String? = null,                 // 查不到/未接入提示
    val searchResult: PickupCode? = null,             // 查到的取件码
    val searchDetail: com.aggapp.platform.ryll.RyllOrderDetail? = null, // 如意邻里查件详情+轨迹
    val needSuffixFor: String? = null,                // 顺丰单号不在列表 → 需输入手机后4位
    // 短信取件码（本地提取，非平台数据）
    val smsPickups: List<com.aggapp.app.sms.SmsCodeExtractor.SmsPickup> = emptyList(),
    val smsPickedIds: Set<String> = emptySet(),
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

    private val _context: Context = context

    private val _ui = MutableStateFlow(ExpressUiState())
    val ui: StateFlow<ExpressUiState> = _ui.asStateFlow()

    /** 上次刷新时间，进入页面自动刷新用 30s 节流 */
    private var lastRefreshAt = 0L

    /** 本轮已完成平台的原始列表：流式上屏 + 收尾全量流程共用 */
    private val rawItems = mutableMapOf<Platform, List<ExpressItem>>()

    /** 跟踪单最近一次的卡片形态：部分上屏期间占位，避免已跟踪单在流式刷新中闪烁消失 */
    private val trackedCache = mutableMapOf<String, ExpressItem>()

    /** 刷新进行中标记：流式上屏会提前解除 loading，此标记防并发重入 */
    private var inFlight = false

    // 注意：可变字段必须全部声明在 init 之前——init 里的 refresh 走 Main.immediate
    // 同步执行到首个挂起点，后置声明字段此时仍为 null（v1.0.10 启动闪退根因）
    init {
        refresh(force = true)
    }

    /** 清除查询结果展示（点空白处/刷新时调用；结果单号已加入跟踪列表，不受影响） */
    fun clearSearchResult() {
        _ui.value = _ui.value.copy(searchResult = null, searchDetail = null, searchNotice = null)
    }

    /**
     * 拉取各平台包裹列表 + 跟踪单状态合并（顺丰+淘宝+拼多多+如意邻里，各自独立容错）。
     * v1.0.10：四平台并行拉取（PDD 内部 2s 翻页/0.8s 源间限频延迟只约束自身协程，原样保留），
     * 每个平台完成即流式上屏（先到先显示，首屏从「最慢平台」变「最快平台」）；
     * 全部完成后收尾跑完整流程（去重+互补补码+跟踪单补查）再上屏一次。
     *
     * @param force 用户主动刷新用 true：强制执行并显示 loading；
     *              进入页面自动刷新用默认 false：30s 节流，已有数据时静默刷新（不闪 loading，
     *              全部失败保留旧列表不弹错）
     */
    fun refresh(force: Boolean = false) {
        if (inFlight) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now
        val silent = !force && _ui.value.stations.isNotEmpty()
        val creds = sessionRepo.credentials.value
        val loggedIn = creds.keys.filter {
            it == Platform.SF || it == Platform.TAOBAO || it == Platform.PDD || it == Platform.RYLL
        }.toSet()
        if (loggedIn.isEmpty()) {
            _ui.value = ExpressUiState(error = "请先在「我的」页登录顺丰/淘宝（短信验证码）/拼多多/如意邻里")
            return
        }
        _ui.value = _ui.value.copy(loading = !silent, error = null, loggedInPlatforms = loggedIn, searchResult = null, searchDetail = null, searchNotice = null)
        viewModelScope.launch {
            inFlight = true
            try {
                rawItems.clear()
                val errors = mutableListOf<String>()
                // 短信取件码与平台拉取并行（本地提取，独立容错不干扰平台流程）
                launch { runCatching { scanSmsQuiet() } }
                // 四平台并行：独立协程独立容错；单平台完成即发布部分数据上屏
                coroutineScope {
                    for (platform in PLATFORM_ORDER) {
                        val cred = creds[platform] ?: continue
                        launch {
                            try {
                                val items = mutableListOf<ExpressItem>()
                                for (page in 1..MAX_PAGES) {
                                    val batch = adapters.getValue(platform).fetchExpressList(cred, page)
                                    items += batch
                                    if (batch.size < PAGE_SIZE) break
                                }
                                rawItems[platform] = items
                                publishPartial()
                            } catch (e: Exception) {
                                errors += "${platformLabel(platform)}：${e.message}"
                            }
                        }
                    }
                }
                finalizeRefresh(silent, creds, errors)
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * 流式上屏：已完成平台的三分区拆分后立即显示（互补补码/跟踪单补查留到收尾阶段）。
     * 跟踪单并入 trackedCache 占位，避免部分上屏期间已跟踪单闪没；
     * 某分区结果为空时保留旧值（其余平台仍在途，避免「暂无包裹」误闪）。
     */
    private fun publishPartial() {
        val all = dedupeByTrackingNo(rawItems.values.flatten() + trackedCache.values)
        val (wait, transit, picked) = segmentsOf(all)
        if (wait.isEmpty() && transit.isEmpty() && picked.isEmpty()) return
        _ui.value = _ui.value.copy(
            loading = false,
            stations = if (wait.isNotEmpty()) groupByStation(wait) else _ui.value.stations,
            inTransit = if (transit.isNotEmpty()) groupByStation(transit) else _ui.value.inTransit,
            picked = if (picked.isNotEmpty()) groupByStation(picked) else _ui.value.picked,
        )
    }

    /**
     * 收尾（全部平台完成后执行）：跨平台去重 → 互补补码 → 跟踪单补查 → 分组上屏。
     * 语义与 v1.0.9 串行实现逐字一致，只是把「全部拉完才显示」改为流式 + 收尾两段。
     */
    private suspend fun finalizeRefresh(silent: Boolean, creds: Map<Platform, Credential>, errors: List<String>) {
        // 全量索引（含签收，供跟踪状态判断）
        val byNo = mutableMapOf<String, ExpressItem>()
        for (items in rawItems.values) {
            for (item in items) item.trackingNo?.let { byNo[it] = item }
        }
        // ① 跨平台运单号去重：同一单多平台各有一份 → 合并为一条（有码优先、字段互补）。
        // v1.0.10 全状态进入三分区（不再只保留待取件，否则收尾重上屏会清空运输中/已签收）
        val deduped = dedupeByTrackingNo(rawItems.values.flatten())
        // ② 无码待取件：用运单号调其他平台查件接口互补补码；查不到的原样展示
        val completed = backfillPickupCodes(deduped, creds)

        // ③ 跟踪单：列表没有的按来源平台单独查状态；查无 → 移除跟踪；已签收 → 保留进已签收分区
        // PDD 列表查询限频（54001）：多个跟踪单共享一次拉取结果
        var pddListCache: List<ExpressItem>? = null
        val tracked = _tracked.value.toMutableMap()
        val trackedItems = mutableListOf<ExpressItem>()
        for ((no, plat) in tracked.toList()) {
            val inList = byNo[no]
            if (inList != null) {
                // 列表已有：签收/超时的保留展示（进已签收分区），未签收的不动
                if (inList.status == ExpressStatus.PICKED || inList.status == ExpressStatus.TIMEOUT) {
                    trackedItems += inList
                    trackedCache[no] = inList
                }
                continue
            }
            val cred = creds[plat] ?: continue // 来源平台未登录：保留跟踪不误删
            try {
                when (plat) {
                    Platform.PDD -> {
                        // 快递助手列表一次拿状态（含取件码），多跟踪单复用缓存
                        val list = pddListCache
                            ?: adapters.getValue(Platform.PDD).fetchExpressList(cred, 1).also { pddListCache = it }
                        val it2 = list.firstOrNull { it.trackingNo == no }
                        when {
                            // 查无此单 → 移除跟踪；查到（含已签收）→ 保留展示
                            it2 == null -> {
                                tracked.remove(no)
                                trackedCache.remove(no)
                            }
                            else -> {
                                trackedItems += it2
                                trackedCache[no] = it2
                            }
                        }
                    }
                    Platform.RYLL -> {
                        // 必须带 token：mobileCheck 验证态绑定会话，无 token 详情永远不出取件码段
                        val d = lookupRyllDetail(no, cred.token)
                        if (d == null) {
                            // 查无此单 → 移除跟踪
                            tracked.remove(no)
                            trackedCache.remove(no)
                        } else {
                            // 签收 → 已签收分区；在架 → 待取件；其余 → 运输中
                            val status = when {
                                d.statusName.contains("签收") -> ExpressStatus.PICKED
                                d.atStation -> ExpressStatus.WAIT_PICKUP
                                else -> ExpressStatus.UNKNOWN
                            }
                            val item = ExpressItem(
                                id = no,
                                platform = Platform.RYLL,
                                // 驿站名：详情解析出的真名 → 状态文案兜底（此前只显示"已到代收点"）
                                station = d.stationName?.takeIf { it.isNotBlank() }
                                    ?: d.statusName.ifBlank { "如意邻里" },
                                stationAddress = null,
                                trackingNo = no,
                                pickupCode = d.pickupCode?.takeIf { it.isNotBlank() },
                                summary = d.receiverName?.let { "收 $it" },
                                status = status,
                                arrivedAt = null,
                                fetchDeadline = null,
                                raw = null,
                            )
                            trackedItems += item
                            trackedCache[no] = item
                        }
                    }
                    else -> {
                        val code = adapters.getValue(plat).fetchPickupCode(cred, no) ?: continue
                        val item = ExpressItem(
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
                        )
                        trackedItems += item
                        trackedCache[no] = item
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
        // 三分区拆分（手动标记「已取出」的单一优先落入已签收分区）
        val (wait, transit, picked) = segmentsOf(dedupeByTrackingNo(completed + trackedItems))
        val groups = groupByStation(wait)
        val transitGroups = groupByStation(transit)
        val pickedGroups = groupByStation(picked)
        val allEmpty = groups.isEmpty() && transitGroups.isEmpty() && pickedGroups.isEmpty()
        val keepOld = silent && allEmpty && errors.isNotEmpty() // 静默刷新全部失败时保留旧列表
        _ui.value = _ui.value.copy(
            loading = false,
            stations = if (keepOld) _ui.value.stations else groups,
            inTransit = if (keepOld) _ui.value.inTransit else transitGroups,
            picked = if (keepOld) _ui.value.picked else pickedGroups,
            error = if (silent) {
                null // 静默刷新不弹错误（旧数据仍在展示）
            } else if (allEmpty && errors.isNotEmpty()) errors.joinToString("\n") else null,
        )
    }

    /**
     * 三分区拆分（v1.0.10）：待取件=WAIT_PICKUP｜运输中=IN_TRANSIT+UNKNOWN｜已签收=PICKED+TIMEOUT。
     * 手动标记「已取出」的单号（picked_overrides）强制落入已签收分区（平台仍报待取时以标记为准）。
     */
    private fun segmentsOf(items: List<ExpressItem>): Triple<List<ExpressItem>, List<ExpressItem>, List<ExpressItem>> {
        val overrides = loadOverrides()
        val wait = mutableListOf<ExpressItem>()
        val transit = mutableListOf<ExpressItem>()
        val picked = mutableListOf<ExpressItem>()
        for (item in items) {
            val no = item.trackingNo ?: item.id
            when {
                overrides.containsKey(no) -> picked += item.copy(status = ExpressStatus.PICKED)
                item.status == ExpressStatus.WAIT_PICKUP -> wait += item
                item.status == ExpressStatus.PICKED || item.status == ExpressStatus.TIMEOUT -> picked += item
                else -> transit += item // IN_TRANSIT / UNKNOWN
            }
        }
        return Triple(wait, transit, picked)
    }

    /** 按驿站分组（跨平台合并；流式上屏与收尾共用） */
    private fun groupByStation(items: List<ExpressItem>): List<StationGroup> {
        val groups = linkedMapOf<String, MutableList<ExpressItem>>()
        for (item in items) {
            val name = item.station.ifBlank { "未知驿站" }
            groups.getOrPut(name) { mutableListOf() } += item
        }
        return groups.map { (name, list) -> StationGroup(name, list.toList()) }
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
            // 补码只针对无码待取件；运输中/已签收等直接透传（不对已签收单浪费跨平台查询）
            // 伪码处理：PDD 对菜鸟驿站单会返回「取件出示单号后五位08186」这类提示文案而非真码
            //（特征：含中文或含 5 位纯数字后缀模式）。视为伪码 → 仍走互补查询
            //（淘宝 multiOneSearch 可查到真码）；查不到时保留伪码兜底展示
            val effectiveCode = item.pickupCode?.takeIf { it.isNotBlank() && isFakePickupCode(it) }
            if (no == null || item.status != ExpressStatus.WAIT_PICKUP || effectiveCode == null) {
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
                } catch (e: Exception) {
                    AppLog.e("Express", "fillCode $no: ${platformLabel(plat)} fetchPickupCode 失败", e)
                    null // 单渠道失败不阻断，继续下一个
                }
                if (code != null && !isFakePickupCode(code.code)) {
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

    /** 切换分段（待取件/运输中/已签收） */
    fun setTab(tab: ExpressTab) {
        _ui.value = _ui.value.copy(tab = tab)
    }

    /**
     * 伪码判定：真取件码是纯数字/字母数字/分段格式（如 3-2-1008、A12-3456）。
     * 含中文（如「取件出示单号后五位08186」「凭手机尾号取件」）视为提示文案非真码。
     */
    private fun isFakePickupCode(code: String): Boolean =
        code.any { it.code > 0x4E00 && it.code < 0x9FFF }

    // ============ 短信取件码（本地提取，数据仅存本机） ============

    /** 进入页面加载本地已提取的短信取件码 */
    fun loadSmsPickups() {
        val ctx = _context
        _ui.value = _ui.value.copy(
            smsPickups = dedupeSmsByExisting(com.aggapp.app.sms.SmsCodeExtractor.loadAll(ctx)),
            smsPickedIds = com.aggapp.app.sms.SmsCodeExtractor.pickedIds(ctx),
        )
    }

    /** 短信取件码与平台列表去重：平台侧已有同码的条目不再重复展示 */
    private fun dedupeSmsByExisting(list: List<com.aggapp.app.sms.SmsCodeExtractor.SmsPickup>): List<com.aggapp.app.sms.SmsCodeExtractor.SmsPickup> {
        val codes = _ui.value.stations.asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { it.pickupCode }
            .toSet()
        return list.filterNot { it.code in codes }
    }

    /** 刷新时随平台拉取并行执行：静默扫描短信（无权限则跳过，不打扰） */
    private suspend fun scanSmsQuiet() {
        val ctx = _context
        if (!com.aggapp.app.sms.SmsCodeExtractor.hasReadSmsPermission(ctx)) return
        com.aggapp.app.sms.SmsCodeExtractor.scanAndMerge(ctx).onSuccess {
            _ui.value = _ui.value.copy(
                smsPickups = dedupeSmsByExisting(com.aggapp.app.sms.SmsCodeExtractor.loadAll(ctx)),
                smsPickedIds = com.aggapp.app.sms.SmsCodeExtractor.pickedIds(ctx),
            )
        }
    }

    fun markSmsPicked(id: String) {
        com.aggapp.app.sms.SmsCodeExtractor.markPicked(_context, id)
        _ui.value = _ui.value.copy(smsPickedIds = _ui.value.smsPickedIds + id)
    }

    fun deleteSmsPickup(id: String) {
        com.aggapp.app.sms.SmsCodeExtractor.delete(_context, id)
        _ui.value = _ui.value.copy(
            smsPickups = _ui.value.smsPickups.filterNot { it.id == id },
        )
    }

    /**
     * 待取件 → 已签收（手动标记）：立即移区 + 落盘（picked_overrides，LRU 200 条）。
     * 平台侧后续仍报待取时也以标记为准（segmentsOf 强制落入已签收分区）。
     */
    fun markPicked(item: ExpressItem) {
        val no = item.trackingNo ?: item.id
        val overrides = loadOverrides()
        if (!overrides.containsKey(no)) {
            saveOverrides(overrides + (no to System.currentTimeMillis()))
        }
        val marked = item.copy(status = ExpressStatus.PICKED)
        _ui.value = _ui.value.copy(
            stations = removeFromGroups(_ui.value.stations, no),
            inTransit = removeFromGroups(_ui.value.inTransit, no),
            picked = addToGroups(_ui.value.picked, marked),
        )
    }

    /** 详情弹窗懒加载：点击卡片触发对应平台 fetchDetail；不支持/失败降级提示（卡片字段仍可见） */
    fun loadDetail(item: ExpressItem) {
        _ui.value = _ui.value.copy(detailFor = item, detail = null, detailLoading = true, detailError = null)
        viewModelScope.launch {
            val cred = sessionRepo.credentials.value[item.platform]
            if (cred == null) {
                _ui.value = _ui.value.copy(detailLoading = false, detailError = "${platformLabel(item.platform)}未登录，无法查询详情")
                return@launch
            }
            // 详情缓存：同单短时间复用，减少平台侧查询频率（防限频 54001）
            val now = System.currentTimeMillis()
            val cached = detailCache[item.id]
            if (cached != null) {
                val ttl = if (item.status == ExpressStatus.PICKED || item.status == ExpressStatus.TIMEOUT) {
                    DETAIL_CACHE_TTL_TERMINAL
                } else {
                    DETAIL_CACHE_TTL_TRANSIT
                }
                if (now - cached.first < ttl) {
                    _ui.value = _ui.value.copy(detailLoading = false, detail = cached.second)
                    return@launch
                }
            }
            try {
                val d = adapters.getValue(item.platform).fetchDetail(cred, item)
                if (d != null) detailCache[item.id] = now to d
                _ui.value = _ui.value.copy(
                    detailLoading = false,
                    detail = d,
                    detailError = if (d == null) "该包裹暂不支持物流详情" else null,
                )
            } catch (e: Exception) {
                AppLog.e("Express", "detail ${item.trackingNo} 失败", e)
                _ui.value = _ui.value.copy(detailLoading = false, detailError = "查询失败：${e.message}")
            }
        }
    }

    /** 关闭详情弹窗（清空懒加载状态） */
    fun dismissDetail() {
        _ui.value = _ui.value.copy(detailFor = null, detail = null, detailLoading = false, detailError = null)
    }

    /** 详情缓存：单号 → (时间戳, detail)。已签收单轨迹不再变化，缓存更久 */
    private val detailCache = mutableMapOf<String, Pair<Long, ExpressDetail>>()

    /** 从分组中移除指定单号；组空则删除组 */
    private fun removeFromGroups(groups: List<StationGroup>, no: String): List<StationGroup> =
        groups.mapNotNull { g ->
            g.items.filterNot { (it.trackingNo ?: it.id) == no }
                .takeIf { it.isNotEmpty() }?.let { StationGroup(g.stationName, it) }
        }

    /** 已签收分区加入一条（按驿站归组；新驿站组置于最前） */
    private fun addToGroups(groups: List<StationGroup>, item: ExpressItem): List<StationGroup> {
        val name = item.station.ifBlank { "未知驿站" }
        val out = groups.toMutableList()
        val idx = out.indexOfFirst { it.stationName == name }
        if (idx >= 0) out[idx] = StationGroup(name, out[idx].items + item) else out.add(0, StationGroup(name, listOf(item)))
        return out
    }

    /** 手动标记「已取出」：单号 → 标记时间戳（prefs StringSet `no|ts`，按时间戳 LRU 保留 200 条） */
    private fun loadOverrides(): Map<String, Long> =
        prefs.getStringSet(KEY_PICKED_OVERRIDES, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val ts = s.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            s.substring(0, i) to ts
        }.toMap()

    private fun saveOverrides(m: Map<String, Long>) {
        val entries = m.entries.sortedByDescending { it.value }.take(OVERRIDES_MAX)
        prefs.edit().putStringSet(KEY_PICKED_OVERRIDES, entries.map { "${it.key}|${it.value}" }.toSet()).apply()
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
                        AppLog.w("Express", "lookup $no: 顺丰未登录，跳过")
                        _ui.value = _ui.value.copy(searching = false, searchNotice = "请先在「我的」页登录顺丰")
                        return@launch
                    }
                    val code = try {
                        adapters.getValue(Platform.SF).fetchPickupCode(cred, no)
                    } catch (e: Exception) {
                        AppLog.e("Express", "lookup $no: 顺丰 fetchPickupCode 失败", e)
                        null
                    }
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
                        val detailDef = async { lookupRyllDetail(no, creds[Platform.RYLL]?.token) }
                        val pddDef = async {
                            creds[Platform.PDD]?.let { c ->
                                try {
                                    adapters.getValue(Platform.PDD).fetchPickupCode(c, no)
                                } catch (e: Exception) {
                                    AppLog.e("Express", "lookup $no: 拼多多 fetchPickupCode 失败", e)
                                    null
                                }
                            }
                        }
                        val detail = detailDef.await()
                        val code = pddDef.await()
                        // 如意邻里详情已直出取件码（owner 单/验证态已置位）→ 直接用，不再引导尾号验证
                        val ryllCode = code ?: detail?.pickupCode
                            ?.takeIf { it.isNotBlank() }
                            ?.let { com.aggapp.core.model.PickupCode(no, Platform.RYLL, it) }
                        if (detail != null || ryllCode != null) {
                            val signed = detail != null &&
                                (detail.status.contains("SIGN", true) || detail.statusName.contains("签收"))
                            if (!signed) addTracked(no, if (ryllCode != null) ryllCode.platform else Platform.RYLL)
                            // 已拿到链路详情/取件码 → 不再引导尾号验证（避免结果与验证卡同时出现）
                            _ui.value = _ui.value.copy(
                                searching = false,
                                searchDetail = detail,
                                searchResult = ryllCode,
                            )
                            return@coroutineScope
                        }
                        // 两边都没查到（未登录/单不在驿站在架）→ 继续淘宝兜底
                        val tbCode = creds[Platform.TAOBAO]?.let { c ->
                            try {
                                adapters.getValue(Platform.TAOBAO).fetchPickupCode(c, no)
                            } catch (e: Exception) {
                                AppLog.e("Express", "lookup $no: 淘宝 fetchPickupCode 失败", e)
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
                        AppLog.e("Express", "lookup $no: 淘宝 fetchPickupCode 失败", e)
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
                        AppLog.e("Express", "lookup $no: 拼多多 fetchPickupCode 失败", e)
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

    /** 如意邻里查件（纯查询不置 state，调用方合并展示；null=查无此单/网络异常；带 token 复用 mobileCheck 验证态） */
    private suspend fun lookupRyllDetail(
        no: String,
        token: String? = null,
    ): com.aggapp.platform.ryll.RyllOrderDetail? {
        val adapter = adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter
        return try {
            adapter.queryOrderDetail(no, token)
        } catch (e: Exception) {
            AppLog.e("Express", "lookup $no: 如意邻里 queryOrderDetail 失败", e)
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

    /**
     * 单号 + 手机后4位验证查询：
     * YT 单 → 如意邻里 mobileCheck（验证态绑定 token 会话）后重查详情出码；
     * 其他 → 顺丰 v2/getWaybillPickupCode（逐驿站尝试）。
     */
    fun lookupWithSuffix(suffix: String) {
        val no = _ui.value.needSuffixFor ?: return
        val s = suffix.trim()
        if (s.length != 4 || !s.all { it.isDigit() }) {
            _ui.value = _ui.value.copy(searchNotice = "请输入 4 位数字手机尾号")
            return
        }
        val isRyll = RYLL_WAYBILL_REGEX.matches(no)
        val plat = if (isRyll) Platform.RYLL else Platform.SF
        val cred = sessionRepo.credentials.value[plat] ?: run {
            _ui.value = _ui.value.copy(searchNotice = "请先在「我的」页登录${platformLabel(plat)}")
            return
        }
        _ui.value = _ui.value.copy(searching = true, searchNotice = null)
        viewModelScope.launch {
            try {
                val code = if (isRyll) {
                    (adapters.getValue(Platform.RYLL) as com.aggapp.platform.ryll.RyllAdapter)
                        .fetchPickupCodeWithTail(cred, no, s)
                } else {
                    (adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter)
                        .fetchPickupCodeWithSuffix(cred, no, s)
                }
                if (code != null) addTracked(no, plat)
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

    /** 收回尾号验证引导（点关闭/空白处） */
    fun dismissSuffix() {
        _ui.value = _ui.value.copy(needSuffixFor = null, searchNotice = null)
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
        const val KEY_PICKED_OVERRIDES = "picked_overrides"

        /** 详情缓存 TTL：运输中 60s（轨迹会更新）；已签收/超时 24h（轨迹不再变化） */
        const val DETAIL_CACHE_TTL_TRANSIT = 60_000L
        const val DETAIL_CACHE_TTL_TERMINAL = 24 * 60 * 60 * 1000L

        /** 手动标记「已取出」最多保留条数（LRU） */
        const val OVERRIDES_MAX = 200

        /** 并行拉取的平台集合（顺序仅决定展示合并次序，不决定耗时） */
        val PLATFORM_ORDER = listOf(Platform.SF, Platform.TAOBAO, Platform.PDD, Platform.RYLL)

        /** 无码待取件互补查询顺序：淘宝优先（multiOneSearch 轻量）、PDD 较重（54001 限频）靠后 */
        val FALLBACK_ORDER = listOf(Platform.TAOBAO, Platform.PDD, Platform.RYLL, Platform.SF)
        val SF_WAYBILL_REGEX = Regex("^SF\\d{12,15}$")
        val RYLL_WAYBILL_REGEX = Regex("^YT\\d{13}$") // 圆通单号（如意邻里=圆通妈妈驿站）
    }
}
