package com.lagradost.cloudstream3.remote.sync

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.remote.ExtFileChunkPayload
import com.lagradost.cloudstream3.remote.ExtFileEndPayload
import com.lagradost.cloudstream3.remote.ExtFileStartPayload
import com.lagradost.cloudstream3.remote.ExtensionSyncPayload
import com.lagradost.cloudstream3.remote.ExtensionSyncReply
import com.lagradost.cloudstream3.remote.LanRemoteClient
import com.lagradost.cloudstream3.remote.PairedTv
import com.lagradost.cloudstream3.remote.PluginSyncResult
import com.lagradost.cloudstream3.remote.RemoteAuth
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.SyncedPlugin
import com.lagradost.cloudstream3.remote.payloadAs
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Extension sync (plan §8).
 *
 * Phone side: builds [ExtensionSyncPayload] from the installed repos + online plugins.
 * TV side: [apply] adds missing repos, plans install/update/remove/keep-newer against the
 * locally installed set, and executes. If a repo download fails the phone pushes the bytes
 * via EXT_FILE_* (plan §8.4).
 *
 * Divergence healing: the TV keeps its normal auto-update cycle; if the TV has a newer
 * plugin than the phone, the phone's next push is ignored (NEWER_KEPT) and the phone
 * catches up via its own auto-update (plan §8.3).
 */
object ExtensionSyncManager {
    const val SYNCED_PLUGINS_KEY = "companion/synced_plugins"
    private const val MAX_CHUNK_BYTES = 384 * 1024

    // ------------------------------------------------------------------
    // Phone side — payload construction
    // ------------------------------------------------------------------

    fun buildPayload(context: Context): ExtensionSyncPayload {
        val repos = RepositoryManager.getRepositories().toList()
        val plugins = PluginManager.getPluginsOnline().map { plugin ->
            SyncedPlugin(
                internalName = plugin.internalName,
                url = plugin.url ?: "",
                repositoryUrl = plugin.repositoryUrl ?: "",
                version = plugin.version,
                fileHash = plugin.fileHash,
                displayName = plugin.internalName,
            )
        }
        return ExtensionSyncPayload(repos = repos, plugins = plugins)
    }

    /** Cheap change detector advertised in HELLO (plan §8.1): sha256 over sorted "internalName@version@repoUrl". */
    fun pluginSetHash(): String? {
        val plugins = PluginManager.getPluginsOnline()
        if (plugins.isEmpty()) return null
        val canonical = plugins
            .map { "${it.internalName}@${it.version}@${it.repositoryUrl ?: ""}" }
            .sorted()
            .joinToString("\n")
        return sha256Hex(canonical)
    }

    // ------------------------------------------------------------------
    // TV side — apply
    // ------------------------------------------------------------------

