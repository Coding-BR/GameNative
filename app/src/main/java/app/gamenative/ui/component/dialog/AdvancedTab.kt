package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsCPUList
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.container.Container
import com.winlator.core.RootPerformanceHelper

@Composable
fun AdvancedTabContent(state: ContainerConfigState) {
    val config = state.config.value

    SettingsGroup() {
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.startup_selection)) },
            value = config.startupSelection.toInt().takeIf { it in state.getStartupSelectionOptions().indices } ?: 1,
            items = state.getStartupSelectionOptions(),
            onItemSelected = {
                state.config.value = config.copy(startupSelection = it.toByte())
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity)) },
            value = config.cpuList,
            onValueChange = {
                state.config.value = config.copy(cpuList = it)
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity_32bit)) },
            value = config.cpuListWoW64,
            onValueChange = { state.config.value = config.copy(cpuListWoW64 = it) },
        )
        val rootProfileValues = listOf(
            Container.ROOT_PERFORMANCE_GLOBAL,
            Container.ROOT_PERFORMANCE_OFF,
            Container.ROOT_PERFORMANCE_SAFE,
            Container.ROOT_PERFORMANCE_PERFORMANCE,
            Container.ROOT_PERFORMANCE_EXTREME,
        )
        val rootProfileItems = listOf(
            stringResource(R.string.root_performance_use_global),
            stringResource(R.string.root_performance_off),
            stringResource(R.string.root_performance_safe),
            stringResource(R.string.root_performance_performance),
            stringResource(R.string.root_performance_extreme),
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.root_performance_profile)) },
            value = rootProfileValues.indexOf(config.rootPerformanceProfile).coerceAtLeast(0),
            items = rootProfileItems,
            onItemSelected = { index ->
                val profile = rootProfileValues.getOrElse(index) { Container.ROOT_PERFORMANCE_OFF }
                state.config.value = config.copy(
                    rootPerformanceMode = profile != Container.ROOT_PERFORMANCE_OFF,
                    rootPerformanceProfile = profile,
                )
                if (profile != Container.ROOT_PERFORMANCE_OFF && profile != Container.ROOT_PERFORMANCE_GLOBAL) {
                    RootPerformanceHelper.requestRootAccessForSettings()
                }
            },
        )
    }
}
