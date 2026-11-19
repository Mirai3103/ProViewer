package com.proviewer.app.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import uniffi.proviewer_core.engineVersion

/**
 * The Home screen: an "Open CBZ/ZIP" button (SAF `OpenDocument`), an optional Benchmark entry,
 * an inline error line, and a copy-progress bar with a cancel button. The engine version stays in
 * the footer with the `engine-version` tag (the Phase 0 macrobenchmark looks for `ProViewer core `).
 */
@Composable
fun HomeScreen(
    onOpen: (Uri) -> Unit,
    error: String?,
    copyProgress: Float?,
    onCancelCopy: () -> Unit,
    benchmarkAvailable: Boolean,
    onBenchmark: () -> Unit,
) {
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onOpen)
    }
    val mimeTypes = arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(onClick = { openDocument.launch(mimeTypes) }, modifier = Modifier.testTag("home-open")) {
            Text("Open CBZ/ZIP")
        }

        if (benchmarkAvailable) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBenchmark, modifier = Modifier.testTag("home-benchmark")) {
                Text("Benchmark")
            }
        }

        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                modifier = Modifier.testTag("home-error"),
                color = MaterialTheme.colorScheme.error,
            )
        }

        copyProgress?.let { progress ->
            Spacer(Modifier.height(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(0.7f).testTag("home-copy-progress"),
                )
                TextButton(onClick = onCancelCopy) { Text("Cancel") }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "ProViewer core ${engineVersion()}",
            modifier = Modifier.testTag("engine-version"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
