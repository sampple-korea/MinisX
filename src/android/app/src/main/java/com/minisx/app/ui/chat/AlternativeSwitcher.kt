package com.minisx.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.minisx.app.R

/**
 * ‹ 2/3 › under the newest answer, to flip between the answers a retry produced. The arrows at either end
 * dim instead of disappearing, so the control keeps its place.
 */
@Composable
fun AlternativeSwitcher(
    position: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val hasPrev = position > 1
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.alt_previous),
            tint = tint,
            modifier = Modifier
                .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                .padding(8.dp)
                .size(20.dp)
                .alpha(if (hasPrev) 1f else 0.35f)
                .clickable(enabled = hasPrev, role = Role.Button, onClick = onPrevious),
        )
        Text(
            text = stringResource(R.string.alt_position, position, count),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        val hasNext = position < count
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.alt_next),
            tint = tint,
            modifier = Modifier
                .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                .padding(8.dp)
                .size(20.dp)
                .alpha(if (hasNext) 1f else 0.35f)
                .clickable(enabled = hasNext, role = Role.Button, onClick = onNext),
        )
    }
}
