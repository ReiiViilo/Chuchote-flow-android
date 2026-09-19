# Relais, confidentialité et sécurité Android

> **Type** : référence de frontières de confiance
> **Statut** : comportement observé et divulgations alignées dans la candidate; preuve machine détaillée dans `BUILD_AND_VALIDATION.md`, recette réseau réelle ouverte
> **Base auditée** : `main@552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`
> **Candidate décrite** : tip de `codex/android-alpha`; vérifier son SHA à la reprise

## Permissions déclarées

Le manifeste demande :

- `RECORD_AUDIO`;
- `INTERNET`;
- `SYSTEM_ALERT_WINDOW`;
- `FOREGROUND_SERVICE`;
- `FOREGROUND_SERVICE_MICROPHONE`;
- `POST_NOTIFICATIONS`.

Référence : [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L5-L16).

Les permissions sensibles correspondent aux fonctions centrales : microphone, widget au-dessus des applications et service d'accessibilité pour l'insertion et l'observation de corrections.

## Relais facultatif

Le relais est désactivé par défaut. Il exige :

- une URL de base;
- un Bearer token;
- l'option activée.

Ces valeurs sont consommées comme une paire immuable. Un lien partagé complet
remplace URL et jeton atomiquement; un lien incomplet est rejeté. Une édition
manuelle de l'URL désactive le relais et efface le jeton précédent. Une requête
déjà commencée conserve son snapshot et ne peut donc pas combiner l'ancienne
URL avec le nouveau jeton, ou l'inverse.

