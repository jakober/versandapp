package de.versandapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.versandapp.data.model.ParcelStatus

private fun statusColor(status: ParcelStatus): Color = when (status) {
    ParcelStatus.UNKNOWN -> Color(0xFF9E9E9E)
    ParcelStatus.REGISTERED -> Color(0xFF757575)
    ParcelStatus.IN_TRANSIT -> Color(0xFF1976D2)
    ParcelStatus.OUT_FOR_DELIVERY -> Color(0xFFF57C00)
    ParcelStatus.DELIVERED -> Color(0xFF388E3C)
    ParcelStatus.FAILED -> Color(0xFFD32F2F)
}

@Composable
fun StatusChip(status: ParcelStatus, modifier: Modifier = Modifier) {
    Text(
        text = status.displayName,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(statusColor(status))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
