package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.remote.sync.ExtensionSyncManager
import com.lagradost.cloudstream3.remote.sync.ExtensionSyncManager.PlanAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSyncManagerTest {

    private fun plugin(
        name: String,
        version: Int,
        repoUrl: String = "https://repo.example.com",
        filePath: String? = null,
        fileHash: String? = null,
    ) = PluginData(
        internalName = name,
        url = "https://repo.example.com/$name.cs3",
        isOnline = true,
        filePath = filePath ?: "/ext/$repoUrl/$name.cs3",
        version = version,
        fileHash = fileHash,
        repositoryUrl = repoUrl,
    )

    private fun desired(
        name: String,
        version: Int,
        repoUrl: String = "https://repo.example.com",
        fileHash: String? = null,
    ) = SyncedPlugin(
        internalName = name,
        url = "https://repo.example.com/$name.cs3",
        repositoryUrl = repoUrl,
        version = version,
        fileHash = fileHash,
        displayName = name,
    )

    @Test
    fun `missing plugin is installed`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 3)),
            current = arrayOf(plugin("B", 1)),
            managed = emptySet(),
        )
        assertEquals(1, actions.size)
        assertEquals(PlanAction.InstallOrUpdate(desired("A", 3)), actions[0])
    }

    @Test
    fun `newer desired version updates`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 5)),
            current = arrayOf(plugin("A", 4)),
            managed = setOf("/ext/x/A.cs3"),
        )
        assertEquals(listOf(PlanAction.InstallOrUpdate(desired("A", 5))), actions)
    }

    @Test
    fun `equal version is left alone`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 4)),
            current = arrayOf(plugin("A", 4)),
            managed = setOf("/ext/x/A.cs3"),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `older desired version is kept (no downgrade)`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 2)),
            current = arrayOf(plugin("A", 9)),
            managed = setOf("/ext/x/A.cs3"),
        )
        assertEquals(1, actions.size)
        assertTrue(actions[0] is PlanAction.KeepNewer)
        assertEquals(9, (actions[0] as PlanAction.KeepNewer).currentVersion)
    }

    @Test
    fun `managed plugin no longer desired is removed`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 1)),
            current = arrayOf(plugin("A", 1, filePath = "/ext/x/A.cs3"), plugin("B", 1, filePath = "/ext/x/B.cs3")),
            managed = setOf("/ext/x/A.cs3", "/ext/x/B.cs3"),
        )
        assertEquals(1, actions.size)
        assertEquals(PlanAction.Remove("/ext/x/B.cs3", "B"), actions[0])
    }

    @Test
    fun `unmanaged plugin is left alone`() {
        val actions = ExtensionSyncManager.plan(
            desired = emptyList(),
            current = arrayOf(plugin("B", 1, filePath = "/ext/x/B.cs3")),
            managed = emptySet(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `TV-local install is never removed`() {
        // Same internalName as desired: even though filePath is not in managed, it is desired -> kept.
        val actions = ExtensionSyncManager.plan(
            desired = listOf(desired("A", 1)),
            current = arrayOf(plugin("A", 1, filePath = "/ext/user/A.cs3")),
            managed = emptySet(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `combined matrix`() {
        val actions = ExtensionSyncManager.plan(
            desired = listOf(
                desired("install-me", 1),
                desired("update-me", 2),
                desired("keep-newer", 1),
                desired("same", 3),
            ),
            current = arrayOf(
                plugin("update-me", 1),
                plugin("keep-newer", 5),
                plugin("same", 3),
                plugin("remove-me", 1, filePath = "/ext/x/remove-me.cs3"),
                plugin("tv-local", 1, filePath = "/ext/tv/tv-local.cs3"),
            ),
            managed = setOf("/ext/x/remove-me.cs3"),
        )
        assertEquals(4, actions.size)
        assertTrue(actions.any { it is PlanAction.InstallOrUpdate && it.plugin.internalName == "install-me" })
        assertTrue(actions.any { it is PlanAction.InstallOrUpdate && it.plugin.internalName == "update-me" })
        assertTrue(actions.any { it is PlanAction.KeepNewer && it.plugin.internalName == "keep-newer" })
        assertTrue(actions.none { it is PlanAction.Remove && it.internalName == "tv-local" })
        assertTrue(actions.any { it is PlanAction.Remove && it.internalName == "remove-me" })
    }
}
