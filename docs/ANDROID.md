# Application Android (Compose)

Module facultatif : l'énoncé demande « une app Kotlin », et la console (`../app`) couvre déjà tous
les critères avec 69 tests. Ce module existe pour qui veut une interface tablette.

## Prérequis

- Android Studio (Ladybug ou plus récent), SDK 35 installé
- JDK 17

## Lancer

```powershell
Copy-Item androidApp\local.properties.example androidApp\local.properties
# puis renseigner sdk.dir=/chemin/vers/Android/Sdk dans local.properties
```

Ouvrir le dossier racine du projet dans Android Studio, puis **Run ▶ `androidApp`**.
L'instance de démonstration (`cashregister-24f69`) est déjà compilée dans l'application : aucun
réglage n'est nécessaire. Si l'URL est vidée, l'application fonctionne en local et conserve la
file de synchronisation.

## Brancher Firebase

Aucune authentification : l'énoncé ne comporte ni utilisateur ni compte, l'application utilise
`NoAuth` et l'accès est régi par `firebase/database.rules.json`. L'URL de la base est compilée
dans `BuildConfig` :

```properties
# androidApp/build.gradle.kts -> defaultConfig
buildConfigField("String", "FIREBASE_DATABASE_URL", "\"https://cashregister-24f69-default-rtdb.firebaseio.com\"")
```

Pour surcharger sans toucher au code, passez la propriété Gradle :

```powershell
.\gradlew.bat :androidApp:assembleDebug -PFIREBASE_DATABASE_URL=https://autre-projet.firebaseio.com
```

Si l'URL est vide ou absente, `CaisseEnvironment` ne crée pas de `SyncEngine` : tout reste
local, ce qui est un repli sûr.

`google-services.json` n'est **pas** utilisé : l'application parle REST à la Realtime Database,
le SDK Android Firebase n'apporte rien ici. Aucune clé n'est donc embarquée dans l'APK.

## Ce qui vient de `:core` et ce qui est propre à Android

| Besoin | Console | Android |
|---|---|---|
| Stockage durable | `SqliteLocalStore` (sqlite-jdbc) | `AndroidLocalStore` (`SQLiteOpenHelper`) |
| Impression | `PrintSpoolGateway` (fichier) | `ScreenReceiptGateway` (aucune imprimante : reçu affiché à l'écran) |
| Connectivité | `SwitchableConnectivity` (commande `o`) | `AndroidConnectivityMonitor` (`NetworkCallback`) |
| Synchronisation | `SyncEngine` + `RestRealtimeDbGateway` | **identique** |

Les deux implémentations de stockage s'appuient sur `SalesSchema` (colonnes, DDL, index), donc
elles ne peuvent pas diverger : `SalesSchemaTest` compare la table physique au schéma partagé.

Toute la logique métier — numérotation, encaissement atomique, file d'impression, reprise au
démarrage, synchronisation idempotente — est dans `:core` et vérifiée par les tests JVM.

## État de vérification

**Compilé, installé et exercé sur émulateur** : `.\gradlew.bat :androidApp:installDebug`,
puis encaissement → dialogue « Reçu enregistré » avec le ticket complet → OK → caisse vide,
historique en `imprimé` / `synchronisé`, aucun crash dans `logcat`. Le cœur reste `:core`,
vérifié par 69 tests JVM.