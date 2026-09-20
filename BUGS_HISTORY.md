# Historique des correctifs

## 2026-08-23 — WAV historiques présents mais déclarés introuvables

### Symptôme observable

- Installer la candidate QA par-dessus une version qui écrivait ses dictées
  dans `files/dictations`.
- Ouvrir l'historique : les lignes concernées affichent « Audio introuvable »
  et le bouton de reprise reste désactivé, bien que les WAV existent toujours
  dans le stockage privé de l'application.
- Sur l'appareil diagnostiqué, environ 1,465 Go étaient présents sous
  `files/dictations`, tandis que le nouveau `no_backup/dictations` était vide.
  Les chemins SQLite absolus historiques étaient rejetés par le store et le
  gestionnaire de reprise.

### Surface et domaine

- Persistance SQLite v3, résolution des fichiers audio privés, historique et
  parcours « Réessayer ».
- Le défaut touchait les WAV hérités sous `filesDir/dictations`; les nouvelles
  captures sous `noBackupFilesDir/dictations` n'étaient pas la cause.

### Détection

- Retour d'Olivier indiquant que l'audio était sauvegardé sans pouvoir être
  relancé, puis inventaire ADB du stockage privé et comparaison avec les chemins
  `audio_path` de SQLite.
- La validation finale du correctif a été ramenée à des tests unitaires et à six
  scénarios Android instrumentés isolés. Leur compilation est acquise; leur
  exécution commune sur le dernier diff reste ouverte.

### Hypothèses examinées

1. **Les WAV avaient été supprimés pendant la mise à jour** — écartée par
   l'inventaire du dossier privé historique; aucune copie ou migration physique
   n'était nécessaire pour les rendre de nouveau adressables.
2. **Les WAV étaient tous corrompus** — insuffisant pour expliquer le défaut :
   le rejet se produisait à la validation du chemin, avant toute inspection de
   l'en-tête. La validité est maintenant vérifiée fichier par fichier en lecture
   seule avant toute réhabilitation.
3. **Le chemin historique sortait du stockage privé** — écartée pour les cas
   observés : ils pointaient sous le `filesDir` de la même application. Les
   chemins externes, les préfixes siblings, le traversal et les fichiers non-WAV
   restent refusés.

### Cause racine

- La nouvelle capture écrit sous `noBackupFilesDir/dictations`, mais les deux
  validateurs n'autorisaient plus que cette racine.
- Les lignes existantes conservaient légitimement un chemin absolu sous
  `filesDir/dictations`. Elles recevaient alors `audio_missing` ou
  `retry_audio_missing`; ces erreurs durables désactivaient ensuite la politique
  de reprise même si le fichier était toujours présent.

### Correctif

- Un résolveur unique autorise exactement deux racines privées bornées :
  `noBackupFilesDir/dictations` et `filesDir/dictations`.
- Les nouvelles lignes SQLite conservent le chemin absolu de la base v3 afin
  que l'APK de rollback puisse encore les relire. Le résolveur comprend aussi
  les références relatives typées, mais ce patch de compatibilité n'en écrit
  aucune; le contrat `audio_root + audio_key` est différé à une migration
  expand/contract dédiée.
- Au démarrage, seules les lignes marquées `audio_missing` ou
  `retry_audio_missing` sont réévaluées. Un WAV RIFF/WAVE mono 16 bits à 16 kHz, non
  vide et réellement privé fait effacer l'erreur et recalculer la durée; le
  transcript, l'état et le chemin sont conservés.
- Cette compatibilité n'effectue aucune copie, aucun déplacement, aucune
  récupération de `.part` et aucune mutation du WAV. Une migration physique
  éventuelle reste un chantier séparé.

### Test de non-régression

- `PrivateAudioPathResolverTest` couvre les deux racines, les chemins absolus
  historiques, la lecture de références relatives typées, le traversal, les préfixes
  siblings, les chemins relatifs ambigus, les sorties du stockage privé et les
  extensions non-WAV.
- `HistoricalAudioRehabilitationTest` couvre les deux seuls codes réparables,
  les WAV absents/corrompus/incompatibles, la frontière OOM et vérifie que les
  octets comme l'horodatage du WAV restent inchangés.
- Premier RED : le test ne compilait pas sans le résolveur. Contre-test RED :
  un chemin contenant `sub/../` restait accepté avant le rejet lexical.
- Sensibilité confirmée : retirer temporairement la racine `files/dictations`
  fait échouer le scénario historique; la restaurer le remet au vert.
- Le gate complet `testDebugUnitTest lintDebug compileQaAndroidTestKotlin
  assembleQa --no-daemon --no-build-cache` passe sans échec. Les six scénarios
  instrumentés existent et compilent. Le dernier rapport appareil durable
  couvre cinq scénarios d'une candidate alpha5 antérieure; l'exécution des six
  scénarios sur le dernier diff n'a pas encore été réalisée.

### Ce qui l'aurait attrapé plus tôt

- Un test de compatibilité entre l'ancienne racine `filesDir` et la nouvelle
  racine `noBackupFilesDir` avant publication.
- Des références persistées indépendantes du chemin absolu de l'installation,
  accompagnées d'un résolveur central plutôt que deux validateurs dupliqués.

## 2026-08-23 — Deuxième dictée refusée dans le même champ

### Symptôme observable

- Dans la variante QA, placer le curseur dans un champ ChatGPT ou Claude.
- Effectuer une première dictée : le texte est inséré correctement.
- Sans changer de champ, effectuer une deuxième dictée : le WAV et la
  transcription sont sauvegardés, mais le texte n'est pas livré au champ.

### Surface et domaine

- Widget de dictée, service d'accessibilité et identité de la cible entre le
  début de l'enregistrement et la livraison du résultat.
- La persistance audio et la transcription ne sont pas en cause dans ce
  scénario : elles terminent avant l'échec de livraison.

### Détection

- Retour de validation QA d'Olivier, puis réduction à un test unitaire de la
  politique de correspondance. Le téléphone n'était plus connecté pendant le
  correctif; la confirmation produit sur appareil reste donc ouverte.

### Cause racine établie et inconnue résiduelle

- Défaut établi dans le contrat : la candidate QA ne lisait pas `uniqueId`, la
  clé Android prévue pour reconnaître le remplacement d'un nœud. Elle exigeait
  donc la même identité de source Android même lorsqu'un `uniqueId` stable
  aurait pu prouver le même champ logique.
- Inconnue résiduelle : sans les traces de l'appareil, il n'est pas encore
  établi que ChatGPT ou Claude publiaient effectivement ce `uniqueId` lors du
  témoignage. Le correctif est donc une correction sûre et testée du contrat,
  mais sa correspondance exacte avec le cas appareil reste à confirmer.

### Hypothèses examinées

1. **Le champ logique est réexposé sous une nouvelle identité de source avec un
   `uniqueId` stable** — compatible avec le symptôme et désormais couverte,
   mais non confirmée sur l'appareil.
2. **La gate du widget ne revient pas à l'état libre** — écartée :
   `complete()` et `cancel()` réarment la tentative, et le test séquentiel
   existant accepte déjà une génération 2.
3. **La session de reconnaissance précédente bloque la suivante** — moins
   compatible avec le symptôme : ce chemin retourne « moteur occupé » avant de
   créer un nouveau WAV, alors que l'audio de la deuxième dictée a été observé.
4. **Le `SpeechRecognizer` terminal reste lié après le premier cycle** — défaut
   de code confirmé pendant la revue : plusieurs sorties succès/erreur
   réinitialisaient l'UI sans détruire l'instance Binder. Sa contribution exacte
   au témoignage appareil n'est pas prouvée, mais il est directement adjacent à
   la régression et pouvait laisser une ressource précédente jusqu'au prochain
   démarrage.

### Correctif

- La cible est maintenant liée au package et à une fenêtre valide, puis à la
  preuve la plus forte disponible : `uniqueId` d'accessibilité sur Android 13+
  lorsqu'il existe, sinon égalité de la source du nœud Android. Voir
  [`FocusedTargetMatcher.kt`](app/src/main/kotlin/dev/soupslurpr/transcribro/overlay/FocusedTargetMatcher.kt)
  et
  [`TextInsertionAccessibilityService.kt`](app/src/main/kotlin/dev/soupslurpr/transcribro/overlay/TextInsertionAccessibilityService.kt).
- Si une clé unique apparaît d'un seul côté ou change, la livraison échoue
  fermée; elle ne retombe jamais vers un `viewId` réutilisé.
- `viewIdResourceName` reste une métadonnée potentiellement réutilisée : il peut
  contredire une égalité de source, mais ne peut jamais autoriser à lui seul
  deux sources différentes. Le package et la fenêtre seuls ne suffisent jamais.
- Une transcription déjà terminée reste récupérable depuis l'historique par
  son texte. Son WAV peut aussi être retranscrit explicitement après une
  confirmation; les états actifs restent non relançables.
- Chaque terminal accepté de la génération widget courante tente maintenant
  `cancel()` et `destroy()` indépendamment avant de revenir à `IDLE`; un ancien
  callback est rejeté avant de toucher le recognizer successeur. L'IME confine
  aussi une exception de `stopListening()`, réarme sa gate et accepte la
  tentative suivante.

### Test de non-régression

- Un premier cycle RED a couvert la priorité de `uniqueId` : une clé identique
  survit à un changement de représentation, tandis que deux clés différentes
  ne retombent pas vers un `viewId` identique.
