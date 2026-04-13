package com.github.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.github.pcha.foodsense.app.data.ProductItem
import com.github.pcha.foodsense.app.data.di.fakeProducts
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
        isEditing = uiState.editingProduct != null,
        onOpenAddSheet = viewModel::openAddSheet,
        onDismissAddSheet = viewModel::dismissAddSheet,
        onEditProduct = viewModel::openEditSheet,
        onDeleteProduct = viewModel::deleteProduct,
        formName = uiState.formName,
        onFormNameChange = viewModel::onFormNameChange,
        formQuantity = uiState.formQuantity,
        onFormQuantityChange = viewModel::onFormQuantityChange,
        formDate = uiState.formExpirationDate,
        onFormDateSelected = viewModel::onFormDateSelected,
        onSave = viewModel::addProduct,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductScreen(
    items: List<ProductItem>,
    showAddSheet: Boolean,
    isEditing: Boolean,
    onOpenAddSheet: () -> Unit,
    onDismissAddSheet: () -> Unit,
    onEditProduct: (ProductItem) -> Unit,
    onDeleteProduct: (Int) -> Unit,
    formName: String,
    onFormNameChange: (String) -> Unit,
    formQuantity: String,
    onFormQuantityChange: (String) -> Unit,
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
                    onDelete = { onDeleteProduct(product.uid) },
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
                expirationDate = formDate,
                onDateSelected = onFormDateSelected,
                isEditing = isEditing,
                onSave = onSave,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ProductCard(
    product: ProductItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium)
                Text("Qty: ${product.quantity} units", style = MaterialTheme.typography.bodyMedium)
                Text("Expires: ${product.expirationDate.format(formatter)}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductForm(
    name: String,
    onNameChange: (String) -> Unit,
    quantity: String,
    onQuantityChange: (String) -> Unit,
    expirationDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    isEditing: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = name,
            onValueChange = onNameChange,
            label = { Text("Product name") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = quantity,
            onValueChange = onQuantityChange,
            label = { Text("Quantity") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = expirationDate?.format(formatter) ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Expiration date") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Pick date")
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSave,
            enabled = name.isNotBlank() && expirationDate != null,
        ) {
            Text(if (isEditing) "Save changes" else "Add product")
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

@Preview(showBackground = true)
@Composable
private fun DefaultPreview() {
    MyApplicationTheme {
        ProductScreen(
            items = fakeProducts,
            showAddSheet = false,
            isEditing = false,
            onOpenAddSheet = {},
            onDismissAddSheet = {},
            onEditProduct = {},
            onDeleteProduct = {},
            formName = "",
            onFormNameChange = {},
            formQuantity = "1",
            onFormQuantityChange = {},
            formDate = null,
            onFormDateSelected = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EditSheetPreview() {
    MyApplicationTheme {
        ProductScreen(
            items = fakeProducts,
            showAddSheet = true,
            isEditing = true,
            onOpenAddSheet = {},
            onDismissAddSheet = {},
            onEditProduct = {},
            onDeleteProduct = {},
            formName = "Milk",
            onFormNameChange = {},
            formQuantity = "2",
            onFormQuantityChange = {},
            formDate = LocalDate.now().plusDays(5),
            onFormDateSelected = {},
            onSave = {},
        )
    }
}
