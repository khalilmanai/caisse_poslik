package com.poslik.pos.pos.app

import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.ProductCatalog
import com.poslik.pos.core.print.TicketRenderer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.ZoneId
import kotlin.math.round
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        AppOptions.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        printUsage()
        exitProcess(2)
    }

    val application = ApplicationFactory.build(options)
    val reader = BufferedReader(InputStreamReader(System.`in`))

    try {
        val startup = application.startup()
        println(
            ConsoleFormatter.banner(
                options,
                application.store.terminal().id,
                application.isOnline(),
                application.syncEngine != null,
            ),
        )
        if (startup.printQueueRecovered > 0) {
            println("Reprise au d\u00E9marrage : ${startup.printQueueRecovered} ticket(s) en attente ou en \u00E9chec renvoy\u00E9s \u00E0 l'impression.")
        }
        println("Reprise au d\u00E9marrage : ${startup.print.printed} ticket(s) r\u00E9imprim\u00E9(s), ${startup.print.failed} en \u00E9chec.")
        println("Commandes : 1-8 ajouter | +ESP 2 ajouter | -ESP retirer | e encaisser | h historique | o r\u00E9seau | s synchroniser | q quitter")
        println()

        loop@ while (true) {
            printPrompt(application)
            val input = reader.readLine()?.trim() ?: break
            if (input.isEmpty()) continue

            when {
                input == "q" || input == "quitter" -> break@loop
                input == "h" || input == "historique" -> printHistory(application)
                input == "e" || input == "encaisser" -> checkout(application, reader)
                input == "o" || input == "reseau" -> toggleNetwork(application)
                input == "s" || input == "sync" -> synchronise(application)
                input == "p" || input == "produits" -> printProducts()
                input == "t" || input == "ticket" -> printLastTicket(application)
                input.startsWith("+") -> addById(application, input.removePrefix("+"))
                input.startsWith("-") -> removeProduct(application, input.removePrefix("-"))
                else -> addById(application, input)
            }
        }
        println("Fermeture de la caisse.")
    } finally {
        application.close()
    }
}

private fun printUsage() {
    println(
        """
        Usage: caisse [options]
          --store <id>              identifiant magasin (d\u00E9faut boutique-centre-ville)
          --label <texte>           nom affich\u00E9 sur le ticket
          --db <chemin>             base SQLite locale
          --spool <dossier>         dossier de sortie des tickets
          --firebase-url <url>      URL Firebase Realtime Database
          --offline                 d\u00E9marrer hors ligne
          --printer-fail-every <n>  simuler une panne d'impression toutes les n tentatives
        Variables d'environnement accept\u00E9es : FIREBASE_DATABASE_URL, POS_FORCE_OFFLINE
        """.trimIndent(),
    )
}

private fun printPrompt(application: PosApplication) {
    val cart = application.cart.snapshot()
    val network = if (application.isOnline()) "en ligne" else "HORS LIGNE"
    print("caisse> panier: ${cart.itemCount} article(s) = ${cart.total.formatPlain()}  [$network] > ")
}

private fun printProducts() {
    println()
    ProductCatalog.all.forEachIndexed { index, product ->
        println("  ${index + 1}. ${product.id.padEnd(4)} ${product.name.padEnd(26)} ${product.unitPrice.format()}")
    }
    println()
}

private fun addById(application: PosApplication, input: String) {
    val parts = input.trim().split(Regex("\\s+"))
    val rawId = parts.first()
    val quantity = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val productId = rawId.toIntOrNull()
    if (productId != null && productId in 1..8) {
        addProduct(application, ProductCatalog.all[productId - 1].id, quantity)
    } else {
        addProduct(application, rawId, quantity)
    }
}

private fun addProduct(application: PosApplication, productId: String, quantity: Int) {
    val line = runCatching { application.cart.add(productId.trim().uppercase(), quantity) }
    if (line.isFailure) {
        println("  ! ${line.exceptionOrNull()?.message}")
        return
    }
    val added = line.getOrThrow()
    println("  + ${added.quantity} x ${added.product.name}")
}

private fun removeProduct(application: PosApplication, productId: String) {
    val normalized = productId.trim().uppercase()
    val removed = application.cart.remove(normalized)
    println(if (removed) "  - $normalized retire" else "  ! $normalized absent du panier")
}

private fun checkout(application: PosApplication, reader: BufferedReader) {
    val snapshot = application.cart.snapshot()
    if (snapshot.isEmpty) {
        println("  ! panier vide")
        return
    }
    println("  TOTAL A ENCAISSER : ${snapshot.total.formatPlain()}")
    print("  paiement (montant en dinars pour les esp\u00E8ces, 'cb' pour carte) > ")
    val answer = reader.readLine()?.trim().orEmpty()

    val method = if (answer.equals("cb", ignoreCase = true) || answer.isEmpty()) {
        PaymentMethod.CARD
    } else {
        val given = parseMoney(answer)
        if (given == null || given < snapshot.total) {
            println("  ! montant invalide ou insuffisant, encaissement annul\u00E9")
            return
        }
        PaymentMethod.CASH
    }
    val cashGiven = if (method == PaymentMethod.CASH) parseMoney(answer) else null

    val startedAt = System.nanoTime()
    val sale = application.checkout(method, cashGiven)
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

    println("  TICKET ${sale.ticketNumber.value} enregistr\u00E9 en ${elapsedMs} ms  total ${sale.total.formatPlain()}")
    if (method == PaymentMethod.CASH && !sale.changeDue.isZero()) {
        println("  RENDU ${sale.changeDue.formatPlain()}")
    }
    println("  panier vide, impression et synchronisation en arri\u00E8re-plan.")
    println()
}

private fun parseMoney(raw: String): Money? {
    val cleaned = raw.replace(',', '.').removeSuffix("\u20AC").removeSuffix("DT").trim()
    val value = cleaned.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return Money(round(value * 1000).toLong(), "TND")
}

private fun printLastTicket(application: PosApplication) {
    val latest = application.store.history(1).firstOrNull()
    if (latest == null) {
        println("  aucune vente enregistr\u00E9e")
        return
    }
    println()
    println(TicketRenderer(application.options.storeLabel, zone = ZoneId.systemDefault()).render(latest.sale))
    println("  \u00E9tat d'impression : ${latest.print.label}")
}

private fun printHistory(application: PosApplication) {
    val history = application.service.history(25)
    println()
    if (history.isEmpty()) {
        println("  aucune vente")
    } else {
        print(ConsoleFormatter.history(history))
        val counts = application.store.printStateCounts()
        println("  \u00E9tats d'impression : $counts")
        println("  en attente de synchronisation : ${application.store.unsyncedCount()}")
    }
    println()
}

private fun toggleNetwork(application: PosApplication) {
    val wasOnline = application.isOnline()
    application.setOnline(!wasOnline)
    if (wasOnline) {
        println("  r\u00E9seau : HORS LIGNE - encaissement local, file d'attente conserv\u00E9e")
    } else {
        println("  r\u00E9seau : EN LIGNE - synchronisation lanc\u00E9e")
        application.synchroniseNow()
        println("  reste \u00E0 synchroniser : ${application.store.unsyncedCount()}")
    }
}

private fun synchronise(application: PosApplication) {
    application.synchroniseNow()
    println("  reste \u00E0 synchroniser : ${application.store.unsyncedCount()}")
}
