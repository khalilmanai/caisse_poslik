# Caisse POS — Kotlin + Firebase Realtime Database

> Mini caisse de point de vente : 8 produits en dur, panier, encaissement en un geste, ticket, historique. Fonctionne **identiquement avec ou sans réseau**. Deux caisses — console terminal (`app`) et tablette Android (`androidApp`, Jetpack Compose) — partagent le même cœur `core`, vérifié par **69 tests JVM**.

---

## Sommaire

- [Aperçu](#aperçu)
- [Architecture](#architecture)
- [Démarrage rapide](#démarrage-rapide)
  - [Console (JVM)](#console-jvm)
  - [Tablette Android](#tablette-android)
- [Tests](#tests)
- [Configuration Firebase](#configuration-firebase)
- [Fonctionnement](#fonctionnement)
  - [Un geste d'encaissement](#un-geste-dencaissement)
  - [Unicité des numéros](#unicité-des-numéros)
  - [Non-perte des tickets](#non-perte-des-tickets)
  - [Hors ligne et reconnexion](#hors-ligne-et-reconnexion)
- [Schéma de données](#schéma-de-données)
- [Sécurité et périmètre](#sécurité-et-périmètre)
- [Limites assumées](#limites-assumées)

---

## Aperçu

| Module | Rôle | Vérifié |
|---|---|---|
| `core` | Logique métier, SQLite, impression, synchronisation — **aucun import android/androidx** | ✅ 69 tests JVM |
| `app` | Caisse en console (`.\gradlew.bat :app:run`), sans aucune configuration requise | ✅ Testé à la main, en ligne et hors ligne |
| `androidApp` | Caisse tablette Compose, reçu affiché à l'écran (aucune imprimante) | ✅ Testé sur émulateur : vente → reçu → historique → synchro croisée |

Le projet se branche sur Firebase Realtime Database **sans compte ni SDK** : requêtes REST en `NoAuth`, intégrité portée par `firebase/database.rules.json`.

> **Instance de démonstration réelle :** `cashregister-24f69` — 7 ventes, 2 terminaux, round-trip console ↔ tablette vérifié.

---

## Architecture

`core` est du **Kotlin pur** derrière quatre interfaces :

| Interface | Rôle | Implémentation `app` | Implémentation `androidApp` |
|---|---|---|---|
| `LocalStore` | Persistance locale | sqlite-jdbc | SQLiteOpenHelper |
| `PrintGateway` | Impression | Fichier | Écran |
| `RealtimeDbGateway` | Synchronisation | REST | REST |
| `ConnectivityMonitor` | Détection réseau | Bascule manuelle | NetworkCallback |

Un seul chemin de synchronisation REST, **identique des deux côtés**.

---

## Démarrage rapide

**Prérequis :** JDK 17. Rien d'autre (Gradle est téléchargé par le wrapper).

### Console (JVM)

```powershell
# Mode local (aucune configuration requise)
.\gradlew.bat :app:run

# Avec Firebase (aucun compte, aucune connexion : REST + NoAuth)
$env:FIREBASE_DATABASE_URL = "https://cashregister-24f69-default-rtdb.firebaseio.com"
.\gradlew.bat :app:run

# Forcer le hors-ligne
.\gradlew.bat :app:run --args="--offline"

# Simuler une panne d'impression
.\gradlew.bat :app:run --args="--printer-fail-every 2"
```

**Commandes disponibles :**

| Touche | Action |
|---|---|
| `1`-`8` ou `+ESP 2` | Ajouter un produit |
| `-ESP` | Retirer un produit |
| `e` | Encaisser |
| `h` | Historique |
| `t` | Dernier ticket |
| `o` | Basculer réseau |
| `s` | Synchroniser |
| `q` | Quitter |

**Options disponibles :**

| Option | Effet |
|---|---|
| `FIREBASE_DATABASE_URL` | Active la synchronisation |
| `--offline` / `POS_FORCE_OFFLINE=1` | Force le hors-ligne |
| `--printer-fail-every N` | Simule une panne d'impression |

### Tablette Android

```powershell
.\gradlew.bat :androidApp:installDebug
adb shell am start -n com.poslik.pos.android/.CaisseActivity
```

**Documentation détaillée :**
- Firebase : [`docs/FIREBASE-SETUP.md`](docs/FIREBASE-SETUP.md)
- Android : [`docs/ANDROID.md`](docs/ANDROID.md)

---

## Tests

```powershell
.\gradlew.bat test          # 69 tests, aucun réseau ni matériel requis
```

| Fichier de test | Ce qu'il garantit |
|---|---|
| `CheckoutTest` | Un geste = numéro + vente durable + impression + panier vidé ; rend la main avant l'impression ; dense sans trou sur 100 ventes ; **survit à un crash** (processus tué puis base rouverte) ; un encaissement refusé ne brûle aucun numéro |
| `TicketNumberingTest` | Séquence dense, 2 terminaux hors ligne simultanés sans collision, probabilité de collision, format, réinstallation |
| `ConcurrencyTest` | **320 encaissements sur 8 threads** : 320 numéros uniques, séquence 1..320 sans trou ni doublon |
| `PrintOutboxTest` | `PENDING→PRINTING→PRINTED/FAILED`, backoff, reprise au démarrage, **jamais de réimpression d'un ticket imprimé**, `PRINTED` non régressible |
| `SyncEngineTest` | Hors ligne→en ligne sans perte ni doublon, 5 syncs successifs = 5 ventes, échec partiel, erreur réseau, conflit de numéro, réinstallation ; serveur **sans `.indexOn`** → repli automatique sur le nœud entier, le 400 n'est retenté qu'une fois |
| `OfflineScenarioTest` | Le scénario complet de l'énoncé, bout en bout |
| `RestRealtimeDbGatewayTest` | Vraies requêtes HTTP contre un serveur local : URL, en-têtes, `?orderBy/&startAt/&limitToFirst`, 401, réseau injoignable |
| `RealtimeQueryTest` | Encodage exact de la chaîne de requête : `orderBy=%22updatedAt%22&startAt=…&limitToFirst=…`, aucun guillemet brut ni `$` parasite |
| `SalesSchemaTest` | Schéma partagé cohérent, colonnes acceptées exactement, aller-retour d'un enregistrement, `null` préservé (pas relu comme `0`) |
| `TicketRendererTest` | Ticket 44 colonnes aligné, troncature, fichier UTF-8, échec d'impression |

---

## Configuration Firebase

- **Aucune authentification, aucun login, aucun SDK.** L'application parle REST en `NoAuth` ; l'accès est régi par `firebase/database.rules.json`. Aucun `google-services.json`, aucune clé embarquée.
- **Console (JVM)** : variable `FIREBASE_DATABASE_URL` ou `--firebase-url <url>`. Sans elle, l'application démarre en mode local : tout fonctionne, la file de synchronisation conserve les ventes, rien n'est perdu.
- **Android** : l'URL est compilée dans `BuildConfig` (`androidApp/build.gradle.kts`, projet réel `cashregister-24f69`), surchargeable par `-PFIREBASE_DATABASE_URL=<url>`.
- **Déployer règles et index** : `npm install -g firebase-tools && firebase login && firebase deploy --only database`.
- **Index** : les règles du dépôt imposent `.indexOn: ["updatedAt", "createdAt"]` sur `sales`. Si l'instance déployée ne l'a pas, le tirage reçoit un 400 `Index not defined` et **retombe automatiquement** sur le nœud entier (déduplication par id, tentative refusée mémorisée) : aucun tirage n'est perdu, seul le volume retéléchargé augmente.

---

## Fonctionnement

### Un geste d'encaissement

`PosService.checkout` fait **une seule transaction SQLite**, puis rend la main :

1. `nextSequence()` → numéro de ticket
2. Construction de la vente, `insert()` → **durable** (`synchronous=FULL`)
3. Retour immédiat, panier vidé par l'appelant
4. *Ensuite, en tâche de fond* : impression (`PrintWorker.pump`) et synchronisation (`requestSync`)

> Un test le vérifie : juste après `checkout`, la vente est en base en `PENDING` et l'imprimante n'a rien reçu. L'encaissement (~40 ms) ne dépend **jamais** du réseau.

### Unicité des numéros

**Un compteur monotone par terminal, namespacé par un identifiant terminal aléatoire.**

```
T-<6 hex du terminal>-<séquence sur 6 chiffres>      ex.  T-3F2A9C-000042
```

Le terminal tire un UUID au premier lancement, le persiste, et ne le change jamais. La séquence vient d'une ligne `meta` incrémentée **dans la transaction d'encaissement**.

**Pourquoi ça suffit :** la séquence ne peut se répéter que si le même terminal réémet un numéro déjà utilisé — impossible, car `nextSequence()` est mono-tonique et vit dans la même transaction atomique que l'insertion de la vente (verrou `BEGIN IMMEDIATE`, confirmé par le test à 320 threads). Deux terminaux distincts ont des préfixes distincts, donc leurs séquences ne se croisent pas.

**Défense en profondeur, du client vers le serveur :**

1. `UNIQUE` sur `ticket_number` en local → une collision ne peut pas s'écrire
2. `ticketIndex/{numéro} = {idVente}` écrit dans le **même `PATCH` atomique** que la vente
3. **Règle serveur `!data.exists()`** sur `ticketIndex` → un numéro déjà pris est rejeté ; l'`UPDATE` échoue donc en entier
4. En cas de rejet, la vente reste en file locale et passe en `conflit` **visible dans l'historique** — jamais écrasée en silence

### Non-perte des tickets

La vente est écrite en base **avant** toute action externe. Tout le reste est un travail dérivé, repris jusqu'à succès :

- **Impression** : machine à états `PENDING → PRINTING → PRINTED | FAILED`, backoff exponentiel, et **relance au démarrage** des seuls `PENDING`/`FAILED` (`recoverPrintQueueAtStartup`). Un `PRINTING` interrompu par un crash est remis en `PENDING`. Un `PRINTED` n'est **jamais** réimprimé.
- **Synchronisation** : file locale, `PATCH` idempotent (même `PATCH` écrit la même valeur → aucun doublon), backoff, et le pull reprend au curseur `updatedAt` sans jamais réimporter une vente connue.
- **Une vente n'est effacée de la file que lorsque le serveur l'a confirmée.**

### Hors ligne et reconnexion

| Situation | Comportement |
|---|---|
| **Sans réseau** | L'encaissement est **identique** : numéro attribué, vente durable, ticket imprimé, panier vidé, historique consultable. Aucun appel réseau n'est tenté (`SyncEngine` sort immédiatement). Les ventes s'empilent en file. |
| **Au retour du réseau** | `ConnectivityMonitor` déclenche une sync coalescée. `ticketIndex` + vente partent en un `PATCH` atomique, puis un pull reprend au curseur. Ventes importées des autres terminaux sans toucher au compteur local. |
| **Si le serveur refuse** | Backoff exponentiel, la vente reste en file, l'état `échec`/`conflit` s'affiche dans l'historique. Aucun doublon, aucune perte. |

---

## Schéma de données

### Local (`caisse.db`, SQLite) — la source de vérité du poste

```sql
meta(key, value)                    -- terminal_id, ticket_seq, namespace_length, sync_cursor

sales(
  id PK,
  ticket_number UNIQUE,
  terminal_id,
  total_minor,
  created_at,
  payment_method,
  cash_given_minor,
  change_minor,
  lines_json,
  print_state,
  print_attempts,
  next_print_attempt_at,
  last_print_error,
  printed_at,
  sync_state,
  sync_attempts,
  next_sync_attempt_at,
  last_sync_error,
  conflict_note
)

-- Index
-- (print_state, next_print_attempt_at)
-- (sync_state, next_sync_attempt_at)
```

### Firebase Realtime Database

```
/stores/{storeId}/sales/{pushId}
  ├── id, ticketNumber, terminalId, totalMinor, currency
  ├── lines[], createdAt, paymentMethod
  ├── printState, printUpdatedAt
  └── updatedAt (= ServerValue.TIMESTAMP)

/stores/{storeId}/ticketIndex/{numero}    → idDeVente      (immuable, unicité serveur)
/stores/{storeId}/namespaces/{prefixe}    → { terminalId } (immuable, arbitrage de préfixe)
/stores/{storeId}/terminals/{terminalId}  → terminalId, namespace, lastTicketSequence, lastSeenAt
```

> L'`id` est un **push id généré sur l'appareil**, donc la clé est stable d'un bout à l'autre : réécrire la même vente écrase la même clé, ce qui rend le `PATCH` naturellement idempotent.

---

## Sécurité et périmètre

- **Aucune authentification.** L'énoncé ne comporte ni utilisateur ni compte. L'accès est donc régi par `firebase/database.rules.json`, ce qui garantit l'**intégrité** (unicité des numéros, cohérence) mais **pas** la confidentialité : quiconque connaît l'URL peut lire et écrire. Pour une production, passer `.read` à `auth != null` — le code n'a pas à changer.
- **`google-services.json` n'est pas utilisé.** L'application parle REST à la Realtime Database ; le SDK Android Firebase n'apporte rien ici. Aucune clé n'est embarquée dans l'APK.
- **Aucune clé dans le dépôt.** `.gitignore` couvre `service-account*.json`, `google-services.json`, `*.db`, `local.properties` et `.firebaserc`.
- **Round-trip réel vérifié** (2026-10-01) contre l'instance `cashregister-24f69` : les ventes de la console apparaissent sur la tablette et inversement, `ticketIndex` et curseur cohérents, aucun crash. L'instance déployée n'ayant pas encore son `.indexOn`, le repli automatique sur le nœud entier prend le relais — comportement également prouvé par `SyncEngineTest`.

---

## Limites assumées

| Limite | Détail |
|---|---|
| **Séquence par terminal, pas globale** | Deux tablettes donnent `T-3F2A9C-000007` et `T-71B4C2-000007`. L'unicité est garantie, la numérotation globale ne l'est pas. Un compteur global exigerait d'être **en ligne** au moment de l'encaissement, ce qui casserait l'exigence offline. |
| **Deux tablettes hors ligne simultanées** | Chacune vend dans son propre préfixe, sans collision (test à 3 terminaux × 500 ventes). Si deux terminaux tiraient **le même** UUID, la réclamation de préfixe au premier sync échouerait et le client **élargit** son préfixe (6 → 8 → 10 hex) jusqu'à obtenir un préfixe libre. Probabilité Birthday sur 6 hex ≈ 2×10⁻⁴ sur 10 000 terminaux. |
| **Trou de séquence possible** | Après une réinstallation mal propre (base effacée, UUID restauré manuellement). Acceptable : l'unicité tient, la contiguïté non. Le compteur est réaligné sur la valeur serveur à chaque sync. |
| **Horloge non fiable** | La numérotation ne dépend d'aucune horloge (contrairement à un UUID v7 ou un timestamp). Seule la date d'affichage en dépend. |