    suspend fun apply(
        context: Context,
        payload: ExtensionSyncPayload,
        onStatus: (PluginSyncResult) -> Unit = {},
    ): ExtensionSyncReply {
        // 1. Repos: add missing, never delete TV-extra repos (plan §8.2).
        payload.repos.forEach { repo ->
            if (RepositoryManager.getRepositories().none { it.url == repo.url }) {
                runCatching { RepositoryManager.addRepository(repo) }
            }
        }

        // 2. Plan.
        val current = PluginManager.getPluginsOnline()
        val managed = getManagedSet()
        val actions = plan(payload.plugins, current, managed)

        // 3. Execute.
        val results = mutableListOf<PluginSyncResult>()
        val installedPaths = mutableListOf<String>()
        val removedPaths = mutableListOf<String>()
        for (action in actions) {
            when (action) {
                is PlanAction.InstallOrUpdate -> {
                    // Guard: never unload the plugin currently being played (plan §12).
                    if (isNameInUse(action.plugin.internalName)) {
                        val skipped = PluginSyncResult(
                            action.plugin.internalName,
                            PluginSyncResult.Status.FAILED,
                            "in-use",
                        )
                        results.add(skipped)
                        onStatus(skipped)
                        continue
                    }
                    val result = installOrUpdate(context, action.plugin)
                    if (result.status == PluginSyncResult.Status.OK_INSTALLED ||
                        result.status == PluginSyncResult.Status.UPDATED ||
                        result.status == PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE
                    ) {
                        installedPaths.add(
                            PluginManager.getPluginPath(
                                context, action.plugin.internalName, action.plugin.repositoryUrl
                            ).absolutePath
                        )
                    }
                    results.add(result)
                    onStatus(result)
                }

                is PlanAction.KeepNewer -> {
                    val result = PluginSyncResult(
                        action.plugin.internalName,
                        PluginSyncResult.Status.NEWER_KEPT,
                        "TV has version ${action.currentVersion}",
                    )
                    results.add(result)
                    onStatus(result)
                }

                is PlanAction.Remove -> {
                    if (isNameInUse(action.internalName)) {
                        val skipped = PluginSyncResult(
                            action.internalName,
                            PluginSyncResult.Status.FAILED,
                            "in-use",
                        )
                        results.add(skipped)
                        onStatus(skipped)
                        continue
                    }
                    removedPaths.add(action.filePath)
                    val removed = runCatching {
                        PluginManager.deletePlugin(File(action.filePath))
                    }.getOrDefault(false)
                    val result = if (removed) {
                        PluginSyncResult(action.internalName, PluginSyncResult.Status.REMOVED)
                    } else {
                        PluginSyncResult(action.internalName, PluginSyncResult.Status.FAILED, "remove")
                    }
                    results.add(result)
                    onStatus(result)
                }
            }
        }

        updateManagedSet(installedPaths, removedPaths)
        afterPluginsLoadedEvent.invoke(false)
        return ExtensionSyncReply(results)
    }

    private suspend fun installOrUpdate(context: Context, plugin: SyncedPlugin): PluginSyncResult {
        val file = PluginManager.getPluginPath(context, plugin.internalName, plugin.repositoryUrl)
        val existedBefore = file.exists()
        val downloaded = runCatching {
            RepositoryManager.downloadPluginToFile(context, plugin.url, file, plugin.fileHash)
        }.getOrNull()
        if (downloaded == null) {
            return PluginSyncResult(plugin.internalName, PluginSyncResult.Status.FAILED, "download")
        }
        val data = PluginData(
            internalName = plugin.internalName,
            url = plugin.url,
            isOnline = true,
            filePath = downloaded.absolutePath,
            version = plugin.version,
            fileHash = plugin.fileHash,
            repositoryUrl = plugin.repositoryUrl,
        )
        if (PluginManager.isSafeMode()) {
            runCatching { PluginManager.persistPluginData(data) }
            return PluginSyncResult(
                plugin.internalName,
                PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE,
            )
        }
        val loaded = runCatching {
            // Unload the previously loaded copy first: loadPlugin returns early when the
            // same filePath is already loaded, which would keep the old code running while
            // reporting UPDATED (review finding 3). Fresh installs are a no-op here.
            PluginManager.unloadPlugin(downloaded.absolutePath)
            PluginManager.loadPluginFile(context, downloaded, data)
        }.getOrDefault(false)
        return if (loaded) {
            PluginSyncResult(
                plugin.internalName,
                if (existedBefore) PluginSyncResult.Status.UPDATED
                else PluginSyncResult.Status.OK_INSTALLED,
            )
        } else {
            PluginSyncResult(plugin.internalName, PluginSyncResult.Status.FAILED, "load")
        }
    }

    // ------------------------------------------------------------------
    // Plan (pure, unit-tested)
    // ------------------------------------------------------------------

    sealed class PlanAction {
        data class InstallOrUpdate(val plugin: SyncedPlugin) : PlanAction()
        data class KeepNewer(val plugin: SyncedPlugin, val currentVersion: Int) : PlanAction()
        data class Remove(val filePath: String, val internalName: String) : PlanAction()
    }

