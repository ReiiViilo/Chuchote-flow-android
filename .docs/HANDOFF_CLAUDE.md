# Note de reprise Claude — candidate Android alpha

> **Type** : note de reprise autonome
> **Public** : Claude Code, futurs agents et développeurs
> **Dernière vérification locale** : 24 août 2026
> **Fuseau de référence** : `America/Toronto`
> **Dépôt** : `ReiiViilo/Chuchote-flow-android`
> **Branche de travail** : `codex/android-alpha`
> **Base de la PR** : `main@552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`
> **Base de la tranche audio** : `0892ad04142c7cfa5d441f7dc72a4efd0a326e57`
> **Commit d'implémentation** : `9c18c1d52c96db39e832c768e52c310ea06899af`
> **PR** : [#14 — brouillon](https://github.com/ReiiViilo/Chuchote-flow-android/pull/14); vérifier son HEAD et ses checks avec `gh pr view 14`
> **État** : gate hors appareil vert; six tests instrumentés compilés mais non relancés ensemble sur le dernier diff; validation produit ouverte

## Objectif de cette reprise

Cette branche stabilise l'application Android avant les chantiers de
synchronisation, de statistiques et d'apprentissage avancé. Elle vise surtout :

- l'insertion fiable au curseur, dans une sélection et lors de dictées
  successives;
- la conservation durable du WAV et la reprise d'une dictée interrompue;
- la réhabilitation sûre des WAV historiques encore présents dans le stockage
  privé;
- des frontières explicites de consentement, de cible d'accessibilité, de
  concurrence, de réseau et de données privées.

Ce handoff permet de reprendre le travail sans le transcript de la session qui
l'a produit. Il ne constitue ni une approbation de merge ni une validation
humaine de l'alpha.

## Reprendre sur un autre ordinateur

Le dépôt Android se suffit à lui-même pour construire l'application :

```powershell
git clone --recurse-submodules --branch codex/android-alpha https://github.com/ReiiViilo/Chuchote-flow-android.git
Set-Location Chuchote-flow-android
git submodule update --init --recursive
git status --short --branch
git rev-parse HEAD
git submodule status --recursive
```

Résultat attendu avant modification : worktree propre, branche
`codex/android-alpha`, upstream du même nom et sous-module `whisper.cpp` au
gitlink `51c6961c7b64b406833f4b6a4a20e67142f69225`.

Pour consulter ou modifier les contrats inter-dépôts, cloner aussi le desktop
comme dossier frère. Ce second clone n'est pas requis pour Gradle :

```powershell
Set-Location ..
git clone --branch codex/desktop-alpha https://github.com/ReiiViilo/Chuchote-Flow.git
```

Les documents Android utilisent des URL GitHub pour les sources inter-dépôts,
afin qu'un clone Android isolé reste navigable. Le snapshot desktop observé au
moment de ce handoff est `ab0479f136bc3f6fc0d9dffc22ffa08a58fd4552` sur
`codex/desktop-alpha`; le vérifier avant tout changement transversal.

## Prérequis de build

- JDK 17;
- Android SDK 36;
- Android Build Tools 36.0.0;
- Android SDK Platform-Tools avec `adb` dans le `PATH`, ou un chemin explicite
  vers `adb.exe`;
- Android NDK 27.2.12479018;
- CMake et chaîne C/C++ du SDK;
- GitHub CLI `gh` authentifié pour inspecter ou mettre à jour la PR; Git seul
  suffit au build local;
- accès réseau au premier build si le modèle Whisper n'est pas en cache.

Le modèle `ggml-small-q8_0.bin` n'est pas versionné. La tâche Gradle
`downloadWhisperModel` le télécharge automatiquement. Ne pas copier le modèle,
un APK ou `local.properties` depuis l'ancien ordinateur dans Git.

## Lectures obligatoires

| Besoin | Source principale |
|---|---|
| Orientation et index | [`README.md`](README.md) |
| Commandes et état exact des preuves | [`BUILD_AND_VALIDATION.md`](BUILD_AND_VALIDATION.md) |
| Architecture et pipeline | [`CURRENT_ARCHITECTURE.md`](CURRENT_ARCHITECTURE.md) |
| SQLite, WAV et migrations | [`DATA_AND_PERSISTENCE.md`](DATA_AND_PERSISTENCE.md) |
| Problèmes Android et roadmap | [`ANDROID_REMEDIATION_AND_ROADMAP.md`](ANDROID_REMEDIATION_AND_ROADMAP.md) |
| Recette sur téléphone | [`ANDROID_ALPHA_TEST_PLAN.md`](ANDROID_ALPHA_TEST_PLAN.md) |
| Dictionnaire actuel | [`DICTIONARY_AND_LEARNING.md`](DICTIONARY_AND_LEARNING.md) |
| Relais et confidentialité | [`REMOTE_RELAY_PRIVACY_SECURITY.md`](REMOTE_RELAY_PRIVACY_SECURITY.md) |
| Règles agent minimales | [`../CLAUDE.md`](../CLAUDE.md) |

Le fichier propriétaire d'un fait prévaut sur les résumés. Les documents du
dépôt desktop décrivent encore le snapshot Android de départ pour certains
tableaux; pour le comportement de cette branche, les documents Android
ci-dessus sont la source courante.

## État fonctionnel transmis

Le commit déjà distant `0892ad0` porte la remédiation Android large : pipeline
audio séquentiel, WAV récupérable, SQLite v3, reprise, insertion/accessibilité,
générations de reconnaissance, consentement, relais annulable, confidentialité
et documentation de l'alpha.

Le correctif préparé après ce commit ajoute ou durcit :

- `PrivateAudioPathResolver`, qui accepte exactement
  `noBackupFilesDir/dictations` et `filesDir/dictations` après
  canonicalisation, et refuse traversal, siblings, chemins externes et
  non-WAV;
- l'inspection stricte RIFF/WAVE PCM mono 16 kHz 16 bits, sans mutation du
  fichier historique;
- la réhabilitation des seuls diagnostics `audio_missing` et
  `retry_audio_missing`, limitée à 100 lignes par démarrage;
- un curseur circulaire durable dans `maintenance_state` et deux pages keyset
  utilisant l'index partiel `idx_dictees_historical_audio_errors`;
- la publication de l'historique et du dictionnaire avant la maintenance
  best-effort;
- la projection d'une récupération partielle avant restitution de l'exception
  SQLite originale;
- une frontière de test qui observe l'initialisation sans absorber les erreurs
  racines de production;
- un nouveau résolveur de production, quatre nouveaux fichiers de tests
  unitaires et six scénarios instrumentés isolés dans
  `ChuchoteStoreMigrationTest`.

La réhabilitation est **zéro copie** : elle ne déplace, ne renomme, ne réécrit
et ne supprime pas le WAV. Elle ne corrige que l'erreur et la durée SQLite d'un
WAV déjà valide et privé. Les nouvelles lignes continuent d'écrire un chemin
absolu pour rester lisibles par l'APK v3 précédent en cas de rollback.

## Données et packages

| Usage | Package | Règle |
|---|---|---|
| release de base | `dev.soupslurpr.transcribro` | ne pas effacer ni désinstaller |
| debug historique utilisée par Olivier | `dev.soupslurpr.transcribro.debug` | ne pas effacer ni désinstaller |
| candidate de développement | `dev.soupslurpr.transcribro.qa` | données jetables avec l'accord d'Olivier |

Il n'existe aucun `sharedUserId`. Android isole donc les espaces de données.
Les tests instrumentés créent un cache UUID, une base SQLite et deux racines
audio sous le package QA, puis les nettoient. Ils ne lisent ni n'écrivent la
base `chuchote.db` du package QA comme fixture, encore moins celle d'un autre
package.

Avant toute commande ADB destructive, vérifier explicitement le package. Les
seules cibles autorisées dans cette phase sont les données ou l'installation
de `dev.soupslurpr.transcribro.qa`.

## Preuves acquises

Gate local propre exécuté le 24 août 2026 sur le source transmis :

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug compileQaAndroidTestKotlin assembleQa --no-daemon --no-build-cache
```

Résultat observé :

- `BUILD SUCCESSFUL` en 7 min 15 s;
- 182 tests unitaires dans 38 suites : 181 réussis, 0 échec, 0 erreur,
  1 scénario symlink ignoré sous Windows;
- un contre-test déterministe non ignoré couvre la même sortie canonique;
- Lint : 0 erreur, 54 avertissements dans `:app`, 3 dans `:lib`;
- compilation des six tests `qaAndroidTest` réussie;
- `assembleQa` réussi;
- `git diff --check` réussi;
- revue indépendante statique du dernier correctif : GO, aucun finding P0 à
  P3. Ce rapport n'est pas un artefact versionné et doit être remplacé par une
  nouvelle revue si le code concerné change.

APK local provisoire issu de ce gate, **non versionné et non promu** :

- sortie : `app/build/outputs/apk/qa/app-qa.apk`;
- package `dev.soupslurpr.transcribro.qa`, version `9-qa`;
- 363 100 034 octets;
- SHA-256
  `E4134256C1225D9430275B65DC67494F75EF602B5EDBF02509FEABEEDE5E3997`;
- signature APK v2, certificat Android Debug;
- `zipalign -c -P 16` réussi;
- 11 bibliothèques et 30 segments ELF `LOAD`, tous à `p_align=0x4000`.

Ce hash sert de provenance locale, pas de gel de livraison. Recompiler et
recalculer toutes ces valeurs après la preuve appareil avant de nommer un
artefact `alpha6`.

## Preuves explicitement ouvertes

Les six scénarios instrumentés du dernier diff **n'ont pas été exécutés
ensemble sur le téléphone**. Une candidate antérieure alpha5 possédait un
rapport à cinq scénarios verts, mais elle précédait les derniers durcissements
et ne couvre pas ce source.

Quand Olivier rend le Samsung SM-S721W disponible pour une séance bornée :

```powershell
$adbExe = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adbExe devices -l
.\gradlew.bat connectedQaAndroidTest --no-daemon --no-build-cache
```

Exiger `device`, pas `unauthorized`. La tâche Gradle peut installer puis
désinstaller le package QA. Après succès, reconstruire le gate complet,
recalculer le hash et vérifier signature, ZIP et ELF avant tout gel.

La checklist humaine reste entièrement ouverte sur le dernier diff :

- Gmail, ChatGPT et Claude;
- insertion au milieu d'une phrase et remplacement d'une sélection;
- deux dictées successives dans le même champ;
- dictée supérieure à deux minutes;
- interruption puis reprise depuis l'historique;
- accessibilité relancée après arrêt ou crash;
- comportement réel du pont microphone et de l'orbe.

Voir [`ANDROID_ALPHA_TEST_PLAN.md`](ANDROID_ALPHA_TEST_PLAN.md) pour la matrice
complète. Une CI verte ne remplace ni ces tests ni le verdict d'Olivier.

## État Git, PR et CI

État publié de la reprise :

- `origin/main` et le merge-base valent
  `552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`;
- la tranche audio précédente est ancrée à
  `0892ad04142c7cfa5d441f7dc72a4efd0a326e57`;
- le correctif de réhabilitation audio est ancré à
  `9c18c1d52c96db39e832c768e52c310ea06899af`;
- la PR [#14](https://github.com/ReiiViilo/Chuchote-flow-android/pull/14)
  est ouverte en brouillon vers `main`;
- le HEAD de la PR avance avec les commits documentaires et doit donc être
  revérifié, pas déduit du commit d'implémentation;
- le workflow GitHub s'exécute pour une PR vers `main`, mais pas pour un simple
  push sur cette branche;
- ne jamais interpréter la possibilité technique de pousser ou fusionner comme
  une autorisation. Vérifier les règles de branche courantes dans GitHub et
  garder la PR en brouillon jusqu'aux gates appareil et au verdict humain.

Après un clone ou une reprise, ne pas se fier à ce snapshot sans exécuter :

```powershell
git fetch origin --prune
git status --short --branch
git rev-list --left-right --count origin/main...HEAD
gh auth status
gh pr view 14 --json number,url,state,isDraft,baseRefName,headRefName,headRefOid,statusCheckRollup
gh pr checks 14 --watch
```

## Non-objectifs de cette branche

- synchronisation Android–desktop;
- choix définitif entre Neon, Supabase ou une autre couche de données;
- authentification ou identité d'appareil;
- upload cloud des WAV;
- statistiques d'usage;
- apprentissage automatique contextuel du vocabulaire;
- refonte complète des paramètres ou personnalisation finale de l'orbe;
- migration du namespace historique vers un nouvel identifiant de production;
- signature de production ou publication d'une release finale.

Ces sujets restent dans la roadmap. Ne pas les ajouter à cette PR sans nouveau
scope et nouvelles décisions.

## Critères avant de sortir la PR du mode brouillon

1. les six tests `connectedQaAndroidTest` passent sur le dernier commit;
2. le gate local complet repasse après cette preuve;
3. GitHub Actions est vert sur le commit courant de la PR;
4. l'APK reconstruit est vérifié et son hash est consigné;
5. la matrice critique Gmail/ChatGPT/Claude, double dictée, sélection et longue
   dictée ne révèle aucune perte ni mauvaise cible;
6. les findings de revue sont fermés ou explicitement acceptés;
7. Olivier valide humainement la candidate;
8. la documentation est resynchronisée avec les preuves finales.

## Procédure de fin de reprise

Avant de rendre le travail à un autre agent :

1. relever `git status`, `git rev-parse HEAD`, upstream et URL de PR;
2. distinguer tests compilés, exécutés localement, exécutés sur appareil et QA
   humaine;
3. ne conserver aucun secret, audio, DB, APK ou log dans Git;
4. mettre à jour ce handoff seulement si un fait propriétaire a réellement
   changé;
5. laisser le worktree propre ou documenter précisément chaque fichier non
   commité et sa raison.
