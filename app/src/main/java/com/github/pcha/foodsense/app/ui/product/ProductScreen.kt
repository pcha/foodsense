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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import android.content.res.Resources
import com.github.pcha.foodsense.app.R
import com.github.pcha.foodsense.app.data.ExpiryThresholds
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.di.fakeProducts
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import com.github.pcha.foodsense.app.ui.theme.MyApplicationTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val ExpiryColorUrgent = Color(0xFFFF9800)
private val ExpiryColorWarning = Color(0xFF4CAF50)

@Composable
fun ProductScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProductScreen(
        items = uiState.products,
        nameSuggestions = uiState.allProductNames,
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
    nameSuggestions: List<String>,
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_product))
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
                nameSuggestions = nameSuggestions,
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
    val today = remember { LocalDate.now() }
    val resources = LocalContext.current.resources
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_items))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit_product))
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
                            val daysUntil = ChronoUnit.DAYS.between(today, date)
                            val expiryColor = when {
                                daysUntil < 0 -> MaterialTheme.colorScheme.error
                                daysUntil <= ExpiryThresholds.URGENT_DAYS -> ExpiryColorUrgent
                                daysUntil <= ExpiryThresholds.WARNING_DAYS -> ExpiryColorWarning
                                else -> Color.Unspecified
                            }
                            Text(
                                expiryLabel(daysUntil, date, today, resources),
                                style = MaterialTheme.typography.bodySmall,
                                color = expiryColor,
                                fontWeight = if (expiryColor != Color.Unspecified) FontWeight.Bold else null,
                            )
                        } else {
                            Text(addedLabel(groupItems.first().addedAt, today, resources), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onEditGroup(groupItems) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit_group))
                    }
                    IconButton(onClick = {
                        if (groupItems.size == 1) onDeleteItem(groupItems.first().uid)
                        else deleteGroupItems = groupItems
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
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
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = {
            OutlinedTextField(
                value = count,
                onValueChange = { value ->
                    if (value.all { it.isDigit() }) {
                        val n = value.toIntOrNull()
                        if (n == null || n in 1..groupSize) count = value
                    }
                },
                label = { Text(stringResource(R.string.dialog_delete_count_label, groupSize)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

private fun quantityLabel(count: Int, quantity: Float, unit: ProductUnit?): String {
    val qtyStr = if (quantity == quantity.toLong().toFloat()) quantity.toLong().toString() else quantity.toString()
    val base = if (unit != null) "$qtyStr ${unit.displayLabel()}" else "Qty: $qtyStr"
    return if (count > 1) "$count × $base" else base
}

private fun relativeMagnitude(from: LocalDate, to: LocalDate, resources: Resources): String {
    val years = ChronoUnit.YEARS.between(from, to)
    val months = ChronoUnit.MONTHS.between(from, to)
    val days = ChronoUnit.DAYS.between(from, to)

    fun qty(n: Long, pluralsId: Int) = resources.getQuantityString(pluralsId, n.toInt(), n)

    return when {
        years >= 1L -> {
            val remMonths = ChronoUnit.MONTHS.between(from.plusYears(years), to)
            val yearsStr = qty(years, R.plurals.expiry_years)
            if (remMonths > 0L) resources.getString(R.string.expiry_and, yearsStr, qty(remMonths, R.plurals.expiry_months))
            else yearsStr
        }
        months >= 1L -> {
            val remDays = ChronoUnit.DAYS.between(from.plusMonths(months), to)
            val monthsStr = qty(months, R.plurals.expiry_months)
            if (remDays > 0L) resources.getString(R.string.expiry_and, monthsStr, qty(remDays, R.plurals.expiry_days))
            else monthsStr
        }
        else -> qty(days, R.plurals.expiry_days)
    }
}

private fun expiryLabel(daysUntil: Long, date: LocalDate, today: LocalDate, resources: Resources): String {
    val magnitude = if (daysUntil < 0) relativeMagnitude(date, today, resources)
                    else relativeMagnitude(today, date, resources)
    return when {
        daysUntil == 0L  -> resources.getString(R.string.expiry_today)
        daysUntil == 1L  -> resources.getString(R.string.expiry_tomorrow)
        daysUntil == -1L -> resources.getString(R.string.expired_yesterday)
        daysUntil < 0    -> resources.getString(R.string.expired_ago, magnitude)
        else             -> resources.getString(R.string.expiry_in, magnitude)
    }
}

private fun addedLabel(addedAt: LocalDate, today: LocalDate, resources: Resources): String {
    val daysAgo = ChronoUnit.DAYS.between(addedAt, today)
    return when {
        daysAgo == 0L -> resources.getString(R.string.added_today)
        daysAgo == 1L -> resources.getString(R.string.added_yesterday)
        else          -> resources.getString(R.string.added_ago, relativeMagnitude(addedAt, today, resources))
    }
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
    nameSuggestions: List<String>,
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
    val saveLabel = if (isEditingProduct || isEditingGroup) stringResource(R.string.btn_save_changes) else stringResource(R.string.btn_add_product)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showName) {
            var nameDropdownExpanded by remember { mutableStateOf(false) }
            val filteredSuggestions = remember(name, nameSuggestions) {
                if (name.isBlank()) nameSuggestions
                else nameSuggestions.filter { it.contains(name, ignoreCase = true) && !it.equals(name, ignoreCase = true) }
            }
            ExposedDropdownMenuBox(
                expanded = nameEditable && nameDropdownExpanded && filteredSuggestions.isNotEmpty(),
                onExpandedChange = { if (nameEditable) nameDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                    value = name,
                    onValueChange = {
                        if (nameEditable) {
                            onNameChange(it)
                            nameDropdownExpanded = true
                        }
                    },
                    label = { Text(stringResource(R.string.field_product_name)) },
                    singleLine = true,
                    readOnly = !nameEditable,
                )
                ExposedDropdownMenu(
                    expanded = nameEditable && nameDropdownExpanded && filteredSuggestions.isNotEmpty(),
                    onDismissRequest = { nameDropdownExpanded = false },
                ) {
                    filteredSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                onNameChange(suggestion)
                                nameDropdownExpanded = false
                            },
                        )
                    }
                }
            }
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
                    label = { Text(stringResource(R.string.field_quantity)) },
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
                    label = { Text(stringResource(R.string.field_number_of_items)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (showApplyCount) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = applyCount,
                    onValueChange = onApplyCountChange,
                    label = { Text(stringResource(R.string.field_apply_to_how_many, maxApplyCount)) },
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
                    label = { Text(stringResource(R.string.field_expiration_date)) },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.btn_pick_date))
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
                    Text(stringResource(R.string.btn_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.btn_cancel))
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
            label = { Text(stringResource(R.string.field_unit)) },
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

@Preview(showBackground = true, name = "Expiry colors – Light")
@Preview(showBackground = true, name = "Expiry colors – Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExpiryColorsPreview() {
    val today = remember { LocalDate.now() }
    val expiryItems = remember {listOf(
        Product(1, "Expired 5 days ago",  listOf(Item(1,  1, 1f, ProductUnit.L, today.minusDays(5), today))),
        Product(2, "Expired yesterday",   listOf(Item(2,  2, 1f, ProductUnit.L, today.minusDays(1), today))),
        Product(3, "Expires today",       listOf(Item(3,  3, 1f, ProductUnit.L, today,              today))),
        Product(4, "Expires tomorrow",    listOf(Item(4,  4, 1f, ProductUnit.L, today.plusDays(1),  today))),
        Product(5, "Expires in 2 days",   listOf(Item(5,  5, 1f, ProductUnit.L, today.plusDays(2),  today))),
        Product(6, "Expires in 5 days",   listOf(Item(6,  6, 1f, ProductUnit.L, today.plusDays(5),  today))),
        Product(7, "Expires in 40 days",  listOf(Item(7,  7, 1f, ProductUnit.L, today.plusDays(40), today))),
        Product(8, "Expires in 400 days", listOf(Item(8,  8, 1f, ProductUnit.L, today.plusDays(400),today))),
        Product(9, "No expiry date",      listOf(Item(9,  9, 1f, null,          null,               today))),
    )}
    MyApplicationTheme {
        ProductScreen(
            items = expiryItems,
            nameSuggestions = emptyList(),
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
private fun DefaultPreview() {
    MyApplicationTheme {
        ProductScreen(
            items = fakeProducts,
            nameSuggestions = emptyList(),
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
            nameSuggestions = emptyList(),
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
