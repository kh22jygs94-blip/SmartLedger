package com.example.smartledger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.smartledger.data.CategoryEntity
import com.example.smartledger.data.Direction
import com.example.smartledger.data.TransactionEntity
import com.example.smartledger.util.Format
import com.example.smartledger.util.label

/** 首页底部的待确认卡片：可改金额与分类，确认后计入账本 */
@Composable
internal fun PendingCard(
    item: TransactionEntity,
    categories: List<CategoryEntity>,
    onConfirm: (amountCents: Long, categoryId: Long) -> Unit,
    onIgnore: () -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var amountText by remember(item.id) { mutableStateOf(Format.yuanForEdit(item.amountCents)) }
    var selectedCategoryId by remember(item.id) { mutableStateOf(item.categoryId) }

    val options = remember(item.direction, categories) {
        val target = if (item.direction == Direction.EXPENSE) Direction.EXPENSE else Direction.INCOME
        categories.filter { it.direction == target }
    }
    val parsedAmount = Format.parseYuanToCents(amountText)

    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.merchant,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.channel} · ${Format.dateTime(item.occurredAt)} · ${item.direction.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "¥${Format.yuan(item.amountCents)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (item.rawText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF4F7F6)) {
                    Text(
                        text = item.rawText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            if (editing) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        if (input.isEmpty() || input.matches(Regex("""[0-9]*\.?[0-9]{0,2}"""))) {
                            amountText = input
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("金额") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it.id }) { category ->
                        CategoryChip(
                            category = category,
                            selected = category.id == selectedCategoryId,
                            onClick = { selectedCategoryId = category.id }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "置信度 ${(item.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "收起" else "修改")
                }
                TextButton(onClick = onIgnore) { Text("忽略") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        val amount = parsedAmount ?: item.amountCents
                        val categoryId = if (selectedCategoryId != 0L) selectedCategoryId else item.categoryId
                        onConfirm(amount, categoryId)
                        editing = false
                    },
                    enabled = (parsedAmount != null) &&
                        (selectedCategoryId != 0L || item.categoryId != 0L),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("确认入账", fontSize = 13.sp)
                }
            }
        }
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
        Text(
            text = "${category.emoji} ${category.name}",
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF3A4743),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
