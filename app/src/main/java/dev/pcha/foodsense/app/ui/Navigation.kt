/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.pcha.foodsense.app.ui

import android.content.Context
import dev.pcha.foodsense.app.ui.auth.AuthViewModel
import dev.pcha.foodsense.app.ui.auth.LoginScreen
import dev.pcha.foodsense.app.ui.product.ProductScreen
import dev.pcha.foodsense.app.ui.product.ProductViewModel
import dev.pcha.foodsense.app.ui.scan.ScanScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.google.firebase.auth.FirebaseAuth

@Composable
fun MainNavigation() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val onboardingDone = remember { prefs.getBoolean("onboarding_done", false) }
    val isLoggedIn = remember { FirebaseAuth.getInstance().currentUser != null }

    val productViewModel: ProductViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    val backStack = rememberNavBackStack(Main)
    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (!onboardingDone && !isLoggedIn) backStack.add(Login)
    }

    val completeOnboarding = {
        prefs.edit().putBoolean("onboarding_done", true).apply()
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                ProductScreen(
                    viewModel = productViewModel,
                    currentUser = currentUser,
                    onScan = { backStack.add(Scan) },
                    onNavigateToLogin = { backStack.add(Login) },
                    onSignOut = authViewModel::signOut,
                    modifier = Modifier.safeDrawingPadding().padding(16.dp),
                )
            }
            entry<Scan> {
                ScanScreen(
                    onResult = { barcode, name, quantity, unit, dateStr ->
                        productViewModel.openAddSheetWithScanResult(barcode, name, quantity, unit, dateStr)
                        backStack.removeLastOrNull()
                    },
                    onRejected = { barcode ->
                        productViewModel.openAddSheetForManualEntry(barcode)
                        backStack.removeLastOrNull()
                    },
                    onCancel = { backStack.removeLastOrNull() },
                )
            }
            entry<Login> {
                LoginScreen(
                    onLoginSuccess = {
                        completeOnboarding()
                        backStack.removeLastOrNull()
                    },
                    onContinueWithoutAccount = {
                        completeOnboarding()
                        backStack.removeLastOrNull()
                    },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        }
    )
}
