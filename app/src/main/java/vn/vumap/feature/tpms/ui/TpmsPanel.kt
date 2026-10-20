package vn.vumap.feature.tpms.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.vumap.feature.tpms.DisplayState
import vn.vumap.feature.tpms.Wheel
import vn.vumap.feature.tpms.WheelDisplay

/**
 * P2b TPMS UI: nav mini-chip + full 4-wheel panel + optional camera overlay hook.
 *
 * Standalone composables; NOT wired into MapScreen/nav in this slice to keep
 * P0 navigation untouched. Host wires [TpmsMiniChip] into the nav map and
 * [TpmsPanel] into a settings/driving panel when ready, plus
 * [TpmsCamOverlay] over the Android RTSP preview (UI only, no C2M changes).
 *
 * UNKNOWN/STALE renders grey with "Mất tín hiệu"/"Chưa có dữ liệu"/"Chưa đủ dữ
 * liệu để xác nhận", never 0 bar and never green.
 */
@Composable
fun TpmsMiniChip(
    displays: Map<Wheel, WheelDisplay>,
    overall: DisplayState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val bg = colorFor(overall)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TPMS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Wheel.entries.forEach { wheel ->
            val d = displays[wheel]
            val dot = colorFor(d?.displayState ?: DisplayState.UNKNOWN_STALE)
            Text(
                text = wheel.name,
                color = dot,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun TpmsPanel(
    displays: Map<Wheel, WheelDisplay>,
    linkStateText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Áp suất lốp (TPMS)", style = MaterialTheme.typography.titleMedium)
        Text(
            linkStateText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WheelCard(displays[Wheel.FL], Modifier.weight(1f))
            WheelCard(displays[Wheel.FR], Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WheelCard(displays[Wheel.RL], Modifier.weight(1f))
            WheelCard(displays[Wheel.RR], Modifier.weight(1f))
        }
        Text(
            "Chưa có dữ liệu / mất tín hiệu / chưa đủ dữ liệu để xác nhận hiển thị màu xám, không phải 0 bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WheelCard(display: WheelDisplay?, modifier: Modifier = Modifier) {
    val state = display?.displayState ?: DisplayState.UNKNOWN_STALE
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colorFor(state).copy(alpha = 0.12f),
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                display?.wheel?.name ?: "—",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colorFor(state),
            )
            Text(
                display?.pressureText() ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                (display?.temperatureText() ?: "—") +
                    "  •  Pin " + (display?.reading?.batteryPercent?.let { "$it%" } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                display?.statusLineVi() ?: "Chưa có dữ liệu",
                style = MaterialTheme.typography.bodySmall,
                color = colorFor(state),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Optional overlay hook for the C2M RTSP preview (Android UI only).
 * Renders a compact single-line TPMS summary; never modifies camera firmware.
 */
@Composable
fun TpmsCamOverlay(
    displays: Map<Wheel, WheelDisplay>,
    overall: DisplayState,
    modifier: Modifier = Modifier,
) {
    val summary = Wheel.entries.joinToString("  ") { wheel ->
        val d = displays[wheel]
        val p = d?.pressureText() ?: "—"
        "${wheel.name}:$p"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(2.dp))
        Text(summary, color = Color.White, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(0.dp))
    }
}

private fun colorFor(state: DisplayState): Color = when (state) {
    DisplayState.NORMAL -> Color(0xFF2E7D32)
    DisplayState.WARNING -> Color(0xFFEF6C00)
    DisplayState.CRITICAL -> Color(0xFFC62828)
    // Both unknown buckets are grey: not enough evidence, never "green = safe".
    DisplayState.UNKNOWN -> Color(0xFF9E9E9E)
    DisplayState.UNKNOWN_STALE -> Color(0xFF9E9E9E)
}
