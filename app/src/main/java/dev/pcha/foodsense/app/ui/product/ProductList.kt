package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.ExpiryThresholds
import dev.pcha.foodsense.app.data.Item
import dev.pcha.foodsense.app.data.Product
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
internal fun ProductList(
    products: List<Product>,
    syncError: Boolean,
    contentPadding: PaddingValues,
    onEditProduct: (Product) -> Unit,
    onQuickAdd: (Product) -> Unit,
    onDeleteItem: (Int) -> Unit,
    onDeleteItems: (List<Int>) -> Unit,
    onEditGroup: (List<Item>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val sections = remember(products, today) { products.partitionByExpiry(today) }
    var expiringSoonExpanded by rememberSaveable { mutableStateOf(true) }
    var okExpanded by rememberSaveable { mutableStateOf(true) }
    var noExpiryExpanded by rememberSaveable { mutableStateOf(false) }
    var expiredExpanded by rememberSaveable { mutableStateOf(false) }
    val expiredTitle = stringResource(R.string.section_expired)
    val expiringSoonTitle = stringResource(R.string.section_expiring_soon)
    val noExpiryTitle = stringResource(R.string.section_no_expiry)
    val okTitle = stringResource(R.string.section_ok)
    val errorColor = MaterialTheme.colorScheme.error

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (syncError) {
            item(key = "sync_error_banner") { SyncErrorBanner() }
        }
        productSection(
            keyPrefix = "expired",
            title = expiredTitle,
            products = sections.expired,
            expanded = expiredExpanded,
            onToggle = { expiredExpanded = !expiredExpanded },
            titleColor = errorColor,
            onEditProduct = onEditProduct,
            onQuickAdd = onQuickAdd,
            onDeleteItem = onDeleteItem,
            onDeleteItems = onDeleteItems,
            onEditGroup = onEditGroup,
        )
        productSection(
            keyPrefix = "expiring_soon",
            title = expiringSoonTitle,
            products = sections.expiringSoon,
            expanded = expiringSoonExpanded,
            onToggle = { expiringSoonExpanded = !expiringSoonExpanded },
            titleColor = ExpiryColorUrgent,
            onEditProduct = onEditProduct,
            onQuickAdd = onQuickAdd,
            onDeleteItem = onDeleteItem,
            onDeleteItems = onDeleteItems,
            onEditGroup = onEditGroup,
        )
        productSection(
            keyPrefix = "no_expiry",
            title = noExpiryTitle,
            products = sections.noExpiry,
            expanded = noExpiryExpanded,
            onToggle = { noExpiryExpanded = !noExpiryExpanded },
            onEditProduct = onEditProduct,
            onQuickAdd = onQuickAdd,
            onDeleteItem = onDeleteItem,
            onDeleteItems = onDeleteItems,
            onEditGroup = onEditGroup,
        )
        productSection(
            keyPrefix = "ok",
            title = okTitle,
            products = sections.ok,
            expanded = okExpanded,
            onToggle = { okExpanded = !okExpanded },
            onEditProduct = onEditProduct,
            onQuickAdd = onQuickAdd,
            onDeleteItem = onDeleteItem,
            onDeleteItems = onDeleteItems,
            onEditGroup = onEditGroup,
        )
    }
}

private fun LazyListScope.productSection(
    keyPrefix: String,
    title: String,
    products: List<Product>,
    expanded: Boolean,
    onToggle: () -> Unit,
    titleColor: Color? = null,
    onEditProduct: (Product) -> Unit,
    onQuickAdd: (Product) -> Unit,
    onDeleteItem: (Int) -> Unit,
    onDeleteItems: (List<Int>) -> Unit,
    onEditGroup: (List<Item>) -> Unit,
) {
    if (products.isEmpty()) return

    item(key = "${keyPrefix}_header") {
        CollapsableSectionHeader(
            title = title,
            count = products.size,
            expanded = expanded,
            onToggle = onToggle,
            titleColor = titleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        items(products, key = { it.uid }) { product ->
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

private data class PartitionedProducts(
    val expired: List<Product>,
    val expiringSoon: List<Product>,
    val noExpiry: List<Product>,
    val ok: List<Product>,
)

private fun List<Product>.partitionByExpiry(today: LocalDate): PartitionedProducts {
    val (withExpiry, noExpiry) = partition { it.items.first().expirationDate != null }
    val (expired, active) = withExpiry.partition { it.items.first().expirationDate!!.isBefore(today) }
    val (expiringSoon, ok) = active.partition { product ->
        ChronoUnit.DAYS.between(today, product.items.first().expirationDate!!) <= ExpiryThresholds.WARNING_DAYS
    }
    return PartitionedProducts(
        expired = expired,
        expiringSoon = expiringSoon,
        noExpiry = noExpiry,
        ok = ok,
    )
}
