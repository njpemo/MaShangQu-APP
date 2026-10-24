package com.aggapp.app.ui

import android.widget.Toast
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import com.aggapp.app.R
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggapp.core.model.Platform

/**
 * 「我的」页（2026-09-29 布局重构）：
 * - 顶部一行 4 个平台登录态卡片（点击切换下方展开对应渠道的登录表单）；
 * - 登录表单区：手机号+验证码协议登录为主，兜底粘贴入口折叠；
 * - 平台登录态总览卡片（各平台协议直连登录/兜底粘贴）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(vm: MineViewModel = hiltViewModel(), bottomContentPadding: Dp = 0.dp) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var jdPtKey by remember { mutableStateOf("") }
    var jdPin by remember { mutableStateOf("") }
    var sfToken by remember { mutableStateOf("") }

    // 当前展开登录表单的平台（默认淘宝）
    var expanded by remember { mutableStateOf(Platform.TAOBAO) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===== 简洁头部（surface 底，与身份码页/底部导航同风格）=====
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text(
                        "我的",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "平台登录与账号管理",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            // ===== 顶部：5 平台登录态总览（点击展开对应登录表单）=====
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platform.entries.forEach { p ->
                    val ls = state.loginStates[p]
                    val selected = expanded == p
                    val accent = com.aggapp.app.ui.theme.Brand.PlatformColor[p]
                        ?: MaterialTheme.colorScheme.primary
                    val greenGlow = Color(0xFF34C759)
                    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    Card(
                        onClick = { expanded = p },
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (ls?.loggedIn == true) {
                                    // 已登录：绿色描边 + 绿色光晕
                                    Modifier
                                        .shadow(
                                            6.dp, cardShape, clip = false,
                                            ambientColor = greenGlow, spotColor = greenGlow,
                                        )
                                } else Modifier
                            ),
                        shape = cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                selected -> MaterialTheme.colorScheme.primaryContainer
                                ls?.loggedIn == true -> MaterialTheme.colorScheme.surface
                                ls?.needRelogin == true -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                        ),
                        border = when {
                            ls?.loggedIn == true ->
                                androidx.compose.foundation.BorderStroke(1.5.dp, greenGlow)
                            else -> null
                        },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // 平台品牌色圆点
                            Box(
                                Modifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .background(accent, androidx.compose.foundation.shape.CircleShape),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(p.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                when {
                                    ls == null -> "未检查"
                                    ls.loggedIn -> "已登录"
                                    ls.needRelogin -> "需重登"
                                    else -> "未登录"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    ls?.loggedIn == true -> MaterialTheme.colorScheme.primary
                                    ls?.needRelogin == true -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { vm.refreshLoginStates() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新检查所有登录态") }

            // ===== 展开的平台登录表单 =====
            when (expanded) {
                Platform.TAOBAO -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("淘宝（菜鸟驿站）· 短信登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "输入手机号获取短信验证码，协议直连登录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("手机号") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    label = { Text("验证码") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = { vm.sendTaobaoSms(phone) },
                                    enabled = !state.busy,
                                ) { Text("获取验证码") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.loginTaobao(smsCode) },
                                enabled = !state.busy && state.smsSent && smsCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("登录") }
                            LogoutRow(state.loginStates[Platform.TAOBAO]) { vm.logout(Platform.TAOBAO) }
                        }
                    }
                }

                Platform.JD -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("京东 · 短信验证码登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "手机号 + 短信验证码协议直连登录（滑块验证后发码）；pt_key 有效期约 1 年",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("手机号") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.jdPrepareSms(phone) },
                                enabled = !state.busy && phone.length == 11,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("获取验证码") }
                            if (state.jdSmsSent) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    label = { Text("短信验证码") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { vm.jdVerifySms(smsCode) },
                                    enabled = !state.busy && smsCode.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("登录") }
                            }
                            state.jdCaptchaUrl?.let { url ->
                                // 2026-10-21：本设备 Compose 容器内 WebView 不出帧，改用
                                // 纯 View 原生 Activity 承载滑块，结果经静态回调回传 VM
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                LaunchedEffect(url) {
                                    CaptchaActivity.onResult = { json ->
                                        if (json == null) vm.jdDismissCaptcha() else vm.jdOnCaptchaResult(json)
                                    }
                                    ctx.startActivity(android.content.Intent(ctx, CaptchaActivity::class.java).putExtra(CaptchaActivity.EXTRA_URL, url))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            var showJdPaste by remember { mutableStateOf(false) }
                            TextButton(onClick = { showJdPaste = !showJdPaste }) {
                                Text(if (showJdPaste) "收起粘贴登录" else "粘贴 pt_key/pin 兜底")
                            }
                            if (showJdPaste) {
                                OutlinedTextField(
                                    value = jdPtKey,
                                    onValueChange = { jdPtKey = it },
                                    label = { Text("pt_key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = jdPin,
                                        onValueChange = { jdPin = it },
                                        label = { Text("pin") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    Button(
                                        onClick = { vm.loginJd(jdPtKey, jdPin) },
                                        enabled = !state.busy,
                                    ) { Text("保存") }
                                }
                            }
                            LogoutRow(state.loginStates[Platform.JD]) { vm.logout(Platform.JD) }
                        }
                    }
                }

                Platform.SF -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("顺丰 · 短信验证码登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "发码前需过极验滑块；身份码静态段一次获取、永久本地生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("手机号") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    label = { Text("验证码") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Button(
                                    onClick = { vm.sfStartCaptcha(phone) },
                                    enabled = !state.busy,
                                ) { Text("获取验证码") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.loginSfSms(smsCode) },
                                enabled = !state.busy && state.sfSmsSent && smsCode.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("登录") }
                            Spacer(Modifier.height(4.dp))
                            var showSfPaste by remember { mutableStateOf(false) }
                            TextButton(onClick = { showSfPaste = !showSfPaste }) {
                                Text(if (showSfPaste) "收起粘贴登录" else "粘贴 YJY-Auth 兜底（旧驿收发链路）")
                            }
                            if (showSfPaste) {
                                OutlinedTextField(
                                    value = sfToken,
                                    onValueChange = { sfToken = it },
                                    label = { Text("YJY-Auth") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { vm.loginSf(sfToken) },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("保存") }
                            }
                            LogoutRow(state.loginStates[Platform.SF]) { vm.logout(Platform.SF) }
                        }
                    }
                    // 顺丰极验滑块：改用原生 Activity（Compose 容器内 WebView 不出帧），成功后自动发码
                    val sfCtx = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(state.sfGeetestOpen) {
                        if (state.sfGeetestOpen) {
                            CaptchaActivity.onResult = { json ->
                                if (json == null) vm.sfDismissCaptcha() else vm.sfOnCaptchaResult(json)
                            }
                            sfCtx.startActivity(
                                android.content.Intent(sfCtx, CaptchaActivity::class.java)
                                    .putExtra(CaptchaActivity.EXTRA_URL, "")
                                    .putExtra(CaptchaActivity.EXTRA_ASSET, true),
                            )
                        }
                    }
                }

                Platform.PDD -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("拼多多（多多驿站）· 扫码登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "生成登录二维码保存到相册，拼多多 App 扫一扫相册识别（不踢 App 下线，token 约 1 年）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.startPddQrLogin() },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("扫码登录") }
                            LogoutRow(state.loginStates[Platform.PDD]) { vm.logout(Platform.PDD) }
                        }
                    }
                }

                Platform.RYLL -> {
                    var ryllCaptcha by remember { mutableStateOf("") }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("如意邻里（圆通）· 短信验证码登录", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "发短信需先输图形验证码；身份码为静态码，登录一次后永久本地生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("手机号") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            // 图形验证码：点图换一张（发短信前置，README §3.2）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                state.ryllVcodeBase64?.let { dataUrl ->
                                    val bmp = remember(dataUrl) { decodeDataUrlBitmap(dataUrl) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "图形验证码",
                                            modifier = Modifier
                                                .width(132.dp)
                                                .height(44.dp)
                                                .androidClickable { vm.ryllFetchVcode() },
                                        )
                                    } else {
                                        Text("图形码解码失败", style = MaterialTheme.typography.bodySmall)
                                    }
                                } ?: run {
                                    OutlinedButton(
                                        onClick = { vm.ryllFetchVcode() },
                                        enabled = !state.busy,
                                    ) { Text("获取图形码") }
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = ryllCaptcha,
                                    onValueChange = { ryllCaptcha = it },
                                    label = { Text("图形验证码") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { vm.ryllSendSms(phone, ryllCaptcha) },
                                enabled = !state.busy && phone.length == 11 && ryllCaptcha.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("发送短信验证码") }
                            if (state.ryllSmsSent) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    label = { Text("短信验证码") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { vm.ryllLogin(smsCode) },
                                    enabled = !state.busy && smsCode.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("登录") }
                            }
                            LogoutRow(state.loginStates[Platform.RYLL]) { vm.logout(Platform.RYLL) }
                        }
                    }
                }
            }

            // ===== 联系/赞助作者 =====
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var showWx by remember { mutableStateOf(false) }
                var showSkm by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showWx = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("联系作者") }
                Button(
                    onClick = { showSkm = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = com.aggapp.app.ui.theme.Brand.Amber),
                ) { Text("赞助作者") }

                // 居中弹窗：微信二维码
                if (showWx) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showWx = false },
                        confirmButton = {
                            TextButton(onClick = { showWx = false }) { Text("关闭") }
                        },
                        title = { Text("联系作者", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    painterResource(R.drawable.wx_qr),
                                    contentDescription = "QQ群二维码",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "QQ扫码加入交流群",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                // 居中弹窗：赞助码
                if (showSkm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showSkm = false },
                        confirmButton = {
                            TextButton(onClick = { showSkm = false }) { Text("关闭") }
                        },
                        title = { Text("赞助作者", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    painterResource(R.drawable.skm_qr),
                                    contentDescription = "赞助码",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "感谢每一份支持",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            // ===== 调试日志导出（debug 包：操作日志 + 请求日志 + 登录态） =====
            OutlinedButton(
                onClick = { vm.exportDebugLog() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("导出调试日志（操作/请求/登录态）") }

            // 淘宝多账号选择弹窗（手机号绑定多个淘宝账号，登录前需选择）
            if (state.tbAccounts.isNotEmpty()) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { vm.tbDismissAccountSelect() },
                    confirmButton = {
                        TextButton(onClick = { vm.tbDismissAccountSelect() }) { Text("取消") }
                    },
                    title = { Text("选择淘宝账号", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "该手机号绑定了多个账号，请选择要登录的账号",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.tbAccounts.forEach { acc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.tbSelectAccount(acc.uniqId) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        acc.nick.ifEmpty { "淘宝账号" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (acc.mostFrequent) {
                                        Text(
                                            "登录次数最多",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else if (acc.lastActiveTime.isNotEmpty()) {
                                        Text(
                                            "最近活跃 ${acc.lastActiveTime}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }

            // 淘宝 iv 风控 WebView 弹窗（多账号收尾命中风控：用户手动过风控，
            // 检测到登录态 Cookie（cookie2/unb）出现后自动重试协议收尾并关闭）
            if (state.tbRiskUrl != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { vm.tbDismissRiskCheck() },
                    confirmButton = {
                        TextButton(onClick = { vm.tbDismissRiskCheck() }) { Text("关闭") }
                    },
                    title = { Text("安全验证", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                    text = {
                        androidx.compose.ui.viewinterop.AndroidView(
                            modifier = Modifier.fillMaxWidth().height(420.dp),
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                    val cm = android.webkit.CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    // 预置会话 Cookie（保持服务端上下文）
                                    state.tbWebviewCookies.forEach { (u, c) -> cm.setCookie(u, c) }
                                    cm.flush()
                                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                    val check = object : Runnable {
                                        override fun run() {
                                            val ck = cm.getCookie("https://www.taobao.com/") ?: ""
                                            if (ck.contains("cookie2=") && ck.contains("unb=")) {
                                                vm.tbRiskCheckPassed(ck)
                                                return
                                            }
                                            handler.postDelayed(this, 1500)
                                        }
                                    }
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                            handler.postDelayed(check, 1500)
                                        }
                                    }
                                    loadUrl(state.tbRiskUrl!!)
                                    handler.postDelayed(check, 3000)
                                }
                            },
                        )
                    },
                )
            }

            // 拼多多扫码登录弹窗（二维码展示 + 保存相册 + Text 二次验证原生输码）
            if (state.pddQrOpen) {
                PddQrLoginDialog(
                    state = state,
                    onVerifySms = vm::pddVerifySms,
                    onAnswerQuiz = vm::pddAnswerQuiz,
                    onDismiss = vm::dismissPddQr,
                )
            }

            // 拼多多二次验证：回到 App 内 WebView，但用 PC 桌面 Chrome UA（desktopUa）——
            // 老版本扫码登录页即此配置且正常渲染；移动 UA 下 PDD 风控做渲染层隐藏（白屏）。
            // Cookie 出现 PDDAccessToken 即自动落库；「收尾登录」按钮兜底。
            state.pddVerifyUrl?.let { url ->
                WebViewLoginDialog(
                    title = "拼多多 · 安全验证",
                    loginUrl = url,
                    checkSuccess = vm::pddDetectLogin,
                    onCredential = vm::onPddWebViewCredential,
                    onDismiss = vm::pddDismissVerifyUrl,
                    tip = null, // 动态显示页面提取的题目文字
                    desktopUa = true,
                    clearCookies = true,
                    actionLabel = "页面已提示通过？点此收尾登录",
                    onAction = vm::pddManualFinishVerify,
                )
            }

            }
        }
    }
}

/** 已登录平台在表单底部展示「登出该账号」 */
@Composable
private fun LogoutRow(ls: com.aggapp.core.model.PlatformLoginState?, onLogout: () -> Unit) {
    if (ls != null && (ls.loggedIn || ls.needRelogin)) {
        TextButton(onClick = onLogout) { Text("登出该账号") }
    }
}

/** data:image/png;base64 → Bitmap（如意邻里图形验证码显示用） */
private fun decodeDataUrlBitmap(dataUrl: String): android.graphics.Bitmap? = try {
    val bytes = Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (_: Exception) {
    null
}

/** clickable 的简易别名（避免与 material3 语义冲突的私有扩展） */
private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
