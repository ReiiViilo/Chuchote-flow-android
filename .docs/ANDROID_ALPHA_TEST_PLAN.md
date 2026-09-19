# Plan de validation humaine — Chuchote Flow Android alpha

> **Type** : checklist de validation produit
> **Cible** : variante `qa` de `codex/android-alpha`
> **Appareil de référence** : Samsung SM-S721W, Android 16/API 36
> **État** : gate machine final réussi et détaillé dans `BUILD_AND_VALIDATION.md`; checklist appareil non exécutée sur le dernier diff

## Principe de sécurité

Conserver l'application Android actuellement fonctionnelle. La variante QA
utilise l'identifiant `dev.soupslurpr.transcribro.qa` et le nom « Chuchote Flow
QA » : elle doit s'installer côte à côte. Ne désinstaller l'ancienne version
qu'après une décision explicite de promotion et une vérification de la stratégie
de signature/données.

Utiliser du texte fictif pour les essais d'accessibilité et de relais. Ne pas
tester l'apprentissage dans un champ de mot de passe ni avec des données
professionnelles sensibles.

## 1. Installation et consentement

- [ ] Installer l'APK QA sans retirer la version existante.
- [ ] Vérifier le nom visible « Chuchote Flow QA » et la version attendue.
- [ ] Effacer les données de l'app QA (jetables par invariant) ou vérifier
  que la clé `ACCEPTED_PRIVACY_POLICY_AND_LICENSE_2026_09_15` n'y est pas déjà :
  le texte de la politique a changé depuis la première QA de cette branche
  sans nouvelle clé, et l'écran ne se représente pas à qui l'a déjà acceptée.
- [ ] Ouvrir l'app : la politique du 15 septembre 2026 doit être présentée
  même si une ancienne politique (23 août 2026 ou antérieure) avait déjà été
  acceptée; son texte doit mentionner l'envoi du texte des dictées et du
  dictionnaire au relais.
- [ ] Avant acceptation, vérifier qu'aucun tap sur le clavier, le widget ou le
  lanceur ne démarre le microphone.
- [ ] Accepter la politique, puis accorder microphone, superposition et service
  d'accessibilité uniquement à la QA.
- [ ] Sur API 34, 35 et 36, depuis ChatGPT puis Claude en arrière-plan, toucher
  le lanceur avec permission micro déjà accordée puis lors du premier accord :
  l'activité translucide démarre l'orbe et rend le focus à l'app cible, sans
  `ForegroundServiceStartNotAllowedException`, `SecurityException` ni refus
  de lancement d'activité dans `logcat`.
- [ ] Sur un appareil ou émulateur Android 15/16 configuré avec des pages de
  16 Kio, lancer la QA puis effectuer une courte transcription locale. La
  structure ELF et ZIP est validée par les outils, mais cette exécution reste
  une preuve distincte.
- [ ] Gate technique séparé : un test instrumenté devra mettre la préférence
  courante à `false` pendant un enregistrement et vérifier l'arrêt ainsi que le
  WAV à reprendre. La QA n'expose pas encore une commande utilisateur de
  révocation; ne pas prétendre que ce scénario est validable depuis les écrans.

## 2. Insertion directe multi-apps

Répéter la matrice dans Gmail, ChatGPT et Claude :

| Cas | Résultat attendu |
|---|---|
| champ vide | insertion directe, aucun passage par le presse-papiers |
| curseur à la fin | texte ajouté une fois, curseur après la dictée |
| curseur au milieu | texte inséré une fois entre les deux portions existantes |
| curseur entre deux mots | espaces ajoutés seulement aux frontières nécessaires; aucune fusion |
| mot sélectionné | sélection remplacée une seule fois par le résultat final |
| sélection inversée | même résultat qu'une sélection normale |
| champ multiligne | contenu avant/après et sauts de ligne préservés |
| changement de champ pendant la transcription | aucun callback ne doit écrire dans le nouveau champ |
| changement d'application pendant la transcription | historique conservé; aucune insertion ni copie implicite |
| champ riche avec mention ou lien | contenu riche intact; repli seulement si la même cible reste prouvée |
| annulation, puis nouvelle dictée | aucun résultat de l'ancienne session ne doit apparaître |
| deux dictées successives même champ | le second cycle démarre immédiatement et insère une seule fois |

