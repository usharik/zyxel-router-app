package com.usharik.zyxelrouter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** Матрица QR-кода без белой рамки: рамку рисует сам экран или картинка. */
internal fun qrMatrix(payload: String): BitMatrix = QRCodeWriter().encode(
    payload, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
)

private sealed interface ShareLoad {
    data object Loading : ShareLoad
    data class Ready(val credentials: WifiCredentials, val payload: String) : ShareLoad
    data class Failed(val message: String) : ShareLoad
}

/**
 * Окно «Поделиться сетью»: QR-код для камеры другого телефона, пароль (скрыт, пока не попросят) и отправка
 * картинки через системное меню. Пароль запрашивается у роутера при открытии и живёт, пока открыто окно.
 */
@Composable
internal fun WifiShareDialog(network: WifiNetwork, vm: RouterViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Картинка с паролем удаляется, когда окно закрывают, а не при повороте экрана: получатель может ещё её читать.
    // Если процесс завершится раньше, её удалит ZyxelApp при следующем запуске.
    val onClose = { deleteSharedQr(context); onDismiss() }
    // Окно привязано к сети (место и имя), а не ко всему снимку: смена канала или статуса при опросе его не сбрасывает.
    val key = network.index to network.ssid
    var load by remember(key) { mutableStateOf<ShareLoad>(ShareLoad.Loading) }
    var reveal by remember(key) { mutableStateOf(false) }
    var reported by rememberSaveable(key) { mutableStateOf(false) }
    val notSupported = stringResource(R.string.share_wifi_unsupported)
    val latest by rememberUpdatedState(network)
    TrackOverlay()
    LaunchedEffect(key) {
        load = vm.wifiCredentials(latest).fold(
            { c -> wifiQrPayload(c)?.let { ShareLoad.Ready(c, it) } ?: ShareLoad.Failed(notSupported) },
            { e -> ShareLoad.Failed(e.message.orEmpty()) },
        )
        if (load is ShareLoad.Ready && !reported) Telemetry.event("wifi_qr_shown", mapOf("guest" to if (latest.guest) 1L else 0L))
        reported = true
    }
    val shareTitle = stringResource(R.string.share_wifi_chooser)
    val ready = load as? ShareLoad.Ready
    val shareText = ready?.let { wifiShareText(it.credentials) }
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.QrCode2, null) },
        title = { Text(network.ssid, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (ready != null && shareText != null) TextButton({
                shareWifiQr(context, ready.payload, shareText, shareTitle)
                Telemetry.event("wifi_qr_shared")
            }) { Text(stringResource(R.string.share)) }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val l = load) {
                    ShareLoad.Loading -> Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is ShareLoad.Failed -> Text(l.message, color = MaterialTheme.colorScheme.error)
                    is ShareLoad.Ready -> {
                        val description = stringResource(R.string.share_wifi_qr_description, network.ssid)
                        // Белый фон и тихая зона вокруг кода и в тёмной теме: иначе камеры читают его хуже.
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp).semantics { contentDescription = description }) {
                            QrImage(remember(l.payload) { qrMatrix(l.payload) }, Modifier.size(200.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.share_wifi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        if (!network.enabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.share_wifi_disabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(12.dp))
                        if (l.credentials.security == WifiSecurity.OPEN) {
                            Text(stringResource(R.string.share_wifi_open), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            PasswordRow(l.credentials.password, reveal, { reveal = !reveal }) { copySensitive(context, l.credentials.password) }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PasswordRow(password: String, reveal: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(Modifier.weight(1f, fill = false).widthIn(min = 0.dp)) {
            Text(stringResource(R.string.wifi_password), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (reveal) password else "•".repeat(password.length.coerceIn(8, 16)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onToggle) {
            Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (reveal) R.string.hide_password else R.string.show_password))
        }
        IconButton(onCopy) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy_password)) }
    }
}

@Composable
private fun QrImage(matrix: BitMatrix, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        val module = Size(cell, cell)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), module)
        }
    }
}

@Composable
private fun wifiShareText(c: WifiCredentials): String =
    if (c.security == WifiSecurity.OPEN) stringResource(R.string.share_wifi_text_open, c.ssid)
    else stringResource(R.string.share_wifi_text, c.ssid, c.password)

/** ClipDescription.EXTRA_IS_SENSITIVE (API 33); на старых версиях Android ключ просто игнорируется. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Пароль в буфер с пометкой «чувствительно»: Android 13+ не покажет его в превью и подсказках клавиатуры. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText(null, text).apply {
        description.extras = android.os.PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun sharedQrFile(context: Context) = File(File(context.cacheDir, ZyxelApp.SHARED_CACHE_DIR), "wifi-qr.png")

private fun deleteSharedQr(context: Context) { runCatching { sharedQrFile(context).delete() } }

/** Сеть исчезла из ответа роутера, пока окно было открыто (переименовали, выключили модуль). */
@Composable
internal fun WifiGoneDialog(onClose: () -> Unit) {
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.QrCode2, null) },
        text = { Text(stringResource(R.string.share_wifi_gone)) },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

/** Картинка с кодом (и текст с именем и паролем — для тех, кому удобнее ввести вручную) через системное меню. */
private fun shareWifiQr(context: Context, payload: String, text: String, chooserTitle: String) {
    val uri = runCatching {
        val file = sharedQrFile(context).apply { parentFile?.mkdirs() }
        file.outputStream().use { qrBitmap(qrMatrix(payload)).compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        if (uri != null) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)   // превью в системном меню и разрешение на чтение
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    runCatching { context.startActivity(Intent.createChooser(send, chooserTitle)) }
}

/** PNG для отправки: модуль 16 px и белая рамка в 4 модуля, как требует стандарт. */
private fun qrBitmap(matrix: BitMatrix, scale: Int = 16, quiet: Int = 4): Bitmap {
    val side = (matrix.width + quiet * 2) * scale
    val pixels = IntArray(side * side) { android.graphics.Color.WHITE }
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        if (!matrix[x, y]) continue
        val left = (x + quiet) * scale
        val top = (y + quiet) * scale
        for (dy in 0 until scale) pixels.fill(android.graphics.Color.BLACK, (top + dy) * side + left, (top + dy) * side + left + scale)
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}