    /**
     * Pure decision function (plan §8.2):
     * - desired & (missing | version > current) -> InstallOrUpdate
     * - desired & version < current -> KeepNewer (no downgrade)
     * - current & managed(filePath) & not desired -> Remove
     * - current & not managed -> untouched (TV-local installs)
     */
    fun plan(
        desired: List<SyncedPlugin>,
        current: Array<PluginData>,
        managed: Set<String>,
    ): List<PlanAction> {
        val currentByInternal = current.associateBy { it.internalName }
        val actions = mutableListOf<PlanAction>()
        for (plugin in desired) {
            val existing = currentByInternal[plugin.internalName]
            when {
                existing == null -> actions.add(PlanAction.InstallOrUpdate(plugin))
                plugin.version > existing.version -> actions.add(PlanAction.InstallOrUpdate(plugin))
                plugin.version < existing.version -> actions.add(
                    PlanAction.KeepNewer(plugin, existing.version)
                )
            }
        }
        current.forEach { plugin ->
            if (plugin.filePath in managed && desired.none { it.internalName == plugin.internalName }) {
                actions.add(PlanAction.Remove(plugin.filePath, plugin.internalName))
            }
        }
        return actions
    }

    // ------------------------------------------------------------------
    // Managed set (TV side)
    // ------------------------------------------------------------------

    fun getManagedSet(): Set<String> =
        (getKey<Array<String>>(SYNCED_PLUGINS_KEY) ?: emptyArray()).toSet()

    /**
     * Updates the managed set from what this batch actually executed: previously-managed
     * paths are preserved, removed paths are dropped, and only genuinely installed/updated
     * plugins are added. Never adopts TV-local plugins by name (reviews R2/R4, plan §8.2).
     */
    private fun updateManagedSet(installedFilePaths: List<String>, removedFilePaths: List<String>) {
        val managed = (getManagedSet() - removedFilePaths.toSet()) + installedFilePaths
        setKey(SYNCED_PLUGINS_KEY, managed.toTypedArray())
    }

    /**
     * In-use guard (plan §12): [NowPlayingHub.currentPlaySource] is the provider apiName
     * ("Cinemeta") while plugin ids are internalNames ("CinemetaProvider"); normalize with
     * the same mapping PluginManager.loadSinglePlugin uses (review R3).
     */
    private fun isNameInUse(internalName: String): Boolean {
        val source = NowPlayingHub.currentPlaySource() ?: return false
        return internalName.replace("provider", "", ignoreCase = true) == source
    }

    // ------------------------------------------------------------------
    // Fallback file transfer (plan §8.4): TV receive side
    // ------------------------------------------------------------------

    private data class FileTransfer(
        val internalName: String,
        val repositoryUrl: String,
        val expectedSize: Long,
        val expectedSha256: String,
        val tempFile: File,
    )

    private val transfers = ConcurrentHashMap<String, FileTransfer>()

    fun extFileStart(context: Context, payload: ExtFileStartPayload): PluginSyncResult? {
        val tempFile = File.createTempFile("ext-${payload.internalName}", ".cs3", context.cacheDir)
        transfers[payload.internalName] = FileTransfer(
            payload.internalName,
            payload.repositoryUrl,
            payload.sizeBytes,
            payload.sha256,
            tempFile,
        )
        return null // accepted
    }

    fun extFileChunk(payload: ExtFileChunkPayload): PluginSyncResult? {
        val transfer = transfers[payload.internalName] ?: return PluginSyncResult(
            payload.internalName, PluginSyncResult.Status.FAILED, "no-transfer",
        )
        val bytes = RemoteAuth.decodeBase64(payload.dataB64) ?: return PluginSyncResult(
            payload.internalName, PluginSyncResult.Status.FAILED, "bad-chunk",
        )
        val file = transfer.tempFile
        if (file.length() + bytes.size > transfer.expectedSize) {
            return PluginSyncResult(payload.internalName, PluginSyncResult.Status.FAILED, "oversize")
        }
        runCatching { file.appendBytes(bytes) }
        return null
    }