- La revue adversariale a ensuite rendu rouges deux contre-tests : un `viewId`
  seul ne peut autoriser une autre source, et une fenêtre indéfinie est refusée.
  Les deux passent après le durcissement.
- Les contre-tests refusent toujours un autre package, une autre fenêtre, un
  autre champ et une cible renouvelée sans clé stable. Voir
  [`FocusedTargetMatcherTest.kt`](app/src/test/kotlin/dev/soupslurpr/transcribro/overlay/FocusedTargetMatcherTest.kt).
- `RecognizerCommandBoundaryTest`, `WidgetRecognitionAttemptGateTest` et
  `ImeTranscriptionSessionTest` couvrent cleanup indépendant, callbacks de
  génération et deuxième tentative après échec Binder.
- Validation réelle encore requise sur la variante QA : deux dictées de suite
  dans le même champ, puis sélection et insertion au milieu d'une phrase dans
  ChatGPT, Claude et Gmail.

### Ce qui l'aurait attrapé plus tôt

- Un test de contrat fondé dès l'origine sur les garanties exactes Android
  (`uniqueId`, identité de source, non-unicité possible de `viewId`) aurait évité
  à la fois le faux refus et le premier correctif trop permissif.
- Un test instrumenté avec les arbres réels de ChatGPT et Claude manque encore;
  son ajout reste la condition pour détecter automatiquement les particularités
  propres à ces applications.

## 2026-08-23 — Longue dictée perdue après environ deux minutes

### Reproduction minimale

- Appareil observé : Samsung SM-S721W, Android 16 / API 36, version installée `9-debug`.
- Enregistrer une dictée longue, valider, puis attendre la transcription locale.
- Les journaux `logcat -b crash` contenaient plusieurs échecs reproductibles :
  `OutOfMemoryError` pendant l'accumulation/copie audio et
  `ConcurrentModificationException` pendant l'itération des travaux de
  transcription.
- Indépendamment du plantage, le widget appelait `SpeechRecognizer.cancel()`
  après exactement 120 secondes et affichait « dictée abandonnée ».

### Hypothèses examinées

1. **Pression mémoire et courses internes au pipeline audio** — confirmée.
   L'audio entier vivait dans des `MutableList<Short>` (objets encapsulés), des
   copies `slice().toList()` étaient créées, une coroutine était lancée par bloc
   micro et plusieurs coroutines partageaient les mêmes listes, le même VAD et
   le même contexte Whisper.
2. **Relais Neon ou réseau lent** — écartée comme cause principale : les traces
   fatales provenaient du pipeline local avant toute preuve d'une défaillance
   Neon; le délai fixe de 120 secondes annulait aussi une transcription encore
   saine.
3. **AudioRecord ou permission micro perdue** — écartée pour les plantages
   observés : AudioRecord fournissait bien des échantillons et les piles
   pointaient vers les copies/collections et ONNX.

### Instrumentation et cause racine

- `adb shell dumpsys`, `dumpsys activity exit-info` et `adb logcat -b crash`
  ont confirmé une limite de tas d'environ 256 Mio, plusieurs OOM dans le VAD
  ou les copies audio, puis deux modifications concurrentes de collections.
- Le minuteur destructif de 120 secondes a été localisé dans
  `FloatingWidgetService`.
- Cause racine : architecture non bornée et non sérialisée, aggravée par une
  annulation temporelle indépendante de l'état réel.

### Correctif

- WAV PCM écrit progressivement dans le stockage privé dès le début de la
  dictée, avec fichier `.part`, en-tête réparable et finalisation atomique.
- Base SQLite version 3 avec états `recording`, `queued`, `transcribing`,
  `retryable` et `completed`, chemin audio, durée, tentatives et segments.
- Segments limités à 30 secondes et transcrits strictement dans l'ordre, avec
  un seul contexte Whisper actif dans le processus.
- Toute interruption conserve le WAV; l'historique affiche « Réessayer ».
- Le délai de 120 secondes n'annule plus : il informe que la transcription
  continue et que l'audio est sauvegardé.

### Non-régression

- `RecoverableWavFileTest` : récupère un WAV dont le processus a disparu avant
  la finalisation et borne les lectures.
- `AudioSegmentPlannerTest` et `AudioSegmentCodecTest` : bornes, découpage et
  persistance des segments.
- `SequentialTranscriptionPipelineTest` : ordre stable et absence de
  transcription concurrente.
- `TranscriptionProgressEstimatorTest` : progression plus lente pour une longue
  dictée et vert final réservé au succès explicite.
- Sensibilité historique vérifiée : la première exécution des tests a échoué
  parce que les nouveaux composants n'existaient pas; une première candidate a
  ensuite passé 17 tests et Android Lint. Les garde-fous ajoutés après cette
  preuve ont porté la suite à 111 scénarios. Les contre-tests de cible, reprise,
  confidentialité, service microphone, relais et frontière native, puis les
  pressions du résolveur audio, portent le gel courant à 182 scénarios dans 38
  suites/fichiers; 1 scénario d'intégration symlink est ignoré faute de privilège
  Windows, mais la sortie canonique simulée est couverte sans skip et le gate
  courant est vert.
- `ChuchoteStoreMigrationTest` utilise pour chaque scénario un cache UUID, une
  base et deux racines audio jetables, distincts de `chuchote.db`. Il vérifie sur
  Android 16 la conservation de l'ancienne dictée pendant la migration v3, puis
  la réhabilitation minimale d'un WAV historique et l'écriture absolue d'un
  nouveau `audio_path`. Un troisième force un échec SQLite et vérifie que
  l'historique, le dictionnaire et la récupération des captures interrompues
  restent disponibles. Un quatrième place un WAV valide derrière 100 chemins
  invalides permanents, vérifie leurs diagnostics et prouve que le curseur
  SQLite revient ensuite vers un ID inférieur. Un cinquième prouve qu'un
  démarrage sans mutation ne relit pas tout l'historique. Un sixième force une
  récupération partielle et vérifie la projection comme la cause originale. Les
  6 tests instrumentés compilent; leur relance commune attend la reconnexion ADB.
- Validation produit encore requise sur l'appareil avec la variante QA :
  dictée supérieure à deux minutes, interruption simulée et bouton Réessayer.

## 2026-08-23 — Insertion directe remplacée silencieusement par le presse-papiers

### Reproduction minimale

- Le service apparaissait activé dans les réglages Android.
- Gmail recevait parfois le texte, tandis que ChatGPT et Claude tombaient sur
  le presse-papiers.
- `dumpsys accessibility` montrait Chuchote Flow dans les services plantés et
  non dans les services liés. Désactiver puis réactiver le service le reliait;
  l'utilisateur a ensuite confirmé que l'insertion fonctionnait dans ChatGPT.

### Hypothèses examinées

1. **Service affiché comme activé mais processus/service non lié** — confirmée.
2. **Conflit avec une autre application d'accessibilité** — écarté : le retrait
   de l'autre application ne changeait pas le symptôme.
3. **Champ ChatGPT non modifiable pour Accessibility** — secondaire seulement :
   UI Automator exposait un `android.widget.EditText` focalisé, et l'insertion
   fonctionnait dès que le service était réellement relié.

### Instrumentation et cause racine

- Comparaison de `settings secure enabled_accessibility_services` avec
  `dumpsys accessibility` pour distinguer « coché » de « lié ».
- Inspection UI Automator du compositeur ChatGPT : fenêtre hôte, classe
  `EditText`, focus actif, non mot de passe.
- Cause racine : les OOM/CME du moteur tuaient tout le processus, donc aussi le
  service d'accessibilité. La référence de service devenait nulle et le widget
  exécutait son repli presse-papiers sans expliquer l'état interrompu.

### Correctif

- Suppression des causes de plantage du processus décrites ci-dessus.
- Recherche du champ focalisé dans toutes les fenêtres interactives, avec prise
  en charge des nœuds Compose/WebView qui exposent `ACTION_SET_TEXT` ou
  `ACTION_PASTE` même sans `isEditable`.
- Repli `ACTION_SET_TEXT` respectant curseur et sélection.
- Résultat d'insertion typé : service interrompu, champ absent ou action
  refusée donnent désormais un message distinct. Si le service est coché mais
  non lié, l'app indique précisément de le désactiver/réactiver.
- L'écran des paramètres distingue maintenant « activé et lié » de « activé
  mais interrompu ».
- Le widget persistant se rappelle au focus d'un champ et conserve sa dernière
  position, sans tenter de démarrer clandestinement un service microphone
  depuis l'arrière-plan.

### Non-régression

- `TextInsertionComposerTest` couvre insertion au curseur, remplacement de la
  sélection et sélection invalide.
- Les exceptions de parcours Accessibility sont contenues pour ne pas tuer le
  processus.
- Sensibilité vérifiée : le comportement historique `existing + text` échoue
  aux cas curseur et sélection; le compositeur corrigé les fait passer.
- Validation manuelle encore requise sur la variante QA dans Gmail, ChatGPT et
  Claude, puis après arrêt/reliaison du service.

## 2026-08-23 — Durcissement de la candidate après revue indépendante

### Findings reproduits statiquement

- Une acceptation historique de la politique pouvait encore ouvrir certains
  chemins hors de `MainActivity`.
- Un job Android en annulation n'était plus `isActive`, alors que son `finally`
  utilisait encore le WAV, le VAD et le store; une seconde session pouvait donc
  commencer trop tôt.
- Des callbacks Binder tardifs pouvaient arriver après un changement de champ.
- `ACTION_SET_SELECTION` pouvait échouer après `ACTION_SET_TEXT` sans être
  vérifié, ce qui rendait le curseur annoncé incertain.
