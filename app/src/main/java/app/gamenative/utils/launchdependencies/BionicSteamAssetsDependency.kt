package app.gamenative.utils.launchdependencies

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.LOADING_PROGRESS_UNKNOWN
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Pre-downloads and extracts assets required for the experimental bionic-Steam
 * launch path:
 *   - steam.exe (cached in filesDir; the Wine-side copy is refreshed each boot
 *     by extractSteamFiles in XServerScreen.kt)
 *   - lsteamclient archive for the active Proton variant; extracted to
 *     <winepath>/lib/wine/ so the .so siblings land in lib/wine/<arch>-unix/
 *     and the PE DLLs land in lib/wine/<arch>-windows/. The DLLs are then
 *     copied into the wineprefix's system32 / syswow64.
 *   - bionic Android libsteamclient.so (steam-androidarm64.tzst), extracted
 *     relative to the imagefs root so it lands at imagefs/usr/lib/.
 */
object BionicSteamAssetsDependency : LaunchDependency {
    private const val STEAM_EXE = "steam.exe"
    private const val STEAM_EXE_PROTON11 = "steam-proton11.exe"
    private const val BIONIC_STEAM_ARCHIVE = "steam-androidarm64.tzst"
    private const val STEAMCLIENT_DLLS_ARCHIVE = "steamclient-dlls-20260619.tzst"
    private const val LSTEAMCLIENT_DLL = "lsteamclient.dll"
    private const val LIBSTEAMCLIENT_SO = "libsteamclient.so"
    private const val CACERT_PEM = "cacert.pem"

    /**
     * Exact bionic wine-version identifier -> lsteamclient archive. The unixlib
     * ABI is wine-version-locked, so each Proton build ships its own archive.
     * Identifiers are the installable set from manifest.json (proton) and
     * R.array.bionic_wine_entries (proton-9). Add an entry when a Proton is added.
     */
    private val LSTEAMCLIENT_ARCHIVE_BY_WINE = mapOf(
        "proton-9.0-x86_64" to "lsteamclient-x86_64-proton9.tzst",
        "proton-9.0-arm64ec" to "lsteamclient-arm64ec-proton9.tzst",
        "proton-10.0-4-x86_64-1" to "lsteamclient-x86_64.tzst",
        "proton-10.0-arm64ec-2" to "lsteamclient-arm64ec.tzst",
        "proton-10.0-4-arm64ec-1" to "lsteamclient-arm64ec.tzst",
        "proton-11.0-1-x86_64-1" to "lsteamclient-x86_64-proton11.tzst",
        "proton-11.0-1-arm64ec-1" to "lsteamclient-arm64ec-proton11.tzst",
    )

    /**
     * Wine versions that ship a dedicated steam.exe helper. steam.exe is
     * otherwise version-independent, so all other versions use the default.
     */
    private val STEAM_EXE_BY_WINE = mapOf(
        "proton-11.0-1-x86_64-1" to STEAM_EXE_PROTON11,
        "proton-11.0-1-arm64ec-1" to STEAM_EXE_PROTON11,
    )

    /** Filename of the steam.exe helper (server asset + filesDir cache) for this container. */
    fun steamExeAssetFor(container: Container): String =
        STEAM_EXE_BY_WINE[container.wineVersion] ?: STEAM_EXE

    /** All steam.exe cache names we may have installed, for bionic-vs-real detection. */
    fun bionicSteamExeNames(): List<String> = listOf(STEAM_EXE, STEAM_EXE_PROTON11)

    private fun lsteamclientArchiveFor(container: Container): String? =
        LSTEAMCLIENT_ARCHIVE_BY_WINE[container.wineVersion]

    private fun system32SrcArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-windows" else "x86_64-windows"

    private fun unixArchDir(container: Container): String =
        if (container.wineVersion.contains("arm64ec")) "aarch64-unix" else "x86_64-unix"

    /** `lib/wine/` inside the active Proton's install tree, where the archive extracts. */
    private fun wineLibDir(context: Context, container: Container): File =
        File(wineInstallDir(context, container), "lib/wine")

    private fun unixSoIn(wineLibDir: File, container: Container): File =
        File(wineLibDir, "${unixArchDir(container)}/lsteamclient.so")