Référence : [`RemoteTranscriptionSettings.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteTranscriptionSettings.kt#L5-L39).

Android envoie :

```http
POST {baseUrl}/api/transcribe
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

Contenu : WAV PCM mono 16 bits, `language=fr` et prompt de vocabulaire
facultatif — les seules entrées de vocabulaire du dictionnaire, jamais les
cibles de substitution (voir
[DICTIONARY_AND_LEARNING.md](DICTIONARY_AND_LEARNING.md)); aucun autre champ
(pas de `temperature`). Le corps est bâti par
[`TranscriptionRequestBody`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/TranscriptionRequestBody.kt),
testé sur la JVM. La réponse attendue est `{ "text": "..." }`. Le corps `2xx` est
borné à 262 144 caractères et `text` doit être une vraie chaîne JSON; un champ
absent, `null`, numérique, objet ou tableau est invalide et entraîne le repli
local. Références : [`RemoteTranscriber.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteTranscriber.kt) et [`RemoteResponseDecoder.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteResponseDecoder.kt).

Le contrat serveur, les variables d'environnement et les erreurs sont documentés dans la source canonique [RELAY_API.md](https://github.com/ReiiViilo/Chuchote-Flow/blob/ab0479f136bc3f6fc0d9dffc22ffa08a58fd4552/.docs/RELAY_API.md).

## Synchronisation vers le relais

Depuis le 15 septembre 2026, [`SyncPusher`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/SyncPusher.kt)
pousse vers le même relais, **même URL et même jeton** que la transcription,
deux familles de données, en JSON (`Content-Type: application/json`), construites
par [`SyncPayloads`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/SyncPayloads.kt)
et vérifiées sur la JVM — avec le `org.json` de référence (`testImplementation`), pas celui d'Android : les deux divergent sur des bords que ces tests n'exercent pas (`optString` d'un `null` explicite, ordre des clés, exceptions), la preuve appareil reste donc celle du plan de test alpha :

| Point d'entrée | Quand | Ce qui part |
|---|---|---|
| `POST /api/sync/dictations` | chaque dictée terminée, depuis `MainRecognitionService` | `device="android"`, identifiant local de la dictée, `created_at` ISO UTC, texte brut STT, texte final après substitutions, durée, source |
| `POST /api/sync/dictionary` | à l'ajout d'une entrée; à sa suppression (pierre tombale `deleted=true`, inscrite en file **avant** que la ligne soit effacée); au démarrage du store, les pierres tombales encore en file puis la liste entière si son empreinte SHA-256 — liée à l'adresse du relais, effacée par toute mutation du dictionnaire — n'est pas celle que **ce** relais a déjà acceptée : un relais nouvellement configuré reçoit donc tout le dictionnaire | `heard`, `replace_with`, `deleted` — par lots de 500 entrées au plus (plafond du relais), autant de lots que nécessaire, tous adressés au relais lu au départ de la republication; l'empreinte n'est retenue que si tous les lots sont passés et qu'aucune mutation n'a couru pendant les envois |

Frontières, identiques au relais de transcription : rien ne part sans relais
configuré **et** sans consentement courant, relu dans la coroutine d'envoi —
et une série d'envois (lots d'une republication, pierres tombales d'un rejeu)
s'arrête dès que le relais est coupé, son jeton tourné ou son adresse changée :
la requête en vol s'achève, aucune autre ne part vers l'ancien relais.
Tout est best-effort et hors du chemin critique : un échec (réseau, HTTP non
`2xx`) est journalisé (`Log.w`, tag `SyncPusher`, code de diagnostic expurgé,
jamais le contenu ni le message brut) puis oublié, la vérité locale reste
`chuchote.db`. Tout ce qui part vers `/api/sync/dictionary` — ajouts, pierres
tombales, republication — passe par un seul fil d'envoi, dans l'ordre où le
magasin l'a demandé ([`DictionarySyncCoordinator`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/DictionarySyncCoordinator.kt)),
pour qu'une republication encore en vol ne ressuscite jamais un mot supprimé
pendant qu'elle partait. **Une seule chose survit à la mort du processus** :
la pierre tombale d'une suppression, conservée dans `chuchote_sync` (exclu des
sauvegardes) tant que le relais ne l'a pas acceptée, rejouée à chaque démarrage
et à chaque nouvelle suppression — un rejeu n'envoie que les pierres tombales
inscrites avant sa demande —, retirée de la file si le mot est réappris
entre-temps. La file
n'est alimentée que si la synchronisation a déjà été configurée une fois sur
l'appareil (drapeau `sync_configured` dans `chuchote_sync` : un appareil sans
relais ne garde aucune trace de ses suppressions, mais un relais momentanément
éteint le temps d'une rotation de jeton n'en perd aucune), plafonnée à 200
paires — les plus anciennes cèdent la place — et une pierre tombale de plus de
30 jours n'est plus rejouée : le relais date chaque réception, et une
suppression si vieille effacerait un mot que le desktop a pu réapprendre depuis
(`PendingTombstones`). Un consentement retiré ne vide pas la file : rien ne
part tant qu'il est absent, et la péremption la borne (arbitrage d'Olivier du
16 septembre 2026). Le verrou de la file n'est jamais tenu pendant un appel
réseau; les pierres tombales acceptées sont retirées de la file courante, pas
remplacées par l'instantané du départ. Sans cela,
une suppression faite hors ligne laisserait le desktop réécrire le mot
indéfiniment. Les ajouts et les dictées, eux, ne sont pas rejoués : un ajout
manqué est rattrapé par la republication de démarrage, une dictée manquée est
perdue pour le cerveau commun (elle reste dans l'historique local). Il n'y a
**aucun tirage** depuis le relais : Android n'importe ni dictées ni mots venus
du desktop. Les deux points d'entrée sont idempotents côté serveur
(paire `(device, device_local_id)` pour les dictées, paire `(heard,
replace_with)` pour le dictionnaire), donc un renvoi ne crée pas de doublon.

Conséquence pour la vie privée : le **texte** des dictées quitte désormais
l'appareil même quand la transcription a été faite localement, dès que le
relais est configuré et le consentement donné. La politique du 15 septembre
2026 le divulgue et le consentement a été reversionné en conséquence (voir
« Consentement d'exécution »). Depuis le 19 septembre 2026, le dictionnaire
personnel entier est republié vers **chaque** adresse de relais nouvellement
configurée (l'empreinte est liée à l'adresse), et non plus une seule fois :
l'ensemble des destinataires s'élargit avec chaque relais saisi — aucune donnée
nouvelle ne sort, la divulgation du 15 septembre reste couvrante. Le contrat serveur et ses défauts connus sont
décrits dans le plan
[`PLAN_SYNC_TEMPS_REEL_ET_UI_COMMUNE.md`](https://github.com/ReiiViilo/Chuchote-Flow/blob/main/.docs/PLAN_SYNC_TEMPS_REEL_ET_UI_COMMUNE.md)
du dépôt desktop.

## Frontières de données

| Fonction | Données qui restent locales | Données qui peuvent quitter l'appareil |
|---|---|---|
| Whisper local | WAV privé, segment audio, résultat et dictionnaire | aucune donnée STT applicative |
| Relais activé | WAV privé et historique SQLite final | WAV du segment, langue, prompt de vocabulaire, jeton; texte des dictées terminées et entrées du dictionnaire (voir « Synchronisation vers le relais ») |
| Insertion widget | dictionnaire et proposition acceptée | texte inséré dans l'application cible choisie |
| Sauvegarde Android | WAV, base SQLite, historique, dictionnaire et configuration du relais explicitement exclus | préférences générales non secrètes potentiellement éligibles |

Le relais transmet à son tour l'audio et le prompt au fournisseur STT configuré. Le vocabulaire peut contenir des noms propres ou termes professionnels sensibles.

## Divulgations corrigées dans la candidate

### Politique audio

La politique distingue maintenant la transcription locale du relais facultatif. Elle indique que les segments audio, la langue et un prompt de vocabulaire sont envoyés au serveur configuré, puis possiblement à son fournisseur STT.

### README et permission Internet

Le README indique maintenant que le fork déclare `INTERNET` pour le relais facultatif et que ce mode transmet des données hors de l'appareil. Le mode local reste utilisable sans relais.

### Écran Historique

L'état vide de l'écran Historique explique désormais que l'audio de reprise reste privé, mais que les segments et le vocabulaire peuvent être envoyés si le relais est activé. Le commentaire de `ChuchoteStore` ne formule plus de promesse réseau générale.

### Service d'accessibilité

La description système et la politique indiquent désormais que le service peut
relire temporairement le même champ pendant environ 30 secondes afin de
détecter une correction. La candidate borne la mémoire conservée à une fenêtre
locale, exclut les mots de passe et purge l'observation lors d'un changement de
cible sensible. Une correction acceptée est écrite au dictionnaire local; seules les
entrées de vocabulaire de ce dictionnaire (sans cible de remplacement) entrent
ensuite dans le prompt envoyé au relais si celui-ci est activé — une
correction apprise n'y entre donc pas. La politique visible divulgue
maintenant cette conséquence.

Références : [`strings.xml`](../app/src/main/res/values/strings.xml#L40), [`accessibility_service_config.xml`](../app/src/main/res/xml/accessibility_service_config.xml) et [`TextInsertionAccessibilityService.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/overlay/TextInsertionAccessibilityService.kt#L139-L167).

Ces textes sont cohérents dans le diff courant, mais doivent encore être relus sur l'écran réel avant publication.

## Gestion du jeton

Le jeton est :

- conservé dans un `SharedPreferences` ordinaire;
- affiché dans un champ masqué;
- conservé dans un état Compose non sauvegardable, donc non recopié dans le
  `SavedState` de l'Activity;
- exclu de `backup_rules.xml` et des sections cloud/transfert de
  `data_extraction_rules.xml`;
- concaténé à l'URL sous la forme `baseUrl#token` par l'action de partage de configuration.

Références : [`RemoteTranscriptionSettings.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteTranscriptionSettings.kt#L12-L39) et [`SettingsStartScreen.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/ui/settings/SettingsStartScreen.kt#L250-L283).

L'interface avertit l'utilisateur avant le partage, mais cela reste une exposition volontaire d'un secret statique. Le secret reste en clair dans le bac à sable applicatif; les règles de sauvegarde empêchent sa restauration implicite sur un autre appareil, sans remplacer Android Keystore ni une rotation côté serveur.

## Consentement d'exécution

La candidate versionne la politique du 15 septembre 2026
(`ACCEPTED_PRIVACY_POLICY_AND_LICENSE_2026_09_15`), qui divulgue la
synchronisation du texte des dictées et du dictionnaire vers le relais. Une
acceptation de l'ancienne politique (23 août 2026) ne déverrouille rien
automatiquement : à la mise à jour, l'écran de consentement se représente. Le consentement
courant est relu avant le widget, l'IME, la capture, une reprise de WAV et une
requête au relais. Une garde centrale l'observe ensuite pendant toute la durée
de l'upload, tandis que le relais le relit encore immédiatement avant d'ouvrir
le corps HTTP. Une révocation ferme l'observation d'accessibilité, arrête les
services de widget/reconnaissance actifs, annule l'enfant d'upload et demande la
déconnexion de la `HttpURLConnection` engagée. Cette même garde couvre la
retranscription depuis l'historique. Les octets déjà transmis ne peuvent pas
être rappelés; l'essai sur réseau lent doit encore mesurer le délai réel de
déblocage. En cas d'erreur ou de délai de lecture du DataStore, la décision est
fermée par défaut.

Pour Whisper local, le consentement est relu après l'attente du mutex et entre
les segments. Une annulation déjà demandée empêche un appel JNI encore en file
de démarrer. En revanche, un `fullTranscribe` natif déjà entré n'est pas
préemptible; la révocation interdit les segments et effets suivants, mais ne
promet pas d'arrêter instantanément ce calcul local en cours.

## Protections présentes

- le relais est désactivé par défaut;
- toute erreur distante retombe sur le modèle local;
- le corps des erreurs HTTP du relais n'est plus écrit dans les journaux;
- les réponses `2xx` sont bornées et strictement typées, et les diagnostics de
  parsing/transport ne contiennent ni corps ni message d'exception;
- une annulation HTTP reste une annulation et ne déclenche pas un repli Whisper
  local après retrait du consentement;
- le service d'accessibilité et l'IME utilisent les permissions système dédiées;
- le widget n'est pas exporté;
- le service microphone du widget est créé via une activité visible non
  exportée, avec revalidation des permissions et du consentement avant le
  premier plan;
- le service de reconnaissance est protégé par la permission microphone;
- le WAV complet est persisté dans le stockage privé afin de permettre une reprise; sa suppression suit celle de la dictée.

Références : [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L39-L80) et [`RemoteTranscriptionSettings.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteTranscriptionSettings.kt#L17-L32).

## Risques et limites restants

- SQLite, préférences et jeton non chiffrés par l'application;
- WAV privés non chiffrés par l'application et politique de quota/rétention encore à définir;
- `allowBackup=true`; WAV, SQLite et configuration du relais sont exclus, mais
  les préférences générales non secrètes peuvent encore participer à une
  sauvegarde ou un transfert Android;
- URL de relais libre, sans contrat versionné ni pinning;
- jeton sans identité, portée, expiration ou rotation dans le protocole actuel;
- entrées de vocabulaire du dictionnaire transmises au fournisseur sous forme de prompt;
- texte des dictées et dictionnaire entier poussés au relais, sous le **même
  jeton** que la transcription : qui détient le jeton lit l'historique commun
  (voir la décision E du plan de synchronisation, un jeton distinct est
  recommandé);
- un seul consentement couvre audio, texte et vocabulaire, alors que le texte
  des dictées quitte désormais l'appareil même sans transcription distante;
- une dictée dont la poussée échoue (réseau absent au moment de la dictée)
  n'est jamais renvoyée : le cerveau commun peut manquer des dictées que
  l'historique local possède;
- la file de pierres tombales vit dans un `SharedPreferences` : un effacement
  des données de l'app la perd, et le desktop garde alors le mot jusqu'à ce
  qu'il soit de nouveau supprimé en ligne; même sort pour une pierre tombale
  périmée (30 jours) ou évincée par le plafond (200), et pour toute la file si
  elle devient illisible;
- le relais applique dernier-écrit-gagne : une pierre tombale rejouée en
  retard (moins de 30 jours) efface un mot que le desktop aurait réappris
  entre-temps, tant que le relais ne compare pas les dates (tranche 1 du plan);
- permission notifications déclarée, mais aucune demande runtime correspondante n'a été trouvée;
- aucune preuve réseau automatisée montrant exactement les données transmises;
- aucune preuve de limitation de débit fournie par ce dépôt client;
- aucune authentification utilisateur ou révocation d'appareil.

### Absence de garantie « exactement une fois »

Le client n'envoie ni identifiant de requête stable ni clé d'idempotence au
relais. Après un timeout, Android ne peut pas savoir si le serveur a reçu et
traité le segment. Le repli local évite de perdre la dictée, mais une répétition
ultérieure peut entraîner un second traitement distant. Le relais doit rester
facultatif pour l'alpha; une garantie forte exige un contrat serveur versionné,
des clés d'idempotence persistées et des accusés durables.

## Exigences avant synchronisation cloud

La poussée vers le relais (section « Synchronisation vers le relais ») en est
une première marche : elle passe par l'API du relais, sans secret Neon dans
l'APK, et documente ce qui part et pourquoi. Le reste de la liste ci-dessous
n'est **pas** couvert — en particulier le consentement distinct pour le texte
et le vocabulaire, et l'identité d'appareil — et doit l'être avant le tirage
bidirectionnel prévu au plan. Une future intégration Neon ou autre doit passer
par une API authentifiée et ne jamais embarquer de secret administrateur dans
l'APK. Elle devra aussi documenter :

- données envoyées et finalité;
- fournisseur, région et rétention;
- chiffrement en transit et au repos;
- identité et révocation d'appareil;
- export et suppression complète;
- comportement hors-ligne;
- télémétrie et journaux;
- consentement distinct pour audio, texte et vocabulaire.
