package vn.aiview.nvr.gatewayonboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.aiview.nvr.gatewayonboard.OnboardViewModel
import vn.aiview.nvr.gatewayonboard.Screen

@Composable
fun OnboardApp(vm: OnboardViewModel = viewModel()) {
    when (val s = vm.screen) {
        Screen.Scan -> ScanScreen(vm)
        is Screen.Detail -> {
            BackHandler { vm.back() }
            DetailScreen(vm, s.gateway)
        }
        is Screen.Add -> {
            BackHandler { vm.back() }
            AddScreen(vm, s.gateway)
        }
    }
}