    private fun treeSystem32DllIn(wineLibDir: File, container: Container): File =
        File(wineLibDir, "${system32SrcArchDir(container)}/$LSTEAMCLIENT_DLL")

    private fun treeSyswow64DllIn(wineLibDir: File): File =
        File(wineLibDir, "i386-windows/$LSTEAMCLIENT_DLL")

    /**
     * Resolves the actual Wine/Proton install directory for the container.
     * imageFs.winePath is not initialized yet at dependency-install time
     * (it's set later in XServerScreen via setWinePath), so we resolve it
     * the same way XServerScreen does — through WineInfo.fromIdentifier.
     */
    private fun wineInstallDir(context: Context, container: Container): File {
        val contentsManager = ContentsManager(context).also { it.syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, container.wineVersion)
        val path = wineInfo.path
        return if (!path.isNullOrEmpty()) {
            File(path)
        } else {
            File(ImageFs.find(context).rootDir, "opt/wine")
        }
    }

    private fun system32Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/system32/" + LSTEAMCLIENT_DLL)

    private fun syswow64Dll(container: Container): File =
        File(container.rootDir, ".wine/drive_c/windows/syswow64/" + LSTEAMCLIENT_DLL)

    private fun libsteamclientSo(imageFs: ImageFs): File =
        File(imageFs.libDir, LIBSTEAMCLIENT_SO)

    /**
     * Copies the active Proton's lsteamclient PE DLLs from its install tree into
     * the container prefix (system32 + syswow64), overwriting any previous copy.
     * Run every boot (like steam.exe) so switching a container's Proton version
     * can't leave a stale, ABI-mismatched DLL behind. No-op for wine versions
     * without a bundled lsteamclient.
     */
    fun copyLsteamclientDllsIntoPrefix(context: Context, container: Container) {
        if (lsteamclientArchiveFor(container) == null) return
        val libDir = wineLibDir(context, container)
        val sys32Src = treeSystem32DllIn(libDir, container)
        val sysWowSrc = treeSyswow64DllIn(libDir)
        if (!sys32Src.exists() || !sysWowSrc.exists()) {
            Timber.e("lsteamclient tree DLLs missing under ${libDir.absolutePath}; prefix copy skipped")
            return
        }
        val dstSystem32 = system32Dll(container)
        val dstSyswow64 = syswow64Dll(container)
        dstSystem32.parentFile?.mkdirs()
        dstSyswow64.parentFile?.mkdirs()
        if (!FileUtils.copy(sys32Src, dstSystem32)) {
            Timber.e("Failed to copy ${sys32Src.absolutePath} to ${dstSystem32.absolutePath}")
        }
        if (!FileUtils.copy(sysWowSrc, dstSyswow64)) {
            Timber.e("Failed to copy ${sysWowSrc.absolutePath} to ${dstSyswow64.absolutePath}")
        }
    }

    override fun appliesTo(container: Container, gameSource: GameSource, gameId: Int): Boolean =
        container.isLaunchBionicSteam

    override fun isSatisfied(context: Context, container: Container, gameSource: GameSource, gameId: Int): Boolean {
        val imageFs = ImageFs.find(context)
        val filesDir = imageFs.filesDir
        if (!File(filesDir, steamExeAssetFor(container)).exists()) return false
        if (!File(filesDir, CACERT_PEM).exists()) return false
        if (!File(filesDir, STEAMCLIENT_DLLS_ARCHIVE).exists()) return false
        if (!libsteamclientSo(imageFs).exists()) return false
        if (lsteamclientArchiveFor(container) != null) {
            // Only the Proton-tree extraction is cached here (it's Proton-specific,
            // so it never goes stale). The prefix DLL copy is redone every boot by
            // copyLsteamclientDllsIntoPrefix, so it isn't checked here.
            val libDir = wineLibDir(context, container)
            if (!unixSoIn(libDir, container).exists()) return false
            if (!treeSystem32DllIn(libDir, container).exists()) return false
            if (!treeSyswow64DllIn(libDir).exists()) return false
        }
        return true
    }

    override fun getLoadingMessage(context: Context, container: Container, gameSource: GameSource, gameId: Int): String =
        "Preparing real Steam assets"

