# Caisse POS — Kotlin + Firebase Realtime Database

Mini caisse : 8 produits en dur, panier, encaissement en un geste, ticket, historique. Fonctionne
**identiquement avec ou sans réseau**. Deux caisses — console terminal (`app`) et tablette Android
(`androidApp`, Jetpack Compose) — partagent le même cœur `core`, vérifié par **69 tests JVM**.

## 1. Le projet

| Module | Rôle | Vérifié |
|---|---|---|
| `core` | logique métier, SQLite, impression, synchronisation — **aucun import android/androidx** | ✅ 69 tests JVM |
| `app` | caisse en console (`.\gradlew.bat :app:run`), sans aucune configuration requise | ✅ testé à la main, en ligne et hors ligne |
| `androidApp` | caisse tablette Compose, reçu affiché à l'écran (aucune imprimante) | ✅ testé sur émulateur : vente → reçu → historique → synchro croisée |

Le projet se branche sur Firebase Realtime Database **sans compte ni SDK** : requêtes REST en
`NoAuth`, intégrité portée par `firebase/database.rules.json`. Instance de démonstration
réelle : `cashregister-24f69` — 7 ventes, 2 terminaux, round-trip console ↔ tablette vérifié.

## 2. En une page

**Lancer / tester.** JDK 17 suffit (Gradle via le wrapper). Console locale : `.\gradlew.bat :app:run`
— `FIREBASE_DATABASE_URL` active la synchronisation, `--offline` / `POS_FORCE_OFFLINE=1` force le
hors-ligne, `--printer-fail-every N` simule une panne d'impression. Tablette :
`.\gradlew.bat :androidApp:installDebug`. Tests : `.\gradlew.bat test` = **69 tests**, sans
imprimante, sans réseau, sans Firebase.

**Architecture.** `core` est du Kotlin pur derrière quatre interfaces — `LocalStore`, `PrintGateway`,
`RealtimeDbGateway`, `ConnectivityMonitor` ; `app` et `androidApp` se bornent à les implémenter
(sqlite-jdbc / SQLiteOpenHelper, fichier / écran, bascule manuelle / NetworkCallback). Un seul chemin
de synchronisation REST, identique des deux côtés.

**Schéma.** Local : `sales` (`ticket_number` UNIQUE, états impression et synchro) et `meta`
(`terminal_id`, `ticket_seq`, `sync_cursor`). Serveur : `/stores/{storeId}/sales/{pushId}` — push id
généré sur l'appareil donc `PATCH` naturellement idempotent —, `ticketIndex/{numéro} → idVente`
(immuable), `namespaces/{préfixe}`, `terminals/{terminalId}`.

**Unicité et non-perte.** Numéro `T-<6 hex du terminal>-<séquence sur 6 chiffres>` ; la séquence est
incrémentée **dans la transaction d'encaissement** (`BEGIN IMMEDIATE`). Défense en profondeur : `UNIQUE`
en local, `ticketIndex` écrit dans le même `PATCH` que la vente, règle serveur `!data.exists()` → un
numéro pris est refusé, la vente reste en file et remonte en `conflit` visible — jamais d'écrasement
silencieux. La vente est durable **avant** toute action externe : outbox d'impression
`PENDING→PRINTING→PRINTED` (jamais réimprimée), file de synchronisation à backoff ; rien n'est
supprimé sans confirmation du serveur.

**Hors ligne / reconnexion.** L'encaissement est identique hors ligne : aucun appel réseau n'est tenté,
les ventes s'empilent. Au retour du réseau, une synchronisation coalescée pousse `ticketIndex` + vente
en un `PATCH` atomique puis reprend le tirage au curseur `updatedAt` — les ventes des autres terminaux
s'importent sans toucher au compteur local. Serveur injoignable : backoff, état visible dans
l'historique, aucun doublon, aucune perte.

## 3. Lancer

Prérequis : **JDK 17**. Rien d'autre (Gradle est téléchargé par le wrapper).

