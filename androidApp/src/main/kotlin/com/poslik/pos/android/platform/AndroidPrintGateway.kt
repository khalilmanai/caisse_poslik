package com.poslik.pos.android.platform

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.print.PrintFailure
import com.poslik.pos.core.print.PrintGateway
import com.poslik.pos.core.print.TicketRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Impression Android via [PrintManager].
 *
 * Le ticket part en texte brut dans le flux du job : pas de WebView, et le rendu
 * est exactement celui de [TicketRenderer].
 *
 * Contrainte de la plateforme : `PrintManager.print` n'accepte qu'un contexte [Activity]
 * (sinon `IllegalStateException: Can print only from an activity`) et le lancement de
 * l'UI d'impression se fait depuis le thread principal. L'activite est donc detenue en
 * reference faible et l'appel est poste sur `Dispatchers.Main` ; un contexte absent ou
 * en cours de destruction est rapporte en [PrintFailure] pour que [com.poslik.pos.core.print.PrintWorker]
 * bascule le ticket en `FAILED` avec backoff au lieu de faire planter l'application.
 *
 * [PrintManager.print] ne fournit aucun retour d'echec une fois le job cree : le job est
 * considere comme envoye des sa creation. Une annulation utilisateur ou une panne du
 * spooler ne peut donc pas etre observee ici. Consequence assumee : en cas d'echec reel
 * d'impression, le ticket reste `PRINTED` cote base. Pour une couverture complete, il faut
 * interroger [android.print.PrintJob.isCompleted] / `isFailed` depuis le cycle de vie de
 * l'activite et repasser le ticket en file via [com.poslik.pos.core.print.PrintWorker].
 */
class AndroidPrintGateway(
    activity: Activity,
    private val storeLabel: String,
) : PrintGateway {

    private val activityRef = WeakReference(activity)

    override suspend fun print(sale: Sale, attempt: Int) {
        withContext(Dispatchers.Main) {
            val activity = activityRef.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                throw PrintFailure("aucune activite pour imprimer le ticket ${sale.ticketNumber.value}")
            }

            val bytes = TicketRenderer(storeLabel, width = 44).render(sale).toByteArray(Charsets.UTF_8)
            val adapter = TicketDocumentAdapter(sale.ticketNumber.value, bytes)

            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .setResolution(PrintAttributes.Resolution("caisse", "caisse", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()

            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: throw PrintFailure("service d'impression indisponible sur cet appareil")

            try {
                val job = printManager.print("$storeLabel ${sale.ticketNumber.value}", adapter, attributes)
                if (job == null) {
                    throw PrintFailure("le systeme a refuse le ticket ${sale.ticketNumber.value}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: PrintFailure) {
                throw failure
            } catch (error: Exception) {
                throw PrintFailure(
                    "echec d'impression du ticket ${sale.ticketNumber.value}: ${error.message}",
                    error,
                )
            }
        }
    }

    private inner class TicketDocumentAdapter(
        private val ticketNumber: String,
        private val content: ByteArray,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$storeLabel $ticketNumber")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, oldAttributes == null || oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
                return
            }
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { stream ->
                    stream.write(content)
                    stream.flush()
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (error: Exception) {
                callback.onWriteFailed(error.message ?: "echec d'ecriture du ticket")
            }
        }
    }
}
