package com.aggapp.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.aggapp.app.ui.theme.Brand
import com.aggapp.core.model.ExpressDetail
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快递页：顶部渐变头部 + 运单号查询卡 + 三分段列表（待取件/运输中/已签收）+ 详情弹窗。
 * v1.0.10：分段控件与身份码页同风格（浅灰轨道 + 选中白底浮起 + 数量角标）；
 * 待取件卡片带「已取出」按钮移入已签收；点卡片弹 ModalBottomSheet 懒加载物流详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressScreen(viewModel: ExpressViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    var waybillInput by remember { mutableStateOf("") }
    var suffixInput by remember { mutableStateOf("") }
    val toast = rememberToast()

    // 每次进入本页自动刷新（VM 内 30s 节流 + 静默合并，不闪 loading；刷新时并行扫描短信取件码）
    // 首次进入请求短信权限（拒绝过则不再重复弹，可去系统设置开启）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var smsPermAsked by remember { mutableStateOf(false) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        viewModel.loadSmsPickups()
        viewModel.refresh()
        if (!smsPermAsked &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.READ_SMS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            smsPermAsked = true
            permLauncher.launch(android.Manifest.permission.READ_SMS)
        }
    }

    // 点击页面空白处清除查询结果展示（结果单号已加入跟踪列表）
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interaction, indication = null) { viewModel.clearSearchResult() },
    ) {
        // ===== 简洁头部（surface 底，与身份码页/底部导航同风格）=====
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "快递",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "包裹动态与取件码查询",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                // 手动刷新（force=true 绕过 30s 自动刷新节流）
                IconButton(onClick = { viewModel.refresh(force = true) }, enabled = !ui.loading) {
                    if (ui.loading) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh, "刷新",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                // 尾号验证引导弹出时，点空白处收回（卡片内控件自行消费点击，不受影响）
                .clickable(enabled = ui.needSuffixFor != null) { viewModel.dismissSuffix() },
        ) {
            Spacer(Modifier.height(16.dp))

            // ============ 运单号查询卡 ============
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = waybillInput,
                            onValueChange = { waybillInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("运单号") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                viewModel.lookupByTrackingNo(waybillInput)
                                waybillInput = "" // 查询后清空输入框（结果已在下方展示/加入跟踪）
                            },
                            enabled = waybillInput.isNotBlank() && !ui.searching,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Indigo),
                        ) {
                            if (ui.searching) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("查询")
                        }
                    }

                    ui.searchResult?.let { code ->
                        val copy = rememberCopy()
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F0F8), RoundedCornerShape(12.dp))
                                .clickable { copy(code.code) }
                                .padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    "取件码（${code.expressId}）· 点击复制",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    code.code,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = Brand.Indigo,
                                )
                            }
                        }
                    }
                    ui.searchDetail?.let { d ->
                        Spacer(Modifier.height(8.dp))
                        RyllDetailCard(d)
                    }
                    ui.searchNotice?.let { notice ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 手机后4位验证（单号不在列表/详情无码时；可点关闭或空白处收回）
            ui.needSuffixFor?.let { no ->
                Spacer(Modifier.height(10.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "验证收件手机后 4 位查询 $no",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = viewModel::dismissSuffix) {
                                Icon(Icons.Filled.Close, "收回", Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = suffixInput,
                                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) suffixInput = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("手机后4位") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = { viewModel.lookupWithSuffix(suffixInput) },
                                enabled = suffixInput.length == 4 && !ui.searching,
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("验证查询") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ============ 三分段控件（与身份码页同风格：浅灰轨道 + 选中白底浮起 + 数量角标）============
            SegmentTabs(
                current = ui.tab,
                counts = mapOf(
                    ExpressTab.WAIT to ui.stations.sumOf { it.items.size } + ui.smsPickups.count { it.id !in ui.smsPickedIds },
                    ExpressTab.TRANSIT to ui.inTransit.sumOf { it.items.size },
                    ExpressTab.PICKED to ui.picked.sumOf { it.items.size },
                ),
                onSelect = viewModel::setTab,
            )

            Spacer(Modifier.height(4.dp))

            // ============ 分段包裹列表（按驿站分组；待取件分区首组前并入短信识别的取件码） ============
            val smsWaiting = if (ui.tab == ExpressTab.WAIT) {
                ui.smsPickups.filterNot { it.id in ui.smsPickedIds }
            } else {
                emptyList()
            }
            val groups = when (ui.tab) {
                ExpressTab.WAIT -> ui.stations
                ExpressTab.TRANSIT -> ui.inTransit
                ExpressTab.PICKED -> ui.picked
            }
            when {
                ui.loading && groups.isEmpty() && smsWaiting.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Indigo)
                }

                ui.error != null && groups.isEmpty() && smsWaiting.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(ui.error ?: "未知错误", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.refresh(force = true) }, shape = RoundedCornerShape(12.dp)) { Text("重试") }
                }

                groups.isEmpty() && smsWaiting.isEmpty() -> Text(
                    when (ui.tab) {
                        ExpressTab.WAIT -> "暂无待取件包裹"
                        ExpressTab.TRANSIT -> "暂无运输中包裹"
                        ExpressTab.PICKED -> "暂无已签收包裹"
                    },
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn {
                    // 短信识别组（仅待取件分区）：无站点信息时归入「短信识别」组名
                    if (smsWaiting.isNotEmpty()) {
                        item(key = "header_${ui.tab.name}_sms") {
                            Row(
                                Modifier.padding(top = 16.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.LocationOn, null, Modifier.size(18.dp), tint = Brand.Indigo,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "短信识别",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "来自本机短信 · 自动识别",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(smsWaiting, key = { "sms_${it.id}" }) { p ->
                            SmsPickupRow(
                                p = p,
                                onPicked = {
                                    viewModel.markSmsPicked(p.id)
                                    toast("已标记已取")
                                },
                                onDelete = {
                                    viewModel.deleteSmsPickup(p.id)
                                    toast("已删除")
                                },
                            )
                        }
                    }
                    groups.forEach { group ->
                        item(key = "header_${ui.tab.name}_${group.stationName}") {
                            Row(
                                Modifier.padding(top = 16.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.LocationOn,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = Brand.Indigo,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    group.stationName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        items(group.items, key = { "pkg_${ui.tab.name}_${it.id}" }) { item ->
                            ExpressItemRow(
                                item = item,
                                showPickedAction = ui.tab != ExpressTab.PICKED, // 待取件/运输中均可手动标记已取出
                                onPicked = {
                                    viewModel.markPicked(item)
                                    toast("已移入已签收")
                                },
                                onClick = { viewModel.loadDetail(item) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ============ 详情弹窗（点击卡片懒加载） ============
    val detailItem = ui.detailFor
    if (detailItem != null) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissDetail) {
            ExpressDetailSheet(
                item = detailItem,
                detail = ui.detail,
                loading = ui.detailLoading,
                error = ui.detailError,
                onRetry = { viewModel.loadDetail(detailItem) },
                toast = toast,
            )
        }
    }
}

/** 分段控件（待取件/运输中/已签收）：与身份码页平台切换同风格，右侧带数量角标 */
@Composable
private fun SegmentTabs(current: ExpressTab, counts: Map<ExpressTab, Int>, onSelect: (ExpressTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (tab in ExpressTab.entries) {
            val selected = tab == current
            val count = counts[tab] ?: 0
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tab.label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (count > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$count",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Brand.Indigo else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 复制文本到剪贴板并 Toast 提示 */
@Composable
private fun rememberCopy(): (String) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return { text ->
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
        android.widget.Toast.makeText(ctx, "已复制：$text", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 短信识别取件码卡片（样式对齐 ExpressItemRow）：快递首字图标 + 取件码大字 + 快递公司/站点/时间/短信摘要 */
@Composable
private fun SmsPickupRow(
    p: com.aggapp.app.sms.SmsCodeExtractor.SmsPickup,
    onPicked: () -> Unit,
    onDelete: () -> Unit,
) {
    val copy = rememberCopy()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ===== 头部行：快递公司首字图标 + 状态标签 =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brand.Indigo.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (p.carrier.ifBlank { "短信" }).first().toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Indigo,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.carrier.ifBlank { "未知快递" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .background(Brand.Indigo.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text("待取件", style = MaterialTheme.typography.labelSmall, color = Brand.Indigo)
                        }
                    }
                    p.station.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 短信原文摘要（展示更多信息；点击复制取件码）
            Text(
                p.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { copy(p.content) },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    fmtTime(p.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        p.code,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { copy(p.code) },
                        color = Brand.Indigo,
                    )
                    TextButton(
                        onClick = onPicked,
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text("已取出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, "删除", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Toast 提示 */
@Composable
private fun rememberToast(): (String) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable
private fun ExpressItemRow(
    item: ExpressItem,
    showPickedAction: Boolean,
    onPicked: () -> Unit,
    onClick: () -> Unit,
) {
    val copy = rememberCopy()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ===== 头部行：商品图（无则平台色首字降级）+ 单号/状态 + 商品名 =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                val accent = Brand.PlatformColor[item.platform] ?: Brand.Indigo
                val img = item.imageUrl
                if (img != null) {
                    AsyncImage(
                        model = img,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            item.platform.label.first().toString(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            item.trackingNo.orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            // 复制运单号仅在详情弹窗内可点，列表卡片纯展示
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        val picked = item.status == ExpressStatus.WAIT_PICKUP
                        val transit = item.status == ExpressStatus.IN_TRANSIT
                        Box(
                            Modifier
                                .background(
                                    when {
                                        picked -> Brand.Indigo.copy(alpha = 0.12f)
                                        transit -> Brand.Blue.copy(alpha = 0.12f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                when (item.status) {
                                    ExpressStatus.WAIT_PICKUP -> "待取件"
                                    ExpressStatus.IN_TRANSIT -> "运输中"
                                    ExpressStatus.PICKED -> "已取出"
                                    ExpressStatus.TIMEOUT -> "超时"
                                    ExpressStatus.UNKNOWN -> "未知"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    picked -> Brand.Indigo
                                    transit -> Brand.Blue
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    // 商品名单行小字
                    item.summary?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    item.arrivedAt?.let { "入仓 " + fmtTime(it) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.pickupCode?.let {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                it,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { copy(it) },
                                color = Brand.Indigo,
                            )
                            // 互补查询命中时标注取件码来源渠道（如「来自淘宝」）
                            item.pickupCodeSource?.let { src ->
                                Text(
                                    "来自${src.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // 「已取出」：待取件分区内一键移入已签收（小号灰字，避免喧宾夺主）
                    if (showPickedAction) {
                        TextButton(
                            onClick = onPicked,
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text(
                                "已取出",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
private fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))

/** 如意邻里查件详情卡（orderDetailByWayBillNo，2026-09-29 实测）：状态 + 收寄件 + 轨迹时间线 */
@Composable
private fun RyllDetailCard(d: com.aggapp.platform.ryll.RyllOrderDetail) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.mailNo, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    d.statusName.ifBlank { "查询成功" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (d.atStation) Brand.Indigo else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    "${d.fromCity.orEmpty()} → ${d.toCity.orEmpty()}".ifBlank { null },
                    d.senderName?.let { "寄 $it" },
                    d.receiverName?.let { "收 $it" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.stationName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "驿站 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Indigo,
                    fontWeight = FontWeight.Medium,
                )
            }
            d.pickupCode?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "取件码 $it",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.Indigo,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            // 轨迹区限高（约 6 条），超出窗口内上下滑动（外层整个页面不跟着撑长）
            val tracks = if (expanded) d.tracks else d.tracks.take(3)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (expanded) 320.dp else Dp.Unspecified),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    tracks.forEach { t ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(t.opName.orEmpty(), style = MaterialTheme.typography.labelMedium)
                                Text(
                                    t.opTime.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            t.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (d.tracks.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起轨迹" else "展开全部 ${d.tracks.size} 条轨迹")
                }
            }
        }
    }
}

/**
 * 详情弹窗内容：商品图/平台色图标 + 商品名 + 单号（点击复制）+ 状态 +
 * 收寄信息 + 轨迹时间线；加载中骨架屏，结果 120ms 淡入。
 */
@Composable
private fun ExpressDetailSheet(
    item: ExpressItem,
    detail: ExpressDetail?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    toast: (String) -> Unit,
) {
    val copy = rememberCopy()
    val accent = Brand.PlatformColor[item.platform] ?: Brand.Indigo
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        // ===== 头部：商品图（无则平台色图标降级）+ 商品名 + 单号 + 状态 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            val img = detail?.goodsImage
            if (img != null) {
                AsyncImage(
                    model = img,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.platform.label.first().toString(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    detail?.goodsName ?: item.summary ?: item.station,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.trackingNo?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { copy(it) },
                    )
                }
            }
            detail?.statusDesc?.takeIf { it.isNotBlank() }?.let {
                Box(
                    Modifier
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
        }
        // ===== 收寄信息行（含快递公司） =====
        val extra = listOfNotNull(
            detail?.courier?.takeIf { it.isNotBlank() }?.let { "快递 $it" },
            detail?.fromTo,
            detail?.sender?.let { "寄 $it" },
            detail?.receiver?.let { "收 $it" },
        ).joinToString("  ·  ")
        if (extra.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                extra,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        when {
            loading -> DetailSkeleton()
            detail != null -> {
                // 结果 120ms 淡入（弹窗内轻动效，不引入重组风暴）
                val alpha = remember(detail) { Animatable(0f) }
                LaunchedEffect(detail) { alpha.animateTo(1f, tween(120)) }
                Column(Modifier.graphicsLayer { this.alpha = alpha.value }) {
                    TrackTimeline(detail.tracks)
                }
            }
            else -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error ?: "暂无物流详情",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp)) { Text("重试") }
            }
        }
    }
}

/** 加载中骨架屏（轨迹区占位） */
@Composable
private fun DetailSkeleton() {
    Column {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** 物流轨迹时间线：首节点高亮，圆点 + 竖线连接，右侧节点名 + 时间 + 描述 */
@Composable
private fun TrackTimeline(tracks: List<TrackPoint>) {
    if (tracks.isEmpty()) {
        Text(
            "暂无物流轨迹",
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        tracks.forEachIndexed { index, t ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (index == 0) Brand.Indigo else MaterialTheme.colorScheme.surfaceVariant),
                    )
                    if (index != tracks.lastIndex) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(bottom = if (index == tracks.lastIndex) 0.dp else 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.opName?.takeIf { it.isNotBlank() } ?: "物流更新",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        t.opTime?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    t.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
