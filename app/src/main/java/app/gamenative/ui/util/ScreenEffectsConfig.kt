package app.gamenative.ui.util

import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer

data class ScreenEffectsConfig(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1.0f,
    val scalingMode: Int = SCALING_MODE_NONE,
    val fsrSharpnessLevel: Int = FSR_DEFAULT_LEVEL,
    val enableToon: Boolean = false,
    val enableFXAA: Boolean = false,
    val enableVivid: Boolean = false,
    val enableCRT: Boolean = false,
    val enableNTSC: Boolean = false,
) {
    companion object {
        const val SCALING_MODE_NONE = 0
        const val SCALING_MODE_NEAREST = 1
        const val SCALING_MODE_LINEAR = 2
        const val SCALING_MODE_FILL = 3
        const val SCALING_MODE_STRETCH = 4
        const val SCALING_MODE_FSR = 5
        const val SCALING_MODE_FSR_ASPECT = 6
        const val FSR_MIN_LEVEL = 1
        const val FSR_MAX_LEVEL = 5
        const val FSR_DEFAULT_LEVEL = 3

        // Container extra keys
        const val KEY_BRIGHTNESS = "screenEffectsBrightness"
        const val KEY_CONTRAST = "screenEffectsContrast"
        const val KEY_GAMMA = "screenEffectsGamma"
        const val KEY_SCALING_MODE = "screenEffectsScalingMode"
        const val KEY_FSR_SHARPNESS = "screenEffectsFsrSharpness"
        const val KEY_ENABLE_TOON = "screenEffectsEnableToon"
        const val KEY_ENABLE_FXAA = "screenEffectsEnableFXAA"
        const val KEY_ENABLE_VIVID = "screenEffectsEnableVivid"
        const val KEY_ENABLE_CRT = "screenEffectsEnableCRT"
        const val KEY_ENABLE_NTSC = "screenEffectsEnableNTSC"
    }
}

/** Load config from container extras, falling back to hardcoded defaults. */
fun loadScreenEffectsConfig(container: Container?): ScreenEffectsConfig {
    if (container == null) return ScreenEffectsConfig()
    return ScreenEffectsConfig(
        brightness = container.getExtra(ScreenEffectsConfig.KEY_BRIGHTNESS)?.toFloatOrNull() ?: 0f,
        contrast = container.getExtra(ScreenEffectsConfig.KEY_CONTRAST)?.toFloatOrNull() ?: 0f,
        gamma = container.getExtra(ScreenEffectsConfig.KEY_GAMMA)?.toFloatOrNull() ?: 1f,
        scalingMode = container.getExtra(ScreenEffectsConfig.KEY_SCALING_MODE)?.toIntOrNull() ?: ScreenEffectsConfig.SCALING_MODE_NONE,
        fsrSharpnessLevel =
        container.getExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS)?.toIntOrNull() ?: ScreenEffectsConfig.FSR_DEFAULT_LEVEL,
        enableToon = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_TOON)?.toBooleanStrictOrNull() ?: false,
        enableFXAA = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA)?.toBooleanStrictOrNull() ?: false,
        enableVivid = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID)?.toBooleanStrictOrNull() ?: false,
        enableCRT = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_CRT)?.toBooleanStrictOrNull() ?: false,
        enableNTSC = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC)?.toBooleanStrictOrNull() ?: false,
    )
}

/**
 * Persist config to container extras.
 * Callers should debounce [container.saveData] calls.
 */
fun persistScreenEffectsConfig(container: Container?, config: ScreenEffectsConfig) {
    if (container == null) return
    container.putExtra(ScreenEffectsConfig.KEY_BRIGHTNESS, config.brightness)
    container.putExtra(ScreenEffectsConfig.KEY_CONTRAST, config.contrast)
    container.putExtra(ScreenEffectsConfig.KEY_GAMMA, config.gamma)
    container.putExtra(ScreenEffectsConfig.KEY_SCALING_MODE, config.scalingMode)
    container.putExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS, config.fsrSharpnessLevel)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_TOON, config.enableToon)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA, config.enableFXAA)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID, config.enableVivid)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_CRT, config.enableCRT)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC, config.enableNTSC)
}

fun fsrQuickMenuLevelToStops(level: Int): Float {
    val clamped = level.coerceIn(ScreenEffectsConfig.FSR_MIN_LEVEL, ScreenEffectsConfig.FSR_MAX_LEVEL)
    return when (clamped) {
        1 -> 2.0f
        2 -> 1.5f
        3 -> 1.0f
        4 -> 0.5f
        else -> 0.0f
    }
}

/**
 * Applies post-processing config to the Vulkan renderer.
 *
 * Single-pass effects available in the SPIR-V fragment shader:
 *   FSR (+ DLS sharpening), CRT, HDR (Vivid), Natural.
 * Multi-pass effects (FXAA, Toon, NTSC) require additional render passes — deferred.
 * Brightness / contrast / gamma are applied as a post-effect colour adjustment.
 */
fun applyScreenEffectsConfig(renderer: VulkanRenderer, config: ScreenEffectsConfig) {
    // Filter mode: nearest-neighbour vs linear interpolation.
    val filterMode = if (config.scalingMode == ScreenEffectsConfig.SCALING_MODE_NEAREST) 1 else 0
    renderer.setFilterMode(filterMode)

    // Colour adjustments are applied in the fragment shader regardless of effect.
    renderer.setColorAdjustment(config.brightness, config.contrast, config.gamma)

    // Effect selection: priority order matches the original EffectComposer chain.
    val sharpness = fsrQuickMenuLevelToStops(config.fsrSharpnessLevel)
    when {
        config.scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR ||
        config.scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT -> {
            renderer.setEffect(VulkanRenderer.EFFECT_FSR, sharpness)
        }
        config.enableCRT -> renderer.setEffect(VulkanRenderer.EFFECT_CRT, sharpness)
        config.enableVivid -> renderer.setEffect(VulkanRenderer.EFFECT_HDR, sharpness)
        // FXAA, Toon, NTSC need multi-pass — fall back to no post-processing for now.
        else -> renderer.setEffect(VulkanRenderer.EFFECT_NONE, sharpness)
    }
}
