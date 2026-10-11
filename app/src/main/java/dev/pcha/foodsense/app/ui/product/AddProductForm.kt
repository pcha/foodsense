package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.ExpiryThresholds
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProductForm(
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
    onOpenDateScanner: () -> Unit,
    pendingBarcode: String?,
    originalFormName: String?,
    rememberBarcode: Boolean,
    onRememberBarcodeChange: (Boolean) -> Unit,
    isEditingProduct: Boolean,
    isQuickAdd: Boolean,
    isEditingGroup: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val saveEnabled = addProductSaveEnabled(
        name = name,
        quantity = quantity,
        isEditingProduct = isEditingProduct,
        isEditingGroup = isEditingGroup,
    )
    val saveLabel = if (isEditingProduct || isEditingGroup) {
        stringResource(R.string.btn_save_changes)
    } else {
        stringResource(R.string.btn_add_product)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isEditingGroup) {
            ProductNameField(
                name = name,
                onNameChange = onNameChange,
                nameSuggestions = nameSuggestions,
                editable = !isEditingProduct && !isQuickAdd,
            )
        }
        if (!isEditingProduct) {
            ProductQuantityDateFields(
                quantity = quantity,
                onQuantityChange = onQuantityChange,
                unit = unit,
                onUnitChange = onUnitChange,
                batchCount = batchCount,
                onBatchCountChange = onBatchCountChange,
                applyCount = applyCount,
                onApplyCountChange = onApplyCountChange,
                maxApplyCount = maxApplyCount,
                expirationDate = expirationDate,
                today = today,
                showBatchCount = !isEditingGroup,
                showApplyCount = isEditingGroup,
                onOpenDatePicker = { showDatePicker = true },
                onOpenDateScanner = onOpenDateScanner,
            )
        }
        if (shouldShowRememberBarcode(
                pendingBarcode = pendingBarcode,
                isEditingProduct = isEditingProduct,
                isEditingGroup = isEditingGroup,
                name = name,
                originalFormName = originalFormName,
            )
        ) {
            RememberBarcodeCheckbox(
                checked = rememberBarcode,
                onCheckedChange = onRememberBarcodeChange,
            )
        }
        SaveProductButton(
            label = saveLabel,
            enabled = saveEnabled,
            onClick = onSave,
        )
    }

    if (showDatePicker) {
        ExpirationDatePickerDialog(
            onDateSelected = onDateSelected,
            onDismiss = { showDatePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductNameField(
    name: String,
    onNameChange: (String) -> Unit,
    nameSuggestions: List<String>,
    editable: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val filteredSuggestions = remember(name, nameSuggestions) {
        if (name.isBlank()) nameSuggestions
        else nameSuggestions.filter { it.contains(name, ignoreCase = true) && !it.equals(name, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = editable && expanded && filteredSuggestions.isNotEmpty(),
        onExpandedChange = { if (editable) expanded = it },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            value = name,
            onValueChange = { value ->
                if (editable) {
                    onNameChange(value)
                    expanded = true
                }
            },
            label = { Text(stringResource(R.string.field_product_name)) },
            singleLine = true,
            readOnly = !editable,
        )
        ExposedDropdownMenu(
            expanded = editable && expanded && filteredSuggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            filteredSuggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onNameChange(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ProductQuantityDateFields(
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
    today: LocalDate,
    showBatchCount: Boolean,
    showApplyCount: Boolean,
    onOpenDatePicker: () -> Unit,
    onOpenDateScanner: () -> Unit,
) {
    if (showBatchCount) {
        BatchCountField(value = batchCount, onValueChange = onBatchCountChange)
    }
    QuantityUnitRow(
        quantity = quantity,
        onQuantityChange = onQuantityChange,
        unit = unit,
        onUnitChange = onUnitChange,
    )
    if (showApplyCount) {
        ApplyCountField(
            value = applyCount,
            onValueChange = onApplyCountChange,
            maxApplyCount = maxApplyCount,
        )
    }
    ExpirationDateRow(
        expirationDate = expirationDate,
        onOpenDatePicker = onOpenDatePicker,
        onOpenDateScanner = onOpenDateScanner,
    )
    if (expirationDate != null) {
        ExpirationStatusText(expirationDate = expirationDate, today = today)
    }
}

@Composable
private fun BatchCountField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_number_of_items)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun QuantityUnitRow(
    quantity: String,
    onQuantityChange: (String) -> Unit,
    unit: ProductUnit?,
    onUnitChange: (ProductUnit?) -> Unit,
) {
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
}

@Composable
private fun ApplyCountField(
    value: String,
    onValueChange: (String) -> Unit,
    maxApplyCount: Int,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_apply_to_how_many, maxApplyCount)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ExpirationDateRow(
    expirationDate: LocalDate?,
    onOpenDatePicker: () -> Unit,
    onOpenDateScanner: () -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = expirationDate?.format(formatter) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_expiration_date)) },
            singleLine = true,
        )
        ExpirationDateActionButton(
            onClick = onOpenDatePicker,
            contentDescription = stringResource(R.string.btn_pick_date),
            imageVector = Icons.Default.DateRange,
        )
        ExpirationDateActionButton(
            onClick = onOpenDateScanner,
            contentDescription = stringResource(R.string.cd_scan_date),
            imageVector = Icons.Default.PhotoCamera,
        )
    }
}

@Composable
private fun ExpirationDateActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    imageVector: ImageVector,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Icon(imageVector, contentDescription = contentDescription)
    }
}

@Composable
private fun ExpirationStatusText(
    expirationDate: LocalDate,
    today: LocalDate,
) {
    val context = LocalContext.current
    val daysUntil = ChronoUnit.DAYS.between(today, expirationDate)
    Text(
        expiryLabel(daysUntil, expirationDate, today, context),
        style = MaterialTheme.typography.bodySmall,
        color = expirationStatusColor(daysUntil),
    )
}

@Composable
private fun RememberBarcodeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(stringResource(R.string.remember_barcode), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SaveProductButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(label)
    }
}

@Composable
private fun ExpirationDatePickerDialog(
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    onDateSelected(date)
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.btn_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
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
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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

private fun addProductSaveEnabled(
    name: String,
    quantity: String,
    isEditingProduct: Boolean,
    isEditingGroup: Boolean,
): Boolean {
    return when {
        isEditingGroup -> (quantity.toFloatOrNull() ?: 0f) > 0f
        isEditingProduct -> name.isNotBlank()
        else -> name.isNotBlank() && (quantity.toFloatOrNull() ?: 0f) > 0f
    }
}

private fun shouldShowRememberBarcode(
    pendingBarcode: String?,
    isEditingProduct: Boolean,
    isEditingGroup: Boolean,
    name: String,
    originalFormName: String?,
): Boolean {
    return pendingBarcode != null
        && !isEditingProduct
        && !isEditingGroup
        && (originalFormName == null || name.trim() != originalFormName)
}

@Composable
private fun expirationStatusColor(daysUntil: Long): Color {
    return when {
        daysUntil < 0 -> MaterialTheme.colorScheme.error
        daysUntil <= ExpiryThresholds.URGENT_DAYS -> ExpiryColorUrgent
        daysUntil <= ExpiryThresholds.WARNING_DAYS -> ExpiryColorWarning
        else -> Color.Unspecified
    }
}