Pour chaque cas :

- [ ] noter si un toast annonce insertion, curseur incertain, action non
  confirmée ou presse-papiers;
- [ ] vérifier qu'un résultat incertain n'est jamais inséré/collé deux fois;
- [ ] vérifier que le presse-papiers n'est annoncé que si la copie est réellement
  observable;
- [ ] confirmer que les partiels ne font pas clignoter ou réécrire le champ :
  un seul texte final doit apparaître.
- [ ] Avec « envoi automatique » activé, forcer un champ qui refuse
  `commitText` : aucune action Envoyer ne doit partir.
- [ ] Avec « retour automatique au clavier précédent » activé, forcer le même
  refus de `commitText` : le clavier ne doit pas changer.
- [ ] Taper deux fois rapidement sur le micro pendant sa préparation, puis
  annuler : une seule tentative existe et aucun callback tardif ne ferme la
  suivante.

## 3. Champs sensibles et apprentissage

- [ ] Focaliser un champ de mot de passe : aucune observation de correction ni
  proposition de dictionnaire ne doit rester active.
- [ ] Commencer dans un champ normal, puis le faire devenir sensible si l'app de
  test le permet : l'état temporaire doit être purgé.
- [ ] Corriger manuellement un mot à l'intérieur d'une phrase après insertion :
  la proposition doit concerner uniquement la paire modifiée.
- [ ] Changer de champ avant la proposition : aucune donnée de l'ancien champ ne
  doit être proposée.
- [ ] Corriger à la fin exacte du champ : aucune proposition automatique n'est
  attendue dans cette alpha, faute d'ancre droite sûre.

Limite assumée : le compteur « même correction deux fois » et les règles
contextuelles pour les homophones ne sont pas encore livrés. Cette alpha ne
valide que la proposition explicite et bornée existante.

## 4. Dictée longue et reprise

Exécuter successivement une dictée d'environ 10 secondes, 2 minutes et 5
minutes avec du contenu non sensible.

- [ ] Une ligne d'historique apparaît dès le début de chaque capture.
- [ ] Après validation, l'orbe reste rouge-orange/jaune plus longtemps pour les
  longs audios; le vert plein n'apparaît qu'au résultat final.
- [ ] Après 120 secondes de transcription, un message informe que le travail
  continue; aucune annulation ni « dictée abandonnée » ne doit survenir.
- [ ] Le résultat final est inséré une seule fois et l'historique indique durée,
  source et audio.
- [ ] Forcer l'arrêt de la QA pendant une capture de test, rouvrir l'app et
  confirmer qu'une entrée « Réessayer » possède un audio récupérable.
- [ ] Toucher « Réessayer » deux fois rapidement : une seule tentative doit
  s'exécuter.
- [ ] Couper le réseau avec le relais désactivé : la reprise locale reste
  fonctionnelle.
- [ ] Supprimer une dictée de test : la ligne et son WAV disparaissent ensemble;
  un échec de suppression doit garder la ligne visible.

## 5. Orbe, position et accessibilité visuelle

- [ ] Fermer le widget, puis focaliser un champ : le petit lanceur apparaît à la
  dernière position sans démarrer le microphone.
- [ ] Toucher le lanceur : l'orbe principale apparaît; toucher ensuite l'orbe
  démarre la capture.
- [ ] Déplacer l'orbe, la ranger, la rappeler et vérifier la position restaurée.
- [ ] Passer portrait → paysage → portrait avec le clavier ouvert : l'orbe reste
  entièrement visible.
