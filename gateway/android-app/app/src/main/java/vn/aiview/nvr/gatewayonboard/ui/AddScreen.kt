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
import androidx.compose.material3.HorizontalDivider
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
                SuccessPanel(
                    vm,
                    address = if (gw.identity.overlay == "nebula") gw.identity.overlayIp
                              else submitted.ztAddress.ifBlank { gw.identity.ztAddress },
                    isNebula = gw.identity.overlay == "nebula",
                )
            }
        }
    }
}

@Composable
private fun AddForm(vm: OnboardViewModel, gw: Gateway) {
    EnrollSection(vm, gw)

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))

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

/**
 * Region enrollment (Nebula): the field worker types the "mã khu vực" the admin gave them — the HQ
 * signing-service URL + a one-time token. The gateway generates a keypair and submits its public key;
 * the admin then "Cấp phép" on the NVR to sign the cert. This is the key new step; naming the gateway
 * (below) is optional/cosmetic.
 */
@Composable
private fun EnrollSection(vm: OnboardViewModel, gw: Gateway) {
    var signingUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    val alreadyEnrolled = gw.identity.enrolled || (vm.enrollResult?.requested == true)

    Text("Khu vực (Nebula)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Nhập \"mã khu vực\" admin cấp: địa chỉ máy chủ ký (signing URL) và mã token. Sau khi gửi, " +
            "admin bấm \"Cấp phép\" trên NVR là xong — không cần đụng vào gateway.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (!alreadyEnrolled) "Hiện chưa vào khu vực nào."
        else "Đã gửi yêu cầu" + gw.identity.overlayIp.takeIf { it.isNotBlank() }?.let { " (IP: $it)" }.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = signingUrl,
        onValueChange = { signingUrl = it.trim() },
        label = { Text("Signing URL (vd http://hq:9443)") },
        singleLine = true,
        isError = signingUrl.isNotEmpty() && !isValidSigningUrl(signingUrl),
        supportingText = {
            if (signingUrl.isNotEmpty() && !isValidSigningUrl(signingUrl)) Text("Phải bắt đầu bằng http:// hoặc https://")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it.trim() },
        label = { Text("Token (mã khu vực)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    vm.enrollError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
    vm.enrollResult?.let { r ->
        Spacer(Modifier.height(8.dp))
        // status is normally "pending" here: submitted, waiting for admin authorization.
        val msg = if (r.status == "signed") {
            "✓ Đã được cấp phép và cấp chứng chỉ" + r.overlayIp.takeIf { it.isNotBlank() }?.let { " (IP $it)" }.orEmpty() + "."
        } else {
            "✓ Đã gửi yêu cầu. Báo admin bấm \"Cấp phép\" trên NVR."
        }
        Text(msg, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { vm.submitEnroll(gw, signingUrl, token) },
        enabled = !vm.enrolling && isValidSigningUrl(signingUrl) && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (vm.enrolling) "Đang kết nối…" else "Kết nối khu vực")
    }
}

/** Local sanity check for the signing-service URL the admin hands out. */
private fun isValidSigningUrl(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

@Composable
private fun SuccessPanel(vm: OnboardViewModel, address: String, isNebula: Boolean) {
    val clipboard = LocalClipboardManager.current
    val blankHint = if (isNebula) "(chưa enroll khu vực)" else "(chưa join ZeroTier)"

    Text("Đã lưu thông tin ✓", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Text(
        "Báo admin bấm \"Cấp phép\" trên NVR." +
            if (isNebula) " Gateway sẽ nhận chứng chỉ và lên overlay sau đó." else " Địa chỉ ZeroTier (10 ký tự) của gateway này:",
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
                text = address.ifBlank { blankHint },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (address.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                    Text("Sao chép")
                }
            }
        }
    }
    if (address.isBlank() && !isNebula) {
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
