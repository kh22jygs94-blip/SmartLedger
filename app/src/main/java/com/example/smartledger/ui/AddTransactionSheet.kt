package com.example.smartledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smartledger.data.CategoryEntity
import com.example.smartledger.data.Direction
import com.example.smartledger.parser.ParsedPayment
import com.example.smartledger.parser.PaymentParser
import com.example.smartledger.util.Format
import com.example.smartledger.util.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    categories: List<CategoryEntity>,
    tier: WindowTier,
    clipboardText: String,
    onDismiss: () -> Unit,
    onSave: (amountText: String, direction: Direction, categoryId: Long, merchant: String, note: String) -> Unit
) {
    var direction by remember { mutableStateOf(Direction.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    // 微信/支付宝账单页只提供「复制」，所以用剪贴板兜底。
    // 文本在点「＋」的那一刻就由 Activity 读好传进来——剪贴板只对前台且持有焦点的应用开放。
    val clipPreview = remember(clipboardText) {
        clipboardText.takeIf { it.isNotBlank() }
            ?.let { PaymentParser.parse(it) }
            ?.takeIf { it.direction == Direction.EXPENSE || it.direction == Direction.INCOME }
    }
    var clipApplied by remember { mutableStateOf(false) }
    val applyClip: (ParsedPayment) -> Unit = { preview ->
        direction = preview.direction
        amountText = Format.yuanForEdit(preview.amountCents)
        // 没认出商户就别往输入框里塞「未知商户」，留空让用户自己补
        merchant = preview.merchant.takeIf { it != "未知商户" }.orEmpty()
        clipApplied = true
    }

    val options = remember(direction, categories) { categories.filter { it.direction == direction } }
    var categoryId by remember(direction, options) { mutableStateOf(options.firstOrNull()?.id ?: 0L) }
    val amountCents = Format.parseYuanToCents(amountText)

    if (tier == WindowTier.Compact) {
        // 手机：底部抽屉，单手可及
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = Color.White
        ) {
            TransactionForm(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .imePadding(),
                direction = direction,
                onDirectionChange = { direction = it },
                clipPreview = clipPreview.takeIf { !clipApplied },
                onApplyClip = applyClip,
                amountText = amountText,
                onAmountChange = { amountText = it },
                options = options,
                categoryId = categoryId,
                onCategoryChange = { categoryId = it },
                merchant = merchant,
                onMerchantChange = { merchant = it },
                note = note,
                onNoteChange = { note = it },
                canSave = amountCents != null && categoryId != 0L,
                onSave = { onSave(amountText, direction, categoryId, merchant, note) }
            )
        }
    } else {
        // 平板 / 横屏：居中对话框，避免抽屉把整屏高度盖住
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                ) {
                    TransactionForm(
                        modifier = Modifier.padding(24.dp),
                        direction = direction,
                        onDirectionChange = { direction = it },
                        clipPreview = clipPreview.takeIf { !clipApplied },
                        onApplyClip = applyClip,
                        amountText = amountText,
                        onAmountChange = { amountText = it },
                        options = options,
                        categoryId = categoryId,
                        onCategoryChange = { categoryId = it },
                        merchant = merchant,
                        onMerchantChange = { merchant = it },
                        note = note,
                        onNoteChange = { note = it },
                        canSave = amountCents != null && categoryId != 0L,
                        onSave = { onSave(amountText, direction, categoryId, merchant, note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionForm(
    modifier: Modifier,
    direction: Direction,
    onDirectionChange: (Direction) -> Unit,
    clipPreview: ParsedPayment?,
    onApplyClip: (ParsedPayment) -> Unit,
    amountText: String,
    onAmountChange: (String) -> Unit,
    options: List<CategoryEntity>,
    categoryId: Long,
    onCategoryChange: (Long) -> Unit,
    merchant: String,
    onMerchantChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 横屏加键盘时高度不够，允许滚动到「保存」
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记一笔", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                text = "手动记账",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        clipPreview?.let { ClipboardHint(preview = it, onApply = { onApplyClip(it) }) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectionChip("支出", direction == Direction.EXPENSE) { onDirectionChange(Direction.EXPENSE) }
            DirectionChip("收入", direction == Direction.INCOME) { onDirectionChange(Direction.INCOME) }
        }

        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                // 只允许数字和一个小数点，避免出现无法解析的内容
                if (input.isEmpty() || input.matches(Regex("""[0-9]*\.?[0-9]{0,2}"""))) {
                    onAmountChange(input)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("金额") },
            prefix = { Text("¥") },
            placeholder = { Text("0.00") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.headlineSmall
        )

        Column {
            Text(
                text = "分类",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.id }) { category ->
                    CategoryChip(
                        category = category,
                        selected = category.id == categoryId,
                        onClick = { onCategoryChange(category.id) }
                    )
                }
            }
        }

        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("商户（选填）") },
            singleLine = true
        )

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（选填）") },
            singleLine = true
        )

        Button(
            onClick = onSave,
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("保存", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ClipboardHint(preview: ParsedPayment, onApply: () -> Unit) {
    Surface(
        onClick = onApply,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "剪贴板里有一笔${preview.channel}账单",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = listOfNotNull(
                        preview.merchant.takeIf { it != "未知商户" },
                        preview.direction.label,
                        "¥${Format.yuan(preview.amountCents)}"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "一键填入",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DirectionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFF4F7F6)
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color(0xFF5C6C67),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun CategoryChip(category: CategoryEntity, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFDCE4E1)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(category.emoji, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                text = category.name,
                fontSize = 13.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF3A4743)
            )
        }
    }
}
