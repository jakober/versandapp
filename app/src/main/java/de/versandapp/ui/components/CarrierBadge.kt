package de.versandapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.versandapp.data.model.Carrier

/**
 * Farbiges Badge in den Markenfarben des Dienstleisters.
 *
 * Original-Logos sind Markenzeichen – sobald lizenzierte Assets vorliegen,
 * können sie in res/drawable abgelegt und hier per Image() statt des Kürzels
 * gerendert werden.
 */
@Composable
fun CarrierBadge(
    carrier: Carrier,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(carrier.brandColor)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = carrier.shortCode,
            color = Color(carrier.onBrandColor),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
