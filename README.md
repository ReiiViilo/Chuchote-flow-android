# Chuchote Flow Android

Le client Android de **Chuchote Flow** — dictée vocale personnelle, gratuite et locale. Fork de [Transcribro](https://github.com/soupslurpr/Transcribro) (licence ISC), le clavier vocal open source basé sur whisper.cpp.

Ce dépôt forme, avec [Chuchote-Flow](https://github.com/ReiiViilo/Chuchote-Flow) (desktop, fork de Handy), **un seul produit** : des interfaces par plateforme, un cerveau commun.

## Architecture « un seul produit »

```
   ┌─────────────────────────────────────────────┐
   │              Cerveau commun (nuage)          │
   │  historique des dictées · prompts FR-QC ·    │
   │  dictionnaire personnel · apprentissages     │
   └──────────────┬──────────────┬───────────────┘
                  │              │
        ┌─────────┴────┐   ┌─────┴──────────┐
        │   Desktop    │   │    Android     │
        │ (fork Handy) │   │ (ce dépôt,     │
        │              │   │ fork Transcribro) │
        └──────────────┘   └────────────────┘
```

Peu importe l'appareil où tu dictes, tout aboutit au même historique, avec les mêmes prompts de nettoyage et le même dictionnaire personnel — comme le compte Wispr Flow, mais à toi.

## Ce que Transcribro fournit déjà

- Clavier vocal Android (IME) : le micro apparaît dans n'importe quelle app, le texte dicté s'insère dans le champ actif
- Transcription 100 % locale avec whisper.cpp + détection de voix Silero VAD
- Aucune connexion réseau requise, aucune télémétrie

## Feuille de route Chuchote Flow

1. **Fondation** (cette étape) : import de Transcribro, build APK automatique par GitHub Actions
2. **Nettoyage LLM** : la même étape de post-traitement que le desktop — les prompts « Chuchote — Nettoyage (FR-QC) » et « Reformulation (FR-QC) » appliqués à la transcription avant insertion (API ou serveur local)
3. **Synchronisation** : historique des dictées poussé vers le cerveau commun (Supabase), partagé avec le desktop
4. **Bulle flottante** : le widget persistant façon Wispr Flow — superposition par-dessus n'importe quelle app, vignette avec waveform en temps réel, déclenchement par secousse, insertion du texte dans le champ actif

## Installer

**Directement depuis le téléphone** (la façon la plus simple) : ouvrir la page [**Releases → latest**](../../releases/latest) dans le navigateur, toucher `chuchote-flow.apk`, autoriser le navigateur à installer des applications si Android le demande, puis ouvrir le fichier téléchargé. La release est recréée à chaque build, donc elle contient toujours la dernière version de `main`.

Activer ensuite le clavier dans Paramètres → Système → Langues et saisie → Clavier à l'écran.

> Les artefacts de l'onglet [Actions](../../actions/workflows/build-apk.yml) contiennent le même APK, mais zippé et réservé aux utilisateurs connectés — l'app GitHub mobile ne peut pas les télécharger. Utiliser la page Releases sur mobile.

Comme l'APK est signé avec la clé debug du runner, qui change d'un build à l'autre, une mise à jour peut exiger de désinstaller la version précédente d'abord (une clé de signature stable viendra plus tard).

Pour compiler localement : Android Studio, ouvrir le projet, `Run` — voir [UPSTREAM_README.md](UPSTREAM_README.md).

## Garder le fork à jour avec Transcribro

L'historique complet est conservé :

```sh
git remote add upstream https://github.com/soupslurpr/Transcribro   # une seule fois
git fetch upstream
git merge upstream/main
```

## Licence et attribution

Projet personnel, non commercial. Basé sur [Transcribro](https://github.com/soupslurpr/Transcribro) de soupslurpr, sous licence ISC — conservée dans [LICENSE.txt](LICENSE.txt), avec les licences de whisper.cpp, Silero VAD et des modèles Whisper. Merci à la communauté Transcribro.
