package com.github.pcha.foodsense.app.ui.product

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.github.pcha.foodsense.app.data.di.fakeProducts
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ProductScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        composeTestRule.setContent {
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

    @Test
    fun firstItem_exists() {
        composeTestRule.onNodeWithText(fakeProducts.first().name).assertExists()
    }
}
