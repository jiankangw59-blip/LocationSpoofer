package com.vincenthzr.locationspoofer.ui.screen.tabs.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.model.LocationLookupFeedback
import com.vincenthzr.locationspoofer.data.model.LocationLookupSource
import com.vincenthzr.locationspoofer.ui.R

@Composable
internal fun LocationLookupCard(
    feedback: LocationLookupFeedback,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.widthIn(max = 320.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            Modifier.padding(start = 12.dp, top = 4.dp, end = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.lookup_title), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.lookup_dismiss))
                }
            }
            for (source in LocationLookupSource.entries) {
                val line = feedback.lines[source] ?: continue
                val text = if (line.arguments.isEmpty()) stringResource(line.text)
                    else stringResource(line.text, *line.arguments.toTypedArray())
                Text(
                    if (line.fromPreview) stringResource(R.string.lookup_previous_preview_result, text) else text,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
