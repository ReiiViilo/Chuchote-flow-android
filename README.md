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
- Aucune connexion réseau requise, aucune télémétrie (l'app ne demande que la permission micro — pas même `INTERNET`)

## Ce que ce fork change

- **Français** : Transcribro embarque `tiny.en`, un modèle **anglais uniquement**, et fige la langue à `en` dans le code natif. Ce fork utilise le modèle multilingue `small-q8_0` et dicte en français (`fr`).
- Le modèle (~264 Mo) dépasse la limite de 100 Mo par fichier de GitHub : il est donc **téléchargé automatiquement au moment du build** par la tâche Gradle `downloadWhisperModel`, et non versionné. Rien à faire manuellement, y compris dans Android Studio.
- Pour changer de modèle ou de langue : `whisperModel` dans [`app/build.gradle.kts`](app/build.gradle.kts) (voir la liste officielle dans `whisper.cpp/models/download-ggml-model.sh`), le chemin dans `MainRecognitionService.kt`, et `params.language` dans [`lib/src/main/jni/whisper/jni.c`](lib/src/main/jni/whisper/jni.c).

## Feuille de route Chuchote Flow

1. **Fondation** ✅ : import de Transcribro, build APK automatique par GitHub Actions, transcription en français
2. **Nettoyage LLM** : la même étape de post-traitement que le desktop — les prompts « Chuchote — Nettoyage (FR-QC) » et « Reformulation (FR-QC) » appliqués à la transcription avant insertion (API ou serveur local)
3. **Synchronisation** : historique des dictées poussé vers le cerveau commun (Supabase), partagé avec le desktop
4. **Bulle flottante** ✅ : le widget persistant façon Wispr Flow — superposition par-dessus n'importe quelle app, vignette avec niveau sonore en temps réel, déclenchement par secousse, confirmation explicite, insertion du texte dans le champ actif

## Le widget flottant

Une bulle reste posée par-dessus toutes les applications. On la touche (ou on secoue le téléphone) pour lancer la dictée : un panneau s'ouvre en bas de l'écran avec le niveau sonore en direct, puis **✓** transcrit et insère le texte dans le champ de saisie actif, **✕** annule.

Activation en trois étapes depuis l'écran d'accueil de l'app :

1. **Affichage par-dessus les autres apps** — pour la bulle elle-même.
2. **Accessibilité** — c'est le seul mécanisme Android qui permet d'écrire dans le champ d'une autre application. Sans cette étape le widget fonctionne quand même, mais dépose le texte dans le presse-papiers au lieu de l'écrire.
3. **Démarrer le widget** — il tourne comme service de premier plan (notification permanente), ce qui est la condition pour capter le micro hors de l'app.

Détails d'implémentation : la fenêtre du widget est volontairement non focalisable, sinon elle volerait le focus au champ où le texte doit atterrir. La reconnaissance vise directement `MainRecognitionService` via `SpeechRecognizer`, ce qui évite de dépendre du réglage système « application de saisie vocale » — inaccessible sur certains appareils Samsung. Le niveau sonore est calculé dans la boucle de capture existante et publié par `rmsChanged` : un second `AudioRecord` échouerait, le micro n'acceptant qu'un client à la fois.

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
