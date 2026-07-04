package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.Box64PresetsDialog
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.FEXCorePresetsDialog
import app.gamenative.ui.component.dialog.OrientationDialog
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.container.Container
import com.winlator.core.RootPerformanceHelper

@Composable
fun SettingsGroupEmulation() {
    SettingsGroup(
    ) {
        var showConfigDialog by rememberSaveable { mutableStateOf(false) }
        var showOrientationDialog by rememberSaveable { mutableStateOf(false) }
        var showBox64PresetsDialog by rememberSaveable { mutableStateOf(false) }

        OrientationDialog(
            openDialog = showOrientationDialog,
            onDismiss = { showOrientationDialog = false },
        )

        ContainerConfigDialog(
            visible = showConfigDialog,
            title = stringResource(R.string.settings_emulation_default_config_dialog_title),
            default = true,
            initialConfig = ContainerUtils.getDefaultContainerData(),
            onDismissRequest = { showConfigDialog = false },
            onSave = {
                showConfigDialog = false
                ContainerUtils.setDefaultContainerData(it)
            },
        )

        Box64PresetsDialog(
            visible = showBox64PresetsDialog,
            onDismissRequest = { showBox64PresetsDialog = false },
        )
        var showFexcorePresetsDialog by rememberSaveable { mutableStateOf(false) }
        if (showFexcorePresetsDialog) {
            FEXCorePresetsDialog(
                visible = showFexcorePresetsDialog,
                onDismissRequest = { showFexcorePresetsDialog = false },
            )
        }

        var showDriverManager by rememberSaveable { mutableStateOf(false) }
        if (showDriverManager) {
            // Lazy-load dialog composable to avoid cyclic imports
            DriverManagerDialog(open = showDriverManager, onDismiss = { showDriverManager = false })
        }

        var showContentsManager by rememberSaveable { mutableStateOf(false) }
        if (showContentsManager) {
            ContentsManagerDialog(open = showContentsManager, onDismiss = { showContentsManager = false })
        }

        var showWineProtonManager by rememberSaveable { mutableStateOf(false) }
        if (showWineProtonManager) {
            WineProtonManagerDialog(open = showWineProtonManager, onDismiss = { showWineProtonManager = false })
        }

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_orientations_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_orientations_subtitle)) },
            onClick = { showOrientationDialog = true },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_default_config_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_default_config_subtitle)) },
            onClick = { showConfigDialog = true },
        )
        val rootProfileValues = listOf(
            Container.ROOT_PERFORMANCE_OFF,
            Container.ROOT_PERFORMANCE_SAFE,
            Container.ROOT_PERFORMANCE_PERFORMANCE,
            Container.ROOT_PERFORMANCE_EXTREME,
        )
        val rootProfileItems = listOf(
            stringResource(R.string.root_performance_off),
            stringResource(R.string.root_performance_safe),
            stringResource(R.string.root_performance_performance),
            stringResource(R.string.root_performance_extreme),
        )
        var rootPerformanceProfile by rememberSaveable { mutableStateOf(PrefManager.rootPerformanceProfile) }
        SettingsListDropdown(
            colors = settingsTileColors(),
            value = rootProfileValues.indexOf(rootPerformanceProfile).coerceAtLeast(0),
            items = rootProfileItems,
            title = { Text(text = stringResource(R.string.root_performance_profile)) },
            subtitle = { Text(text = stringResource(R.string.root_performance_global_description)) },
            onItemSelected = { index ->
                val profile = rootProfileValues.getOrElse(index) { Container.ROOT_PERFORMANCE_OFF }
                rootPerformanceProfile = profile
                PrefManager.rootPerformanceProfile = profile
                if (profile != Container.ROOT_PERFORMANCE_OFF) {
                    RootPerformanceHelper.requestRootAccessForSettings()
                }
            },
        )
        var autoApplyKnownConfig by rememberSaveable { mutableStateOf(PrefManager.autoApplyKnownConfig) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = autoApplyKnownConfig,
            title = { Text(text = stringResource(R.string.settings_emulation_auto_apply_known_config_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_auto_apply_known_config_subtitle)) },
            onCheckedChange = {
                autoApplyKnownConfig = it
                PrefManager.autoApplyKnownConfig = it
            },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_box64_presets_title)) },
            subtitle = { Text(stringResource(R.string.settings_emulation_box64_presets_subtitle)) },
            onClick = { showBox64PresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.fexcore_presets)) },
            subtitle = { Text(text = stringResource(R.string.fexcore_presets_description)) },
            onClick = { showFexcorePresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_driver_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_driver_manager_subtitle)) },
            onClick = { showDriverManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_contents_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_contents_manager_subtitle)) },
            onClick = { showContentsManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_subtitle)) },
            onClick = { showWineProtonManager = true },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_SettingsGroupEmulation() {
    val isPreview = LocalInspectionMode.current
    if (!isPreview) {
        val context = LocalContext.current
        PrefManager.init(context)
    }
    PluviaTheme {
        SettingsGroupEmulation()
    }
}
