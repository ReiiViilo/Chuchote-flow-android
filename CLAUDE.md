# Reprise Claude — Chuchote Flow Android

Ce fichier est le point d'entrée minimal pour Claude Code et les autres agents.
Le contexte complet de la candidate vit dans
[`HANDOFF_CLAUDE.md`](.docs/HANDOFF_CLAUDE.md); ne duplique pas ses faits ici.

## Démarrage obligatoire

1. Exécuter `git status --short --branch`, `git rev-parse HEAD` et
   `git submodule status --recursive` avant toute modification.
2. Lire, dans cet ordre :
   - [handoff courant](.docs/HANDOFF_CLAUDE.md);
   - [index documentaire](.docs/README.md);
   - [preuves de build](.docs/BUILD_AND_VALIDATION.md);
   - le document propriétaire du domaine modifié.
3. Comparer l'état observé au handoff. Un SHA, un résultat CI ou un état
   d'appareil est une donnée à revérifier, pas une hypothèse à recopier.

## Invariants du chantier

- La variante de travail est `qa`, package
  `dev.soupslurpr.transcribro.qa`. Ne jamais effacer ou désinstaller
  `dev.soupslurpr.transcribro` ni `dev.soupslurpr.transcribro.debug`.
- Olivier accepte que les données de la QA soient jetables pendant le
  développement. Elles ne doivent jamais servir de seule fixture de migration;
  les tests instrumentés utilisent leurs propres caches et bases éphémères.
- Ne pas annoncer les six tests Android comme réussis sur le dernier diff tant
  qu'un rapport `connectedQaAndroidTest` courant ne le prouve pas.
- Ne pas nommer une candidate `alpha6`, promouvoir la PR ou fusionner vers
  `main` avant les preuves appareil et la validation explicite d'Olivier.
- Android et desktop restent deux clients locaux : la vérité est `chuchote.db`.
  La seule synchronisation implémentée ici est `SyncPusher` — poussée
  best-effort des dictées terminées et des mots du dictionnaire (ajouts,
  pierres tombales rejouées au démarrage, liste entière par lots) vers le relais
  (`/api/sync/*`), sous consentement courant. Aucun accès direct à Neon,
  Supabase ou PostgreSQL, et aucun tirage depuis le relais (voir le plan
  `Chuchote-Flow/.docs/PLAN_SYNC_TEMPS_REEL_ET_UI_COMMUNE.md`).
- Ne jamais versionner APK, WAV, SQLite, `local.properties`, clé ADB, keystore,
  secret de relais, journaux appareil ou contenu de dictée.
- Le sous-module `whisper.cpp` doit rester au gitlink versionné, sauf tâche
  explicite de mise à niveau.

## Gate machine local

Sous PowerShell, avec JDK 17 et le SDK Android configurés :

```powershell
.\gradlew.bat testDebugUnitTest lintDebug compileQaAndroidTestKotlin assembleQa --no-daemon --no-build-cache
```

Avec Android SDK Platform-Tools disponible, puis un téléphone explicitement
disponible et autorisé par ADB :

```powershell
.\gradlew.bat connectedQaAndroidTest --no-daemon --no-build-cache
```

La seconde commande peut installer puis désinstaller le package QA. Elle ne
doit être lancée que pendant une séance appareil convenue avec Olivier.