- L'IME committait des résultats partiels que le moteur pouvait ensuite réviser.
- L'observation d'une correction à la fin exacte du champ ne possédait aucune
  ancre droite et pouvait absorber le texte tapé ensuite.
- Le relais journalisait auparavant un corps d'erreur potentiellement sensible
  et ne possède aucun contrat d'idempotence.
- Une réponse relais `2xx` mal formée pouvait faire entrer son corps dans le
  message d'une exception JSON journalisée; `optString` acceptait aussi un
  nombre ou objet hors contrat en le convertissant en texte.
- Le contexte Whisper utilisait un `CoroutineScope` doté de son propre `Job` :
  un appel JNI encore en file pouvait commencer après l'annulation du
  propriétaire. La création native pouvait aussi perdre son résultat au
  handoff sans propriétaire explicite.
- Les règles de sauvegarde excluaient les WAV et le jeton, mais pas la base qui
  contient historique et dictionnaire.
- Un upload bloquant pouvait continuer après l'annulation, un terminal public
  pouvait rejouer son `PendingIntent` et le widget pouvait livrer le résultat au
  champ focalisé à la fin plutôt qu'au champ de départ.
- Un final IME inséré au milieu de deux mots pouvait fusionner les fragments;
  un curseur scindant une paire UTF-16 pouvait aussi corrompre le champ.

### Correctifs de la candidate courante

- Consentement versionné, fermé par défaut et relu à toutes les frontières :
  accessibilité, lanceur, widget, IME, capture, reprise et relais. Une révocation
  purge l'observation et arrête les services actifs.
- Une nouvelle session est refusée jusqu'à `Job.isCompleted`.
- Les callbacks qui transportent un UUID de session sont validés contre la
  session active. Pour les callbacks Android sans UUID, le widget, l'IME et le
  parcours public utilisent aussi une génération monotone et une instance de
  `SpeechRecognizer` distincte; l'IME ajoute une génération du champ actif.
- L'insertion relit le texte et le curseur. Si seul le texte est confirmé, elle
  avertit sans retenter, afin d'éviter une duplication. Les bornes apprises
  excluent les espaces synthétiques et les spans parcelables survivants sont
  remaniés avant toute mutation; un état riche non prouvable échoue fermé.
- L'IME ne modifie plus le champ sur les partiels; un seul commit final remplace
  la sélection éventuelle.
- L'apprentissage automatique est désactivé à la fin exacte du champ tant qu'une
  ancre sûre n'existe pas.
- Le presse-papiers de repli est marqué sensible lorsque possible et le corps
  des erreurs HTTP n'est plus journalisé. Les réponses réussies sont bornées,
  exigent une vraie chaîne `text` et ne produisent que des codes diagnostiques
  expurgés sur erreur.
- Le widget capture le champ avant `startListening()` et refuse toute livraison,
  y compris le presse-papiers, si cette cible change ou ne peut plus être
  prouvée. Les champs riches sont laissés intacts par `ACTION_SET_TEXT`.
- Le widget et l'IME utilisent une génération et un `SpeechRecognizer` distinct
  par tentative; l'IME ajoute les frontières Unicode nécessaires, refuse une
  frontière UTF-16 invalide et n'auto-envoie qu'après un commit confirmé dans
  le même éditeur.
- L'annulation déconnecte la connexion HTTP active et remonte sans repli local,
  y compris pour une reprise depuis l'historique. Le parcours public isole
  chaque tentative par génération et recognizer, valide l'Intent exporté, puis
  choisit un seul canal de transcript. Le consentement est relu immédiatement
  avant cet effet; un `PendingIntent` en échec ne retombe jamais vers le résultat
  Activity.
- Le bearer token du relais est masqué dans les paramètres, n'est plus recopié
  dans le `SavedState` Compose et est exclu des sauvegardes cloud comme des
  transferts Android. Le domaine SQLite complet — historique et dictionnaire —
  est maintenant exclu des sauvegardes cloud et transferts Android.
- Le moteur Whisper conserve le `Job` appelant sur son dispatcher, revalide
  l'annulation immédiatement avant JNI et libère toute création native non
  publiée. Seul le nettoyage est non annulable; un JNI déjà entré demeure
  bloquant jusqu'à son retour et reste un scénario de QA longue durée.
- Android 14+ démarre le service microphone par une activité visible non
  exportée qui revalide consentement, micro et superposition; le service refait
  ces contrôles avant `startForeground()`.

### État de preuve

- Les fichiers Kotlin/XML passent les contrôles statiques et `git diff --check`.
- La suite exécutée contient 182 scénarios unitaires dans 38 suites/fichiers :
  181 réussissent et 1 scénario d'intégration symlink est ignoré faute de
  privilège Windows. Le cas canonique équivalent reste obligatoire et passe.
- Six scénarios instrumentés SQLite/audio sont définis et compilés avec un cache
  UUID nettoyé; leur relance commune sur le Samsung attend la reconnexion ADB et
  ils ne prennent jamais les données QA comme fixture.
- `testDebugUnitTest`, `lintDebug`, `compileQaAndroidTestKotlin` et `assembleQa`
  passent sur le diff courant; `connectedQaAndroidTest` reste à relancer.
  La matrice appareil reste à exécuter.
- Le relais reste opt-in : sans clé d'idempotence serveur, aucune garantie
  « exactement une fois » n'est revendiquée.

## 2026-08-24 — « Transcription échouée » aléatoire malgré un audio sain

### Symptôme observable

- Dicter depuis l'orbe, n'importe quelle application cible.
- Environ une dictée sur deux se termine par « la transcription a échoué;
  l'audio est dans l'historique », sans lien avec la durée : une dictée de
  16,9 s échoue pendant qu'une de 2,2 s réussit.
- « Réessayer » ne récupère jamais ces dictées.

### Surface et domaine

- Boucle de capture de `MainRecognitionService.recordAndTranscribe` et
  validation d'entrée du VAD Silero. Aucune application cible n'est en cause.

### Détection

- Séance appareil du 24 août 2026 (SM-S721W) : trace `logcat` montrant
  `IllegalArgumentException: Input audio is too short` levée par
  `SileroVadOnnxModel.validateInput`, puis analyse des 18 WAV conservés.
- Le WAV de 16,9 s rejeté contenait 92,7 % d'échantillons non silencieux avec
  un pic à 83,8 % de la pleine échelle : l'audio était complet et sain.

### Hypothèses examinées

1. **Micro coupé par Android hors focus** — réfutée par l'analyse d'amplitude
   des WAV conservés.
2. **Dictée trop courte pour le VAD** — réfutée : l'échec frappait des dictées
   longues et épargnait des dictées courtes.
3. **Bloc partiel d'`AudioRecord.read` sous la fenêtre minimale du VAD** —
   confirmée : sur 18 dictées, les 8 échecs étaient exactement celles dont le
   compte d'échantillons laissait un reliquat sous-fenêtre; aucune dictée à
   reliquat nul n'a échoué.

### Cause racine

`AudioRecord.read` peut retourner un bloc plus court que le tampon. Ce bloc
partiel était transmis tel quel au VAD, qui refuse toute fenêtre de moins de
512 échantillons à 16 kHz; l'exception faisait échouer la dictée entière après
la parole, alors que le WAV écrit en parallèle était complet.

### Correctif

`VadWindowBuffer` (`recognitionservice/audio/`) retient les blocs partiels et
ne restitue au détecteur que des fenêtres d'au moins 512 échantillons. Le
compteur du VAD reste aligné sur le WAV, au retard près des échantillons en
attente, résorbé à la fenêtre suivante. Commit `4667e18`.

### Test de non-régression

`VadWindowBufferTest` : sept scénarios JVM purs, dont les reliquats exacts
observés sur l'appareil (64, 128, 192, 320, 448) et la conservation intégrale
des échantillons émis ou en attente.

### Ce qui l'aurait attrapé plus tôt

- Un test de la boucle de capture avec des tailles de lecture adverses plutôt
  que des multiples exacts du tampon.
- Journaliser la taille du bloc au moment de l'exception aurait fait le lien
  avec `AudioRecord.read` dès la première occurrence.

## 2026-08-24 — Repli presse-papiers systématique dans ChatGPT et Claude

### Symptôme observable

- Dicter vers un champ ChatGPT ou Claude : le texte finit dans le
  presse-papiers au lieu de s'insérer, alors que Gmail s'insère directement.
- Le toast annonçait parfois « Insertion impossible » alors que le texte était
  bel et bien copié.

### Surface et domaine

