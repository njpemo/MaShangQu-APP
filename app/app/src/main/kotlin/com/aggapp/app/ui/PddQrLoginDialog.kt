package com.aggapp.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拼多多扫码登录弹窗（2026-09-29 去短信登录，扫码不踢 App 下线）：
 * - 展示 AUTH_LOGIN 二维码（VM 3s 轮询，过期自动重刷），「保存到相册」→ 拼多多 App 扫一扫相册识别；
 * - 扫码确认后若触发 54002 二次验证：Text(短信码) 原生输码框直调协议（不展示官方页）；
 * - 其他验证类型由 VM 转 WebView 官方验证页（pddVerifyUrl），本弹窗自动关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PddQrLoginDialog(
    state: MineUiState,
    onVerifySms: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var smsCode by remember { mutableStateOf("") }

    // 二维码内容 → Bitmap（zxing core，与身份码条码渲染同库）
    val qrBitmap: Bitmap? = remember(state.pddQrContent) {
        state.pddQrContent?.let { content ->
            try {
                val size = 560
                val matrix = QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size,
                    mapOf(EncodeHintType.MARGIN to 2),
                )
                Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bmp ->
                    for (x in 0 until size) for (y in 0 until size) {
                        bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("拼多多 · 扫码登录", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Text(
                state.pddQrHint ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            qrBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(240.dp).background(androidx.compose.ui.graphics.Color.White),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val ok = saveQrToGallery(context, bmp)
                            Toast.makeText(
                                context,
                                if (ok) "已保存到相册，请打开拼多多 App 扫一扫 → 相册选择本图并确认登录"
                                else "保存到相册失败",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ) { Text("保存到相册") }
            } ?: run {
                Text("正在生成二维码…", style = MaterialTheme.typography.bodyMedium)
            }

            // 二次验证为短信码：原生输码（协议直调，不展示官方页面）
            if (state.pddSmsVerifySent) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { smsCode = it.take(6) },
                    label = { Text("短信验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onVerifySms(smsCode) },
                    enabled = smsCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("验证并登录") }
            }
        }
    }
}

/** 二维码存入系统相册（MediaStore Pictures），成功返回 true */
private suspend fun saveQrToGallery(context: android.content.Context, bmp: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val name = "pdd_login_qr_${System.currentTimeMillis()}.png"
            MediaStore.Images.Media.insertImage(
                context.contentResolver, bmp, name, "拼多多登录二维码",
            ) != null
        } catch (_: Exception) {
            false
        }
    }
