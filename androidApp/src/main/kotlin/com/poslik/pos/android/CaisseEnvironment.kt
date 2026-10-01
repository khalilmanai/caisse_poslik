package com.poslik.pos.android

import android.content.Context
import com.poslik.pos.android.platform.AndroidConnectivityMonitor
import com.poslik.pos.android.platform.AndroidLocalStore
import com.poslik.pos.android.platform.AndroidRealtimeDbGateway
import com.poslik.pos.android.platform.ScreenReceiptGateway
import com.poslik.pos.core.app.PosConfig
import com.poslik.pos.core.app.PosService
import com.poslik.pos.core.domain.Cart
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Product
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.print.PrintWorker
import com.poslik.pos.core.sync.NoAuth
import com.poslik.pos.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable

/**
 * Assemble les adaptateurs Android autour du coeur de metier partage.
 *
 * Toute la logique (numerotation, encaissement atomique, file d'impression,
 * reprise au demarrage, synchronisation idempotente) vient de `:core`, deja
 * couvert par les tests JVM. Cette classe ne fait que brancher la couche
 * Android sur les interfaces LocalStore / PrintGateway / RealtimeDbGateway /
 * ConnectivityMonitor.
 *
 * Aucune imprimante n'est branchee : le recu est affiche a l'ecran par l'interface, et
 * [ScreenReceiptGateway] maintient la file d'impression de `:core` (état "imprimé" dans
 * l'historique) sans jamais appeler `PrintManager`.
 *
 * Pas de Firebase Authentication : l'enonce ne comporte ni utilisateur ni
 * compte. L'acces est donc regle par `database.rules.json` (voir docs).
 */
class CaisseEnvironment(
    context: Context,
    val storeId: String = BuildConfig.STORE_ID,
    val storeLabel: String = BuildConfig.STORE_LABEL,
    firebaseUrl: String? = BuildConfig.FIREBASE_DATABASE_URL,
) : Closeable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localStore = AndroidLocalStore(appContext).also { it.bootstrapTerminal() }
    private val connectivity = AndroidConnectivityMonitor(appContext)
    private val printGateway = ScreenReceiptGateway()

    val printWorker = PrintWorker(store = localStore, gateway = printGateway)

    val syncEngine: SyncEngine? = firebaseUrl
        ?.takeIf { it.isNotBlank() && it.startsWith("http") }
        ?.let { url ->
            SyncEngine(
                store = localStore,
                gateway = AndroidRealtimeDbGateway(databaseUrl = url, auth = NoAuth),
                storeId = storeId,
                isOnline = connectivity::isOnline,
            )
        }

    val service = PosService(
        store = localStore,
        printWorker = printWorker,
        config = PosConfig(storeId = storeId, storeLabel = storeLabel),
        scope = scope,
        syncEngine = syncEngine,
        connectivity = connectivity,
    )

    val cart = Cart()

    fun addToCart(product: Product) {
        cart.add(product.id, 1)
    }

    fun removeFromCart(product: Product) {
        cart.remove(product.id)
    }

    /** Meme contrat que la console : une transaction, puis retour immediat. */
    fun checkout(method: PaymentMethod, cashGiven: Money?): Sale {
        val sale = service.checkout(cart.snapshot(), method, cashGiven)
        cart.clear()
        return sale
    }

    fun isOnline(): Boolean = connectivity.isOnline()

    suspend fun syncNow() {
        val report = syncEngine?.syncOnce()
        android.util.Log.i("PosLik", "syncOnce -> $report")
    }

    override fun close() {
        service.shutdown()
        scope.cancel()
        localStore.close()
    }
}