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
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
    val showDatePicker = remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
    val today = remember { LocalDate.now() }
    val context = LocalContext.current

    val showName = !isEditingGroup
    val nameEditable = !isEditingProduct && !isQuickAdd
    val showQtyUnitDate = !isEditingProduct
    val showBatchCount = !isEditingProduct && !isEditingGroup

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
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
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
            if (isEditingGroup) {
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
                IconButton(
                    onClick = { showDatePicker.value = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = stringResource(R.string.btn_pick_date))
                }
                IconButton(
                    onClick = onOpenDateScanner,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = stringResource(R.string.cd_scan_date))
                }
            }
            if (expirationDate != null) {
                val daysUntil = ChronoUnit.DAYS.between(today, expirationDate)
                Text(
                    expiryLabel(daysUntil, expirationDate, today, context),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        daysUntil < 0 -> MaterialTheme.colorScheme.error
                        daysUntil <= ExpiryThresholds.URGENT_DAYS -> ExpiryColorUrgent
                        daysUntil <= ExpiryThresholds.WARNING_DAYS -> ExpiryColorWarning
                        else -> Color.Unspecified
                    },
                )
            }
        }
        val showRememberCheckbox = pendingBarcode != null
            && !isEditingProduct
            && !isEditingGroup
            && (originalFormName == null || name.trim() != originalFormName)
        if (showRememberCheckbox) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = rememberBarcode,
                    onCheckedChange = onRememberBarcodeChange,
                )
                Text(stringResource(R.string.remember_barcode), style = MaterialTheme.typography.bodyMedium)
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

    if (showDatePicker.value) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker.value = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        onDateSelected(date)
                    }
                    showDatePicker.value = false
                }) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker.value = false }) {
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
    val expanded = remember { mutableStateOf(false) }
    val options: List<ProductUnit?> = listOf(null) + ProductUnit.entries

    ExposedDropdownMenuBox(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            value = selected?.displayLabel() ?: "—",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_unit)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded.value) },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.displayLabel() ?: "—") },
                    onClick = {
                        onSelected(option)
                        expanded.value = false
                    },
                )
            }
        }
    }
}
