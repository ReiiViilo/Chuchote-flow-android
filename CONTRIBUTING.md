# Contribuer à Chuchote Flow Android

Ce dépôt est le fork Android de Chuchote Flow, basé sur Transcribro. Pour
signaler un bogue ou proposer une fonctionnalité, ouvrir d'abord une issue dans
`ReiiViilo/Chuchote-flow-android` en décrivant le comportement attendu, les
applications Android concernées et la preuve disponible.

## Cloner correctement

Le projet utilise `whisper.cpp` comme sous-module :

```powershell
git clone --recurse-submodules https://github.com/ReiiViilo/Chuchote-flow-android.git
Set-Location Chuchote-flow-android
git submodule update --init --recursive
```

Pour reprendre la candidate Android actuelle plutôt que `main`, ajouter
`--branch codex/android-alpha` à la commande de clone. Voir la
[note de reprise Claude](.docs/HANDOFF_CLAUDE.md) avant toute modification.

## Environnement et vérification

Le build exige JDK 17, Android SDK 36, Build Tools 36.0.0, NDK
27.2.12479018 et CMake. Le modèle Whisper est téléchargé automatiquement au
premier build et ne doit pas être ajouté à Git.

Gate local attendu :

```powershell
.\gradlew.bat testDebugUnitTest lintDebug compileQaAndroidTestKotlin assembleQa --no-daemon --no-build-cache
```

Les tests sur téléphone constituent une preuve distincte. Ne pas annoncer une
capacité comme validée sur appareil à partir d'un test unitaire, d'un build ou
d'une ancienne candidate.

## Règles de contribution

- utiliser Kotlin et Jetpack Compose pour l'application, sauf contrainte native
  explicitement justifiée;
- préserver l'attribution et les licences de Transcribro, whisper.cpp, Whisper
  et Silero VAD;
- ajouter un test de non-régression pour chaque bogue corrigé;
- mettre à jour le document propriétaire dans [`.docs`](.docs/README.md) avec
  le même changement;
- ne jamais committer APK, modèle Whisper, audio, base SQLite, clé, jeton,
  `local.properties` ou journal d'appareil;
- garder les preuves machine, la validation appareil et la validation humaine
  explicitement séparées.

Pour contribuer au projet Transcribro d'origine plutôt qu'à ce fork, consulter
son dépôt et ses règles propres :
[soupslurpr/Transcribro](https://github.com/soupslurpr/Transcribro).
