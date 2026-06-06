package vn.aiview.nvr.gatewayonboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import vn.aiview.nvr.gatewayonboard.OnboardViewModel
import vn.aiview.nvr.gatewayonboard.net.Gateway

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: OnboardViewModel, gw: Gateway) {
    val submitted = vm.submitted
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (submitted == null) "Thêm gateway" else "Hoàn tất") },
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
            if (submitted == null) {
                AddForm(vm, gw)
            } else {
                SuccessPanel(vm, ztAddress = submitted.ztAddress.ifBlank { gw.identity.ztAddress })
            }
        }
    }
}

@Composable
private fun AddForm(vm: OnboardViewModel, gw: Gateway) {
    var name by rememberSaveable { mutableStateOf(gw.identity.name) }
    var installer by rememberSaveable { mutableStateOf(gw.identity.installer) }
    var location by rememberSaveable { mutableStateOf(gw.identity.location) }

    Text(
        "Đặt tên cho gateway tại hiện trường. Thông tin lưu trên gateway để admin nhận diện khi duyệt.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Tên gateway") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = installer,
        onValueChange = { installer = it },
        label = { Text("Người lắp đặt") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = location,
        onValueChange = { location = it },
        label = { Text("Vị trí") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    vm.submitError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { vm.submitLabel(gw, name, installer, location) },
        enabled = !vm.saving && (name.isNotBlank() || installer.isNotBlank() || location.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (vm.saving) "Đang lưu…" else "Lưu & lấy mã duyệt")
    }
}

@Composable
private fun SuccessPanel(vm: OnboardViewModel, ztAddress: String) {
    val clipboard = LocalClipboardManager.current

    Text("Đã lưu thông tin ✓", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Text(
        "Báo admin DUYỆT thành viên này trên ztncui (địa chỉ ZeroTier 10 ký tự):",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ztAddress.ifBlank { "(chưa join ZeroTier)" },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (ztAddress.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(ztAddress)) }) {
                    Text("Sao chép")
                }
            }
        }
    }
    if (ztAddress.isBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Gateway chưa join ZeroTier — kiểm tra Internet của gateway, rồi mở lại để lấy địa chỉ.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(28.dp))
    OutlinedButton(onClick = { vm.finishToScan() }, modifier = Modifier.fillMaxWidth()) {
        Text("Xong — về danh sách")
    }
}