```powershell
# application console (mode local, aucune configuration requise)
.\gradlew.bat :app:run

# avec Firebase (aucun compte, aucune connexion : REST + NoAuth)
$env:FIREBASE_DATABASE_URL = "https://cashregister-24f69-default-rtdb.firebaseio.com"
.\gradlew.bat :app:run

# forcer le hors-ligne, simuler une panne d'impression
.\gradlew.bat :app:run --args="--offline"
.\gradlew.bat :app:run --args="--printer-fail-every 2"
```

Commandes : `1`-`8` ou `+ESP 2` ajouter · `-ESP` retirer · `e` encaisser · `h` historique · `t` dernier ticket · `o` basculer réseau · `s` synchroniser · `q` quitter.

Tablette Android :

```powershell
.\gradlew.bat :androidApp:installDebug
adb shell am start -n com.poslik.pos.android/.CaisseActivity
```

Configuration Firebase détaillée : [`docs/FIREBASE-SETUP.md`](docs/FIREBASE-SETUP.md) · Android : [`docs/ANDROID.md`](docs/ANDROID.md).

## 4. Tester

```powershell
.\gradlew.bat test          # 69 tests, aucun réseau ni matériel requis
```

| Fichier de test | Ce qu'il garantit |
|---|---|
| `CheckoutTest` | un geste = numéro + vente durable + impression + panier vidé ; rend la main avant l'impression ; dense sans trou sur 100 ventes ; **survit à un crash** (processus tué puis base rouverte) ; un encaissement refusé ne brûle aucun numéro |
| `TicketNumberingTest` | séquence dense, 2 terminaux hors ligne simultanés sans collision, probabilité de collision, format, réinstallation |
| `ConcurrencyTest` | **320 encaissements sur 8 threads** : 320 numéros uniques, séquence 1..320 sans trou ni doublon |
| `PrintOutboxTest` | PENDING→PRINTING→PRINTED/FAILED, backoff, reprise au démarrage, **jamais de réimpression d'un ticket imprimé**, `PRINTED` non régressible |
| `SyncEngineTest` | hors ligne→en ligne sans perte ni doublon, 5 syncs successifs = 5 ventes, échec partiel, erreur réseau, conflit de numéro, réinstallation ; serveur **sans `.indexOn`** → repli automatique sur le nœud entier, le 400 n'est retenté qu'une fois |
| `OfflineScenarioTest` | le scénario complet de l'énoncé, bout en bout |
| `RestRealtimeDbGatewayTest` | vraies requêtes HTTP contre un serveur local : URL, en-têtes, `?orderBy/&startAt/&limitToFirst`, 401, réseau injoignable |
| `RealtimeQueryTest` | encodage exact de la chaîne de requête : `orderBy=%22updatedAt%22&startAt=…&limitToFirst=…`, aucun guillemet brut ni `$` parasite |
| `SalesSchemaTest` | schéma partagé cohérent, colonnes acceptées exactement, aller-retour d'un enregistrement, `null` préservé (pas relu comme `0`) |
| `TicketRendererTest` | ticket 44 colonnes aligné, troncature, fichier UTF-8, échec d'impression |

## 5. Configuration Firebase

- **Aucune authentification, aucun login, aucun SDK.** L'application parle REST en `NoAuth` ;
  l'accès est régi par `firebase/database.rules.json`. Aucun `google-services.json`, aucune clé
  embarquée.
- **Console (JVM)** : variable `FIREBASE_DATABASE_URL` ou `--firebase-url <url>`. Sans elle,
  l'application démarre en mode local : tout fonctionne, la file de synchronisation conserve les
  ventes, rien n'est perdu.
- **Android** : l'URL est compilée dans `BuildConfig` (`androidApp/build.gradle.kts`, projet réel
  `cashregister-24f69`), surchargeable par `-PFIREBASE_DATABASE_URL=<url>`.
