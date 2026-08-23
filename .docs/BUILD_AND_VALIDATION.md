# Build et validation Android

> **Type** : guide de développement et état des preuves
> **Statut** : preuve historique conservée; gate local final de `codex/android-alpha` réussi le 23 août 2026, validation appareil ouverte
> **Snapshot** : `Chuchote-flow-android@552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`
> **Candidate mesurée** : arbre de livraison Android alpha; preuve machine locale acquise, CI distante et validation produit distinctes

## Clone correct

Le projet dépend de `whisper.cpp` comme sous-module Git. Utiliser :

```powershell
git clone --recurse-submodules https://github.com/ReiiViilo/Chuchote-flow-android.git
```

Pour un clone existant :

```powershell
git submodule update --init --recursive
```

Le fichier [`CONTRIBUTING.md`](../CONTRIBUTING.md#L7-L17) contient encore l'URL du dépôt Transcribro amont; cette partie est périmée pour le fork.

Dans le clone audité, le sous-module a été initialisé au commit `51c6961c7b64b406833f4b6a4a20e67142f69225`, qui est le gitlink fixé par le dépôt.

## Prérequis vérifiés dans la configuration

- JDK 17;
- Android SDK 36 et Build Tools 36.0.0;
- Android NDK `27.2.12479018`;
- CMake et outils C/C++ pour le JNI;
- Gradle Wrapper du dépôt;
- accès réseau au premier build pour le modèle Whisper si absent.

Références : [`app/build.gradle.kts`](../app/build.gradle.kts#L53-L86), [`lib/build.gradle`](../lib/build.gradle) et [`CONTRIBUTING.md`](../CONTRIBUTING.md#L21-L24).

## Modèle Whisper

La tâche `downloadWhisperModel` télécharge `ggml-small-q8_0.bin`, environ 264 Mo, depuis Hugging Face avant `preBuild`.

La validation actuelle vérifie seulement que le fichier dépasse 50 Mo. Aucun SHA-256 attendu ni signature n'est contrôlé. Référence : [`app/build.gradle.kts`](../app/build.gradle.kts#L13-L51).

## Commandes principales

Depuis PowerShell :

```powershell
# APK debug
.\gradlew.bat assembleDebug

# Tests et analyse statique
.\gradlew.bat testDebugUnitTest lintDebug

# APK installable côte à côte avec la version .debug existante
.\gradlew.bat assembleQa

# Tests Android de la variante QA sur un appareil connecté
.\gradlew.bat connectedQaAndroidTest
```

Android Studio peut aussi ouvrir la racine du dépôt et utiliser les SDK/NDK configurés.

## Compatibilité avec les pages mémoire de 16 Kio

Le NDK r27 ne produit pas automatiquement toutes les bibliothèques ELF avec
un alignement de 16 Kio. Chaque cible Whisper reçoit donc les options de lien
`max-page-size=16384` et `common-page-size=16384` dans
[`lib/src/main/jni/whisper/CMakeLists.txt`](../lib/src/main/jni/whisper/CMakeLists.txt).
Cette configuration suit la recommandation Android pour le NDK r27 ou
antérieur; une migration future vers NDK r28+ permettra d'obtenir cet
alignement par défaut.

La candidate courante a été reconstruite après `clean`. L'inspection avec
`llvm-readelf -lW` des **11 bibliothèques réellement emballées dans l'APK** a
mesuré 30 segments `LOAD`, tous à `p_align=0x4000`; aucun segment inférieur à
16 Kio n'est présent. `zipalign -c -P 16 -v 4` réussit également et les alertes
Lint `Aligned16KB` sont maintenant à zéro. Ces preuves de structure ne
remplacent pas un lancement et une transcription sur un appareil ou émulateur
réellement configuré avec des pages de 16 Kio.

## Variantes et identité

| Variante | Identité/signature observée |
|---|---|
| `debug` | suffixe `.debug`, clé debug locale ou du runner |
| `qa` | suffixe `.qa`, nom « Chuchote Flow QA », clé debug locale; n'écrase pas `.debug` |
| `staging` | initialisée depuis release, suffixe `.debug`, clé debug |
| `release` | minification et shrink, aucune clé de production définie dans le dépôt |

Référence : [`app/build.gradle.kts`](../app/build.gradle.kts#L96-L116).

## CI actuelle

Le workflow [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) :

1. clone les sous-modules récursivement;
2. installe JDK 17;
3. met le modèle Whisper en cache;
4. exécute `testDebugUnitTest` et `lintDebug` sans cache;
5. exécute `assembleDebug` sans cache;
6. publie l'APK comme artefact;
7. recrée une release `latest` seulement depuis `main`.

L'APK publié utilise la clé debug éphémère du runner. Une mise à jour peut donc exiger la désinstallation de la version précédente, avec risque de perte des données locales si elles ne sont pas sauvegardées ou migrées.

## Tests présents dans la candidate actuelle

- 159 scénarios unitaires JUnit ont été exécutés dans 34 suites et 34 fichiers
  de test, répartis entre `:app` et `:lib`;
- récupération d'un WAV `.part` et lecture bornée;
- normalisation, découpage et encodage des segments audio;
- transcription séquentielle et ordre des textes;
- progression relative à la durée et succès explicite;
- composition du texte au curseur, remplacement de sélection, bornes exactes du
  contenu dicté et remaniement conservateur des spans parcelables;
- politique versionnée et absence de migration implicite de l'ancien consentement;
- exclusion des sessions concurrentes, rejet des callbacks tardifs et commit IME final seulement;
- générations `PENDING/ACTIVE/STOPPING` du clavier et du widget, cible de champ
  stricte par `uniqueId` ou identité de source, refus d'un `viewId` seul et
  d'une fenêtre indéfinie, frontières Unicode de mots, auto-envoi et retour de
  clavier conditionnés au commit;
- reprise directe d'un état interrompu, retranscription confirmée d'un WAV
  terminé et refus des états encore actifs;
- annulation d'une connexion distante avant/après son attachement, surveillance
  du consentement pendant tout l'upload et absence de repli local après
  révocation;
- paire URL/jeton atomique, corps relais borné, champ `text` strictement chaîne
  et diagnostics sans corps ni message sensible;
- conservation du `Job` appelant jusqu'à la frontière JNI, annulation d'un
  appel Whisper encore en file, transfert de propriété du contexte natif et
  fermeture de son dispatcher;
- démarrage du service microphone uniquement depuis le pont visible, nettoyage
  terminal du `SpeechRecognizer`, reprise d'une seconde capture et confinement
  des erreurs Binder;
- exclusions de sauvegarde pour SQLite, dictionnaire, historique, WAV et
  configuration du relais, plus marquage sensible du presse-papiers compatible;
- générations isolées et terminal unique du parcours public
  `ACTION_RECOGNIZE_SPEECH`, validation allowlistée de l'Intent et de ses types,
  extras Android standards tolérés, et canal de transcript exclusif sans repli
  après l'échec d'un `PendingIntent`;
- fenêtre bornée d'apprentissage, différence de corrections et contrat de suppression audio;
- 1 test instrumenté construit une base SQLite v2 synthétique dans le paquet
  isolé `.qa`, ouvre `ChuchoteStore` et vérifie la migration v2 → v3, le texte
  brut, l'état final et la version du schéma; il passe sur le Samsung SM-S721W
  sous Android 16;
- aucun test instrumenté ne simule encore les arbres d'accessibilité de Gmail,
  ChatGPT ou Claude.

La suite actuelle passe intégralement dans le gate local final
`clean testDebugUnitTest lintDebug assembleQa`. Cette preuve couvre le diff du
23 août 2026; elle ne remplace pas la validation instrumentée et humaine sur
appareil.

## Matrice de validation recommandée

| Capacité | Validation attendue |
|---|---|
| capture locale | appareil arm64, silence, parole courte, parole longue, interruption |
| VAD | bruit, pauses, segments consécutifs et ordre des résultats |
| Whisper | français québécois, noms propres, mode avion et latence |
| clavier IME | plusieurs applications, sélection, ponctuation, retour clavier |
| widget | overlay, déplacement, suppression, secousse, insertion et repli presse-papiers |
| accessibilité | contenu lu, délai d'observation, proposition, rejet et révocation de permission |
| dictionnaire | doublons, casse, expressions, collisions et chaînes de corrections |
| relais | jeton valide/invalide, timeout, gros WAV, fournisseur indisponible, repli local |
| historique | états, reprise, suppression audio et migrations v1→v2→v3 |
| sauvegarde | transfert/restauration selon versions Android ciblées |
| mise à jour | même clé, clé différente, préservation de `chuchote.db` et préférences |
| confidentialité | capture réseau et comparaison exacte avec les textes visibles |

## Statut de preuve au 23 août 2026

### Preuve historique de la première candidate QA

- Sources inspectées : oui.
- Sous-module initialisé dans le clone : oui.
- Gradle exécuté : oui (`help`, `assembleDebug`, 17 tests de l'époque, `lintDebug`, `assembleQa`, `connectedAndroidTest`).
- Modèle téléchargé : oui, `ggml-small-q8_0.bin` présent localement.
- APK construit : oui, variante QA `9-qa`, 363 196 975 octets, SHA-256 `C7E2FC986AD6AFCF9A602860CBCD29C99930C8674BA6BBB83E697B9ED2E2F811`.
- APK installé : oui, côte à côte sous `dev.soupslurpr.transcribro.qa`; la version `.debug` reste installée.
- Migration SQLite : oui, 1 test instrumenté passé sur le Samsung SM-S721W; ce test est volontairement limité au paquet QA.
- Microphone testé : oui sur l'ancienne `9-debug`; QA de remédiation pas encore validée humainement.
- Clavier/widget testés : insertion ChatGPT rétablie sur `9-debug` après reliaison; matrice QA Gmail/ChatGPT/Claude ouverte.
- Relais appelé : non.
- CI du commit vérifiée en ligne : non.

### Candidate courante `codex/android-alpha`

- Sources et diff inspectés : oui.
- `git diff --check` : réussi.
- Manifeste et trois fichiers XML de configuration : syntaxe XML validée.
- Tests présents : 159 unitaires exécutés et 1 test instrumenté déclaré.
- Tests unitaires courants : **réussis**, 159 scénarios dans 34 suites/fichiers,
  0 échec, 0 erreur et 0 ignoré. Les XML proviennent de
  `app/build/test-results/testDebugUnitTest/` et
  `lib/build/test-results/testDebugUnitTest/`.
- Lint courant : **réussi**, 0 erreur. Les rapports contiennent 54 avertissements
  non bloquants dans `:app` et 3 dans `:lib`, principalement `UseKtx` et versions
  de dépendances; les alertes `Aligned16KB` sont absentes.
- Alignement natif : **réussi sur la structure de l'APK**, 11 bibliothèques et
  30 segments ELF `LOAD` à `0x4000`, aucun segment inférieur; alignement ZIP
  16 Kio également vérifié. Le test d'exécution sur matériel 16 Kio reste ouvert.
- APK courant : **construit**, variante `9-qa`, package
  `dev.soupslurpr.transcribro.qa`, libellé « Chuchote Flow QA », minSdk 29,
  targetSdk 36, ABI arm64-v8a et x86_64, 363 067 266 octets, SHA-256
  `4B98A1E067EA7BBAAFC9A76C23CC72A9EF60B8D3873190A2A656057BF4184D9F`.
- Signature APK : vérifiée par `apksigner`; schéma v2, un signataire, certificat
  `C=US, O=Android, CN=Android Debug`, empreinte SHA-256
  `18D7B3ECE0437C8CFC5C64D6BDFA62E847148532BE0A5CFE27B8611B6252577C`.
- Copie de livraison locale :
  `C:\Users\Utilisateur\Documents\CODE\artifacts\Chuchote-Flow\9-qa-android-alpha\chuchote-flow-android-9-qa-alpha.apk`,
  accompagnée de `README.md` et `SHA256SUMS.txt`.
- CI de la branche : non exécutée dans cette preuve locale; le workflow ne se
  déclenche sur un push que pour `main`, ou lors d'une pull request vers `main`.
- Validation humaine : ouverte.

Il faut donc décrire la candidate actuelle comme **implémentée et validée par le
gate machine local, mais sans validation produit du dernier diff**. Le prochain
gate nécessite le téléphone, puis la matrice manuelle :

```powershell
.\gradlew.bat connectedQaAndroidTest --no-daemon --no-build-cache
```
