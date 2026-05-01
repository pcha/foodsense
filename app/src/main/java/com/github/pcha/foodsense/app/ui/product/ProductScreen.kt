package com.github.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.di.fakeProducts
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import com.github.pcha.foodsense.app.ui.theme.MyApplicationTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProductScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProductScreen(
        items = uiState.products,
        showAddSheet = uiState.showAddSheet,
        isEditingProduct = uiState.editingProductId != null,
        isQuickAdd = uiState.addingToProductId != null,
        isEditingGroup = uiState.editingGroupItemIds.isNotEmpty(),
        maxApplyCount = uiState.maxApplyCount,
        onOpenAddSheet = viewModel::openAddSheet,
        onDismissAddSheet = viewModel::dismissAddSheet,
        onEditProduct = viewModel::openEditSheet,
        onQuickAdd = viewModel::openQuickAddSheet,
        onEditGroup = viewModel::openEditItemSheet,
        onDeleteItem = viewModel::deleteItem,
        onDeleteItems = viewModel::deleteItems,
        formName = uiState.formName,
        onFormNameChange = viewModel::onFormNameChange,
        formQuantity = uiState.formQuantity,
        onFormQuantityChange = viewModel::onFormQuantityChange,
        formUnit = uiState.formUnit,
        onFormUnitChange = viewModel::onFormUnitChange,
        formBatchCount = uiState.formBatchCount,
        onFormBatchCountChange = viewModel::onFormBatchCountChange,
        formApplyCount = uiState.formApplyCount,
        onFormApplyCountChange = viewModel::onFormApplyCountChange,
        formDate = uiState.formExpirationDate,
        onFormDateSelected = viewModel::onFormDateSelected,
        onSave = viewModel::addProduct,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductScreen(
    items: List<Product>,
    showAddSheet: Boolean,
    isEditingProduct: Boolean,
    isQuickAdd: Boolean,
    isEditingGroup: Boolean,
    maxApplyCount: Int,
    onOpenAddSheet: () -> Unit,
    onDismissAddSheet: () -> Unit,
    onEditProduct: (Product) -> Unit,
    onQuickAdd: (Product) -> Unit,
    onEditGroup: (List<Item>) -> Unit,
    onDeleteItem: (Int) -> Unit,
    onDeleteItems: (List<Int>) -> Unit,
    formName: String,
    onFormNameChange: (String) -> Unit,
    formQuantity: String,
    onFormQuantityChange: (String) -> Unit,
    formUnit: ProductUnit?,
    onFormUnitChange: (ProductUnit?) -> Unit,
    formBatchCount: String,
    onFormBatchCountChange: (String) -> Unit,
    formApplyCount: String,
    onFormApplyCountChange: (String) -> Unit,
    formDate: LocalDate?,
    onFormDateSelected: (LocalDate) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenAddSheet) {
                Icon(Icons.Default.Add, contentDescription = "Add product")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items) { product ->
                ProductCard(
                    product = product,
                    onEdit = { onEditProduct(product) },
                    onQuickAdd = { onQuickAdd(product) },
                    onDeleteItem = onDeleteItem,
                    onDeleteItems = onDeleteItems,
                    onEditGroup = onEditGroup,
                )
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissAddSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AddProductForm(
                name = formName,
                onNameChange = onFormNameChange,
                quantity = formQuantity,
                onQuantityChange = onFormQuantityChange,
                unit = formUnit,
                onUnitChange = onFormUnitChange,
                batchCount = formBatchCount,
                onBatchCountChange = onFormBatchCountChange,
                applyCount = formApplyCount,
                onApplyCountChange = onFormApplyCountChange,
                maxApplyCount = maxApplyCount,
                expirationDate = formDate,
                onDateSelected = onFormDateSelected,
                isEditingProduct = isEditingProduct,
                isQuickAdd = isQuickAdd,
                isEditingGroup = isEditingGroup,
                onSave = onSave,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ProductCard(
    product: Product,
    onEdit: () -> Unit,
    onQuickAdd: () -> Unit,
    onDeleteItem: (Int) -> Unit,
    onDeleteItems: (List<Int>) -> Unit,
    onEditGroup: (List<Item>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
    val itemGroups = remember(product.items) {
        product.items.groupBy { Triple(it.quantity, it.unit, it.expirationDate) }.entries.toList()
    }
    var deleteGroupItems by remember { mutableStateOf<List<Item>?>(null) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onQuickAdd) {
                    Icon(Icons.Default.Add, contentDescription = "Add items")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
            }
            itemGroups.forEachIndexed { index, (key, groupItems) ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val (qty, unit, date) = key
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(quantityLabel(groupItems.size, qty, unit), style = MaterialTheme.typography.bodyMedium)
                        if (date != null) {
                            Text("Expires: ${date.format(formatter)}", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Added: ${groupItems.first().addedAt.format(formatter)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onEditGroup(groupItems) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit group")
                    }
                    IconButton(onClick = {
                        if (groupItems.size == 1) onDeleteItem(groupItems.first().uid)
                        else deleteGroupItems = groupItems
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
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
private fun DeleteCountDialog(
    groupSize: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var count by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How many to remove?") },
        text = {
            OutlinedTextField(
                value = count,
                onValueChange = { value ->
                    if (value.all { it.isDigit() }) {
                        val n = value.toIntOrNull()
                        if (n == null || n in 1..groupSize) count = value
                    }
                },
                label = { Text("Count (max $groupSize)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(count.toIntOrNull()?.coerceIn(1, groupSize) ?: 1) },
                enabled = count.toIntOrNull()?.let { it in 1..groupSize } == true,
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun quantityLabel(count: Int, quantity: Float, unit: ProductUnit?): String {
    val qtyStr = if (quantity == quantity.toLong().toFloat()) quantity.toLong().toString() else quantity.toString()
    val base = if (unit != null) "$qtyStr ${unit.displayLabel()}" else "Qty: $qtyStr"
    return if (count > 1) "$count × $base" else base
}

private fun ProductUnit.displayLabel(): String = when (this) {
    ProductUnit.L -> "L"
    ProductUnit.ML -> "mL"
    ProductUnit.G -> "g"
    ProductUnit.KG -> "kg"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductForm(
    name: String,
    onNameChange: (String) -> Unit,
    quantity: String,
    onQuantityChange: (String) -> Unit,
    unit: ProductUnit?,
    onUnitChange: (ProductUnit?) -> Unit,
    batchCount: String,
    onBatchCountChange: (String) -> Unit,
    applyCount: String,
    onApplyCountChange: (String) -> Unit,
    maxApplyCount: Int,
    expirationDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    isEditingProduct: Boolean,
    isQuickAdd: Boolean,
    isEditingGroup: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }

    val showName = !isEditingGroup
    val nameEditable = !isEditingProduct && !isQuickAdd
    val showQtyUnitDate = !isEditingProduct
    val showBatchCount = !isEditingProduct && !isEditingGroup
    val showApplyCount = isEditingGroup

    val saveEnabled = when {
        isEditingGroup -> (quantity.toFloatOrNull() ?: 0f) > 0f
        isEditingProduct -> name.isNotBlank()
        else -> name.isNotBlank() && (quantity.toFloatOrNull() ?: 0f) > 0f
    }
    val saveLabel = if (isEditingProduct || isEditingGroup) "Save changes" else "Add product"

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showName) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = if (nameEditable) onNameChange else { _ -> },
                label = { Text("Product name") },
                singleLine = true,
                readOnly = !nameEditable,
            )
        }
        if (showQtyUnitDate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = quantity,
                    onValueChange = onQuantityChange,
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                UnitDropdown(
                    selected = unit,
                    onSelected = onUnitChange,
                    modifier = Modifier.width(100.dp),
                )
            }
            if (showBatchCount) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = batchCount,
                    onValueChange = onBatchCountChange,
                    label = { Text("Number of items") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (showApplyCount) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = applyCount,
                    onValueChange = onApplyCountChange,
                    label = { Text("Apply to how many? (max $maxApplyCount)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = expirationDate?.format(formatter) ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Expiration date (optional)") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Pick date")
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSave,
            enabled = saveEnabled,
        ) {
            Text(saveLabel)
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        onDateSelected(date)
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(
    selected: ProductUnit?,
    onSelected: (ProductUnit?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<ProductUnit?> = listOf(null) + ProductUnit.entries

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            value = selected?.displayLabel() ?: "—",
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.displayLabel() ?: "—") },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DefaultPreview() {
    MyApplicationTheme {
        ProductScreen(
            items = fakeProducts,
            showAddSheet = false,
            isEditingProduct = false,
            isQuickAdd = false,
            isEditingGroup = false,
            maxApplyCount = 1,
            onOpenAddSheet = {},
            onDismissAddSheet = {},
            onEditProduct = {},
            onQuickAdd = {},
            onEditGroup = {},
            onDeleteItem = {},
            onDeleteItems = {},
            formName = "",
            onFormNameChange = {},
            formQuantity = "1",
            onFormQuantityChange = {},
            formUnit = null,
            onFormUnitChange = {},
            formBatchCount = "1",
            onFormBatchCountChange = {},
            formApplyCount = "1",
            onFormApplyCountChange = {},
            formDate = null,
            onFormDateSelected = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EditGroupPreview() {
    MyApplicationTheme {
        ProductScreen(
            items = fakeProducts,
            showAddSheet = true,
            isEditingProduct = false,
            isQuickAdd = false,
            isEditingGroup = true,
            maxApplyCount = 6,
            onOpenAddSheet = {},
            onDismissAddSheet = {},
            onEditProduct = {},
            onQuickAdd = {},
            onEditGroup = {},
            onDeleteItem = {},
            onDeleteItems = {},
            formName = "",
            onFormNameChange = {},
            formQuantity = "1",
            onFormQuantityChange = {},
            formUnit = ProductUnit.L,
            onFormUnitChange = {},
            formBatchCount = "1",
            onFormBatchCountChange = {},
            formApplyCount = "1",
            onFormApplyCountChange = {},
            formDate = LocalDate.now().plusDays(5),
            onFormDateSelected = {},
            onSave = {},
        )
    }
}
