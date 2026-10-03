package io.boffin.terminal.ui.screens.terminal

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.child
import com.rk.resources.strings
import com.rk.settings.Settings
import io.boffin.terminal.ui.activities.terminal.MainActivity
import io.boffin.terminal.ui.activities.terminal.MainViewModel
import io.boffin.terminal.ui.components.SetStatusBarTextColor
import io.boffin.terminal.ui.screens.downloader.AlpineInstaller
import io.boffin.terminal.ui.screens.downloader.NetHunterInstaller
import io.boffin.terminal.ui.screens.downloader.RootfsInstallFlow
import io.boffin.terminal.ui.screens.downloader.RootfsInstaller
import io.boffin.terminal.ui.screens.downloader.distroLabel
import io.boffin.terminal.ui.screens.downloader.downloadDirectRootfs
import io.boffin.terminal.ui.screens.settings.SettingsCard
import io.boffin.terminal.ui.screens.settings.WorkingMode
import io.boffin.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    mainActivity: MainActivity,
    navController: NavController,
    mainViewModel: MainViewModel = viewModel(mainActivity),
    terminalViewModel: TerminalViewModel = viewModel(mainActivity)
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDarkActive = if (mainViewModel.followSystemTheme) systemDark else mainViewModel.isDarkMode
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val configuration = LocalConfiguration.current
    val drawerWidth = (configuration.screenWidthDp * 0.84).dp
    var showAddDialog by remember { mutableStateOf(false) }
    var installingMode by remember { mutableStateOf<Int?>(null) }
    var installLabel by remember { mutableStateOf("") }
    var installAskExecMode by remember { mutableStateOf(false) }
    var boffinInstaller by remember { mutableStateOf<RootfsInstaller?>(null) }
    var showBoffinUrlDialog by remember { mutableStateOf(false) }

    val sessionBinder = mainViewModel.sessionBinder

    fun proceedToCreateSession(mode: Int) {
        val binder = sessionBinder ?: return
        val sessionId = generateUniqueSessionId(binder.getService().sessionList.keys.toList())
        val terminal = terminalViewModel.terminalView ?: return
        val client = TerminalBackEnd(terminal, mainActivity)
        binder.createSession(sessionId, client, mode)
        terminalViewModel.changeSession(context, binder, sessionId)
        showAddDialog = false
    }

    /**
     * Puts [mode] through its on-demand install flow (download, then for Alpine the chroot-vs-proot
     * question) and only creates the session once the rootfs is actually usable. Distros whose
     * rootfs is already present skip straight to the session, so existing installs are unaffected.
     */
    fun startInstallAndCreateSession(mode: Int, label: String) {
        val alreadyInstalled = Rootfs.isModeInstalled(context, mode)
        if (alreadyInstalled) {
            proceedToCreateSession(mode)
            return
        }
        showAddDialog = false
        installLabel = label
        installAskExecMode = mode == WorkingMode.ALPINE
        boffinInstaller = null
        installingMode = mode
    }

    fun downloaderFor(mode: Int): RootfsInstaller = when (mode) {
        WorkingMode.ALPINE -> { onProgress -> AlpineInstaller.downloadIfNeeded(context, onProgress) }
        WorkingMode.NETHUNTER -> { onProgress -> NetHunterInstaller.downloadIfNeeded(context, onProgress) }
        WorkingMode.BOFFIN -> {
            // Either the URL the user just typed in, or whatever is already saved.
            val installer = boffinInstaller
                ?: { onProgress -> downloadDirectRootfs(context, Settings.boffin_url, "boffin.tar.gz", 120_000, 120_000, onProgress) }
            { onProgress -> installer(onProgress) }
        }
        else -> { _ -> }
    }

    LaunchedEffect(isDarkActive) {
        withContext(Dispatchers.IO) {
            if (context.filesDir.child("background").exists().not()) {
                TerminalUtils.darkText.value = !isDarkActive
                TerminalUtils.hasCustomBackground.value = false
            } else {
                TerminalUtils.hasCustomBackground.value = true
                if (terminalViewModel.bitmap == null) {
                    BitmapFactory.decodeFile(context.filesDir.child("background").absolutePath)?.asImageBitmap()?.let {
                        terminalViewModel.bitmap = it
                    }
                }
            }
        }
    }
    
    // Update virtual keys when they are available
    terminalViewModel.virtualKeysView?.apply {
        virtualKeysViewClient = terminalViewModel.terminalView?.mTermSession?.let { VirtualKeysListener(it) }
        buttonTextColor = TerminalUtils.getViewColor()
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val isDarkIcons = if (drawerState.isClosed) TerminalUtils.darkText.value else !isDarkActive
    SetStatusBarTextColor(isDarkIcons = isDarkIcons)

    if (showAddDialog && sessionBinder != null) {
        AddSessionDialog(
            onDismiss = { showAddDialog = false },
            onCreateSession = { mode ->
                when (mode) {
                    WorkingMode.BOFFIN -> {
                        if (Rootfs.isBoffinRootfsInstalled(context)) {
                            proceedToCreateSession(mode)
                        } else {
                            showAddDialog = false
                            showBoffinUrlDialog = true
                        }
                    }
                    WorkingMode.ALPINE, WorkingMode.NETHUNTER ->
                        startInstallAndCreateSession(mode, distroLabel(mode))
                    else -> proceedToCreateSession(mode)
                }
            },
            onCreateCustomSession = { custom ->
                val terminal = terminalViewModel.terminalView ?: return@AddSessionDialog
                val client = TerminalBackEnd(terminal, mainActivity)
                val pendingCommand = MkSession.buildCustomPendingCommand(context, custom)
                sessionBinder.createSession(custom.name, client, WorkingMode.ALPINE, pendingCommand)
                terminalViewModel.changeSession(context, sessionBinder, custom.name)
                showAddDialog = false
            }
        )
    }

    installingMode?.let { mode ->
        RootfsInstallFlow(
            label = installLabel,
            install = downloaderFor(mode),
            askExecMode = installAskExecMode,
            onReady = {
                installingMode = null
                Rootfs.checkInstallation(context)
                proceedToCreateSession(mode)
            },
            onDismiss = { installingMode = null }
        )
    }

    if (showBoffinUrlDialog) {
        BoffinUrlDialog(
            initialUrl = Settings.boffin_url,
            onDismiss = { showBoffinUrlDialog = false },
            onConfirm = { url ->
                showBoffinUrlDialog = false
                Settings.boffin_url = url
                installLabel = distroLabel(WorkingMode.BOFFIN)
                installAskExecMode = false
                boffinInstaller = { onProgress ->
                    downloadDirectRootfs(context, url, "boffin.tar.gz", 120_000, 120_000, onProgress)
                }
                installingMode = WorkingMode.BOFFIN
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || !terminalViewModel.showToolbar,
        drawerContent = {
            TerminalDrawer(
                drawerWidth = drawerWidth,
                sessionBinder = sessionBinder,
                navController = navController,
                onAddSession = { showAddDialog = true },
                onSessionSelected = { id ->
                    sessionBinder?.let { terminalViewModel.changeSession(context, it, id) }
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BackgroundImage(terminalViewModel)
            
            Column {
                if (terminalViewModel.showToolbar) {
                    TerminalTopBar(
                        sessionBinder = sessionBinder,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onAddClick = { showAddDialog = true },
                        color = TerminalUtils.getComposeColor()
                    )
                }

                val density = LocalDensity.current
                val topPadding = if (terminalViewModel.showToolbar) 0.dp else {
                    with(density) { TopAppBarDefaults.windowInsets.getTop(this).toDp() }
                }

                if (sessionBinder != null) {
                    TerminalViewLayout(
                        viewModel = terminalViewModel,
                        mainActivity = mainActivity,
                        sessionBinder = sessionBinder,
                        modifier = Modifier
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(top = topPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundImage(viewModel: TerminalViewModel) {
    viewModel.bitmap?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(viewModel.wallAlpha)
                .let {
                    if (viewModel.backgroundBlur > 0f) {
                        it.blur(viewModel.backgroundBlur.dp)
                    } else {
                        it
                    }
                }
                .zIndex(-1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSessionDialog(
    onDismiss: () -> Unit,
    onCreateSession: (Int) -> Unit,
    onCreateCustomSession: (CustomSession) -> Unit
) {
    val isArm64 = "arm64-v8a" in Build.SUPPORTED_ABIS
    val isAlpineInstalled = Rootfs.isInstalled.value
    val customSessions = remember { CustomSessions.getAll() }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        PreferenceGroup {
            SettingsCard(
                title = { Text("Alpine") },
                description = {
                    Text(
                        stringResource(strings.alpine_desc) +
                            if (isAlpineInstalled) "" else "\n" + stringResource(strings.rootfs_not_installed_desc)
                    )
                },
                onClick = { onCreateSession(WorkingMode.ALPINE) }
            )
            SettingsCard(
                title = { Text("Android") },
                description = { Text(stringResource(strings.android_desc)) },
                onClick = { onCreateSession(WorkingMode.ANDROID) }
            )
            if (isArm64) {
                SettingsCard(
                    title = { Text("NetHunter") },
                    description = { Text("Kali NetHunter (full, arm64 only)") },
                    onClick = { onCreateSession(WorkingMode.NETHUNTER) }
                )
            }
            SettingsCard(
                title = { Text("Boffin") },
                description = { Text("Debian 12 XFCE4 desktop (enter rootfs URL)") },
                onClick = { onCreateSession(WorkingMode.BOFFIN) }
            )
            customSessions.forEach { session ->
                SettingsCard(
                    title = { Text(session.name) },
                    description = { Text(session.shellPath) },
                    onClick = { onCreateCustomSession(session) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoffinUrlDialog(initialUrl: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Boffin rootfs URL") },
        text = {
            Column {
                Text("Enter a direct download link for the Boffin rootfs archive (.tar.gz).")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://example.com/debian12-full-xfce4-rootfs.tar.gz") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(url.trim()) },
                enabled = url.isNotBlank()
            ) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun generateUniqueSessionId(existingIds: List<String>): String {
    var index = 1
    var newId: String
    do {
        newId = "main$index"
        index++
    } while (newId in existingIds)
    return newId
}
