package vn.aiview.nvr.gatewayonboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.aiview.nvr.gatewayonboard.OnboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: OnboardViewModel) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("NVR Gateway") }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            val subnet = vm.localIp?.let { "${it.substringBeforeLast('.')}.0/24" }
            Text(
                text = if (subnet != null) "Mạng hiện tại: $subnet" else
                    "Chưa thấy mạng LAN — nối Wi-Fi cùng mạng với gateway.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.scan() },
                enabled = !vm.scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.scanning) "Đang quét…" else "Quét mạng")
            }
            if (vm.scanning) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            vm.scanError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            if (vm.gateways.isEmpty() && !vm.scanning) {
                Text(
                    "Chưa có gateway. Nhấn \"Quét mạng\" để tìm gateway trong mạng LAN này.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.gateways, key = { it.host }) { gw ->
                    GatewayCard(gw) { vm.open(gw) }
                }
            }
        }
    }
}