- [ ] Activer TalkBack : les contrôles « orbe » et « annuler » ont une annonce
  compréhensible et le déplacement reste utilisable.
- [ ] Désactiver « Afficher l'orbe dans les champs texte » : aucun lanceur ne
  doit apparaître au prochain focus.

## 6. Relais facultatif

Ne pas activer le relais pour la recette locale principale. Si un serveur de
test expurgé est disponible :

- [ ] confirmer qu'aucune requête ne part avant acceptation de la politique;
- [ ] confirmer qu'audio, `language=fr` et prompt de vocabulaire correspondent
  exactement à la divulgation visible;
- [ ] simuler timeout et HTTP non-2xx : l'audio reste dans l'historique et la
  transcription locale prend le relais;
- [ ] contre un serveur de test volontairement lent, retirer le consentement
  pendant l'upload : la connexion est interrompue, aucun repli local ne démarre
  pour cette tentative et les octets déjà transmis sont traités comme
  irrévocables;
- [ ] vérifier que le corps de l'erreur serveur n'apparaît pas dans `logcat`;
- [ ] retourner successivement un corps `2xx` trop grand, du JSON invalide,
  `{"text":123}` et `{"text":{"error":"x"}}` : aucun contenu ne doit être
  injecté ou journalisé; le repli local doit être utilisé.

Synchronisation vers le relais (`/api/sync/*`), sur le même serveur de test :

- [ ] terminer une dictée **locale** (relais configuré mais serveur STT
  éteint ou repli forcé) : un `POST /api/sync/dictations` part avec
  `device=android`, le texte brut, le texte final et `source`; aucun audio;
- [ ] ajouter une entrée au Dictionnaire : un `POST /api/sync/dictionary`
  part avec `heard`, `replace_with`, `deleted=false`; supprimer l'entrée : même
  route avec `deleted=true`;
- [ ] couper le réseau, supprimer une entrée, rétablir le réseau, tuer et
  relancer l'app : la pierre tombale part au démarrage (`SyncPusher` dans
  `logcat` : « Mot #retrait synchronisé »), et rien ne repart au démarrage
  suivant;
- [ ] sur une installation QA dont le relais n'a **jamais** été configuré —
  donc une seconde installation, ou après effacement des données QA, **avant**
  toute configuration du relais, car le drapeau `sync_configured` posé par les
  étapes précédentes ne se retire pas — supprimer une entrée :
  `chuchote_sync.xml` ne reçoit aucune `pending_tombstones`;
