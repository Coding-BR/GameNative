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
        
        val totalCores = Runtime.getRuntime().availableProcessors()
        val defaultList = (0 until totalCores).joinToString(",")
        
        val presetItems = listOf(
            stringResource(R.string.cpu_preset_default),
            stringResource(R.string.cpu_preset_sd8gen2),
            stringResource(R.string.cpu_preset_sd8gen3),
            stringResource(R.string.cpu_preset_sd8elite),
            stringResource(R.string.cpu_preset_custom)
        )
        
        val currentPresetIndex = when {
            config.cpuList == defaultList && config.cpuListWoW64 == (totalCores / 2 until totalCores).joinToString(",") -> 0
            config.cpuList == "3,4,5,6,7" && config.cpuListWoW64 == "3,4,5,6" -> 1
            config.cpuList == "2,3,4,5,6,7" && config.cpuListWoW64 == "2,3,4,5,6" -> 2
            config.cpuList == "0,1,2,3,4,5,6,7" && config.cpuListWoW64 == "0,1,2,3,4,5" -> 3
            else -> 4
        }
        
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.cpu_affinity_preset)) },
            value = currentPresetIndex,
            items = presetItems,
            onItemSelected = { index ->
                val newConfig = when (index) {
                    0 -> config.copy(
                        cpuList = defaultList,
                        cpuListWoW64 = (totalCores / 2 until totalCores).joinToString(",")
                    )
                    1 -> config.copy(
                        cpuList = "3,4,5,6,7",
                        cpuListWoW64 = "3,4,5,6"
                    )
                    2 -> config.copy(
                        cpuList = "2,3,4,5,6,7",
                        cpuListWoW64 = "2,3,4,5,6"
                    )
                    3 -> config.copy(
                        cpuList = "0,1,2,3,4,5,6,7",
                        cpuListWoW64 = "0,1,2,3,4,5"
                    )
                    else -> config
                }
                state.config.value = newConfig
            }
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