    override suspend fun install(
        context: Context,
        container: Container,
        callbacks: LaunchDependencyCallbacks,
        gameSource: GameSource,
        gameId: Int,
    ) = coroutineScope {
        val imageFs = withContext(Dispatchers.IO) { ImageFs.find(context) }
        val filesDir = imageFs.filesDir

        // 1. steam.exe — cache only; XServerScreen.extractSteamFiles copies into the prefix each boot.
        val steamExeAsset = steamExeAssetFor(container)
        val steamExeCache = File(filesDir, steamExeAsset)
        if (!withContext(Dispatchers.IO) { steamExeCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $steamExeAsset")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = steamExeAsset,
                ).await()
            }
        }

        val steamclientDllsCache = File(filesDir, STEAMCLIENT_DLLS_ARCHIVE)
        if (!withContext(Dispatchers.IO) { steamclientDllsCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $STEAMCLIENT_DLLS_ARCHIVE")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = STEAMCLIENT_DLLS_ARCHIVE,
                ).await()
            }
        }

        val cacertCache = File(filesDir, CACERT_PEM)
        if (!withContext(Dispatchers.IO) { cacertCache.exists() }) {
            callbacks.setLoadingMessage("Downloading $CACERT_PEM")
            withContext(Dispatchers.IO) {
                SteamService.downloadFile(
                    onDownloadProgress = { callbacks.setLoadingProgress(it) },
                    parentScope = this@coroutineScope,
                    context = context,
                    fileName = CACERT_PEM,
                ).await()
            }
        }

        // 2/3. lsteamclient archive for the active Proton variant. Extracted into the
        // Proton's own tree (Proton-specific, so cached here); the prefix DLL copy is
        // redone every boot in copyLsteamclientDllsIntoPrefix.
        val lsteamclientArchive = lsteamclientArchiveFor(container)
        if (lsteamclientArchive != null) {
            val wineLibDir = wineLibDir(context, container)
            val treePresent = withContext(Dispatchers.IO) {
                unixSoIn(wineLibDir, container).exists() &&
                    treeSystem32DllIn(wineLibDir, container).exists() &&
                    treeSyswow64DllIn(wineLibDir).exists()
            }
            if (!treePresent) {
                val archiveCache = File(filesDir, lsteamclientArchive)
                if (!withContext(Dispatchers.IO) { archiveCache.exists() }) {
                    callbacks.setLoadingMessage("Downloading $lsteamclientArchive")
                    withContext(Dispatchers.IO) {
                        SteamService.downloadFile(
                            onDownloadProgress = { callbacks.setLoadingProgress(it) },
                            parentScope = this@coroutineScope,
                            context = context,
                            fileName = lsteamclientArchive,
                        ).await()
                    }
                }

                callbacks.setLoadingMessage("Extracting lsteamclient")
                callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
                withContext(Dispatchers.IO) {
                    wineLibDir.mkdirs()
                    Timber.i("Extracting $lsteamclientArchive into ${wineLibDir.absolutePath}")
                    val ok = TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        archiveCache,
                        wineLibDir,
                    )
                    if (!ok) {
                        throw IllegalStateException("Failed to extract $lsteamclientArchive into ${wineLibDir.absolutePath}")
                    }
                }
            }
        }

        // 4. bionic Android libsteamclient.so.
        val nativeLib = libsteamclientSo(imageFs)
        if (!withContext(Dispatchers.IO) { nativeLib.exists() }) {
            val bionicArchiveCache = File(filesDir, BIONIC_STEAM_ARCHIVE)
            if (!withContext(Dispatchers.IO) { bionicArchiveCache.exists() }) {
                callbacks.setLoadingMessage("Downloading $BIONIC_STEAM_ARCHIVE")
                withContext(Dispatchers.IO) {
                    SteamService.downloadFile(
                        onDownloadProgress = { callbacks.setLoadingProgress(it) },
                        parentScope = this@coroutineScope,
                        context = context,
                        fileName = BIONIC_STEAM_ARCHIVE,
                    ).await()
                }
            }

            callbacks.setLoadingMessage("Extracting bionic Steam client")
            callbacks.setLoadingProgress(LOADING_PROGRESS_UNKNOWN)
            withContext(Dispatchers.IO) {
                val ok = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD,
                    bionicArchiveCache,
                    imageFs.rootDir,
                )
                if (!ok) {
                    throw IllegalStateException("Failed to extract $BIONIC_STEAM_ARCHIVE into ${imageFs.rootDir.absolutePath}")
                }
            }
        }
    }
}