- `TextInsertionAccessibilityService.insertIntoFocusedField` (lecture de
  l'état du champ) et vérification de copie de `FloatingWidgetService.deliver`.

### Détection

- Séance appareil du 24 août 2026 : la trace montrait `targetMatch=true` —
  la cible était trouvée et validée — suivie d'un repli, et
  `ClipboardService: Denying clipboard access … not in focus` lors de la
  relecture de vérification.

### Cause racine

Deux défauts distincts. Les champs web et React Native exposent leur composer
vide comme un placeholder (`isShowingHintText`) avec une sélection absente
`(-1, -1)`; cet état était classé `UNSAFE_FIELD_STATE` et l'insertion refusée
alors que le champ était vide et sûr. Ensuite, la vérification de copie
exigeait la relecture du presse-papiers, qu'Android refuse à une application
hors focus même quand l'écriture a réussi — d'où le faux « Insertion
impossible ».

### Correctif

`EditableFieldState.read` normalise l'état du champ : un placeholder n'est pas
du contenu; texte nul ou vide sans sélection vaut champ vide; texte présent
avec curseur inconnu insère en fin de champ sans rien supprimer, la
vérification post-`ACTION_SET_TEXT` signalant toute divergence. La copie est
considérée réussie dès que `setPrimaryClip` n'échoue pas. Chaque refus
d'insertion est désormais journalisé avec sa raison — longueurs et indicateurs
seulement, jamais le contenu. Commit `2b03030`.

### Test de non-régression

`EditableFieldStateTest` : six scénarios JVM purs couvrant placeholder, texte
nul, curseur inconnu, sélection valide, sélection hors bornes et champ vide.

### Ce qui l'aurait attrapé plus tôt

- Reproduire la matrice d'insertion sur un champ web/React Native réel plutôt
  que sur les seuls champs natifs.
- Distinguer, dans les toasts et les journaux, « champ illisible » de « action
  refusée » : le diagnostic est resté ouvert un jour de plus faute de savoir
  laquelle des deux branches échouait.

## 2026-09-15 — « up » inséré là où rien de tel n'a été dit

### Symptôme observable

- Après avoir appris une correction comme « hop hop » → « OpOp », des dictées
  sans le moindre rapport voient apparaître « up » (ou une forme voisine) à
  des endroits où cela n'a aucun sens.
- Olivier attribue l'effet à l'autocorrecteur : « comme s'il y avait une trop
  grande sensibilité ou une incompréhension de certaine diction ».

### Surface et domaine

- Dictionnaire personnel : substitution après transcription
  (`ChuchoteStore.appliquerCorrections`) et vocabulaire soufflé au relais
  (`ChuchoteStore.motsPourBiais`, `RemoteTranscriber`).

### Détection

- Retour d'Olivier du 15 septembre 2026, sans journal appareil ni contenu du
  dictionnaire. Le diagnostic ci-dessous est donc une lecture du code, pas une
  reproduction : la confirmation sur téléphone reste à faire (voir plus bas).

### Cause racine (hypothèse principale, à confirmer)

Deux mécanismes se cumulaient, et chacun suffit à produire le symptôme :

1. **Le vocabulaire soufflé recopié.** `motsPourBiais()` envoyait au relais,
   pour chaque entrée de substitution, sa *cible* (« OpOp »), en énumération
   nue. Whisper reproduit volontiers le vocabulaire soufflé sur un segment
   court ou hésitant — et la transcription au fil de l'eau découpe justement
   la parole en segments courts. Un souffle, une hésitation, un « hop »
   ordinaire deviennent « OpOp », « Op op », « up ».
2. **Une forme entendue trop courte.** Rien n'empêchait une entrée manuelle
   comme « op » → « OpOp » : avec une regex insensible à la casse, le
   moindre « op » isolé était réécrit. Le seuil de trois caractères
   n'existait qu'à l'apprentissage, pas à l'application.

### Correctif

- `DictionnaireSubstitution` (Kotlin pur) reprend la substitution et le
  vocabulaire : les cibles de substitution ne sont plus soufflées au modèle —
  la substitution les garantit déjà —, seules les entrées de vocabulaire le
  sont, en liste séparée par des virgules, sans préfixe; une forme entendue
  dont le cœur (mot débarrassé de la ponctuation qui le borde, comme
  `CorrectionDiff` le mesure) fait moins de trois caractères ne déclenche
  jamais de substitution, et l'écran Dictionnaire la signale « inactive »;
  chaque entrée qui modifie un texte est journalisée (`Log.d`, tag
  `ChuchoteDictionnaire`, **identifiant** de l'entrée et nombre d'occurrences
  seulement — jamais son texte ni le texte dicté); les regex sont compilées
  une fois par liste.
- `RemoteTranscriber` n'envoie plus `temperature` au relais : `0` était déjà
  la valeur par défaut du fournisseur, et n'y rend pas le décodage
  déterministe (il remonte la température de lui-même sur échec). L'envoyer
  ne changeait rien; le corps multipart vit désormais dans
  `TranscriptionRequestBody`, testé sur la JVM.
- Effet attendu sur « OpOp » : le modèle produit désormais ce qu'il entend
  (« hop hop », « op op »…) et la substitution corrige ce qu'elle connaît. Si
  une variante entendue manque, l'ajouter comme entrée de substitution; qui
  veut orienter le modèle vers un mot l'ajoute comme entrée de vocabulaire, en
  connaissance de cause.

### Test de non-régression

`DictionnaireSubstitutionTest` : onze scénarios JVM purs — frontières de
mots, chevauchement (« chop hop hop » → « chop OpOp ») et reprise après une
lettre multi-octets (« éhop hop hop »), capitale initiale,
occurrences multiples et accents (mêmes vecteurs que `dictionary.rs` desktop),
entrées vides, forme trop courte ignorée, mesure du cœur, journal des entrées
appliquées, réutilisation du cache, cibles exclues du vocabulaire, liste de
vocabulaire seule, borne sans coupure de mot.
`TranscriptionRequestBodyTest` : trois scénarios — vocabulaire dans `prompt`
en UTF-8, absence de `prompt` et de `temperature` sans vocabulaire, audio
transmis tel quel.

### Ce qui reste à prouver sur l'appareil

- Ouvrir l'écran Dictionnaire et repérer toute entrée dont la forme entendue
  est courte ou dont la cible est « up » : elle confirmerait le mécanisme 2.
- Dicter quelques phrases ordinaires avec le relais activé et lire logcat
  (`adb logcat -s ChuchoteDictionnaire`) : une ligne « Substitution #<id> »
  à chaque « up » confirme le mécanisme 2 (l'identifiant se retrouve dans
  `chuchote.db`); aucune ligne alors que « up » apparaît confirme le
  mécanisme 1 (le mot vient du modèle, pas du dictionnaire).

### Ce qui l'aurait attrapé plus tôt

- Un journal des substitutions appliquées : sans lui, impossible de
  distinguer « le modèle l'a dit » de « le dictionnaire l'a réécrit ».
- Un test qui envoie au modèle une phrase sans rapport avec le vocabulaire
  soufflé et vérifie qu'aucun mot du vocabulaire n'apparaît dans la sortie.

## 2026-09-15 — Une suppression du dictionnaire faite hors ligne n'atteignait jamais le relais

### Symptôme observable

Sur la même branche, avant tout appareil : une entrée supprimée dans l'écran
Dictionnaire pendant que le relais est injoignable reste appliquée par le
desktop indéfiniment. La republication de démarrage ne porte que des entrées
vivantes, donc rien ne vient jamais dire au relais que celle-ci est morte.

### Surface et domaine

`SyncPusher` (`remote`), file `pending_tombstones` dans le
`SharedPreferences` `chuchote_sync`.

### Détection

Revue `code-reviewer` de la branche (ronde 2, P1), puis ronde 3 (N2) sur le
correctif lui-même : la file n'avait ni condition d'entrée, ni plafond, ni
péremption — un appareil sans relais accumulait les mots supprimés, et une
pierre tombale d'il y a un mois pouvait effacer un mot réappris entre-temps
sur le desktop.

### Correctif

- La pierre tombale est mise en file **avant** l'envoi, dans `chuchote_sync`
  (exclu des sauvegardes), et rejouée à chaque suppression et au démarrage,
  jusqu'à ce que le relais l'ait acceptée; un mot réappris retire la sienne.
- La file n'est alimentée que si un relais est configuré; elle est bornée à
  200 paires (les plus anciennes cèdent la place) et une pierre tombale de
  plus de 30 jours n'est plus rejouée (`PendingTombstones`, Kotlin pur).
- Une file illisible est journalisée une fois puis abandonnée.
- Tranché par Olivier le 16 septembre 2026 : le verrou de la file n'est plus
  tenu pendant les envois, les pierres tombales acceptées sont retirées de la
  file courante (à l'identité paire + date), les rejeux sont sérialisés entre
  eux; un drapeau collant `sync_configured` alimente la file même relais
  momentanément éteint (rotation de jeton); un consentement retiré ne vide pas
  la file. Reste : le relais date lui-même chaque réception — un rejeu tardif
  reste dernier-écrit-gagne jusqu'à la tranche 1 du plan.

### Test de non-régression

`PendingTombstonesTest` : six scénarios JVM purs — aller-retour avec date,
file absente ou illisible, éléments muets ou en double, plafond et
remplacement d'un doublon, rejeu qui écarte vivantes et périmées et ne garde
que ce qui n'est pas parti, retrait des envoyées sur la file courante à
l'identité paire + date. `SyncPayloadsTest` : sept scénarios — champs
exigés par le relais, facultatifs en `null`, ajout et pierre tombale
symétriques, lots de 500, empreinte insensible à l'ordre, empreinte qui
distingue la frontière entendu/remplacement, JSON final.
`BackupPolicyContractTest` exige désormais l'exclusion de `chuchote_sync.xml`
des trois règles de sauvegarde.

### Ce qui reste à prouver sur l'appareil

Les étapes « Synchronisation vers le relais » du plan de test alpha (§6),
dont la suppression hors ligne puis relance et le relais non configuré.

### Ce qui l'aurait attrapé plus tôt

- Un test de politique de rejeu séparé du transport, dès la première version
  de la file : les trois manques de la ronde 3 étaient tous dans du code que
  rien n'exerçait sur la JVM.

## 2026-09-19 — Trois croisements d'envois pouvaient ressusciter un mot supprimé ou ne jamais republier le dictionnaire

### Symptôme observable

- Aucun retour d'appareil : trois défauts établis par lecture du code lors de
  la revue externe (Codex, 19 septembre 2026, `--base cf38768`), verdict
  « needs-attention — Do not ship: dictionary sync can resurrect deleted
  entries and permanently skip required uploads. »
- Attendus sur l'appareil s'ils s'étaient produits : un mot supprimé pendant
  la republication de démarrage qui continue d'être corrigé sur le desktop;
  un mot réappris hors ligne qui n'atteint jamais le relais; un changement
  d'adresse de relais après lequel le nouveau relais ne reçoit jamais le
  dictionnaire.

### Surface et domaine

- Poussée du dictionnaire vers le relais (`SyncPusher`), démarrage du
  magasin (`ChuchoteStore`), empreinte de republication (`SyncPayloads`).

### Détection

- Revue adversariale externe, relancée après la fin du quota Codex; les trois
  constats ont été reproduits mentalement sur le code, puis rejoués par des
  tests JVM avant correction.

### Cause racine

1. **Envois concurrents vers un relais dernier-écrit-gagne.** La republication
   de démarrage (lots de 500, jusqu'à 35 s chacun) et les pierres tombales
   partaient sur des coroutines indépendantes. Une suppression faite pendant
   un lot voyait sa pierre tombale acceptée puis retirée de la file, avant
   que le lot — construit sur un instantané où le mot vivait encore — ne le
   réécrive `deleted=false`. Rien ne le réparait : le mot était absent
   localement, la pierre tombale partie.
2. **Une empreinte qui ne savait pas qu'une mutation avait échoué.** L'empreinte
   `dictionary_signature` n'était touchée que par une republication réussie.
   Supprimer un mot en ligne puis le réapprendre hors ligne ramenait le
   dictionnaire à l'état déjà signé : le démarrage suivant ne republiait
   pas, et le relais gardait la pierre tombale.
3. **Une empreinte sans destinataire.** L'empreinte ne portait que le
   contenu : changer l'adresse du relais laissait le nouveau relais sans
   dictionnaire, indéfiniment.

### Correctif

- `DictionarySyncCoordinator` (Kotlin pur) reprend toute la politique :
  un seul fil d'envoi (`Channel` consommé par une coroutine) fait partir
  ajouts, pierres tombales et republication dans l'ordre où le magasin les a
  demandés; la trace durable d'une mutation (pierre tombale en file,
  empreinte effacée) est inscrite sur le fil du magasin avant la mise en
  file; la republication relit le dictionnaire au moment où elle part
  (`ChuchoteStore` passe un fournisseur, plus un instantané) et
  n'enregistre son empreinte que si aucune mutation ne s'est glissée pendant
  ses envois; `SyncPayloads.dictionarySignature` prend l'adresse de base du
  relais. `SyncPusher` ne garde que l'appareil : `chuchote_sync`, réglages,
  consentement, HTTP.

### Test de non-régression

`DictionarySyncCoordinatorTest` : six scénarios sur un relais factice qu'on
bloque ou met en panne — suppression pendant le lot (la pierre tombale part
après, l'empreinte reste effacée), dictionnaire lu au départ du travail et
non à la demande, ajout manqué hors ligne rattrapé par la republication
suivante, changement de relais qui republie, lot refusé qui laisse
l'empreinte vide, aucun relais configuré. `SyncPayloadsTest` : l'empreinte
change avec le relais, pas avec une barre oblique finale.

### Ce qui reste à prouver sur l'appareil

- Les deux étapes ajoutées au § 6 du plan de test alpha : suppression pendant
  la republication de démarrage (fixture de plus de 500 entrées), changement
  d'adresse de relais.

### Ce qui l'aurait attrapé plus tôt

- Un test qui bloque un envoi et en lance un autre pendant ce temps : la
  politique vivait dans une classe Android (`SharedPreferences`,
  `HttpURLConnection`) qu'aucun test JVM ne pouvait exercer. La séparer de
  l'appareil a suffi à rendre les trois scénarios rejouables.

## 2026-09-19 — Deuxième ronde : une pierre tombale consommée par le mauvais rejeu, une suppression perdue à la mort du processus, un lot parti au mauvais relais

### Symptôme observable

- Aucun retour d'appareil : trois défauts établis par lecture du code lors de
  la seconde revue externe (Codex, 19 septembre 2026, `--base cf38768`, sur
  le correctif `fix(sync)` du même jour), verdict « needs-attention — Do not
  ship: dictionary sync can permanently resurrect deleted entries, lose
  deletions across crashes, and falsely record successful synchronization. »
- Attendus sur l'appareil s'ils s'étaient produits : un mot supprimé,
  réappris puis resupprimé pendant que le fil d'envoi était occupé restait
  vivant sur le relais (le premier rejeu consommait la seconde pierre
  tombale, l'ajout partait en dernier); une mort du processus entre le
  `DELETE` de `chuchote.db` et l'inscription de la pierre tombale perdait la
  suppression pour toujours (ni dans le dictionnaire, ni en file); un
  changement d'adresse de relais entre deux lots de 500 envoyait le second
  lot au nouveau relais et inscrivait l'empreinte pour l'ancien, qui restait
  incomplet sans jamais être republié.

### Surface et domaine

- Ordre des envois du dictionnaire (`DictionarySyncCoordinator`), suppression
  d'une entrée (`ChuchoteStore.supprimerEntree`), cible d'une republication
  (`SyncPusher.send`).

### Cause racine

1. **Un rejeu lisait la file au moment de partir.** Il envoyait tout ce
   qu'elle contenait, y compris une pierre tombale inscrite après sa demande
   — et devant laquelle un ajout attendait encore son tour.
2. **La trace durable venait après l'action.** `supprimerEntree` effaçait la
   ligne SQLite, rechargeait, puis inscrivait la pierre tombale : la fenêtre
   entre les deux n'était couverte par rien.
3. **La cible n'était pas liée au travail.** `send` relisait les réglages à
   chaque envoi; la republication ne lisait l'adresse qu'une fois, pour
   l'empreinte.

### Correctif

- Chaque pierre tombale inscrite dans ce processus porte la génération à
  laquelle elle l'a été (`inscriptions`, sous le verrou); un rejeu demandé à
  la génération g n'envoie que celles inscrites au plus tard à g — celles
  d'un processus précédent partent toujours.
- La pierre tombale est inscrite dans `chuchote_sync` **avant** le `DELETE`;
  une mort du processus entre les deux laisse au pire une pierre tombale d'un
  mot encore vivant, que le rejeu suivant écarte sans l'envoyer. Le
  correctif plus fort — la pierre tombale dans la même transaction SQLite que
  la suppression — changerait la mémoire de la file (`chuchote_sync`) et
  reste à décider.
- `envoyer` reçoit sa cible : la republication lit `RemoteRequestTarget` une
  fois et l'adresse à tous ses lots comme à son empreinte; un ajout ou un
  rejeu lit la cible au moment de partir. `SyncPusher.send` ne relit plus les
  réglages.
- Constats non bloquants du `code-reviewer` pris au passage : la génération
  est relevée avant de lire le dictionnaire (une mutation glissée entre la
  lecture et le premier envoi laisse l'empreinte effacée); un `trySend`
  refusé est journalisé; les délais de transport vivent dans `SyncTimeouts`,
  ni dans le coordinateur ni dans le pousseur; `REMOTE_RELAY_PRIVACY_SECURITY.md`
  décrit le fil unique, l'empreinte liée au relais et la republication vers
  chaque nouveau relais.

### Test de non-régression

`DictionarySyncCoordinatorTest`, quatre scénarios de plus : rejeu en file qui
ne consomme pas une pierre tombale inscrite après lui (fil occupé, supprimé,
réappris, resupprimé : l'ajout part avant le retrait); pierre tombale
inscrite avant l'envoi qui survit à un nouveau coordinateur sur la même
mémoire; republication en deux lots dont l'adresse change entre les deux (les
deux lots vont au premier relais, l'empreinte est la sienne, le démarrage
suivant republie vers le second); envoi qui lève sans tuer le fil.

### Ce qui reste à prouver sur l'appareil

- Les trois étapes ajoutées au § 6 du plan de test alpha : suppression,
  réapprentissage et resuppression pendant la republication; suppression
  suivie d'un arrêt forcé; changement d'adresse entre deux lots.

### Ce qui l'aurait attrapé plus tôt

- Le même harnais que la ronde précédente, avec un scénario de plus par
  constat : un fil bloqué, trois mutations dessus; deux coordinateurs sur une
  mémoire; une cible qui change entre deux lots. Chacun tient en quinze
  lignes — c'est la revue externe qui a posé les trois questions.

## 2026-09-19 — Troisième ronde : une pierre tombale élaguée avant son heure, des envois qui continuaient vers un relais coupé, un `commit()` jeté

### Symptôme observable

- Aucun retour d'appareil : trois défauts établis par lecture du code lors de
  la troisième revue externe (Codex, 19 septembre 2026, `--base cf38768`,
  sur le second correctif `fix(sync)`), verdict « needs-attention — Do not
  ship: deletion replay can permanently lose tombstones, and sync can
  continue after relay disablement. »
- Attendus sur l'appareil s'ils s'étaient produits : un rejeu de démarrage
  passant entre l'inscription d'une pierre tombale et l'effacement de sa
  ligne (la pierre tombale est inscrite avant, depuis la ronde précédente)
  voyait la paire encore vivante et l'élaguait — le travail de la
  suppression ne trouvait plus rien, le relais gardait le mot; couper le
  relais, tourner le jeton ou changer d'adresse pendant une republication en
  plusieurs lots laissait partir les lots suivants vers l'ancien relais;
  une écriture de préférences refusée par le disque passait pour réussie,
  la ligne était effacée et la pierre tombale n'existait nulle part.

### Surface et domaine

- Rejeu des pierres tombales et republication (`DictionarySyncCoordinator`),
  adaptateur `chuchote_sync` (`SyncPusher`), suppression d'une entrée
  (`ChuchoteStore.supprimerEntree`).

### Cause racine

1. **L'élagage précédait la borne.** `rejouer` retirait de la file les
   paires vivantes et périmées avant de ne garder que les pierres tombales
   inscrites avant sa demande; une pierre tombale plus récente, encore
   vivante par construction, y passait.
2. **La cible était lue une fois, sans être revérifiée.** Lier tous les lots
   à une cible (ronde précédente) garantissait l'empreinte, pas l'arrêt.
3. **`prefs.edit(commit = true) { }` jette le booléen de `commit()`.**

### Correctif

- `rejouer` partage la file en pierres tombales inscrites avant sa demande
  (envoyées, élaguées) et après (laissées intactes, à leur propre travail).
- `envoyerSiToujours` : avant chaque requête d'une série, la cible captée
  doit encore être le relais configuré; sinon la série s'arrête, l'empreinte
  n'est pas inscrite, les pierres tombales restent en file. Un ajout, seul,
  lit sa cible au moment de partir.
- `SyncMemoire.ecrire` et `effacer` rendent le résultat de `commit()`;
  `retirer` rend vrai si la pierre tombale est durable, sinon l'envoie
  aussitôt depuis la mémoire, une seule fois, et le journalise;
  `supprimerEntree` efface la ligne quand même (la vérité locale ne dépend
  pas du relais) et avertit avec l'identifiant seulement. Une empreinte non
  effacée ou non inscrite est journalisée.

### Test de non-régression

`DictionarySyncCoordinatorTest`, trois scénarios de plus (treize au total) :
rejeu de démarrage demandé avant une suppression dont la ligne n'est pas
encore effacée (la pierre tombale n'est pas élaguée et part en dernier);
changement puis coupure du relais entre deux lots (seul le lot en vol part,
pas d'empreinte, tout repart au démarrage suivant vers le relais configuré);
écriture des préférences refusée (la pierre tombale part aussitôt, la
suppression est signalée).

### Constats du `code-reviewer` sur le même commit, pris dans la même ronde

- `supprimerEntree` : une exception dans l'inscription de la pierre tombale
  tuait la coroutine avant le `DELETE` — la suppression locale dépendait de
  la comptabilité de synchronisation. Désormais `runCatching` : la ligne est
  effacée quoi qu'il arrive, l'échec est journalisé sans contenu.
- Le refus de `trySend` était inatteignable (le canal n'était jamais fermé,
  `for (x in channel)` ne le ferme pas) et sa KDoc affirmait l'inverse : le
  fil d'envoi ferme maintenant le canal à la mort de la portée, compte les
  travaux qui ne partiront plus, et un test le fait tirer.
- Deux tests modélisaient l'ordre d'avant (ligne effacée, puis pierre
  tombale) : ils suivent l'ordre du magasin. L'étape « Forcer l'arrêt » du
  plan alpha dit ce qu'elle prouve (la survie de la file) et comment lire le
  résiduel assumé.
- Résiduels de la réordonnance consignés dans `DATA_AND_PERSISTENCE.md` :
  mort entre inscription et `DELETE`, `DELETE` en échec, pierre tombale
  arrivée avant le `DELETE` — tous auto-réparés par la republication suivante.

### Ce qui reste à prouver sur l'appareil

- L'étape modifiée au § 6 du plan de test alpha : couper le relais ou changer
  d'adresse entre deux lots.

### Ce qui l'aurait attrapé plus tôt

- Le premier constat est né du correctif précédent (pierre tombale avant la
  suppression) : chaque réordonnancement d'écritures durables mérite de
  rejouer tous les scénarios de rejeu avec la nouvelle fenêtre. Le troisième
  est une aide KTX qui masque un résultat : préférer `commit()` nu partout où
  la durabilité compte.

## 2026-09-19 — Quatrième ronde : un délai plus court que le relais, une empreinte posée pendant une suppression, un doublon qui effaçait la paire

### Symptôme observable

- Aucun retour d'appareil : trois défauts établis par lecture du code lors de
  la quatrième revue externe (Codex, 19 septembre 2026, `--base cf38768`,
  sur le troisième correctif `fix(sync)`), verdict « needs-attention — Do not
  ship: dictionary sync can resurrect deleted entries after timeouts and
  leave persistent divergence after interrupted deletions. » — et, sur le
  même commit, la revue `code-reviewer` (verdict `decision_required`, voir
  plus bas).
- Attendus sur l'appareil s'ils s'étaient produits : un ajout expiré côté
  client après 15 s alors que le relais, qui s'accorde 30 s, l'écrivait
  encore, suivi d'une pierre tombale partie aussitôt — le mot ressuscitait
  au relais et rien ne le réparait; une republication de démarrage
  s'achevant pendant qu'une suppression était en suspens inscrivait une
  empreinte, puis une mort du processus avant le `DELETE` laissait le mot
  vivant ici et mort au relais, empreinte à l'appui, sans republication
  suivante; supprimer l'un de deux doublons (la table admet les doublons)
  posait une pierre tombale pour la paire entière, que l'appareil
  appliquait encore.

### Surface et domaine

- Délais de transport (`SyncTimeouts`), empreinte et file
  (`DictionarySyncCoordinator`), suppression d'une entrée
  (`ChuchoteStore.supprimerEntree`, nouvel objet `PierreTombale`).

### Cause racine

1. **Le fil unique ordonne les requêtes, pas les écritures d'un relais en
   retard.** Un délai de 15 s pour une seule entrée, contre 30 s de
   `maxDuration` : le client passait à la suite pendant que le relais
   écrivait encore.
2. **La génération avance à l'inscription de la pierre tombale, pas au
   `DELETE`.** Une republication qui lit le dictionnaire entre les deux voit
   un état que l'empreinte tient ensuite pour accepté.
3. **La clé du relais est la paire; celle de la table est `id`.**

### Correctif

- `READ_TIMEOUT_MS` et `BATCH_READ_TIMEOUT_MS` dépassent tous deux
  `RELAY_MAX_DURATION_MS` (35 s contre 30 s), avec un test de contrat. Reste
  la fenêtre d'une requête SQL déjà partie quand la fonction est tuée —
  quelques millisecondes —, à fermer par des versions de mutation côté
  relais (décision ouverte, D-006 du desktop).
- L'empreinte n'est inscrite que si aucune pierre tombale n'est en file en
  fin de republication : sans empreinte, le démarrage suivant republie, et
  une mort avant le `DELETE` laisse le mot vivant des deux côtés.
- `PierreTombale.aAnnoncer` : la pierre tombale n'est posée que pour la
  dernière ligne d'une paire; un doublon supprimé, ou une ligne inconnue,
  est journalisé (identifiant seulement) et n'annonce rien.

### Constats du `code-reviewer` sur le même commit, pris dans la même ronde

- L'arrêt d'une série de pierres tombales au changement de relais était
  documenté et jamais testé (le mutant `envoyer` à la place de
  `envoyerSiToujours` survivait) : testé, relais changé puis coupé entre
  deux pierres tombales.
- Les écritures refusées de la file n'étaient honorées que dans `retirer` :
  `inscriptions` ne suit plus que la file durable, chaque refus est
  journalisé, et un mot réappris dont la pierre tombale n'a pas pu être
  retirée est tenu pour vivant par tout rejeu (`reapprises`) jusqu'à une
  écriture réussie — ce dernier cas est testé, et depuis la sixième ronde
  la garde `if (durable)` de `ecrireFile` aussi (la phrase qui la disait
  « sans scénario discriminant atteignable par l'API » était fausse, voir
  cette ronde); les refus journalisés restent sans test — la fabrique du
  harnais ne câble pas `journal`.
- `runCatching` avalait `CancellationException` avant un `DELETE` :
  relancée.
- `attendreLaFin` pendait sur une portée morte : `demander` rend son succès,
  l'attente se termine.
- Une ligne inconnue supprimée sans trace : journalisée.
- **Non corrigé, sur décision d'Olivier** (`decision_required` : deuxième
  correctif autonome de la famille « rejeu qui élague une pierre tombale
  vivante », règle du checkpoint avant un troisième) : la borne d'un rejeu
  est lue sous le verrou, la mise en file hors de lui — un rejeu demandé
  entre les deux verrait une pierre tombale à sa propre génération et
  l'élaguerait si la ligne n'est pas encore effacée. Inatteignable tant que
  `synchroniserAuDemarrage` n'a qu'un appelant; correctif de trois lignes
  (`demander` sous le verrou) ou boîte d'envoi transactionnelle. Les
  documents disent désormais exactement ce qui tient.
- Le message du commit `90be9e2` annonce « quatre scénarios de plus » :
  trois tests nouveaux et un renommé, l'entrée précédente de ce registre a
  le bon compte.

### Test de non-régression

`SyncTimeoutsTest` (contrat des délais); `DictionarySyncCoordinatorTest`,
quinze scénarios (deux nouveaux, trois prolongés) : « un rejeu de demarrage
n elague pas … » prolongé (empreinte absente, puis le démarrage suivant
republie le mot), « la republication lit le dictionnaire quand elle part … »
(n'attend plus d'empreinte : la pierre tombale était encore en file quand
le lot s'est achevé), « changer puis couper le relais entre deux pierres
tombales … », « un mot reappris dont la pierre tombale n a pu etre
retiree … », attente sur portée morte; `PierreTombaleTest` (trois
scénarios).

### Ce qui reste à prouver sur l'appareil

- L'étape ajoutée au § 6 du plan de test alpha : supprimer l'un de deux
  doublons, puis l'autre.

### Ce qui l'aurait attrapé plus tôt

- Le premier constat réutilise un raisonnement déjà présent pour les lots
  (« on attend un peu au-delà ») sans l'avoir appliqué aux envois unitaires :
  une règle de transport vaut pour toutes les requêtes d'une même route. Le
  second est le troisième effet de la réordonnance pierre-tombale-puis-
  `DELETE` : chaque état intermédiaire nouveau doit être passé au crible de
  « le processus meurt ici ». Le troisième est un écart de clé entre deux
  magasins : à vérifier à chaque frontière. Quant à la famille laissée à la
  décision, trois rondes de findings sur du code écrit dans la boucle sont
  le signal que la conception (pierre tombale dans les préférences, ligne
  dans SQLite, aucun ordre atomique entre les deux) est en cause, pas la
  vigilance.

## 2026-09-19 — Cinquième ronde : consentement retiré pendant un envoi, ajout publié sans être en base, borne et mise en file sous le même verrou

### Symptôme observable

- Aucun retour d'appareil : deux défauts établis par lecture du code lors de
  la cinquième revue externe (Codex, 19 septembre 2026, `--base cf38768`,
  sur 7e9e727), verdict « needs-attention — Do not ship: dictionary sync can
  resurrect deleted entries, upload after consent revocation, and publish
  failed local inserts. » Le troisième constat de cette ronde (`[high] Early
  transport failures still allow writes to overtake deletions`) est tranché,
  pas corrigé ici : voir plus bas. Sur le même commit, la revue
  `code-reviewer` (`decision_required` : troisième ronde consécutive de
  constats sur du code écrit dans la boucle) — Olivier a tranché le soir
  même : continuer en un seul passage, boîte d'envoi transactionnelle en
  tranche 1.
- Attendus sur l'appareil s'ils s'étaient produits : le consentement retiré
  pendant qu'un envoi de dictionnaire était bloqué (connexion lente) ne
  l'interrompait pas — l'envoi partait quand même, à la différence de la
  transcription distante, gardée depuis longtemps; un ajout dont l'insertion
  SQLite échouait (`insert` rend -1 sans lever, disque plein) était publié
  au relais, qui gagnait un mot absent de l'appareil et qu'aucune ligne ne
  permettait de retirer.

### Surface et domaine

- `SyncPusher.send` (tout envoi vers `/api/sync/*`),
  `ChuchoteStore.ajouterEntree`, `DictionarySyncCoordinator` (borne des
  rejeux, attente, empreinte, file illisible), `SyncTimeouts`.

### Cause racine

- Le consentement était relu une fois avant d'ouvrir la connexion, puis plus
  jamais; `SyncPusher` a sa propre portée, sans observateur du consentement
  ni handler d'annulation qui ferme la connexion bloquante.
- Le résultat de `insert` était ignoré, et la publication suivait la
  relecture du dictionnaire, réussie même quand l'insertion ne l'était pas.
- La borne d'un rejeu était lue sous le verrou mais son travail mis en file
  hors de lui (famille « rejeu qui élague une pierre tombale vivante »,
  gelée deux rondes durant en attente de décision).

### Correctif

- `send` passe par `RemoteConsentGuard` — le garde de la transcription
  distante — et `post` est une `suspendCancellableCoroutine` dont le handler
  ferme la connexion (`CancellableConnectionSlot`), avec la relecture
  synchrone de `RemoteUploadGate` juste avant le premier octet. Un
  consentement retiré pendant l'envoi le coupe et compte comme un refus :
  pierre tombale en file, pas d'empreinte, journal « consentement retiré ».
  Une annulation de la portée elle-même reste une annulation
  (`ensureActive`).
- `ajouterEntree` ne recharge ni ne publie rien si `insert` rend -1;
  avertissement journalisé sans contenu.
- Décision 1 d'Olivier : dans `retirer` et `synchroniserAuDemarrage` (et
  `ajouter`, par uniformité), la mise en file se fait sous le verrou, avec la
  borne. Un rejeu demandé pendant une suppression part derrière son travail,
  avec une borne qui la couvre. Le commentaire de `rejouer` et
  `DATA_AND_PERSISTENCE.md` disent ce qui reste entre les deux magasins et
  pourquoi c'est inatteignable sur l'appareil (même fil sérialisé, sans
  suspension entre l'inscription et le `DELETE`).
- Constats du `code-reviewer` pris dans le même passage : `reapprises` vidé
  avec `inscriptions` quand la file est illisible et l'exception de `retirer`
  nommée dans le commentaire de `ecrireFile`; le saut d'empreinte pour cause
  de pierre tombale en file est journalisé; la marge des délais passe de 5 s
  à 15 s (`COLD_START_MARGIN_MS`) et dit ce qu'elle couvre — le budget du
  relais ne court qu'à l'entrée dans la fonction; `attendreLaFin` se termine
  aussi quand la portée meurt après avoir accepté l'attente (fin du fil
  observée); l'amplification d'une pierre tombale indélivrable est
  documentée; « effacée par toute mutation » devient « toute mutation
  annoncée au relais »; le paragraphe `chuchote_sync` est découpé par sujet;
  l'entrée précédente de ce registre dit quinze scénarios, pas seize, et le
  « — testé » ne couvre plus que ce qui l'est.
- Tranché, pas corrigé (D-006, tranche 1, décision d'Olivier) : une panne de
  transport après l'envoi du corps rend faux aussitôt, sans attendre les
  30 s du relais, qui peut encore écrire; une pierre tombale envoyée ensuite
  peut être dépassée. Ce n'est pas un délai qui ferme cela — Codex le dit —
  mais une version de mutation portée par l'appareil et refusée par le
  relais si plus ancienne. Les documents le disent tel quel.

### Test de non-régression

- `DictionarySyncCoordinatorTest`, dix-sept scénarios : « un rejeu demande
  pendant l inscription d une suppression part derriere elle » (un démarrage
  demandé depuis un autre fil pendant l'inscription, bloqué sur le verrou :
  la pierre tombale part la première, la file est vide — sans l'ordre sous
  le verrou, le démarrage partait parfois devant et la suppression était
  perdue; le test ne tue le mutant qu'à la course, la garantie est celle du
  verrou) et « une attente acceptee avant la mort de la portee se termine »
  (déterministe : sans l'observation de la fin du fil, l'attente pendait).
  `SyncTimeoutsTest` : marge d'au moins 15 s.
- Aucun test JVM pour `SyncPusher` ni `ajouterEntree` : `Context`, DataStore
  et `HttpURLConnection` sont hors du harnais JVM (le coordinateur est testé
  avec un `envoyer` en mémoire); le garde lui-même a `RemoteConsentGuardTest`,
  `CancellableConnectionSlotTest` et `RemoteUploadGateTest`. Sur l'appareil :
  étape ajoutée au § 6 du plan de test alpha (retrait du consentement
  pendant un envoi de dictionnaire).

### Ce qui l'aurait attrapé plus tôt

- Le garde du consentement existait pour l'audio; un second chemin qui fait
  quitter des données personnelles à l'appareil devait l'emprunter dès son
  écriture — « même frontière que le relais » était écrit dans le KDoc sans
  être tenu. Le résultat d'un `insert` ignoré est un lint classique
  (`CheckResult`), absent de la configuration. Quant à la borne, la règle
  « ce qui doit être ordonné se décide sous le même verrou » aurait dû être
  appliquée à la mise en file dès que la borne est née.

## 2026-09-19 — Sixième ronde : la garde de l'élagage testée, l'identifiant d'installation devant le numéro de ligne des dictées

### Symptôme observable

- Aucun retour d'appareil. Sixième revue externe (Codex, 19 septembre
  2026, `--base cf38768`, sur e4b76d1) : « needs-attention — Do not ship:
  connection failures can still permanently resurrect deleted dictionary
  entries. » Un seul constat, `[high] Older uploads can overwrite
  acknowledged deletions` (`SyncPusher.kt:204-206`) : « If an upsert
  reaches the relay but its response connection fails, this catch
  immediately returns false and releases the serialized sender. A
  subsequent tombstone can succeed and be removed from the durable queue
  before the older upsert finishes. The relay's last-write-wins behavior
  then resurrects the entry. Restart cannot repair it: the local entry is
  absent and its tombstone is gone. This is the documented D-006 gap; the
  longer timeout does not cover early connection failures. » — le résiduel
  D-006 tel qu'Olivier l'a tranché le soir même (version de mutation
  portée par l'appareil, refusée par le relais si plus ancienne :
  tranche 1) : consigné, non corrigé ici. Sur le même commit,
  `code-reviewer` : `changes_required`, « aucun changement de code
  requis » — un bloquant, STD-1 : l'entrée de la quatrième ronde de ce
  registre affirmait que la garde `if (durable)` de `ecrireFile` n'avait
  « pas de scénario discriminant atteignable par l'API »; c'est faux, et
  le reviewer a donné le contre-exemple, rejoué ci-dessous. Non
  bloquants : STD-2 (la branche non durable de `retirer` met en file hors
  du verrou — sans conséquence, mais les commentaires énonçaient une règle
  plus forte que le code), STD-3 (« consentement retiré » journalisé
  aussi quand c'est la surveillance du consentement qui est interrompue
  ou indisponible, et le plan de test alpha en faisait son critère),
  STD-4 (depuis la cinquième ronde, `send` n'attrape plus `Throwable`
  mais `Exception` : une `Error` tue le fil d'envoi sans que le fil le
  dise), STD-5 (le commentaire du test d'ordre décrivait un dégât
  moindre que celui du mutant).
- Même soirée, revue Codex de toute la tranche desktop : `[high]
  Platform-scoped identifiers overwrite unrelated dictations` — `device`
  ne nomme qu'une plateforme, et le `device_local_id` Android est un
  numéro de ligne SQLite : deux téléphones sur le même relais (deux
  jetons, décision D-002 du 19 septembre), ou une réinstallation qui
  repart à la ligne 1, déposent le même `(android, 1)` et le second
  écrase le texte du premier, chacun avec un 200. C'est la décision F du
  plan de synchronisation desktop, appliquée sur les deux appareils sous
  la délégation « petits points à ma discrétion », à confirmer par
  Olivier.

### Surface et domaine

- `remote/SyncPusher.kt`, `remote/SyncPayloads.kt`,
  `remote/DictionarySyncCoordinator.kt` (commentaires seulement), leurs
  tests, `.docs/DATA_AND_PERSISTENCE.md`,
  `.docs/REMOTE_RELAY_PRIVACY_SECURITY.md`, `.docs/ANDROID_ALPHA_TEST_PLAN.md`.

### Cause racine

- STD-1 : la phrase confondait « non atteignable par l'API » et « non
  branché dans le harnais » — le scénario n'a besoin que de
  `ecritureRefusee` et `enPanne`, deux leviers que le harnais avait déjà.
- Décision F : le numéro de ligne d'une dictée n'a jamais été unique
  au-delà d'une installation; tant qu'un seul téléphone déposait, rien ne
  le montrait.

### Correctif

- `SyncPayloads.dictation` prend l'identifiant d'installation et envoie
  `<installation>:<ligne>` (le numéro seul si l'identifiant est vide);
  `SyncPusher` tire l'identifiant une fois — un `UUID` aléatoire, jamais
  un identifiant matériel — et le garde dans `chuchote_sync`
  (`installation_id`); une écriture refusée le limite au processus, avec
  un journal. Relais inchangé, aucune migration : les dictées déjà
  déposées gardent leur ancien identifiant, aucune ne fusionne.
- STD-3 : le message devient « Sync interrompue pour … : garde du
  consentement fermée (consentement retiré, ou sa surveillance
  indisponible) », et le plan de test alpha l'attend tel quel.
- STD-2, STD-4, STD-5 : commentaires, et la phrase de la quatrième ronde
  corrigée (STD-1).

### Test de non-régression

- `un elagage refuse par le disque ne fait pas oublier un mot reappris`
  (dix-huitième scénario du coordinateur) : suppression durable pendant
  une panne du relais, mot réappris pendant que le disque refuse
  d'écrire, démarrage qui élague et se voit refuser l'écriture, puis
  suppression durable d'un autre mot une fois disque et relais revenus —
  le relais ne reçoit que la pierre tombale du second mot. Mutant :
  retirer `if (durable)` de `ecrireFile` — l'élagage refusé vide alors
  `reapprises`, et le rejeu envoie la pierre tombale
  d'un mot encore vivant ici, que le pair perd.
- `SyncPayloadsTest` : `device_local_id` = `inst:42`; sans identifiant
  d'installation, le numéro de ligne part tel quel.

### Ce qui l'aurait attrapé plus tôt

- Une règle pour ce registre : une absence de test se justifie par un
  scénario qu'on a essayé d'écrire et qui ne discrimine pas, jamais par
  une phrase. Et pour la décision F : un second appareil sur le relais
  dès la première QA.

## 2026-09-19 — Septième ronde : la doc de vie privée qui niait le pseudonyme d'installation, des commentaires plus forts que le code

### Symptôme observable

- Aucun retour d'appareil. Septième revue externe (Codex, 19 septembre
  2026, `--base cf38768`, sur 7a305c3) : « needs-attention — Do not ship:
  connection failures can still permanently resurrect deleted dictionary
  entries. » Un seul constat, `[high] Older uploads can overwrite
  acknowledged deletions` (`SyncPusher.kt:231-233`) : « If an upload
  reaches the relay but the connection fails before its write completes,
  this catch immediately releases the serialized sender. A queued
  tombstone can then succeed and be removed from the durable queue before
  the older upload commits. The relay's unconditional upsert resurrects
  the entry. Restart cannot repair this: the local entry is absent and its
  tombstone is gone. The 45-second timeout does not protect against early
  connection failures. » — le résiduel D-006, tranché (version de
  mutation, tranche 1) : consigné, non corrigé ici. Sur le même commit,
  `code-reviewer` : `decision_required` — deux bloquants, tous deux dans
  du texte écrit pendant la boucle. STD-1 (famille « justification d'une
  absence de couverture démentie par le harnais », budget de deux
  corrections autonomes épuisé) : la phrase réécrite à la sixième ronde,
  « les refus journalisés restent sans test — la fabrique du harnais ne
  câble pas `journal` », est fausse deux fois — deux refus sont déjà
  assertés (`DictionarySyncCoordinatorTest.kt:484` et `:516`) et quatre
  tests construisent le coordinateur en direct avec `journal` câblé; seuls
  les deux refus de `rejouer` (`DictionarySyncCoordinator.kt:350` et
  `:373`) sont sans assertion, et le dix-huitième test déclenche le
  premier sans le vérifier. Options remontées à Olivier : (a) troisième
  correction bornée (réécrire la phrase et asserter le journal dans le
  dix-huitième test), (b) couvrir d'abord les deux refus de `rejouer`,
  (c) supprimer la clause de justification — ne consigner que le couvert;
  recommandation du reviewer : (c), plus l'assertion d'une ligne de (a).
  STD-2 (famille neuve) : `REMOTE_RELAY_PRIVACY_SECURITY.md` et le KDoc
  de `installationId` disaient « rien de ce qui part ne désigne
  l'appareil », alors que la décision F introduit un pseudonyme stable par
  installation qui distingue durablement ses dictées dans l'historique
  partagé (D-002); et la phrase de couverture de la divulgation du 15
  septembre, convention de ce document, manquait. Non bloquants : STD-3
  (le commentaire de clôture du dix-huitième test attribue à l'étape 3 le
  vidage d'`inscriptions`, qui vient de l'étape 2 et n'est pas décisif),
  STD-4 (la prémisse « la pierre tombale n'étant pas en file » de la
  branche non durable de `retirer` a un contre-exemple), STD-5 (une
  `Error` sous `SupervisorJob` ne ferme pas le canal pour les demandes
  suivantes : elle fait tomber le processus), STD-6 (KDoc de classe de
  `SyncPusher` : « l'identifiant local pour les dictées » alors que la
  clé est `<installation>:<ligne>`), STD-7 et STD-8 (observations, aucun
  changement demandé); SPEC-1 (l'étape « deux installations » du plan
  alpha passe même sans le correctif si les numéros de ligne diffèrent),
  SPEC-2 (`RELAY_API.md` desktop « Android à suivre » — corrigé dans le
  dépôt desktop la même nuit).

### Surface et domaine

- Documentation et commentaires (`.docs/REMOTE_RELAY_PRIVACY_SECURITY.md`,
  `.docs/ANDROID_ALPHA_TEST_PLAN.md`, `SyncPusher.kt`,
  `DictionarySyncCoordinator.kt`, `DictionarySyncCoordinatorTest.kt`, ce
  registre). Aucun comportement changé.

### Cause racine

- Prose écrite pendant la boucle qui affirme plus que le code : une
  négation (« rien ne désigne l'appareil ») là où le code ajoute un
  pseudonyme; une prémisse (« pas en file ») qu'un entrelacement dément;
  un mécanisme (« le canal se ferme ») que le runtime ne livre pas. Le
  reviewer note que cinq constats sur dix, dont les deux bloquants, visent
  du texte écrit pendant la boucle, et qu'aucun ne vise le comportement du
  coordinateur ni du pousseur.

### Correctif

- STD-2 : le document de vie privée et le KDoc disent ce qui est vrai —
  un pseudonyme stable par installation, sans matériel ni identité
  derrière, qui distingue durablement les dictées d'une installation dans
  l'historique partagé — et la phrase de couverture est ajoutée : donnée
  nouvelle depuis le 19 septembre 2026, divulgation du 15 septembre tenue
  pour couvrante, à confirmer par Olivier (le reversionnement du
  consentement est sa décision).
- STD-3 à STD-6 : commentaires ramenés à ce que le code fait, KDoc de
  classe mise à jour; le mutant décrit dans l'entrée de la sixième ronde
  corrigé de même. SPEC-1 : l'étape du plan alpha exige deux
  installations avec cette version, chacune à sa première dictée.
- STD-1 : non touché — budget de famille épuisé, décision d'Olivier
  attendue.

### Test de non-régression

- Aucun : rien de comportemental n'a changé; la porte complète est
  relancée (tests, lint, compilation QA, APK).

### Ce qui l'aurait attrapé plus tôt

- Relire chaque phrase nouvelle contre le code qu'elle décrit avant le
  commit, en cherchant l'entrelacement qui la dément.
