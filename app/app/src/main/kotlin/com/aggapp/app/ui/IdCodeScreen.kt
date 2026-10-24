package com.aggapp.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aggapp.app.ui.theme.Brand
import com.aggapp.core.model.Platform
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import com.google.zxing.common.BitMatrix

/** CODE128 条形码渲染（对齐 sf.md §4.2：H5 原版 JsBarcode，displayValue:false，码号文字单独显示） */
fun renderCode128(content: String, width: Int = 1000, height: Int = 300): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 10)
        val matrix: BitMatrix = Code128Writer().encode(content, BarcodeFormat.CODE_128, width, height, hints)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun IdCodeScreen(vm: MainViewModel, bottomContentPadding: Dp = 0.dp) {
    val state by vm.state.collectAsState()
    val platforms = Platform.entries.toList()
    val imageScope = rememberCoroutineScope()
    val barcodeCache = remember(imageScope) { Code128BitmapCache(imageScope) }

    // 双击全屏显示当前平台身份码（单击全屏任意处退出）
    var fullscreen by remember { mutableStateOf(false) }
    // 全屏显示全部平台身份码（「全部平台码」按钮进入）
    var showAllCodes by remember { mutableStateOf(false) }

    // 淘宝账号菜鸟档案未激活（waybill-5026）→ 引导弹窗
    if (state.needCainiaoActivate) {
        CainiaoActivateDialog(onDismiss = { vm.dismissCainiaoActivate() })
    }

    // 每次打开页面刷新（如意邻里身份码非纯静态，回到本页时重新拉取）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refreshAll(force = true) },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomContentPadding),
            ) {
                // ===== 简洁头部（surface 底 + 主色点缀，与底部导航栏同风格）=====
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "身份码",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { vm.refreshAll(force = true) }) {
                            if (state.loading) {
                                CircularProgressIndicator(
                                    Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh, "刷新",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ===== 平台切换：浅灰轨道分段控件（选中白底浮起 + 平台色字/点）=====
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (p in platforms) {
                            val selected = p == state.selected
                            val accent = Brand.PlatformColor[p] ?: MaterialTheme.colorScheme.primary
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                                    )
                                    .clickable { vm.select(p) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.label,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                val code = state.codes[state.selected]
                if (code == null) {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "暂无身份码\n单击条码区域或下拉刷新",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Column(Modifier.padding(20.dp)) {
                        // ===== 身份码大卡 =====
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(code.content) {
                                    detectTapGestures(
                                        onTap = { vm.refreshAll(force = true) },
                                        onDoubleTap = { fullscreen = true },
                                    )
                                },
                            shape = MaterialTheme.shapes.large,
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // 平台徽标行
                                val accent = Brand.PlatformColor[state.selected] ?: Brand.Indigo
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(accent, CircleShape),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        state.selected.label,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1A1B23),
                                    )
                                }
                                Spacer(Modifier.height(16.dp))

                                Code128Image(
                                    code.content, barcodeCache, "身份码条形码",
                                    Modifier.fillMaxWidth().aspectRatio(10f / 3f),
                                )
                                Spacer(Modifier.height(16.dp))
                                // 码号文字单独显示（对齐 H5 原版 displayValue:false）
                                Text(
                                    code.content,
                                    fontSize = 20.sp,
                                    letterSpacing = 3.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1A1B23),
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // 倒计时徽章
                                    state.countdownSec?.let {
                                        Box(
                                            Modifier
                                                .background(Color(0xFFF1F0F8), RoundedCornerShape(50))
                                                .padding(horizontal = 12.dp, vertical = 5.dp),
                                        ) {
                                            Text(
                                                "服务端对齐刷新 ${it / 60}:${(it % 60).toString().padStart(2, '0')}",
                                                fontSize = 12.sp,
                                                color = Color(0xFF46464F),
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .background(Color(0xFFF1F0F8), RoundedCornerShape(50))
                                            .padding(horizontal = 12.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            "单击刷新 · 双击全屏",
                                            fontSize = 12.sp,
                                            color = Color(0xFF46464F),
                                        )
                                    }
                                }
                            }
                        }

                        // 未登录提示
                        if (state.isReal[state.selected] == false) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "⚠ 该平台未登录，请到「我的」页登录后查看身份码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // ===== 快捷操作行 =====
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { fullscreen = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand.Indigo,
                                ),
                            ) {
                                Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全屏亮码")
                            }
                            Button(
                                onClick = { showAllCodes = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand.Violet,
                                ),
                            ) {
                                Icon(Icons.Filled.GridView, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全部平台码")
                            }
                        }
                    }
                }
            }
        }
    }

    // ===== 全屏全部平台码（按钮进入；单击任意处退出；五码均分屏高，免滚动全部可见）=====
    if (showAllCodes) {
        Dialog(
            onDismissRequest = { showAllCodes = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onTap = { showAllCodes = false }) }
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.padding(12.dp), color = Color.White)
                }
                // 每个平台均分剩余高度（weight），条码 ContentScale.Fit 自适应，
                // 免 verticalScroll——任何屏幕都无需上下滑动即可看到全部五码
                platforms.forEach { p ->
                    val code = state.codes[p]
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            p.label,
                            fontSize = 15.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        if (code != null) {
                            Code128Image(
                                code.content, barcodeCache, "${p.label}身份码条形码",
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                            )
                            Text(
                                code.content,
                                fontSize = 13.sp,
                                letterSpacing = 2.sp,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        } else {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    "暂无身份码（未登录或拉取失败）",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }
                }
                Text("单击屏幕退出", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }

    // ===== 全屏身份码（双击进入；单击任意处退出）=====
    if (fullscreen) {
        val code = state.codes[state.selected]
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onTap = { fullscreen = false }) },
                contentAlignment = Alignment.Center,
            ) {
                if (code != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.selected.label,
                            fontSize = 18.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(16.dp))
                        Code128Image(
                            code.content, barcodeCache, "身份码条形码（全屏）",
                            Modifier.fillMaxWidth().padding(24.dp).aspectRatio(10f / 3f),
                            width = 1400, height = 420, contentScale = ContentScale.FillWidth,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            code.content,
                            fontSize = 26.sp,
                            letterSpacing = 3.sp,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("单击屏幕退出全屏", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

/**
 * 淘宝身份码取码被拒（waybill-5026 手淘用户不存在）引导弹窗：
 * 账号在菜鸟侧无用户档案，提供两个菜鸟驿站页面链接，点击拉起淘宝/浏览器完成激活。
 */
@Composable
private fun CainiaoActivateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val links = listOf(
        "菜鸟驿站·身份码" to
            "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code",
        "菜鸟驿站·学堂" to
            "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/school",
    )
    fun open(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要激活菜鸟驿站") },
        text = {
            Column {
                Text("淘宝账号在菜鸟驿站暂无档案，暂无法获取身份码。点击下方链接跳转淘宝，打开一次菜鸟驿站完成激活后，回到本页刷新即可。")
                Spacer(Modifier.height(12.dp))
                for ((label, url) in links) {
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { open(url) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
    )
}
