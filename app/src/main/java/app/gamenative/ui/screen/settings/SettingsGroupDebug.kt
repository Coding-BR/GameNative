package app.gamenative.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import app.gamenative.CrashHandler
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.CrashLogDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsDebug
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import app.gamenative.PrefManager
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.winlator.PrefManager as WinlatorPrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.gamenative.ui.component.dialog.WineDebugChannelsDialog

@Suppress("UnnecessaryOptInAnnotation") // ExperimentalFoundationApi
@OptIn(ExperimentalCoilApi::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsGroupDebug() {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    if (!isPreview) {
        PrefManager.init(context)
        WinlatorPrefManager.init(context)
    }

    // Load Wine debug channels and prepare selection state
    var allWineChannels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showChannelsDialog by remember { mutableStateOf(false) }
    var selectedWineChannels by remember { mutableStateOf(
        if (isPreview) emptyList() else PrefManager.wineDebugChannels.split(",")
    ) }
    LaunchedEffect(Unit) {
        // Read the list of channels from assets
        val json = context.assets.open("wine_debug_channels.json").bufferedReader().use { it.readText() }
        allWineChannels = Json.decodeFromString(json)
    }

    // Dialog for selecting channels
    WineDebugChannelsDialog(
        openDialog = showChannelsDialog,
        allChannels = allWineChannels,
        currentSelection = selectedWineChannels,
        onSave = { newSelection ->
            selectedWineChannels = newSelection
            if (!isPreview) {
                PrefManager.wineDebugChannels = newSelection.joinToString(",")
            }
            showChannelsDialog = false
        },
        onDismiss = { showChannelsDialog = false }
    )

    /* Diagnostics stuff */
    var showDiagnosticsDialog by rememberSaveable { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf("") }

    val saveDiagnostics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { resultUri ->
        try {
            resultUri?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(diagnosticText.toByteArray())
                }
                SnackbarManager.show("Diagnostics saved successfully")
            }
        } catch (e: Exception) {
            SnackbarManager.show("Failed to save diagnostics")
        }
    }

    if (showDiagnosticsDialog) {
        LaunchedEffect(Unit) {
            diagnosticText = generateDiagnosticReport(context)
        }
        CrashLogDialog(
            visible = true,
            fileName = "gamenative_diagnostics.txt",
            fileText = if (diagnosticText.isEmpty()) "Loading..." else diagnosticText,
            onSave = { saveDiagnostics.launch("gamenative_diagnostics.txt") },
            onDismissRequest = { showDiagnosticsDialog = false },
        )
    }

    /* Crash Log stuff */
    var showLogcatDialog by rememberSaveable { mutableStateOf(false) }
    // states for debug toggles
    var enableWineDebugPref by rememberSaveable {
        mutableStateOf(if (isPreview) false else PrefManager.enableWineDebug)
    }
    var enableBox86Logs by rememberSaveable { mutableStateOf(
        if (isPreview) false else WinlatorPrefManager.getBoolean("enable_box86_64_logs", false)
    ) }
    var latestCrashFile: File? by rememberSaveable { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        val crashDir = File(context.getExternalFilesDir(null), "crash_logs")
        latestCrashFile = crashDir.listFiles()
            ?.filter { it.name.startsWith("pluvia_crash_") }
            ?.maxByOrNull { it.lastModified() }
    }

    /* Save crash log */
    val saveResultContract = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { resultUri ->
        try {
            resultUri?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    latestCrashFile?.inputStream()?.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } catch (e: Exception) {
            SnackbarManager.show("Failed to save logcat to destination")
        }
    }

    /* Save log cat */
    val saveLogCat = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { resultUri ->
        try {
            resultUri?.let {
                val logs = CrashHandler.getAppLogs(1000)
                context.contentResolver.openOutputStream(resultUri)?.use { outputStream ->
                    outputStream.write(logs.toByteArray())
                }
            }
        } catch (e: Exception) {
            SnackbarManager.show(context.getString(R.string.toast_failed_log_save))
        }
    }

    if (showLogcatDialog && latestCrashFile != null) {
        val crashText by produceState("Loading...", latestCrashFile) {
            value = withContext(Dispatchers.IO) { readTail(latestCrashFile) }
        }
        CrashLogDialog(
            visible = true,
            fileName = latestCrashFile?.name ?: "No Filename",
            fileText = crashText,
            onSave = { latestCrashFile?.let { file -> saveResultContract.launch(file.name) } },
            onDismissRequest = { showLogcatDialog = false },
        )
    }

    /* Wine Debug Log export setup */
    var showWineLogDialog by rememberSaveable { mutableStateOf(false) }
    var latestWineLogFile: File? by rememberSaveable { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        val wineLogDir = File(context.getExternalFilesDir(null), "wine_logs")
        wineLogDir.mkdirs()
        val wineLogFile = File(wineLogDir, "wine_debug.log")
        latestWineLogFile = if (wineLogFile.exists()) wineLogFile else null
    }
    val saveWineLogContract = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { resultUri ->
        try {
            resultUri?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    latestWineLogFile?.inputStream()?.use { input -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            SnackbarManager.show("Failed to save Wine log to destination")
        }
    }

    if (showWineLogDialog && latestWineLogFile != null) {
        val wineText by produceState("Loading...", latestWineLogFile) {
            value = withContext(Dispatchers.IO) { readTail(latestWineLogFile) }
        }
        CrashLogDialog(
            visible = true,
            fileName = latestWineLogFile?.name ?: "wine_debug.log",
            fileText = wineText,
            onSave = { latestWineLogFile?.let { file -> saveWineLogContract.launch(file.name) } },
            onDismissRequest = { showWineLogDialog = false },
        )
    }

    SettingsGroup() {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_save_logcat_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_save_logcat_subtitle)) },
            onClick = { saveLogCat.launch("app_logs_${CrashHandler.timestamp}.txt") },
        )
        // Link to open channel selector
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_debug_wine_channels_title)) },
            subtitle = {
                Text(
                    text = if (selectedWineChannels.isNotEmpty() && selectedWineChannels.any { it.isNotBlank() })
                        selectedWineChannels.filter { it.isNotBlank() }.joinToString(",")
                    else
                        stringResource(R.string.settings_debug_no_channels_selected)
                )
            },
            onClick = { showChannelsDialog = true },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = enableWineDebugPref,
            title = { Text(text = stringResource(R.string.settings_debug_wine_logs_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_debug_wine_logs_subtitle)) },
            onCheckedChange = {
                enableWineDebugPref = it
                if (!isPreview) {
                    PrefManager.enableWineDebug = it
                }
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = enableBox86Logs,
            title = { Text(text = stringResource(R.string.settings_debug_box_logs_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_debug_box_logs_subtitle)) },
            onCheckedChange = {
                enableBox86Logs = it
                if (!isPreview) {
                    WinlatorPrefManager.putBoolean("enable_box86_64_logs", it)
                }
            },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = "Diagnostics & Root Boost") },
            subtitle = { Text(text = "View Wine locale, active CPU sets, affinity masks and process metrics") },
            onClick = { showDiagnosticsDialog = true },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_debug_view_crash_title)) },
            subtitle = {
                Text(
                    text = if (latestCrashFile != null)
                        stringResource(R.string.settings_debug_view_crash_subtitle)
                    else
                        stringResource(R.string.settings_debug_no_crash_logs)
                )
            },
            enabled = latestCrashFile != null,
            onClick = { showLogcatDialog = true },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_debug_view_log_title)) },
            subtitle = {
                Text(
                    text = if (latestWineLogFile != null)
                        stringResource(R.string.settings_debug_view_log_subtitle)
                    else
                        stringResource(R.string.settings_debug_no_wine_logs)
                )
            },
            enabled = latestWineLogFile != null,
            onClick = { showWineLogDialog = true },
        )

        SettingsMenuLink(
            modifier = Modifier.combinedClickable(
                onLongClick = {
                    SteamService.logOut()
                    (context as ComponentActivity).finishAffinity()
                },
                onClick = {
                    SnackbarManager.show("Long click to activate")
                },
            ),
            colors = settingsTileColorsDebug(),
            title = { Text(text = stringResource(R.string.settings_debug_clear_prefs_title)) },
            subtitle = { Text(stringResource(R.string.settings_debug_clear_prefs_subtitle)) },
            onClick = {},
        )

        SettingsMenuLink(
            modifier = Modifier.combinedClickable(
                onLongClick = {
                    SteamService.stop()
                    SteamService.clearDatabase()
                    (context as ComponentActivity).finishAffinity()
                },
                onClick = {
                    SnackbarManager.show("Long click to activate")
                },
            ),
            colors = settingsTileColorsDebug(),
            title = { Text(text = stringResource(R.string.settings_debug_clear_db_title)) },
            subtitle = { Text(stringResource(R.string.settings_debug_clear_db_subtitle)) },
            onClick = {},
        )

        SettingsMenuLink(
            modifier = Modifier.combinedClickable(
                onLongClick = {
                    context.imageLoader.diskCache?.clear()
                    context.imageLoader.memoryCache?.clear()
                },
                onClick = {
                    SnackbarManager.show("Long click to activate")
                },
            ),
            colors = settingsTileColorsDebug(),
            title = { Text(text = stringResource(R.string.settings_debug_clear_cache_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_debug_clear_cache_subtitle)) },
            onClick = {},
        )
    }
}

