package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pcha.foodsense.app.data.Item
import dev.pcha.foodsense.app.data.Product
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate

@Composable
fun ProductScreen(
    onScan: () -> Unit,
    currentUser: User? = null,
    onNavigateToLogin: () -> Unit = {},
    onSignOut: () -> Unit = {},
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
        pendingBarcode = uiState.pendingBarcode,
        originalFormName = uiState.originalFormName,
        rememberBarcode = uiState.rememberBarcode,
        onRememberBarcodeChange = viewModel::setRememberBarcode,
        showDateScanner = uiState.showDateScanner,
        dateScanError = uiState.dateScanError,
        syncError = uiState.syncError,
        onOpenDateScanner = viewModel::openDateScanner,
        onDismissDateScanner = viewModel::dismissDateScanner,
        onDateImageCaptured = viewModel::processCapturedDateImage,
        onSave = viewModel::addProduct,
        onScan = onScan,
        currentUser = currentUser,
        onNavigateToLogin = onNavigateToLogin,
        onSignOut = onSignOut,
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
    pendingBarcode: String?,
    originalFormName: String?,
    rememberBarcode: Boolean,
    onRememberBarcodeChange: (Boolean) -> Unit,
    showDateScanner: Boolean,
    dateScanError: Boolean,
    syncError: Boolean = false,
    onOpenDateScanner: () -> Unit,
    onDismissDateScanner: () -> Unit,
    onDateImageCaptured: (android.graphics.Bitmap) -> Unit,
    onSave: () -> Unit,
    onScan: () -> Unit,
    currentUser: User? = null,
    onNavigateToLogin: () -> Unit = {},
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            ProductTopAppBar(
                currentUser = currentUser,
                onNavigateToLogin = onNavigateToLogin,
                onSignOut = onSignOut,
            )
        },
        floatingActionButton = {
            ProductFabActions(
                onScan = onScan,
                onOpenAddSheet = onOpenAddSheet,
            )
        },
    ) { innerPadding ->
        ProductList(
            products = items,
            syncError = syncError,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            onEditProduct = onEditProduct,
            onQuickAdd = onQuickAdd,
            onDeleteItem = onDeleteItem,
            onDeleteItems = onDeleteItems,
            onEditGroup = onEditGroup,
        )
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
                onOpenDateScanner = onOpenDateScanner,
                pendingBarcode = pendingBarcode,
                originalFormName = originalFormName,
                rememberBarcode = rememberBarcode,
                onRememberBarcodeChange = onRememberBarcodeChange,
                isEditingProduct = isEditingProduct,
                isQuickAdd = isQuickAdd,
                isEditingGroup = isEditingGroup,
                onSave = onSave,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (showDateScanner) {
        DateScanSheet(
            error = dateScanError,
            onImageCaptured = onDateImageCaptured,
            onDismiss = onDismissDateScanner,
        )
    }
}
