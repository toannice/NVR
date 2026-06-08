package vn.aiview.nvr.gatewayonboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.aiview.nvr.gatewayonboard.OnboardViewModel
import vn.aiview.nvr.gatewayonboard.net.Gateway

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: OnboardViewModel, gw: Gateway) {
    val id = gw.identity
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(id.name.ifBlank { "Gateway" }) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoRow("Địa chỉ", "${gw.host}:${id.agentPort}")
            InfoRow("Gateway ID", id.gatewayId.ifBlank { "—" })
            InfoRow("Người lắp", id.installer.ifBlank { "—" })
            InfoRow("Vị trí", id.location.ifBlank { "—" })
            InfoRow("Khu vực (network)", id.networkId.ifBlank { "(chưa vào khu vực)" })
            InfoRow("ZeroTier addr", id.ztAddress.ifBlank { "—" })
            InfoRow("ZeroTier IP", id.zerotierIp.ifBlank { "—" })
            InfoRow("Camera đang proxy", id.cameraCount.toString())

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(if (id.alive) "Hoạt động" else "Offline", id.alive)
                StatusChip(if (id.ztJoined) "Đã join ZT" else "Chưa join ZT", id.ztJoined)
                StatusChip(if (id.ztOnline) "ZT online" else "ZT offline", id.ztOnline)
            }

            Spacer(Modifier.height(28.dp))
            Button(onClick = { vm.startAdd(gw) }, modifier = Modifier.fillMaxWidth()) {
                Text("Thêm gateway này")
            }
        }
    }
}