- [ ] relais configuré puis éteint (ouvrir le réglage de jeton, ce qui le
  désactive), supprimer une entrée, réactiver : la pierre tombale part
  (« Mot #retrait synchronisé »);
- [ ] avec un dictionnaire de plus de 500 entrées (fixture jetable QA) :
  la republication de démarrage se fait en plusieurs `POST` de 500 au plus,
  et un second démarrage sans changement n'en fait aucun;
- [ ] avec la même fixture, supprimer une entrée **pendant** la republication
  de démarrage (dans les secondes qui suivent le lancement) : la pierre
  tombale part **après** le dernier lot (`logcat` : « Mot #retrait
  synchronisé » après le dernier « lot n/n du dictionnaire synchronisé »),
  un `GET /api/sync/dictionary` ne renvoie plus l'entrée, et le démarrage
  suivant republie (l'empreinte a été effacée par la suppression);
- [ ] avec la même fixture, pendant la republication de démarrage : supprimer
  une entrée, la réapprendre (saisie manuelle), la supprimer de nouveau; après
  le dernier lot, `logcat` montre « Mot #ajout synchronisé » **puis** « Mot
  #retrait synchronisé » en dernier, et un `GET /api/sync/dictionary` ne
  renvoie plus l'entrée;
- [ ] supprimer une entrée puis tuer le processus aussitôt (« Forcer l'arrêt »
  dans les réglages système, dans la seconde) : au lancement suivant, la
  pierre tombale part (« Mot #retrait synchronisé ») et l'entrée n'est ni
  dans le Dictionnaire ni renvoyée par le `GET`;
- [ ] changer l'adresse du relais pour un second serveur de test : au
  démarrage suivant, tout le dictionnaire est republié vers lui; revenir à la
  première adresse : republié aussi, l'empreinte étant liée à l'adresse; avec
  la fixture de plus de 500 entrées, changer l'adresse **entre deux lots** de
  la republication : les deux lots atteignent le premier serveur (journal du
  relais), et le démarrage suivant republie tout vers le second;
- [ ] retirer le consentement : aucun `POST /api/sync/*` ne part, même pour
  une pierre tombale déjà en file;
- [ ] confirmer que ces requêtes correspondent exactement à la divulgation de
  la politique (texte des dictées, entrées ajoutées ou retirées, jamais
  d'audio ni de jeton dans le corps).

Limite assumée : le protocole ne possède pas encore de clé d'idempotence. Un
timeout ne permet pas de garantir qu'un fournisseur distant n'a traité le
segment qu'une seule fois.

## 7. Contrat Android public

Avec une petite application de test qui appelle
`ACTION_RECOGNIZE_SPEECH` :

- [ ] `free_form` et `web_search` produisent au plus un résultat final;
- [ ] un Intent sans modèle, avec un modèle inconnu, un extra inconnu ou un
  mauvais type retourne `RESULT_CANCELED` sans transcript;
- [ ] un bundle de résultat sans `PendingIntent` est rejeté;
- [ ] `EXTRA_PROMPT`, `EXTRA_LANGUAGE` et un `EXTRA_MAX_RESULTS` positif sont
  acceptés sans être annoncés comme honorés; une valeur maximale nulle ou
  négative est rejetée;
- [ ] après rotation pendant la dictée, seul l'écran courant livre le résultat;
- [ ] sans `PendingIntent`, seul le résultat Activity contient le transcript;
  avec `PendingIntent`, seul celui-ci le contient et l'Activity termine sans
  payload de transcript;
- [ ] un `PendingIntent` invalide, annulé ou en échec retourne
  `RESULT_CANCELED`, sans repli vers un transcript Activity;
- [ ] retirer le consentement avant le terminal retourne `RESULT_CANCELED` et
  aucun `PendingIntent` ne reçoit de texte;
- [ ] aucun tableau `EXTRA_CONFIDENCE_SCORES` n'est présent puisque le service
  ne calcule pas de score.

## 8. Sauvegarde et transfert

Avec uniquement des valeurs fictives :

- [ ] configurer un jeton de relais, produire une sauvegarde/restauration ou un
  transfert compatible avec l'API ciblée, puis confirmer que le jeton est vide
  sur l'appareil de destination;
- [ ] confirmer que l'historique et le dictionnaire sont absents sur l'appareil
  de destination, conformément à l'exclusion complète du domaine `database`;
- [ ] vérifier que ni un WAV courant ni un ancien `files/dictations/` n'est
  restauré.

## 9. Verdict et preuves à conserver

Pour chaque anomalie, noter : version APK, application cible, scénario exact,
heure locale, texte fictif attendu/obtenu, toast affiché et capture d'écran. Si
ADB est relié, joindre un extrait `logcat` expurgé sans contenu de dictée ni
jeton.

La candidate peut être promue seulement si :

- tests unitaires, Lint, build QA et migration instrumentée sont verts;
- aucune dictée longue ou interruption ne perd son WAV;
- la matrice Gmail/ChatGPT/Claude ne produit ni mauvaise cible ni doublon;
- aucun champ sensible ne produit une observation;
- Olivier donne son verdict humain explicite sur la latence, l'orbe et la
  confiance de l'insertion.
