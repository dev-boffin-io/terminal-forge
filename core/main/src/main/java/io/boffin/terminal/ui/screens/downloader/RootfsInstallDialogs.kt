package io.boffin.terminal.ui.screens.downloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rk.resources.strings
import io.boffin.terminal.ui.screens.terminal.ExecMode
import io.boffin.terminal.ui.screens.terminal.Rootfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * True if `su` is present and actually grants uid=0. Never call this from the main thread: `su -c id`
 * can block waiting for a root manager's approval prompt.
 */
fun hasRootAccess(): Boolean {
    val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
    if (paths.none { File(it).exists() }) return false
    return try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exited = process.waitFor()
        exited == 0 && output.contains("uid=0")
    } catch (e: Exception) {
        false
    }
}

/**
 * Downloads a distro rootfs showing a progress indicator, and on failure an error with Retry/Close.
 *
 * Shared by the Kali, NetHunter and Boffin install paths so all three behave identically and a
 * failed download can be retried in place, leaving the caller free to stay on the Android shell.
 *
 * @param install the download work; re-invoked on retry
 * @param onSuccess called once the archive is on disk
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootfsDownloadDialog(
    label: String,
    install: suspend (onProgress: (Int) -> Unit) -> Unit,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startDownload() {
        error = null
        progress = 0
        scope.launch {
            try {
                install { pct -> progress = pct }
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    LaunchedEffect(Unit) { startDownload() }

    BasicAlertDialog(onDismissRequest = { if (error != null) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (error != null) {
                    Text(
                        text = stringResource(strings.rootfs_download_failed, label, error!!),
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        TextButton(onClick = onDismiss) { Text(stringResource(strings.close)) }
                        TextButton(onClick = { startDownload() }) { Text(stringResource(strings.retry)) }
                    }
                } else {
                    Text(stringResource(strings.rootfs_downloading, label))
                    Spacer(modifier = Modifier.height(16.dp))
                    if (progress > 0) {
                        CircularProgressIndicator(progress = { progress / 100f })
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("$progress%")
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * Asks whether a rooted device should run its distro via a real chroot or via proot, but only
 * until an answer is chosen - the choice is persisted in Settings.exec_mode, so later sessions skip
 * the question entirely. Unrooted devices are never asked (chroot is not available) and silently
 * get proot.
 *
 * Lives here rather than at app start because it only matters once a distro rootfs actually exists
 * to be chrooted into.
 */
@Composable
fun ExecModeChoiceDialog(onChosen: () -> Unit) {
    // null while `su -c id` is still being probed
    val rooted by produceState<Boolean?>(initialValue = null) {
        value = if (Rootfs.execMode.value != null) false else withContext(Dispatchers.IO) { hasRootAccess() }
    }

    LaunchedEffect(rooted) {
        when {
            Rootfs.execMode.value != null -> onChosen()
            rooted == false -> {
                Rootfs.setExecMode(ExecMode.PROOT)
                onChosen()
            }
            // rooted == true and still unanswered: wait for the dialog below
        }
    }

    if (Rootfs.execMode.value == null && rooted == true) {
        AlertDialog(
            onDismissRequest = { /* must choose one */ },
            title = { Text(stringResource(strings.root_detected_title)) },
            text = { Text(stringResource(strings.root_detected_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    Rootfs.setExecMode(ExecMode.CHROOT)
                    onChosen()
                }) { Text(stringResource(strings.use_chroot)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Rootfs.setExecMode(ExecMode.PROOT)
                    onChosen()
                }) { Text(stringResource(strings.use_proot)) }
            }
        )
    }
}

/**
 * Full on-demand install flow for a distro: download if the archive isn't there yet, then (for Kali,
 * and only the first time) settle chroot-vs-proot, then hand control back via [onReady].
 *
 * The exec-mode step is part of the flow on purpose - the question "root or proot?" is meaningless
 * until there is a rootfs to chroot into, and asking it before the user has ever opted into Kali
 * would nag every Android-shell user on a rooted phone.
 *
 * @param askExecMode pass true for Kali, the only distro that supports chroot
 */
@Composable
fun RootfsInstallFlow(
    label: String,
    install: suspend (onProgress: (Int) -> Unit) -> Unit,
    askExecMode: Boolean,
    onReady: () -> Unit,
    onDismiss: () -> Unit
) {
    var downloaded by remember { mutableStateOf(false) }

    if (!downloaded) {
        RootfsDownloadDialog(
            label = label,
            install = install,
            onSuccess = { downloaded = true },
            onDismiss = onDismiss
        )
    } else if (askExecMode) {
        ExecModeChoiceDialog(onChosen = onReady)
    } else {
        LaunchedEffect(Unit) { onReady() }
    }
}
