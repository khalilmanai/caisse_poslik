package com.poslik.pos.core.sync

/**
 * Point d'extension pour authentifier les appels a la Realtime Database.
 *
 * L'enonce ne comporte ni utilisateur ni compte : l'application utilise [NoAuth] et
 * l'acces est regi par `firebase/database.rules.json`. L'interface reste en place pour
 * un durcissement ulterieur (Firebase Authentication) sans toucher aux adaptateurs :
 * `RestRealtimeDbGateway` et `AndroidRealtimeDbGateway` l'acceptent deja.
 */
interface RealtimeDbAuth {
    suspend fun authorizationHeader(): String?
}

object NoAuth : RealtimeDbAuth {
    override suspend fun authorizationHeader(): String? = null
}