package com.aggapp.app.ui

import android.widget.Toast
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aggapp.core.model.Platform

/**
 * 「我的」页（2026-09-29 布局重构）：
 * - 顶部一行 4 个平台登录态卡片（点击切换下方展开对应渠道的登录表单）；
 * - 登录表单区：手机号+验证码协议登录为主，兜底粘贴入口折叠；
 * - YYB 服务配置卡片保留（拼多多/京东兜底仍可用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(vm: MineViewModel = hiltViewModel()) {
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

    Scaffold(topBar = { TopAppBar(title = { Text("我的") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ===== 顶部：4 平台登录态总览（点击展开对应登录表单）=====
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platform.entries.forEach { p ->
                    val ls = state.loginStates[p]
                    val selected = expanded == p
                    Card(
                        onClick = { expanded = p },
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                ls?.loggedIn == true -> MaterialTheme.colorScheme.primaryContainer
                                ls?.needRelogin == true -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
                                JdCaptchaDialog(
                                    captchaUrl = url,
                                    onCaptcha = vm::jdOnCaptchaResult,
                                    onDismiss = vm::jdDismissCaptcha,
                                )
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
                    // 顺丰极验滑块弹窗：先过验证拿 gt 四件套，成功后自动发短信验证码（errorCode 70000）
                    if (state.sfGeetestOpen) {
                        SfGeetestDialog(
                            onCaptcha = vm::sfOnCaptchaResult,
                            onDismiss = vm::sfDismissCaptcha,
                        )
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

            // 拼多多扫码登录弹窗（二维码展示 + 保存相册 + Text 二次验证原生输码）
            if (state.pddQrOpen) {
                PddQrLoginDialog(
                    state = state,
                    onVerifySms = vm::pddVerifySms,
                    onDismiss = vm::dismissPddQr,
                )
            }

            // 拼多多二次验证：WebView 官方验证页手动过，顶部动态显示页面提取的题干，
            // Cookie 出现 PDDAccessToken 即成功；外置确认按钮兜底页面按钮不渲染的情况
            state.pddVerifyUrl?.let { url ->
                WebViewLoginDialog(
                    title = "拼多多 · 安全验证",
                    loginUrl = url,
                    checkSuccess = vm::pddDetectLogin,
                    onCredential = vm::onPddWebViewCredential,
                    onDismiss = vm::pddDismissVerifyUrl,
                    tip = null, // 动态显示页面提取的题目文字
                    clearCookies = true,
                )
            }

            // YYB 远程服务配置：BASE URL + 账号 ref（扫码加账号在服务端 Web 页 /scan 完成）
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("YYB 服务（v1.3 远程版）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "连接部署的 YYB-GO 服务取微信 code。微信账号在服务端 Web 页 /scan 扫码添加（约 25 天重扫一次）；" +
                            "账号 ref 填 openid 或数字账号 id",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var yybUrl by remember { mutableStateOf(state.yybBaseUrl) }
                    var yybRef by remember { mutableStateOf(state.yybRef) }
                    LaunchedEffect(state.yybBaseUrl) { yybUrl = state.yybBaseUrl }
                    LaunchedEffect(state.yybRef) { yybRef = state.yybRef }
                    OutlinedTextField(
                        value = yybUrl,
                        onValueChange = { yybUrl = it },
                        label = { Text("服务地址（如 http://1.2.3.4:8080）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = yybRef,
                        onValueChange = { yybRef = it },
                        label = { Text("账号 ref（openid 或数字 id）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.saveYybConfig(yybUrl, yybRef) },
                            enabled = !state.busy,
                        ) { Text("保存") }
                        OutlinedButton(
                            onClick = { vm.testYybConnection() },
                            enabled = !state.busy && yybUrl.isNotBlank(),
                        ) { Text("测试连通") }
                    }
                    state.yybTestStatus?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!state.yybAvailable) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "未配置：京东/顺丰/拼多多的 YYB 登录按钮暂不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // 运行日志板块：展示应用内日志环（AppLog），支持一键复制全部发给分析
            AppLogCard()
        }
    }
}

/** 「我的」页底部日志板块：实时展示 AppLog 环缓冲，可复制全部/清空 */
@Composable
private fun AppLogCard() {
    var expanded by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf(com.aggapp.platform.api.AppLog.dump()) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // 展开时 1s 刷新一次
    LaunchedEffect(expanded) {
        while (expanded) {
            logText = com.aggapp.platform.api.AppLog.dump()
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开查看")
                }
            }
            if (expanded) {
                Text(
                    logText.ifBlank { "（暂无日志）" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(logText))
                            android.widget.Toast.makeText(context, "已复制全部日志", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("复制全部") }
                    OutlinedButton(
                        onClick = {
                            com.aggapp.platform.api.AppLog.clear()
                            logText = ""
                        },
                    ) { Text("清空") }
                }
            } else {
                Text(
                    "排查问题时展开并复制全部日志发送分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
