package dev.pcha.foodsense.app.ui.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import dev.pcha.foodsense.app.data.Item
import dev.pcha.foodsense.app.data.Product
import dev.pcha.foodsense.app.data.di.fakeProducts
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import dev.pcha.foodsense.app.ui.theme.MyApplicationTheme
import java.time.LocalDate

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
            pendingBarcode = null,
            originalFormName = null,
            rememberBarcode = true,
            onRememberBarcodeChange = {},
            showDateScanner = false,
            dateScanError = false,
            onOpenDateScanner = {},
            onDismissDateScanner = {},
            onDateImageCaptured = {},
            onSave = {},
            onScan = {},
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
            pendingBarcode = null,
            originalFormName = null,
            rememberBarcode = true,
            onRememberBarcodeChange = {},
            showDateScanner = false,
            dateScanError = false,
            onOpenDateScanner = {},
            onDismissDateScanner = {},
            onDateImageCaptured = {},
            onSave = {},
            onScan = {},
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
            pendingBarcode = null,
            originalFormName = null,
            rememberBarcode = true,
            onRememberBarcodeChange = {},
            showDateScanner = false,
            dateScanError = false,
            onOpenDateScanner = {},
            onDismissDateScanner = {},
            onDateImageCaptured = {},
            onSave = {},
            onScan = {},
        )
    }
}
