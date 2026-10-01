# Configuration Firebase

Ce document explique comment brancher ce projet sur **votre** projet Firebase. Aucun secret n'est
dans le dépôt, **aucun compte de service n'est nécessaire** : l'application parle REST en `NoAuth`
et l'accès est régi par les règles.

## 1. Créer le projet et la base

1. [console.firebase.google.com](https://console.firebase.google.com) → **Add project** (ex. `caisse-poslik`).
2. Menu **Build → Realtime Database → Create Database**.
3. Choisissez une région (ex. `europe-west1`). **Start in locked mode** : les règles ci-dessous protégeront l'accès, mais vous pouvez aussi partir en mode test et la resserrer ensuite.
4. Copiez l'URL affichée dans l'onglet *Data* :
   `https://VOTRE-PROJET-default-rtdb.europe-west1.firebasedatabase.app`

L'instance de démonstration du dépôt est `cashregister-24f69`
(`https://cashregister-24f69-default-rtdb.firebaseio.com`), déjà branchée dans l'application
Android via `BuildConfig`.

## 2. Brancher l'application (JVM / console)

```powershell
$env:FIREBASE_DATABASE_URL = "https://VOTRE-PROJET-default-rtdb.europe-west1.firebasedatabase.app"
.\gradlew.bat :app:run
```

Équivalent sans variable d'environnement :

```powershell
.\gradlew.bat :app:run --args="--firebase-url https://..."
```

Sans URL, l'application démarre en **mode local** : tout fonctionne (encaissement, impression,
historique), les ventes restent sur la poste et la file de synchronisation est conservée. Aucune
vente n'est perdue, elles partiront dès qu'une URL sera fournie.

Sur Android, l'URL est compilée dans `BuildConfig` (voir [`docs/ANDROID.md`](ANDROID.md)) et
surchargeable à la compilation par `-PFIREBASE_DATABASE_URL=<url>`.

### Vérifier le round-trip réel

```powershell
# 1. encaisser hors ligne
#    dans l'application : o  (bascule hors ligne), +ESP 2, e, 20, h
# 2. revenir en ligne :      o
# 3. dans l'onglet Data de la console Firebase :
#    /stores/VOTRE-STORE/sales         -> une entrée par vente
#    /stores/VOTRE-STORE/ticketIndex   -> numeroDeTicket -> idVente
#    /stores/VOTRE-STORE/terminals     -> le terminal et son dernier numéro
```

Ce round-trip a été vérifié sur `cashregister-24f69` : 7 ventes, 2 terminaux (console + tablette),
ventes croisées importées des deux côtés.

## 3. Déployer règles et index

Le CLI Firebase doit être authentifié **en tant que vous** : le projet ne contient aucun jeton.

```powershell
npm install -g firebase-tools
firebase login
firebase deploy --only database
```

Les règles du dépôt (`firebase/database.rules.json`) incluent
`".indexOn": ["updatedAt", "createdAt"]` sur `sales`, nécessaire aux tirages paginés
(`orderBy=updatedAt`).

> **Instance sans index.** Si l'instance déployée ne contient pas ce `.indexOn`, Firebase refuse la
> requête paginée avec un 400 `Index not defined` et le client **retombe automatiquement** sur le
> nœud `sales` entier (déduplication par id, tentative refusée mémorisée). Aucun tirage n'est perdu,
> seul le volume retelechargé augmente. C'est le comportement actuel de `cashregister-24f69`, qui
> fonctionne donc déjà sans redéploiement.

## 4. Ce que garantissent les règles

Aucune authentification : l'énoncé ne comporte ni utilisateur ni compte.

| Chemin | Règle | Effet |
|---|---|---|
| `/` | lecture et écriture refusées | base fermée par défaut |
| `stores/$storeId` | `.read: true` | historique lisible par les caisses du magasin |
| `stores/$storeId/meta` | `.write: true` | état partagé du magasin |
| `stores/$storeId/sales/$saleId` | écriture si `newData.id === $saleId` et `storeId` correct | une vente ne peut pas être déplacée sous une autre clé |
| `stores/$storeId/ticketIndex/$ticketNumber` | `.write: !data.exists()` | **unicité serveur** : le premier arrivé garde le numéro, toute autre écriture est rejetée |
| `stores/$storeId/namespaces/$namespace` | `.write: !data.exists()` | un préfixe de terminal ne peut être revendiqué qu'une fois |
| `stores/$storeId/terminals/$terminalId` | écriture si `terminalId` cohérent | chaque terminal décrit son propre état |

Il n'y a **pas** de `.write` global sur `stores/$storeId` : Firebase accorde l'accès dès qu'une
règle `.write` s'applique à un **parent**, si bien qu'un droit d'écriture large annulerait
l'immuabilité de `ticketIndex`. Chaque nœud sensible porte sa propre règle.

L'unicité des numéros ne repose donc pas sur la confiance envers le client : l'écriture d'un
`ticketIndex` déjà pris est refusée par le serveur, et l'`UPDATE` atomique qui porte la vente +
l'index échoue en entier. La vente reste alors en file locale et remonte en `conflit` dans
l'historique au lieu d'être écrasée en silence.

> **Sécurité.** Sans authentification, quiconque connaît l'URL peut lire et écrire la base.
> C'est acceptable pour une démonstration isolée, pas pour une caisse en production. Les règles
> fournies garantissent l'**intégrité** (unicité, cohérence) mais pas la **confidentialité**.
> En production, remplacez `.read: true` par `auth != null` et ajoutez Firebase Authentication
> côté client : le code n'a pas besoin de changer, l'interface `RealtimeDbAuth`
> (`authorizationHeader()`) existe précisément pour brancher un porteur de jeton — il suffit de
> fournir une implémentation à la place de `NoAuth`.

## 5. Tester sans réseau

```powershell
# Hors ligne force : aucune requete n'est tentee, tout reste local
$env:POS_FORCE_OFFLINE = "1"
.\gradlew.bat :app:run

# Panne d'impression simulee (une sur N tentatives) pour observer "echec" puis la reprise
.\gradlew.bat :app:run --args="--printer-fail-every 2"
```
