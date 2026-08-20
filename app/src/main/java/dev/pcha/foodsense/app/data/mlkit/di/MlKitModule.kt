package dev.pcha.foodsense.app.data.mlkit.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pcha.foodsense.app.data.mlkit.BarcodeImageScanner
import dev.pcha.foodsense.app.data.mlkit.MlKitBarcodeScanner
import dev.pcha.foodsense.app.data.mlkit.MlKitTextRecognizer
import dev.pcha.foodsense.app.data.mlkit.TextRecognizer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MlKitModule {

    @Binds
    @Singleton
    abstract fun bindTextRecognizer(impl: MlKitTextRecognizer): TextRecognizer

    @Binds
    @Singleton
    abstract fun bindBarcodeImageScanner(impl: MlKitBarcodeScanner): BarcodeImageScanner
}
