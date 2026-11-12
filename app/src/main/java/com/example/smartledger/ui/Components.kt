package com.example.smartledger.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartledger.data.CategoryEntity
import com.example.smartledger.data.Direction
import com.example.smartledger.data.TransactionEntity
import com.example.smartledger.parser.BrandResolver
import com.example.smartledger.util.Format
import com.example.smartledger.util.label

/** 统一的一行账目：分类 emoji + 商户 + 渠道时间 + 金额 */
@Composable
fun TransactionRow(
    item: TransactionEntity,
    categories: Map<Long, CategoryEntity>,
    onLongClick: (() -> Unit)? = null
) {
    val category = categories[item.categoryId]
    val emoji = category?.emoji ?: "📦"
    // 认得出平台就显示品牌标记，认不出才退回分类 emoji
    val brand = remember(item.merchant, item.channel) {
        BrandResolver.resolve(item.merchant, item.channel)
    }
    val isExpense = item.direction == Direction.EXPENSE
    val subtitle = listOf(item.channel, Format.time(item.occurredAt), item.note)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onLongClick != null) {
                        Modifier.pointerInput(item.id) {
                            detectTapGestures(onLongPress = { onLongClick() })
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = brand?.let { Color(it.argb) } ?: MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = brand?.glyph ?: emoji,
                        fontSize = if (brand != null) 17.sp else 19.sp,
                        fontWeight = if (brand != null) FontWeight.Bold else null,
                        color = if (brand != null) Color.White else Color.Unspecified
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.merchant,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                val sign = if (isExpense) "-" else "+"
                Text(
                    text = "$sign¥${Format.yuan(item.amountCents)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isExpense) Color(0xFF1B2321) else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = category?.name ?: item.direction.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HintCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
