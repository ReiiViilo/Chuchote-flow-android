# Documentation technique de Chuchote Flow Android

> **Type** : index documentaire
> **Public** : Olivier, développeurs et futurs agents
> **État audité** : base `main@552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`, candidate locale `codex/android-alpha`
> **Dernière vérification** : 23 août 2026
> **Portée** : application Android et son intégration au relais Chuchote

Le commit ci-dessus demeure le point de départ. La candidate du 23 août se
trouve sur `codex/android-alpha`. Son gate local final
`testDebugUnitTest lintDebug assembleQa --no-daemon --no-build-cache` est vert;
la preuve exacte est consignée dans `BUILD_AND_VALIDATION.md`. La validation
humaine sur téléphone demeure ouverte et n'est pas remplacée par ce gate.

Ce dossier décrit le code Android réellement présent. Les promesses du README et de la feuille de route sont classées comme cibles lorsqu'elles ne sont pas reliées au runtime.

## Résumé en une minute

L'application est un fork substantiel de Transcribro. Elle fournit trois surfaces de dictée : clavier vocal, widget flottant et intégration Android `ACTION_RECOGNIZE_SPEECH`. Le son est capté en PCM, segmenté par Silero VAD, puis transcrit par Whisper local ou par le relais distant facultatif. Dans le worktree de remédiation, la même entrée SQLite durable passe de l'enregistrement au résultat ou à l'état « Réessayer ».

Android possède déjà :

- un historique SQLite sans suppression silencieuse dans le worktree de remédiation;
- des WAV privés récupérables et un bouton « Réessayer »;
- un dictionnaire local `entendu → remplacer_par`;
- des propositions de correction après certaines modifications observées par le service d'accessibilité;
- un repli automatique vers Whisper local quand le relais échoue.

Android ne possède pas encore :

- de connexion Neon ou Supabase;
- de compte utilisateur;
- de synchronisation avec le desktop;
- de post-traitement LLM FR-QC;
- d'apprentissage statistique continu;
- de tests instrumentés reproduisant les arbres d'accessibilité de Gmail,
  ChatGPT et Claude. La migration SQLite possède un test instrumenté sur
  appareil. La suite locale contient 159 scénarios unitaires dans 34 fichiers;
  ils passent tous dans le gate courant.

## Parcours de lecture

| Besoin | Document canonique |
|---|---|
| Comprendre les écrans, services et le pipeline | [Architecture Android actuelle](CURRENT_ARCHITECTURE.md) |
| Comprendre SQLite, les préférences et la rétention | [Données et persistance Android](DATA_AND_PERSISTENCE.md) |
| Comprendre exactement ce que le dictionnaire apprend | [Dictionnaire et apprentissage](DICTIONARY_AND_LEARNING.md) |
| Suivre la stabilisation Android et la feuille de route produit | [Stabilisation Android et feuille de route](ANDROID_REMEDIATION_AND_ROADMAP.md) |
| Comprendre le relais, les permissions et la confidentialité | [Relais, confidentialité et sécurité](REMOTE_RELAY_PRIVACY_SECURITY.md) |
| Cloner, construire et connaître les preuves disponibles | [Build et validation](BUILD_AND_VALIDATION.md) |
| Tester l'alpha sur le téléphone sans écraser l'app actuelle | [Plan de validation Android alpha](ANDROID_ALPHA_TEST_PLAN.md) |
| Voir les reproductions, causes racines et tests sensibles | [Historique des correctifs](../BUGS_HISTORY.md) |
| Comparer Android, desktop et la cible commune | [Synthèse inter-dépôts](../../Chuchote-Flow/.docs/CROSS_REPO_DATA_AND_SYNC.md) |
| Utiliser les termes communs sans ambiguïté | [Lexique commun](../../Chuchote-Flow/.docs/GLOSSARY.md) |
| Voir les choix encore ouverts | [Décisions ouvertes](../../Chuchote-Flow/.docs/OPEN_DECISIONS.md) |

## Frontières de propriété documentaire

- Ce dépôt possède les faits Android.
- Le dépôt desktop possède les faits desktop et le contrat du relais qu'il héberge.
- La synthèse comparative, la cible commune, le lexique et le registre de décisions ont une source unique dans `Chuchote-Flow/.docs/`.

Les liens inter-dépôts relatifs supposent la disposition actuelle, avec les deux clones comme dossiers frères sous `personnel/`. Si Android est consulté seul ou publié séparément, ces liens devront être remplacés par des URL GitHub épinglées au commit approprié.

## Limites de la preuve

- Le point de départ reste le commit indiqué; la branche de livraison et son
  SHA distant doivent être vérifiés séparément après le push.
- Le sous-module `whisper.cpp` est initialisé au commit fixé par le dépôt.
- Les OOM/CME, l'état accessibilité « coché mais non lié » et le rétablissement ChatGPT ont été observés sur un Samsung Android 16.
- Une candidate antérieure de la remédiation a passé 17 tests unitaires, Lint,
  les builds debug/QA et 1 migration SQLite instrumentée sur appareil.
- Depuis cette preuve, les garde-fous de consentement, de concurrence,
  d'accessibilité, d'IME, de cible widget, d'annulation HTTP et de terminal
  Android public ont changé. Le gel courant passe 159 scénarios unitaires dans
  34 suites/fichiers, Android Lint et `assembleQa`. L'APK `9-qa` produit fait
  363 067 266 octets et porte le SHA-256
  `4B98A1E067EA7BBAAFC9A76C23CC72A9EF60B8D3873190A2A656057BF4184D9F`.
  `apksigner` confirme une signature v2 par un certificat Android Debug. Les
  11 bibliothèques emballées et leurs 30 segments ELF `LOAD` sont alignés à
  16 Kio; l'exécution sur matériel 16 Kio reste une preuve appareil distincte.
- La longue dictée, la reprise après interruption et la matrice Gmail/ChatGPT/Claude restent à valider humainement sur la QA.
- Aucun déploiement, compte Neon/Supabase ou fournisseur distant n'a été modifié dans cette remédiation.

## Règle de mise à jour

Lorsqu'un changement modifie une permission, un schéma, le comportement du service d'accessibilité, une donnée envoyée au réseau ou une limite :

1. mettre à jour le document propriétaire dans ce dossier;
2. mettre à jour la synthèse inter-dépôts si le contrat commun change;
3. corriger aussi les textes UI ou de confidentialité devenus faux;
4. dater la validation et distinguer code inspecté, build réussi et essai réel sur appareil;
5. faire relire indépendamment les affirmations de sécurité et de confidentialité.
