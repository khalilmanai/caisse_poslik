package com.poslik.pos.android.platform

import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.print.PrintGateway

/**
 * Aucune imprimante : le recu est affiche a l'ecran par l'interface (dialogue de
 * confirmation apres encaissement).
 *
 * Cette passerelle reste branchee sur [com.poslik.pos.core.print.PrintWorker] pour que
 * la machine a etats de la file d'impression (PENDING -> PRINTING -> PRINTED), la reprise
 * au demarrage et l'historique "imprimé" continuent de fonctionner exactement comme dans
 * `:core` — seul le passage a l'imprimante physique disparait.
 */
class ScreenReceiptGateway : PrintGateway {

    override suspend fun print(sale: Sale, attempt: Int) {
        // rien a faire : le recu est rendu par l'UI, aucun systeme d'impression n'est appele
    }
}
