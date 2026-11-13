# Kalendar

Une application de calendrier Android **non officielle** pour un compte [Infomaniak](https://www.infomaniak.com), avec deux widgets (agenda et mois). Elle lit et écrit tes calendriers directement par CalDAV : pas besoin de l'assistant de synchronisation ni des applications Infomaniak.

> Projet personnel, sans lien avec Infomaniak. Le code est sous ta responsabilité : fais une sauvegarde de ton agenda avant de l'essayer sur des données importantes.

## Fonctionnalités

- **Vues** : mois et agenda, plusieurs calendriers avec leurs couleurs, affichage ou masquage par calendrier.
- **Événements** : créer, modifier, supprimer ; journée entière, fuseaux horaires, lieu, notes, rappels.
- **Séries récurrentes** : modifier ou supprimer *cette occurrence*, *celle-ci et les suivantes*, ou *toute la série*.
- **Invitations** : voir l'organisateur et les réponses des invités, répondre (oui / peut-être / non), inviter des personnes par e-mail. Le serveur envoie les courriels d'invitation et de réponse.
- **Rappels** : notifications à l'heure voulue, même appareil en veille.
- **Widgets** : *Agenda* (les prochains événements, jour par jour) et *Mois* (grille du mois avec le nom des événements dans chaque case, navigation d'un mois à l'autre).
- **Hors ligne** : les événements déjà synchronisés restent visibles ; sans réseau, l'app l'indique et ne modifie rien.
- **Synchronisation** : toutes les 30 minutes, à l'ouverture, et à la demande. Les modifications sont refusées si l'événement a changé entre-temps sur le serveur (aucun écrasement silencieux).
- **Confidentialité** : le mot de passe est chiffré avec le Keystore Android ; l'app ne parle qu'à ton serveur CalDAV.

## Obtenir une app fonctionnelle

### 1. Un mot de passe d'application Infomaniak

L'app se connecte avec un identifiant de synchronisation et un mot de passe d'application (pas ton mot de passe de compte) :

1. Va sur <https://config.infomaniak.com> et ouvre l'assistant de synchronisation.
2. Choisis la synchronisation de l'agenda et génère un mot de passe d'application.
3. Note l'**identifiant** (de la forme `AB12345`) et le **mot de passe** affichés.

### 2. Compiler et installer

Prérequis : **JDK 17** et le **SDK Android** (variable `ANDROID_HOME`, ou `sdk.dir` dans `local.properties`).

```bash
git clone <url-du-depot> kalendar
cd kalendar
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou ouvre le dossier dans Android Studio et lance l'application sur un téléphone (Android 8.0 / API 26 minimum).

### 3. Se connecter

Dans l'app : serveur `https://sync.infomaniak.com`, identifiant et mot de passe d'application de l'étape 1. Les calendriers apparaissent après la première synchronisation.

### 4. Ajouter les widgets

Appui long sur l'écran d'accueil → *Widgets* → *Kalendar* → *Agenda* ou *Mois*.

## Version signée (optionnel)

Pour une version « release » signée, crée un fichier `keystore.properties` à la racine (il est ignoré par git) :

```properties
storeFile=kalendar-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

puis `./gradlew assembleRelease`. **Sauvegarde ta clé** : sans elle, impossible de publier une mise à jour installable par-dessus.

## Connexion avec le bouton Infomaniak (désactivée)

Le code contient une connexion OAuth, masquée par défaut : Infomaniak ne donne les droits nécessaires (`user_info`, `user_password`) qu'à ses propres applications. Si cela change, ajoute dans `local.properties` : `infomaniakClientId=...`, `infomaniakClientSecret=...` et `infomaniakOAuth=true`.

## Tests

```bash
./gradlew testDebugUnitTest
```

Les tests couvrent l'analyse et l'écriture iCalendar, les récurrences, le CRUD complet d'un événement (création, lecture, modification, suppression), l'édition d'une occurrence ou d'une série (*cette occurrence*, *les suivantes*), les invitations et réponses, et le client CalDAV face à un serveur simulé (ETag, conflits, authentification).
La couche Android (écrans, widgets, notifications, vérification du réseau) n'est pas couverte par des tests automatiques ; elle a été vérifiée à la main sur émulateur et sur un appareil.

## Structure

```
app/src/main/java/ch/nthnbch/kalendar/
  dav/      client CalDAV (OkHttp)
  ical/     analyse / écriture iCalendar, récurrences
  data/     base locale, synchronisation, alarmes, compte chiffré
  ui/       écrans (Jetpack Compose)
  widget/   widgets Glance (agenda, mois)
```
