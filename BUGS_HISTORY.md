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