- **Déployer règles et index** : `npm install -g firebase-tools && firebase login && firebase deploy --only database`.
- **Index** : les règles du dépôt imposent `.indexOn: ["updatedAt", "createdAt"]` sur `sales`. Si
  l'instance déployée ne l'a pas, le tirage reçoit un 400 `Index not defined` et **retombe
  automatiquement** sur le nœud entier (déduplication par id, tentative refusée mémorisée) : aucun
  tirage n'est perdu, seul le volume retelechargé augmente.

## Un geste d'encaissement

`PosService.checkout` fait **une seule transaction SQLite**, puis rend la main :

1. `nextSequence()` → numéro de ticket
2. construction de la vente, `insert()` → **durable** (`synchronous=FULL`)
3. retour immédiat, panier vidé par l'appelant
4. *ensuite, en tâche de fond* : impression (`PrintWorker.pump`) et synchronisation (`requestSync`)

Un test le vérifie : juste après `checkout`, la vente est en base en `PENDING` et l'imprimante n'a rien reçu. L'encaissement (~40 ms) ne dépend **jamais** du réseau.

## Unicité des numéros — l'approche retenue

**Un compteur monotone par terminal, namespacé par un identifiant terminal aléatoire.**

```
T-<6 hex du terminal>-<séquence sur 6 chiffres>      ex.  T-3F2A9C-000042
```

Le terminal tire un UUID au premier lancement, le persiste, et ne le change jamais. La séquence vient d'une ligne `meta` incrémentée **dans la transaction d'encaissement**.

Pourquoi ça suffit : la séquence ne peut se répéter que si le même terminal réémet un numéro déjà utilisé — impossible, car `nextSequence()` est mono-tonique et vit dans la même transaction atomique que l'insertion de la vente (verrou `BEGIN IMMEDIATE`, confirmée par le test à 320 threads). Deux terminaux distincts ont des préfixes distincts, donc leurs séquences ne se croisent pas.

Défense en profondeur, du client vers le serveur :

1. `UNIQUE` sur `ticket_number` en local → une collision ne peut pas s'écrire
2. `ticketIndex/{numéro} = {idVente}` écrit dans le **même `PATCH` atomique** que la vente
3. **Règle serveur `!data.exists()`** sur `ticketIndex` → un numéro déjà pris est rejeté ; l'`UPDATE` échoue donc en entier
4. En cas de rejet, la vente reste en file locale et passe en `conflit` **visible dans l'historique** — jamais écrasée en silence

### Limites assumées

- **Séquence par terminal, pas globale.** Deux tablettes donnent `T-3F2A9C-000007` et `T-71B4C2-000007`. L'unicité est garantie, la numérotation globale ne l'est pas. Un compteur global exigerait d'être **en ligne** au moment de l'encaissement, ce qui casserait l'exigence offline.
- **Deux tablettes hors ligne en même temps** : chacune vend dans son propre préfixe, sans collision (test à 3 terminaux × 500 ventes). Si deux terminaux tiraient **le même** UUID, la réclamation de préfixe au premier sync échouerait et le client **élargit** son préfixe (6 → 8 → 10 hex) jusqu'à obtenir un préfixe libre. Probabilité Birthday sur 6 hex ≈ 2×10⁻⁴ sur 10 000 terminaux.
- **Trou de séquence possible** après une réinstallation mal propre (base effacée, UUID restauré manuellement). Acceptable : l'unicité tient, la contiguïté non. Le compteur est réaligné sur la valeur serveur à chaque sync.
- **Horloge non fiable** : la numérotation ne dépend d'aucune horloge (contrairement à un UUID v7 ou un timestamp). Seule la date d'affichage en dépend.

## Non-perte des tickets

La vente est écrite en base **avant** toute action externe. Tout le reste est un travail dérivé, repris jusqu'à succès :

