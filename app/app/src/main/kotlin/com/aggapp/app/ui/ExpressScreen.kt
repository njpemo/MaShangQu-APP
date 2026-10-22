package com.aggapp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aggapp.core.model.ExpressStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快递页（第一期仅顺丰）：
 * - 顶部运单号查询框：SF 单号直查取件码；其他单号提示等待接入
 * - 下方包裹列表按驿站分组
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressScreen(viewModel: ExpressViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    var waybillInput by remember { mutableStateOf("") }
    var suffixInput by remember { mutableStateOf("") }

    // 每次进入本页自动刷新（VM 内 30s 节流 + 静默合并，不闪 loading）
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("快递") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ============ 运单号查询 ============
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = waybillInput,
                    onValueChange = { waybillInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("运单号") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.lookupByTrackingNo(waybillInput) },
                    enabled = waybillInput.isNotBlank() && !ui.searching,
                ) {
                    Text(if (ui.searching) "查询中" else "查询")
                }
            }
            Spacer(Modifier.height(8.dp))

            ui.searchResult?.let { code ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("取件码（${code.expressId}）", style = MaterialTheme.typography.labelMedium)
                        Text(
                            code.code,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            ui.searchDetail?.let { d ->
                RyllDetailCard(d)
                Spacer(Modifier.height(8.dp))
            }
            ui.searchNotice?.let { notice ->
                Card(Modifier.fillMaxWidth()) {
                    Text(notice, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }

            // 手机后4位验证（顺丰单号不在列表时）
            ui.needSuffixFor?.let { no ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("验证收件手机后 4 位查询 $no", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = suffixInput,
                                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) suffixInput = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("手机后4位") },
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.lookupWithSuffix(suffixInput) },
                                enabled = suffixInput.length == 4 && !ui.searching,
                            ) { Text("验证查询") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ============ 包裹列表（按驿站分组） ============
            when {
                ui.loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                ui.error != null -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(ui.error ?: "未知错误", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::refresh) { Text("重试") }
                }

                ui.stations.isEmpty() -> Text(
                    "暂无待取件包裹",
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn {
                    ui.stations.forEach { group ->
                        item(key = "header_${group.stationName}") {
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(
                                    "📍 ${group.stationName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            }
                        }
                        items(group.items, key = { "pkg_${it.id}" }) { item ->
                            ExpressItemRow(item)
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ExpressItemRow(item: com.aggapp.core.model.ExpressItem) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.trackingNo.orEmpty(), fontFamily = FontFamily.Monospace)
                Text(
                    when (item.status) {
                        ExpressStatus.WAIT_PICKUP -> "待取件"
                        ExpressStatus.PICKED -> "已取出"
                        ExpressStatus.TIMEOUT -> "超时"
                        ExpressStatus.UNKNOWN -> "未知"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == ExpressStatus.WAIT_PICKUP) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
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
                            "取件码 $it",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.mailNo, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    d.statusName.ifBlank { "查询成功" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (d.atStation) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("取件码 $it", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
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
