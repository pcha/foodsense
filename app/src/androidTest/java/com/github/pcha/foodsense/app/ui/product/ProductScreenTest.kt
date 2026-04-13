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

    @Test
    fun firstItem_exists() {
        composeTestRule.onNodeWithText(fakeProducts.first().name).assertExists()
    }
}
