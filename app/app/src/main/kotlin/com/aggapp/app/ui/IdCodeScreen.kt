package com.aggapp.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCodeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val platforms = Platform.entries.toList()

    // 双击全屏显示当前平台身份码（单击全屏任意处退出）
    var fullscreen by remember { mutableStateOf(false) }
    // 全屏显示全部平台身份码（「显示全部平台码」按钮进入）
    var showAllCodes by remember { mutableStateOf(false) }

    // 每次打开页面刷新（如意邻里身份码非纯静态，回到本页时重新拉取）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAll() // VM 内 30s 节流 + 静默刷新
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("身份码") },
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.width(28.dp).height(28.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                },
            )
        },
    ) { pad ->
        // 下滑刷新（Material3 PullToRefreshBox；内容不足一屏也能触发）
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refreshAll(force = true) },
        ) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
            // 平台 Tab
            TabRow(selectedTabIndex = platforms.indexOf(state.selected)) {
                platforms.forEach { p ->
                    Tab(
                        selected = p == state.selected,
                        onClick = { vm.select(p) },
                        text = { Text(p.label) },
                    )
                }
            }

            // 平台切换淡入淡出（200ms），替代原来的硬切
            AnimatedContent(
                targetState = state.selected,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "platformCode",
            ) { selected ->
            Column {
            val code = state.codes[selected]
            if (code == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("暂无身份码，单击条码区域刷新")
                }
            } else {
                if (state.isReal[selected] == false) {
                    Text(
                        "⚠ 当前为 Mock 演示数据，登录后显示真实身份码",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val bmp = renderCode128(code.content)
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            // 单击刷新；双击全屏（2026-09-29 交互重构：取代不好用的下滑刷新）
                            .pointerInput(code.content) {
                                detectTapGestures(
                                    onTap = { vm.refreshAll(force = true) },
                                    onDoubleTap = { fullscreen = true },
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "身份码条形码",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp).background(Color.LightGray),
                                contentAlignment = Alignment.Center,
                            ) { Text("条码渲染失败") }
                        }
                        Spacer(Modifier.height(12.dp))
                        // 码号文字单独显示（对齐 H5 原版 displayValue:false 的下方文字）
                        Text(
                            code.content,
                            fontSize = 18.sp,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "单击刷新 · 双击全屏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        state.countdownSec?.let {
                            Text(
                                "服务端对齐刷新：${it / 60}分${it % 60}秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            }
            }

            // 全部平台码（2026-10-08：取代聚合码，点击后全屏展示各平台身份码）
            Button(
                onClick = { showAllCodes = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("显示全部平台码")
            }

            Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ===== 全屏全部平台码（按钮进入；单击任意处退出；纵向排列各平台码）=====
    if (showAllCodes) {
        Dialog(
            onDismissRequest = { showAllCodes = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) { detectTapGestures(onTap = { showAllCodes = false }) }
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                }
                platforms.forEach { p ->
                    val code = state.codes[p]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.label, fontSize = 18.sp, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        if (code != null) {
                            renderCode128(code.content)?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "${p.label}身份码条形码",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                code.content,
                                fontSize = 14.sp,
                                letterSpacing = 2.sp,
                                color = Color.White,
                            )
                        } else {
                            Text(
                                "暂无身份码（未登录或拉取失败）",
                                fontSize = 13.sp,
                                color = Color.Gray,
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
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
                        renderCode128(code.content, width = 1400, height = 420)?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "身份码条形码（全屏）",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                                    .aspectRatio(10f / 3f),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            code.content,
                            fontSize = 26.sp,
                            letterSpacing = 3.sp,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("单击屏幕退出全屏", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
