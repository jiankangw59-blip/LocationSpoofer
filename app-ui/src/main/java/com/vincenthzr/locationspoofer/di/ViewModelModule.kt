package com.vincenthzr.locationspoofer.di

import com.vincenthzr.locationspoofer.viewmodel.ManageDataViewModel
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.UpdateViewModel
import com.vincenthzr.locationspoofer.viewmodel.RemoteWifiViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { UpdateViewModel(androidContext()) }
    viewModel { ManageDataViewModel(get()) { com.vincenthzr.locationspoofer.utils.LocalObservationJournal.get(androidContext()) } }
    viewModel { RemoteWifiViewModel(get(), get(), get(), get()) }
}
