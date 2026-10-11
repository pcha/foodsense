package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.ExpiryThresholds
import dev.pcha.foodsense.app.data.Item
import dev.pcha.foodsense.app.data.Product
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private data class ProductItemGroup(
    val quantity: Float,
    val unit: ProductUnit?,
    val expirationDate: LocalDate?,
    val items: List<Item>,
)

@Composable
internal fun ProductCard(
    product: Product,
    onEdit: () -> Unit,
    onQuickAdd: () -> Unit,
    onDeleteItem: (Int) -> Unit,
    onDeleteItems: (List<Int>) -> Unit,
    onEditGroup: (List<Item>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val itemGroups = remember(product.items) { product.items.toProductItemGroups() }
    var deleteGroupItems by remember { mutableStateOf<List<Item>?>(null) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ProductCardHeader(
                productName = product.name,
                onQuickAdd = onQuickAdd,
                onEdit = onEdit,
            )
            itemGroups.forEachIndexed { index, group ->
                if (index > 0) ProductItemGroupDivider()
                ProductItemGroupRow(
                    group = group,
                    today = today,
                    onEdit = { onEditGroup(group.items) },
                    onDelete = {
                        if (group.items.size == 1) onDeleteItem(group.items.first().uid)
                        else deleteGroupItems = group.items
                    },
                )
            }
        }
    }

    deleteGroupItems?.let { groupItems ->
        DeleteCountDialog(
            groupSize = groupItems.size,
            onConfirm = { count ->
                onDeleteItems(groupItems.take(count).map { it.uid })
                deleteGroupItems = null
            },
            onDismiss = { deleteGroupItems = null },
        )
    }
}

@Composable
private fun ProductCardHeader(
    productName: String,
    onQuickAdd: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            productName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        ProductCardActions(onQuickAdd = onQuickAdd, onEdit = onEdit)
    }
}

@Composable
private fun ProductCardActions(
    onQuickAdd: () -> Unit,
    onEdit: () -> Unit,
) {
    IconButton(onClick = onQuickAdd) {
        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_items))
    }
    IconButton(onClick = onEdit) {
        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit_product))
    }
}

@Composable
private fun ProductItemGroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ProductItemGroupRow(
    group: ProductItemGroup,
    today: LocalDate,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductItemGroupDetails(
            group = group,
            today = today,
            modifier = Modifier.weight(1f),
        )
        ProductItemGroupActions(onEdit = onEdit, onDelete = onDelete)
    }
}

@Composable
private fun ProductItemGroupDetails(
    group: ProductItemGroup,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            quantityLabel(group.items.size, group.quantity, group.unit, context),
            style = MaterialTheme.typography.bodyMedium,
        )
        ProductItemDateLabel(group = group, today = today)
    }
}

@Composable
private fun ProductItemDateLabel(
    group: ProductItemGroup,
    today: LocalDate,
) {
    val expirationDate = group.expirationDate
    if (expirationDate != null) {
        ProductExpiryLabel(expirationDate = expirationDate, today = today)
    } else {
        ProductAddedLabel(addedAt = group.items.first().addedAt, today = today)
    }
}

@Composable
private fun ProductExpiryLabel(
    expirationDate: LocalDate,
    today: LocalDate,
) {
    val context = LocalContext.current
    val daysUntil = ChronoUnit.DAYS.between(today, expirationDate)
    val expiryColor = expiryColor(daysUntil)

    Text(
        expiryLabel(daysUntil, expirationDate, today, context),
        style = MaterialTheme.typography.bodySmall,
        color = expiryColor,
        fontWeight = if (expiryColor != Color.Unspecified) FontWeight.Bold else null,
    )
}

@Composable
private fun ProductAddedLabel(
    addedAt: LocalDate,
    today: LocalDate,
) {
    val context = LocalContext.current
    Text(addedLabel(addedAt, today, context), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProductItemGroupActions(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    IconButton(onClick = onEdit) {
        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit_group))
    }
    IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
    }
}

@Composable
private fun DeleteCountDialog(
    groupSize: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = {
            DeleteCountField(
                count = count,
                groupSize = groupSize,
                onCountChange = { count = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(count.toIntOrNull()?.coerceIn(1, groupSize) ?: 1) },
                enabled = count.toIntOrNull()?.let { it in 1..groupSize } == true,
            ) {
                Text(stringResource(R.string.btn_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

@Composable
private fun DeleteCountField(
    count: String,
    groupSize: Int,
    onCountChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = count,
        onValueChange = { value ->
            if (value.isValidDeleteCount(groupSize)) onCountChange(value)
        },
        label = { Text(stringResource(R.string.dialog_delete_count_label, groupSize)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun List<Item>.toProductItemGroups(): List<ProductItemGroup> {
    return groupBy { Triple(it.quantity, it.unit, it.expirationDate) }
        .map { (key, items) ->
            ProductItemGroup(
                quantity = key.first,
                unit = key.second,
                expirationDate = key.third,
                items = items,
            )
        }
}

@Composable
private fun expiryColor(daysUntil: Long): Color {
    return when {
        daysUntil < 0 -> MaterialTheme.colorScheme.error
        daysUntil <= ExpiryThresholds.URGENT_DAYS -> ExpiryColorUrgent
        daysUntil <= ExpiryThresholds.WARNING_DAYS -> ExpiryColorWarning
        else -> Color.Unspecified
    }
}

private fun String.isValidDeleteCount(groupSize: Int): Boolean {
    if (!all { it.isDigit() }) return false
    val n = toIntOrNull()
    return n == null || n in 1..groupSize
}
