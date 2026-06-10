package vn.aiview.nvr.gatewayonboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vn.aiview.nvr.gatewayonboard.net.Gateway

private val ChipGreen = Color(0xFF1B873F)
private val ChipGrey = Color(0xFF6B6B6B)

@Composable
fun StatusChip(label: String, ok: Boolean) {
    Surface(
        color = if (ok) ChipGreen else ChipGrey,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(140.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GatewayCard(gw: Gateway, onClick: () -> Unit) {
    val id = gw.identity
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(id.name.ifBlank { gw.host }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${gw.host}:${id.agentPort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    label = when {
                        id.overlay == "nebula" && id.signed -> "Nebula ✓"
                        id.overlay == "nebula" && id.enrolled -> "Chờ cấp phép"
                        id.overlay == "nebula" -> "Chưa enroll"
                        id.ztOnline -> "ZT online"
                        id.ztJoined -> "ZT đã join"
                        else -> "Chưa nối"
                    },
                    ok = if (id.overlay == "nebula") id.signed else id.ztOnline,
                )
                StatusChip("${id.cameraCount} camera", id.cameraCount > 0)
            }
        }
    }
}
