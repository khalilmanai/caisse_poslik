package com.poslik.pos.core.app

import java.util.concurrent.CopyOnWriteArrayList

interface ConnectivityMonitor {
    fun isOnline(): Boolean
    fun onChange(callback: (Boolean) -> Unit): AutoCloseable
}

class SwitchableConnectivity(initialOnline: Boolean = true) : ConnectivityMonitor {

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var online: Boolean = initialOnline

    override fun isOnline(): Boolean = online

    override fun onChange(callback: (Boolean) -> Unit): AutoCloseable {
        listeners.add(callback)
        return AutoCloseable { listeners.remove(callback) }
    }

    fun set(newOnline: Boolean) {
        if (newOnline == online) return
        online = newOnline
        listeners.forEach { it(newOnline) }
    }
}

object AlwaysOnline : ConnectivityMonitor {
    override fun isOnline(): Boolean = true
    override fun onChange(callback: (Boolean) -> Unit): AutoCloseable = AutoCloseable { }
}

object AlwaysOffline : ConnectivityMonitor {
    override fun isOnline(): Boolean = false
    override fun onChange(callback: (Boolean) -> Unit): AutoCloseable = AutoCloseable { }
}