- **Impression** : machine à états `PENDING → PRINTING → PRINTED | FAILED`, backoff exponentiel, et **relance au démarrage** des seuls `PENDING`/`FAILED` (`recoverPrintQueueAtStartup`). Un `PRINTING` interrompu par un crash est remis en `PENDING`. Un `PRINTED` n'est **jamais** réimprimé.
- **Synchronisation** : file locale, `PATCH` idempotent (même `PATCH` écrit la même valeur → aucun doublon), backoff, et le pull reprend au curseur `updatedAt` sans jamais réimporter une vente connue.
- **Une vente n'est effacée de la file que lorsque le serveur l'a confirmée.**

## Schéma de données

**Local** (`caisse.db`, SQLite) — la source de vérité du poste :

```
meta(key, value)                    terminal_id, ticket_seq, namespace_length, sync_cursor
sales(id PK, ticket_number UNIQUE, terminal_id, total_minor, created_at,
      payment_method, cash_given_minor, change_minor, lines_json,
      print_state, print_attempts, next_print_attempt_at, last_print_error, printed_at,
      sync_state, sync_attempts, next_sync_attempt_at, last_sync_error, conflict_note)
  index: (print_state, next_print_attempt_at)   index: (sync_state, next_sync_attempt_at)
```

**Firebase Realtime Database** :

```
/stores/{storeId}/sales/{pushId}       id, ticketNumber, terminalId, totalMinor, currency,
                                       lines[], createdAt, paymentMethod, printState,
                                       printUpdatedAt, updatedAt (= ServerValue.TIMESTAMP)
/stores/{storeId}/ticketIndex/{numero}  -> idDeVente            immuable (unicité serveur)
/stores/{storeId}/namespaces/{prefixe}  -> { terminalId }       immuable (arbitrage de préfixe)
/stores/{storeId}/terminals/{terminalId} terminalId, namespace, lastTicketSequence, lastSeenAt
```

L'`id` est un **push id généré sur l'appareil**, donc la clé est stable d'un bout à l'autre : réécrire la même vente écrase la même clé, ce qui rend le `PATCH` naturellement idempotent.

## Hors ligne et reconnexion

| | Comportement |
|---|---|
| Sans réseau | L'encaissement est **identique** : numéro attribué, vente durable, ticket imprimé, panier vidé, historique consultable. Aucun appel réseau n'est tenté (`SyncEngine` sort immédiatement). Les ventes s'empilent en file. |
| Au retour du réseau | `ConnectivityMonitor` déclenche une sync coalescée. `ticketIndex` + vente partent en un `PATCH` atomique, puis un pull reprend au curseur. Ventes importées des autres terminaux sans toucher au compteur local. |
| Si le serveur refuse | Backoff exponentiel, la vente reste en file, l'état `échec`/`conflit` s'affiche dans l'historique. Aucun doublon, aucune perte. |

## Sécurité et honnêteté du périmètre

- **Aucune authentification.** L'énoncé ne comporte ni utilisateur ni compte. L'accès est donc
  régi par `firebase/database.rules.json`, ce qui garantit l'**intégrité** (unicité des
  numéros, cohérence) mais **pas** la confidentialité : quiconque connaît l'URL peut lire et
  écrire. Pour une production, passer `.read` à `auth != null` — le code n'a pas à changer.
- **`google-services.json` n'est pas utilisé.** L'application parle REST à la Realtime
  Database ; le SDK Android Firebase n'apporte rien ici. Aucune clé n'est embarquée dans l'APK.
- **Aucune clé dans le dépôt.** `.gitignore` couvre `service-account*.json`,
  `google-services.json`, `*.db`, `local.properties` et `.firebaserc`.
- **Round-trip réel vérifié** (2026-10-01) contre l'instance `cashregister-24f69` : les ventes de
  la console apparaissent sur la tablette et inversement, `ticketIndex` et curseur cohérents, aucun
  crash. L'instance déployée n'ayant pas encore son `.indexOn`, le repli automatique sur le nœud
  entier prend le relais — comportement également prouvé par `SyncEngineTest`.
