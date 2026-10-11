package dev.pcha.foodsense.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pcha.foodsense.app.R

@Composable
internal fun ProductFabActions(
    onScan: () -> Unit,
    onOpenAddSheet: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        SmallFloatingActionButton(onClick = onScan) {
            Icon(painterResource(R.drawable.ic_barcode), contentDescription = stringResource(R.string.cd_scan_product))
        }
        FloatingActionButton(onClick = onOpenAddSheet) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_product))
        }
    }
}
