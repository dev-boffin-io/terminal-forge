package io.boffin.terminal.ui.screens.terminal

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.settings.Settings
import io.boffin.terminal.ui.screens.settings.WorkingMode
import java.io.File

enum class ExecMode(val value: Int) {
    CHROOT(0),
    PROOT(1);

    companion object {
        fun fromInt(v: Int): ExecMode? = entries.firstOrNull { it.value == v }
    }
}

object Rootfs {
    /** True once the Kali rootfs (WorkingMode.ALPINE) is present. Init'ed by checkInstallation(). */
    var isInstalled = mutableStateOf(false)
    var isNetHunterInstalled = mutableStateOf(false)
    var isBoffinInstalled = mutableStateOf(false)
    var execMode = mutableStateOf(ExecMode.fromInt(Settings.exec_mode))

    fun setExecMode(mode: ExecMode) {
        execMode.value = mode
        Settings.exec_mode = mode.value
    }

    fun checkInstallation(context: Context) {
        isInstalled.value = isRootfsInstalled(context)
        isNetHunterInstalled.value = isNetHunterRootfsInstalled(context)
        isBoffinInstalled.value = isBoffinRootfsInstalled(context)
    }

    fun isRootfsInstalled(context: Context): Boolean {
        val alpineDir = context.localDir().child("alpine")
        val isExtracted = alpineDir.exists() && (alpineDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("alpine.tar.gz").exists()
        return isExtracted || isArchivePresent
    }

    fun isNetHunterRootfsInstalled(context: Context): Boolean {
        val netHunterDir = context.localDir().child("nethunter")
        val isExtracted = netHunterDir.exists() && (netHunterDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("nethunter.tar.xz").exists()
        return isExtracted || isArchivePresent
    }

    fun isBoffinRootfsInstalled(context: Context): Boolean {
        val boffinDir = context.localDir().child("boffin")
        val isExtracted = boffinDir.exists() && (boffinDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("boffin.tar.gz").exists()
        return isExtracted || isArchivePresent
    }

    fun isModeInstalled(context: Context, mode: Int): Boolean = when (mode) {
        WorkingMode.ALPINE -> isRootfsInstalled(context)
        WorkingMode.NETHUNTER -> isNetHunterRootfsInstalled(context)
        WorkingMode.BOFFIN -> isBoffinRootfsInstalled(context)
        else -> true
    }

    /**
     * Single choke point for "which mode can actually be launched right now". Kali/NetHunter/Boffin
     * all start via init-host, which unconditionally runs `tar -xf` on the distro archive - with no
     * archive present that just dies at the shell prompt. Since the distro rootfses are now
     * on-demand installs, a saved default (or a pending script target) can legitimately point at a
     * distro that was never installed, so every place that builds a session from a user-supplied
     * mode runs it through here and lands on the always-available Android shell instead.
     */
    fun resolveUsableMode(context: Context, mode: Int): Int =
        if (isModeInstalled(context, mode)) mode else WorkingMode.ANDROID
}