// readTail allocates ByteArray of this size, so must fit in Int
private const val MAX_LOG_DISPLAY_BYTES = 256 * 1024L // 256 KB

private fun readTail(file: File?): String {
    check(MAX_LOG_DISPLAY_BYTES <= Int.MAX_VALUE) { "MAX_LOG_DISPLAY_BYTES exceeds Int.MAX_VALUE" }
    if (file == null || !file.exists()) return "File not found: ${file?.name ?: "null"}"
    return try {
        val len = file.length()
        if (len <= MAX_LOG_DISPLAY_BYTES) {
            file.readText(Charsets.UTF_8)
        } else {
            val start = maxOf(0L, len - MAX_LOG_DISPLAY_BYTES)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                // drop partial first line
                val idx = text.indexOf('\n')
                val trimmed = if (idx >= 0) text.substring(idx + 1) else text
                "... (${(len + 1023) / 1024}KB, showing last ${MAX_LOG_DISPLAY_BYTES / 1024}KB) ...\n$trimmed"
            }
        }
    } catch (e: Exception) {
        "Failed to read file: ${e.message ?: e.javaClass.simpleName}"
    }
}

private fun generateDiagnosticReport(context: android.content.Context): String {
    val sb = java.lang.StringBuilder()
    sb.append("========================================\n")
    sb.append("      GAMENATIVE DIAGNOSTICS REPORT     \n")
    sb.append("========================================\n\n")

    // 1. Root Boost status
    sb.append("--- ROOT BOOST STATUS ---\n")
    sb.append("Status: ").append(com.winlator.core.RootPerformanceHelper.getLastStatus()).append("\n")
    val maxTemp = runCatching {
        val file = java.io.File("/sys/class/thermal")
        var maxVal = -1
        val files = file.listFiles { f -> f.name.startsWith("thermal_zone") }
        files?.forEach { zone ->
            val tempFile = java.io.File(zone, "temp")
            if (tempFile.isFile && tempFile.canRead()) {
                val raw = tempFile.readText().trim().toIntOrNull() ?: 0
                val celsius = if (raw > 1000) raw / 1000 else raw
                if (celsius > maxVal && celsius < 150) maxVal = celsius
            }
        }
        maxVal
    }.getOrDefault(-1)
    sb.append("Max Temperature: ").append(if (maxTemp > 0) "$maxTemp°C" else "unknown").append("\n\n")

    // 2. Container locale and mappings
    sb.append("--- LOCALE & TRANSLATION MAPPINGS ---\n")
    val containerManager = com.winlator.container.ContainerManager(context)
    val containers = containerManager.containers
    if (containers.isEmpty()) {
        sb.append("No containers found.\n")
    } else {
        for (container in containers) {
            sb.append("Container: ").append(container.name).append(" (ID: ").append(container.id).append(")\n")
            val rawLang = container.language ?: "english"
            sb.append("  Raw Language: ").append(rawLang).append("\n")
            val steamLang = com.winlator.core.RuntimeLocaleHelper.steamLanguageForContainer(container)
            sb.append("  Steam Language: ").append(steamLang).append("\n")
            val wineLocale = com.winlator.core.RuntimeLocaleHelper.localeForContainer(container)
            sb.append("  Wine Locale: ").append(wineLocale).append("\n")
            val epicLocale = com.winlator.core.RuntimeLocaleHelper.epicLocaleForContainer(container)
            sb.append("  Epic Locale: ").append(epicLocale).append("\n")
            val installerLang = com.winlator.core.RuntimeLocaleHelper.installerLanguageForContainer(container)
            sb.append("  Installer Language: ").append(installerLang).append("\n\n")
        }
    }

    // 3. System-wide Cpuset
    sb.append("--- SYSTEM CPUSETS ---\n")
    val devCpusetCpus = readFirstLine("/dev/cpuset/cpus")
    sb.append("Global dev/cpuset/cpus: ").append(devCpusetCpus).append("\n")
    val topAppCpus = readFirstLine("/dev/cpuset/top-app/cpus")
    sb.append("top-app/cpus: ").append(topAppCpus).append("\n")
    val gamenativeCpus = readFirstLine("/dev/cpuset/top-app/gamenative/cpus")
    if (gamenativeCpus.isNotEmpty()) {
        sb.append("top-app/gamenative/cpus: ").append(gamenativeCpus).append("\n")
    } else {
        sb.append("top-app/gamenative/cpus: not initialized\n")
    }
    sb.append("\n")

    // 4. Running processes CPU affinity and cpusets
    sb.append("--- ACTIVE CONTAINER PROCESSES ---\n")
    val processes = com.winlator.core.ProcessHelper.listSubProcesses()
    val targetProcesses = processes.filter { process ->
        val name = process.name ?: ""
        val pid = process.pid
        val cmdline = runCatching {
            val file = java.io.File("/proc/$pid/cmdline")
            if (file.isFile && file.canRead()) {
                file.readText().replace('\u0000', ' ').trim()
            } else ""
        }.getOrDefault("")
        
        val lower = (name + " " + cmdline).lowercase()
        lower.contains("wine") || lower.contains("wineserver") || lower.contains("box64") || 
        lower.contains("box86") || lower.contains("fex") || lower.contains("pulseaudio") || 
        lower.contains("gamenative")
    }

    if (targetProcesses.isEmpty()) {
        sb.append("No active container processes found.\n")
    } else {
        sb.append(String.format("%-6s %-10s %-25s %-12s %-12s\n", "PID", "RSS(MB)", "NAME", "CPUSET", "AFFINITY"))
        sb.append("----------------------------------------------------------------------\n")
        for (process in targetProcesses) {
            val pid = process.pid
            val rssMb = process.rssBytes / (1024 * 1024)
            val name = process.name ?: "unknown"
            val cpuset = readFirstLine("/proc/$pid/cpuset").trim()
            val statusFile = java.io.File("/proc/$pid/status")
            var affinity = "unknown"
            if (statusFile.exists() && statusFile.canRead()) {
                statusFile.forEachLine { line ->
                    if (line.startsWith("Cpus_allowed_list:")) {
                        affinity = line.replace("Cpus_allowed_list:", "").trim()
                    }
                }
            }
            sb.append(String.format("%-6d %-10d %-25s %-12s %-12s\n", pid, rssMb, name, cpuset, affinity))
        }
    }
    sb.append("\n========================================\n")
    return sb.toString()
}

private fun readFirstLine(path: String): String {
    val file = java.io.File(path)
    if (!file.isFile() || !file.canRead()) return ""
    return try {
        file.bufferedReader().use { it.readLine() ?: "" }
    } catch (e: Exception) {
        ""
    }
}
