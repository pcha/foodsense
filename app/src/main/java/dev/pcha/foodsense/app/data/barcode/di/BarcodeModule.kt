package dev.pcha.foodsense.app.data.barcode.di

import dev.pcha.foodsense.app.data.barcode.BarcodeCache
import dev.pcha.foodsense.app.data.barcode.BarcodeRepository
import dev.pcha.foodsense.app.data.barcode.BarcodeRepositoryImpl
import dev.pcha.foodsense.app.data.barcode.LocalBarcodeRegistry
import dev.pcha.foodsense.app.data.barcode.OpenFoodFactsBarcodeRepository
import dev.pcha.foodsense.app.data.barcode.RemoteBarcodeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeModule {

    @Binds
    @Singleton
    abstract fun bindBarcodeRepository(impl: BarcodeRepositoryImpl): BarcodeRepository

    @Binds
    abstract fun bindBarcodeCache(impl: LocalBarcodeRegistry): BarcodeCache

    @Binds
    abstract fun bindRemoteBarcodeSource(impl: OpenFoodFactsBarcodeRepository): RemoteBarcodeSource
}