    suspend fun extFileEnd(context: Context, payload: ExtFileEndPayload): PluginSyncResult? {
        val transfer = transfers.remove(payload.internalName) ?: return PluginSyncResult(
            payload.internalName, PluginSyncResult.Status.FAILED, "no-transfer",
        )
        val file = transfer.tempFile
        if (file.length() != transfer.expectedSize) {
            file.delete()
            return PluginSyncResult(payload.internalName, PluginSyncResult.Status.FAILED, "size-mismatch")
        }
        if (transfer.expectedSha256.isNotBlank() && RepositoryManager.sha256(file) != transfer.expectedSha256) {
            file.delete()
            return PluginSyncResult(payload.internalName, PluginSyncResult.Status.FAILED, "hash-mismatch")
        }
        val destination = PluginManager.getPluginPath(
            context, transfer.internalName, transfer.repositoryUrl
        )
        runCatching {
            destination.parentFile?.mkdirs()
            file.copyTo(destination, overwrite = true)
            file.delete()
        }.onFailure {
            return PluginSyncResult(payload.internalName, PluginSyncResult.Status.FAILED, "move")
        }
        val data = PluginData(
            internalName = transfer.internalName,
            url = "",
            isOnline = true,
            filePath = destination.absolutePath,
            version = PLUGIN_VERSION_NOT_SET,
            fileHash = transfer.expectedSha256,
            repositoryUrl = transfer.repositoryUrl,
        )
        val loaded = if (PluginManager.isSafeMode()) {
            runCatching { PluginManager.persistPluginData(data) }.getOrDefault(false)
            false
        } else {
            // Unload the old copy first so the new file actually takes effect (same path).
            runCatching {
                PluginManager.unloadPlugin(destination.absolutePath)
                PluginManager.loadPluginFile(context, destination, data)
            }.getOrDefault(false)
        }
        // Keep the managed set intact: add this transferred plugin, never wipe the others
        // (review finding 4).
        setKey(SYNCED_PLUGINS_KEY, (getManagedSet() + destination.absolutePath).toTypedArray())
        return if (loaded || PluginManager.isSafeMode()) {
            PluginSyncResult(
                transfer.internalName,
                if (PluginManager.isSafeMode()) PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE
                else PluginSyncResult.Status.OK_INSTALLED,
            )
        } else {
            PluginSyncResult(transfer.internalName, PluginSyncResult.Status.FAILED, "load")
        }
    }

    // ------------------------------------------------------------------
    // Fallback file transfer: phone push side
    // ------------------------------------------------------------------

    suspend fun pushPlugin(context: Context, tv: PairedTv, internalName: String): PluginSyncResult? {
        val plugin = PluginManager.getPluginsOnline().firstOrNull { it.internalName == internalName }
            ?: return PluginSyncResult(internalName, PluginSyncResult.Status.FAILED, "not-installed")
        val file = File(plugin.filePath)
        if (!file.exists()) {
            return PluginSyncResult(internalName, PluginSyncResult.Status.FAILED, "file-missing")
        }
        val sha = RepositoryManager.sha256(file)
        val startReply = LanRemoteClient.send(
            tv, RemoteMessageType.EXT_FILE_START,
            ExtFileStartPayload(plugin.internalName, plugin.repositoryUrl ?: "", file.length(), sha),
        )
        if (!startReply.accepted) return PluginSyncResult(internalName, PluginSyncResult.Status.FAILED, startReply.error)

        var seq = 0
        file.inputStream().use { input ->
            val buffer = ByteArray(MAX_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunkReply = LanRemoteClient.send(
                    tv, RemoteMessageType.EXT_FILE_CHUNK,
                    ExtFileChunkPayload(plugin.internalName, seq++, RemoteAuth.encodeBase64(buffer.copyOf(read))),
                )
                if (!chunkReply.accepted) {
                    return PluginSyncResult(internalName, PluginSyncResult.Status.FAILED, chunkReply.error)
                }
            }
        }
        val endReply = LanRemoteClient.send(
            tv, RemoteMessageType.EXT_FILE_END, ExtFileEndPayload(plugin.internalName),
        )
        if (!endReply.accepted) return PluginSyncResult(internalName, PluginSyncResult.Status.FAILED, endReply.error)
        return endReply.payloadAs<PluginSyncResult>()
    }

    // ------------------------------------------------------------------

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE
}
