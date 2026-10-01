package com.poslik.pos.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.poslik.pos.core.app.ConnectivityMonitor

/**
 * Observe la connectivite reelle du poste via [ConnectivityManager].
 *
 * L'encaissement et l'impression ne consultent jamais cet etat : seule la
 * synchronisation en tient compte. C'est ce qui permet de vendre hors ligne
 * sans que le client s'en apercoive.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var lastKnown: Boolean = queryNow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish(queryNow())
        }

        override fun onLost(network: Network) {
            publish(queryNow())
        }
    }

    init {
        runCatching {
            manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }
    }

    override fun isOnline(): Boolean = lastKnown

    override fun onChange(callback: (Boolean) -> Unit): AutoCloseable {
        listeners.add(callback)
        return AutoCloseable { listeners.remove(callback) }
    }

    private fun queryNow(): Boolean {
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun publish(online: Boolean) {
        if (online == lastKnown) return
        lastKnown = online
        listeners.toList().forEach { it(online) }
    }
}