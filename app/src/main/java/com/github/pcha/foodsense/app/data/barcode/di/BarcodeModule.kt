package com.github.pcha.foodsense.app.data.barcode.di

import com.github.pcha.foodsense.app.data.barcode.BarcodeRepository
import com.github.pcha.foodsense.app.data.barcode.BarcodeRepositoryImpl
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
}
