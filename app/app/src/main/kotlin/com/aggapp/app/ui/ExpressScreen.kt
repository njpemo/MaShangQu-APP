package com.aggapp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggapp.app.ui.theme.Brand
import com.aggapp.core.model.ExpressStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快递页（第一期仅顺丰）：顶部渐变头部 + 运单号查询卡 + 驿站分组包裹列表
 */
@Composable
fun ExpressScreen(viewModel: ExpressViewModel = hiltViewModel(), bottomContentPadding: Dp = 0.dp) {
    val ui by viewModel.ui.collectAsState()
    var waybillInput by remember { mutableStateOf("") }
    var suffixInput by remember { mutableStateOf("") }

    // 每次进入本页自动刷新（VM 内 30s 节流 + 静默合并，不闪 loading）
    LaunchedEffect(Unit) { viewModel.refresh() }

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
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(),
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
                            onClick = { viewModel.lookupByTrackingNo(waybillInput) },
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

            Spacer(Modifier.height(8.dp))

            // ============ 包裹列表（按驿站分组） ============
            when {
                ui.loading -> Box(
                    Modifier.fillMaxSize().padding(bottom = bottomContentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Brand.Indigo)
                }

                ui.error != null -> Column(
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(ui.error ?: "未知错误", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.refresh(force = true) }, shape = RoundedCornerShape(12.dp)) { Text("重试") }
                }

                ui.stations.isEmpty() -> Text(
                    "暂无待取件包裹",
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + bottomContentPadding)) {
                    ui.stations.forEach { group ->
                        item(key = "header_${group.stationName}") {
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
                        items(group.items, key = { "pkg_${it.id}" }) { item ->
                            ExpressItemRow(item)
                        }
                    }
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

@Composable
private fun ExpressItemRow(item: com.aggapp.core.model.ExpressItem) {
    val copy = rememberCopy()
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.trackingNo.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false).clickable { copy(item.trackingNo.orEmpty()) },
                )
                Box(
                    Modifier
                        .background(
                            if (item.status == ExpressStatus.WAIT_PICKUP) Brand.Indigo.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        when (item.status) {
                            ExpressStatus.WAIT_PICKUP -> "待取件"
                            ExpressStatus.PICKED -> "已取出"
                            ExpressStatus.TIMEOUT -> "超时"
                            ExpressStatus.UNKNOWN -> "未知"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.status == ExpressStatus.WAIT_PICKUP) Brand.Indigo
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            val tracks = if (expanded) d.tracks else d.tracks.take(3)
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
            if (d.tracks.size > 3) {
                androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起轨迹" else "展开全部 ${d.tracks.size} 条轨迹")
                }
            }
        }
    }
}
