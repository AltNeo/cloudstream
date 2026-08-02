package com.lagradost.cloudstream3.remote.sync

import com.lagradost.cloudstream3.utils.Event

/**
 * Simple listener registries fired by [com.lagradost.cloudstream3.plugins.PluginManager]
 * and [com.lagradost.cloudstream3.plugins.RepositoryManager] whenever the installed
 * plugin / repository set changes. Kept in the remote package so PluginManager does not
 * need to import remote classes beyond this tiny hook object.
 */
object SyncHooks {
    /** Fired after an online plugin was installed, updated or removed. */
    val onPluginsChanged = Event<Unit>()

    /** Fired after a repository was added or removed. */
    val onRepositoriesChanged = Event<Unit>()
}
