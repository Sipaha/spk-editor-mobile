package ru.sipaha.sawe.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.sipaha.sawe.app.ui.nav.AppNav
import ru.sipaha.sawe.app.vm.MainViewModel

@Composable
fun App(vm: MainViewModel = viewModel(), initialRoute: String? = null) {
    AppNav(viewModel = vm, initialRoute = initialRoute)
}
