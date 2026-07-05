package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File

/**
 * Detects when the container is configured to use a Proton/Wine version
 * that is NOT one of the auto-downloadable defaults (proton-9.0-*) and
 * is NOT already installed in imagefs_shared/proton.
 *
 * Throws a clear, user-readable exception instead of silently failing,
 * so the user knows they need to install the Proton version manually
 * via the Wine/Proton Manager.
 */
object BionicExternalProtonDependency : LaunchDependency {

    // Versions handled automatically by BionicDefaultProtonDependency
    private val AUTO_DOWNLOADABLE_VERSIONS = listOf(
        "proton-9.0-arm64ec",
        "proton-9.0-x86_64",
    )

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean {
        if (container.containerVariant != Container.BIONIC) return false
        val v = container.wineVersion
        // Only apply to proton-* versions that are NOT auto-downloadable
        if (!v.lowercase().startsWith("proton-")) return false
        return AUTO_DOWNLOADABLE_VERSIONS.none { v.contains(it) }
    }

    /**
     * Resolves the actual install directory for the configured Proton version.
     *
     * ContentsManager uses 'verName' (e.g. "proton-10.0-arm64ec") as the directory name,
     * NOT the full entryName with verCode suffix (e.g. "proton-10.0-arm64ec-2").
     * So we ask ContentsManager first; if it doesn't know about the profile we fall back
     * to checking the imagefs_shared/proton/<wineVersion> path directly.
     */
    private fun resolveInstallDir(context: Context, protonVersion: String): File {
        try {
            val cm = ContentsManager(context)
            cm.syncContents()
            val profile = cm.getProfileByEntryName(protonVersion)
            if (profile != null) {
                val installDir = ContentsManager.getInstallDir(context, profile)
                Timber.d("BionicExternalProtonDependency: Resolved via ContentsManager -> ${installDir.absolutePath}")
                return installDir
            }
        } catch (e: Exception) {
            Timber.w(e, "BionicExternalProtonDependency: ContentsManager lookup failed, falling back")
        }
        // Fallback: try both with and without version code suffix
        // entryName format: "<type>-<verName>-<verCode>", e.g. "proton-10.0-arm64ec-2"
        // verName is everything except the last dash-segment (the verCode)
        val lastDash = protonVersion.lastIndexOf('-')
        if (lastDash > 0) {
            val verName = protonVersion.substring(0, lastDash)
            val fallbackDir = File(ImageFs.getSharedProtonDir(context), verName)
            Timber.d("BionicExternalProtonDependency: Fallback verName path -> ${fallbackDir.absolutePath}")
            return fallbackDir
        }
        return File(ImageFs.getSharedProtonDir(context), protonVersion)
    }

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val protonVersion = container.wineVersion
        val installDir = resolveInstallDir(context, protonVersion)
        val binDir = File(installDir, "bin")
        val isInstalled = binDir.exists() && binDir.isDirectory
        Timber.d("BionicExternalProtonDependency: $protonVersion -> installed=$isInstalled at ${installDir.absolutePath}")
        return isInstalled
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String {
        return context.getString(R.string.proton_version_not_supported, container.wineVersion)
    }

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) {
        val version = container.wineVersion
        val message = context.getString(R.string.proton_version_not_supported, version)
        Timber.e("BionicExternalProtonDependency: Proton '$version' is not installed and cannot be downloaded automatically.")
        throw IllegalStateException(message)
    }
}
