package com.example.smartledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartledger.data.IngestResult
import com.example.smartledger.data.LedgerRepository
import com.example.smartledger.data.TxSource
import com.example.smartledger.parser.PaymentParser
import com.example.smartledger.ui.theme.SmartLedgerTheme
import com.example.smartledger.util.Format
import com.example.smartledger.util.label
import kotlinx.coroutines.launch

/**
 * 分享记账入口：在支付宝/微信的账单页长按文本，分享到本应用即可入账。
 *
 * 这是「没有通知时怎么记账」的兜底通路，不需要任何敏感权限，
 * 解析和落库全部复用通知监听那套流水线。
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val repository = (application as SmartLedgerApp).container.repository

        setContent {
            SmartLedgerTheme {
                ShareConfirm(sharedText = sharedText, repository = repository, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ShareConfirm(sharedText: String, repository: LedgerRepository, onClose: () -> Unit) {
    val parsed = remember(sharedText) { PaymentParser.parse(sharedText, "分享") }
    var result by remember { mutableStateOf<IngestResult?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7F6))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("分享记账", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "来自其他 App 的分享内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                val finished = result
                when {
                    finished != null -> {
                        Text(
                            text = when (finished) {
                                IngestResult.CONFIRMED -> "已记入账本"
                                IngestResult.PENDING -> "金额或商户不够明确，已放进「待确认」"
                                IngestResult.DUPLICATE -> "这笔刚记过，已跳过"
                                IngestResult.STALE -> "这笔账单时间早于当前时间，已跳过"
                                IngestResult.REJECTED -> "没识别出消费信息"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "你可以去账本里核对，或者直接在待确认页修改金额和分类。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onClose) { Text("关闭") }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(context, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    )
                                    onClose()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("查看账本") }
                        }
                    }

                    parsed == null -> {
                        Text(
                            text = "没识别出金额",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "分享的这段文字里没有找到金额或支付关键词，可以手动记一笔。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        QuoteBlock(sharedText)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onClose) { Text("取消") }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(context, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    )
                                    onClose()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("手动记一笔") }
                        }
                    }

                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = parsed.merchant,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${parsed.direction.label} · ${parsed.channel.ifBlank { "分享" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "¥${Format.yuan(parsed.amountCents)}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        QuoteBlock(sharedText)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onClose) { Text("取消") }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        result = try {
                                            repository.ingest(
                                                rawText = sharedText,
                                                channel = parsed.channel.ifBlank { "分享" },
                                                occurredAt = System.currentTimeMillis(),
                                                source = TxSource.SHARE
                                            )
                                        } catch (t: Throwable) {
                                            IngestResult.REJECTED
                                        }
                                        saving = false
                                    }
                                },
                                enabled = !saving,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (saving) "保存中…" else "记入账本", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF4F7F6)) {
        Text(
            text = text.ifBlank { "（分享内容为空）" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
