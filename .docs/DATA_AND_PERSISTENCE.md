# Données et persistance Android

> **Type** : référence technique
> **Statut** : schéma v2 du point de départ et schéma v3 de la branche alpha
> **Base auditée** : `main@552c4282595922f5a7f1eeb5c6140c4b24f9dfbf`
> **Candidate décrite** : tip de `codex/android-alpha`; vérifier son SHA à la reprise
> **Source principale** : [`ChuchoteStore.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/memory/ChuchoteStore.kt)

## Base SQLite

`ChuchoteStore` utilise `SQLiteOpenHelper` avec la base locale `chuchote.db`, version 3 dans la branche alpha. Les écritures sont envoyées vers un dispatcher IO à parallélisme 1.

Référence : [`ChuchoteStore.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/memory/ChuchoteStore.kt), notamment la classe `ChuchoteStore`, son initialisation et son helper SQLite `Db`.

## Table `dictees`

| Colonne | Type | Sens actuel |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | identifiant local seulement |
| `texte` | `TEXT NOT NULL` | texte après corrections du dictionnaire |
| `raw_text` | `TEXT NULL` | résultat assemblé avant corrections du dictionnaire |
| `cree_le` | `INTEGER NOT NULL` | millisecondes Unix issues de `System.currentTimeMillis()` |
| `duree_ms` | `INTEGER NULL` | délai entre la demande d'arrêt et le texte final; repli sur le temps total de transcription |
| `source` | `TEXT NULL` | `local`, `relais` ou `mixte`, sans contrainte SQL |
| `audio_path` | `TEXT NULL UNIQUE` | WAV privé final ou chemin attendu pendant l'écriture `.part` |
| `audio_duration_ms` | `INTEGER NULL` | durée réelle capturée |
| `etat` | `TEXT NOT NULL` | `recording`, `queued`, `transcribing`, `retryable` ou `completed` |
| `error_code` | `TEXT NULL` | code borné expliquant une reprise |
| `tentatives` | `INTEGER NOT NULL` | nombre de démarrages de transcription |
| `segments` | `TEXT NULL` | bornes PCM compactes `début:fin;…` |
| `transcription_started_at` | `INTEGER NULL` | début de la tentative courante |
| `transcription_ended_at` | `INTEGER NULL` | fin ou échec de la tentative |

Android ne conserve toujours pas séparément :

- le modèle précis;
- la langue;
- le détail segment par segment;
- le prompt de vocabulaire envoyé au relais;
- l'application cible.

## Table `dictionnaire`

| Colonne | Type | Sens actuel |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | identifiant local |
| `entendu` | `TEXT NOT NULL` | forme à détecter ou mot à favoriser |
| `remplacer_par` | `TEXT NOT NULL DEFAULT ''` | correction; vide pour une entrée de biais |

Il n'existe aucune contrainte `UNIQUE(entendu)`. Les doublons et corrections contradictoires peuvent être insérés. Référence : [`ChuchoteStore.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/memory/ChuchoteStore.kt), schéma `dictionnaire` dans `Db.onCreate` et projections `EntreeDictionnaire`.

## Conservation de l'historique et de l'audio

La suppression silencieuse au-delà de 500 lignes a été retirée dans la branche alpha.
Chaque nouvelle dictée crée une ligne avant le premier échantillon. Le WAV est
écrit sous `noBackupFilesDir/dictations`; un arrêt de processus laisse un `.part` dont
l'en-tête est réparé au prochain démarrage. La ligne devient alors
`retryable` au lieu de disparaître. Le résolveur accepte également les WAV
historiques sous `filesDir/dictations`, sans les copier ni les déplacer. Lorsqu'un
ancien WAV valide avait été faussement marqué `audio_missing` ou
`retry_audio_missing`, le démarrage efface uniquement cette erreur et recalcule
sa durée; le texte, l'état, le chemin et le fichier restent inchangés.
L'historique et le dictionnaire sont publiés avant cette maintenance best-effort.
La passe traite au plus 100 candidats par démarrage. Un curseur circulaire est
conservé dans la table auxiliaire `maintenance_state` de la même base SQLite.
Deux requêtes keyset indexées lisent d'abord les IDs supérieurs au curseur, puis
reviennent aux IDs inférieurs ou égaux seulement s'il reste de la place dans le
lot. L'index partiel `idx_dictees_historical_audio_errors` contient uniquement
les deux diagnostics réparables; le plan vérifié n'utilise aucun tri temporaire.
Le curseur est avancé avant le traitement du lot afin que des chemins invalides
ou une erreur d'écriture n'affament pas indéfiniment les IDs suivants. La table
et l'index ne changent pas `user_version=3`, de sorte qu'un rollback v3 ignore simplement la
table supplémentaire; un effacement des données supprime aussi le curseur. Les
erreurs ordinaires de chemin ou de WAV ignorent seulement la ligne concernée;
la première erreur SQLite ou disque pendant une mise à jour abandonne la passe
et est journalisée, sans retirer les données déjà publiées. Un WAV n'est
réhabilité que si son
en-tête PCM canonique, ses chunks, son alignement et ses tailles déclarées
correspondent exactement aux octets présents.

L'interface permet :

- recherche locale par sous-chaîne;
- copie du texte terminé;
- relance directe d'une transcription interrompue dont l'audio est sauvegardé;
- retranscription explicite d'un WAV terminé, après confirmation; le texte
  existant n'est remplacé que si la nouvelle tentative réussit;
- suppression d'une entrée;
- effacement complet.

Référence : [`HistoryScreen.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/ui/history/HistoryScreen.kt#L58-L220).

Supprimer une ligne ou tout l'historique supprime aussi le WAV correspondant,
après vérification que son chemin reste dans l'une des deux racines audio privées
autorisées. Il
n'existe toujours ni entrée épinglée, ni corbeille, ni export, ni tombstone de
synchronisation. Une politique de quota disque explicite reste à concevoir.

## Préférences

Les préférences générales utilisent DataStore, notamment thème, acceptation, retour au clavier, démarrage et envoi automatiques. L'acceptation est versionnée par `ACCEPTED_PRIVACY_POLICY_AND_LICENSE_2026_09_15` (politique du 15 septembre 2026, qui divulgue la synchronisation du texte des dictées et du dictionnaire vers le relais); les clés historiques (`…_2026_08_23`, `…V0.3.0`) n'accordent aucun accès aux traitements de la politique courante.

La synchronisation utilise un `SharedPreferences` nommé `chuchote_sync`, exclu des sauvegardes et transferts Android au même titre que `remote_transcription`. Il tient l'empreinte SHA-256 du dernier dictionnaire accepté par le relais configuré (`dictionary_signature`) et la file des pierres tombales que le relais n'a pas encore acceptées (`pending_tombstones`, JSON d'objets `heard` / `replace_with` / `since`, donc des mots du dictionnaire personnel). Références : [`SyncPusher.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/SyncPusher.kt) (l'appareil) et [`DictionarySyncCoordinator.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/DictionarySyncCoordinator.kt) (la politique, Kotlin pur). Les règles, par sujet :

- **Ordre des envois.** Un seul fil d'envoi ordonne tout ce qui part vers `/api/sync/dictionary` : une suppression ne peut plus être dépassée par une republication de démarrage encore en vol; un rejeu n'envoie que les pierres tombales inscrites avant sa demande (un mot supprimé, réappris puis resupprimé pendant que le fil est occupé part dans cet ordre); une republication adresse tous ses lots au relais lu à son départ, et une série d'envois — lots, pierres tombales — s'arrête dès que ce relais n'est plus celui configuré (coupé, jeton tourné, adresse changée) : ce qui était en vol part, rien de plus, l'empreinte n'est pas inscrite et les pierres tombales restent en file. Les pierres tombales ne sont pas liées à un relais : une suppression envoyée au relais du moment n'atteint pas un relais précédent.
- **File des pierres tombales.** Alimentée seulement si la synchronisation a déjà été configurée une fois (drapeau `sync_configured`), 200 paires au plus, rejouée 30 jours au plus. La pierre tombale est inscrite dans `chuchote_sync` **avant** le `DELETE` de `chuchote.db`, pour qu'une mort du processus entre les deux ne perde jamais la suppression (dans l'autre sens, une pierre tombale d'un mot encore vivant est écartée au rejeu suivant). Un rejeu n'envoie et n'élague (paire redevenue vivante, péremption) que les pierres tombales inscrites à une génération **au plus égale** à celle observée à sa demande : celle d'une suppression demandée après lui est laissée à son propre travail. Borne et mise en file sont prises sous le même verrou que l'inscription (décision d'Olivier du 19 septembre 2026) : un rejeu demandé pendant une suppression est mis en file derrière le travail de celle-ci, avec une borne qui la couvre, et ne la juge donc jamais avant son envoi. Ce qui reste entre les deux magasins : un rejeu demandé après la suppression et parti avant son `DELETE` lirait la paire vivante — impossible sur l'appareil, où l'inscription et le `DELETE` ont lieu sur le même fil sérialisé du magasin, sans suspension entre eux; la boîte d'envoi transactionnelle (tranche 1) rendra l'ordre atomique par construction. Une pierre tombale n'est posée que pour la dernière ligne d'une paire (la table `dictionnaire` admet les doublons, la clé du relais est la paire) : supprimer un doublon n'annonce rien. Les écritures de `chuchote_sync` passent par `commit()` et son résultat : une pierre tombale qui n'a pas pu être inscrite — ou dont l'inscription a levé — part aussitôt, une seule fois, la suppression locale a lieu quand même et un avertissement est journalisé; toute autre écriture refusée de la file est journalisée et n'altère pas ce que le coordinateur en sait, et un mot réappris dont la pierre tombale n'a pas pu être retirée de la file est tenu pour vivant par tout rejeu jusqu'à ce qu'une écriture réussisse sans elle.
- **Empreinte.** Liée à l'adresse de base du relais, effacée par toute mutation annoncée au relais (supprimer un doublon n'en est pas une : la signature, qui ne déduplique pas, change quand même au démarrage suivant et la republication a lieu), réinscrite seulement quand une republication complète est passée sans mutation concurrente **et** qu'aucune pierre tombale n'est en file — une suppression en suspens n'est pas décrite par ce qui vient d'être publié, et le démarrage suivant republie plutôt que de laisser un mot vivant ici et mort au relais; le saut est journalisé (« Empreinte non inscrite : une pierre tombale est en file »). Amplification assumée et bornée : une pierre tombale que le relais refuse durablement (4xx) reste en file, donc chaque démarrage du service republie tout le dictionnaire jusqu'à sa péremption, 30 jours au plus.
- **Délais et consentement.** Tout envoi attend au-delà des 30 s que le relais s'accorde (`SyncTimeouts`, 45 s), pour qu'un délai côté client implique que le relais a renoncé — sans quoi un ajout expiré pourrait encore s'écrire après la pierre tombale envoyée ensuite; la marge de 15 s couvre ce qui précède l'entrée dans la fonction (démarrage à froid de la plateforme, routage, TLS) et le retour de la réponse, le budget du relais ne courant qu'à partir de son entrée. Restent la fenêtre d'une requête SQL déjà partie quand la fonction est tuée et, plus large, une panne de transport après l'envoi du corps (connexion coupée avant la réponse) : l'envoi est tenu pour échoué aussitôt alors que le relais peut encore écrire, et une pierre tombale envoyée ensuite peut être dépassée; Codex l'a redit à la cinquième ronde, aucun délai ne ferme cela — versions de mutation côté relais, tranche 1 (`Chuchote-Flow/.docs/OPEN_DECISIONS.md` D-006, décision d'Olivier du 19 septembre 2026). Le consentement est relu juste avant chaque envoi puis observé pendant toute sa durée (`RemoteConsentGuard`, le même garde que la transcription distante) : retiré pendant un envoi, la connexion est fermée depuis le fil qui révoque, l'envoi compte comme refusé — pierre tombale en file, pas d'empreinte — et les octets déjà partis sont tenus pour irrévocables. Un ajout dont l'insertion SQLite a échoué (`insert` rend -1 sans lever) n'est pas annoncé au relais : il y gagnerait un mot que l'appareil ne connaît pas et qu'aucune ligne ne permettrait de retirer.
- **Résiduels assumés de l'ordre pierre tombale puis `DELETE`**, tous auto-réparés ou bornés : le processus meurt entre l'inscription et le `DELETE` (l'entrée est encore là au lancement suivant, la pierre tombale part puis la republication la rend vivante au relais); le `DELETE` échoue (disque, verrou SQLite) alors que la pierre tombale est déjà en file et partira (le desktop perd le mot jusqu'à la republication suivante, qui le rend); la pierre tombale peut atteindre le relais avant que le `DELETE` ait eu lieu (même réparation).

Les paramètres du relais utilisent un `SharedPreferences` séparé nommé `remote_transcription` :

- `enabled`;
- `base_url`;
- `token`.

Le runtime ne lit pas ces trois valeurs séparément au fil d'une requête. Il
capture un snapshot immuable `baseUrl + token` seulement si la configuration
est complète et activée. Importer un lien partagé remplace URL et jeton comme
une seule paire; un lien incomplet est refusé sans candidat partiel. Modifier
l'URL seule désactive le relais et efface l'ancien jeton, afin d'empêcher une
requête vers une nouvelle origine avec un secret précédent.

Références : [`PreferencesViewModel.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/preferences/PreferencesViewModel.kt#L18-L99) et [`RemoteTranscriptionSettings.kt`](../app/src/main/kotlin/dev/soupslurpr/transcribro/remote/RemoteTranscriptionSettings.kt#L5-L39).

La base et les préférences ne sont pas chiffrées par le code applicatif.

## Sauvegarde Android

Le manifeste active `android:allowBackup="true"`. Les nouveaux WAV vivent sous
`noBackupFilesDir`, qui est hors sauvegarde automatique. En défense
supplémentaire pour d'éventuels fichiers hérités, `backup_rules.xml` exclut
`files/dictations` et `data_extraction_rules.xml` l'exclut du cloud et du
transfert d'appareil. Les deux générations de règles excluent aussi tout le
domaine `database` — donc `chuchote.db`, l'historique et le dictionnaire — ainsi
que `shared_prefs/remote_transcription.xml`. Le bearer token et les données de
dictée ne doivent donc pas être restaurés implicitement sur un autre appareil.

Références : [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L29-L38), [`backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml) et [`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml).

**Inférence à valider sur un appareil et un compte réels** : seules les
préférences générales non secrètes restent potentiellement éligibles aux
mécanismes Android par défaut. Les règles déclarées excluent la base, le
dictionnaire, les WAV et la configuration du relais, mais leur application
effective doit encore être éprouvée sur les API ciblées. La sauvegarde Android
n'est pas une synchronisation Chuchote.

## Migrations existantes

- v1 → v2 ajoute `duree_ms` et `source`;
- v2 → v3 ajoute texte brut, audio, états, erreurs, tentatives, segments et
  timestamps; les anciennes lignes deviennent `completed` et conservent leur
  texte comme `raw_text`.

Les six scénarios du test instrumenté
[`ChuchoteStoreMigrationTest.kt`](../app/src/androidTest/kotlin/dev/soupslurpr/transcribro/memory/ChuchoteStoreMigrationTest.kt)
créent chacun un cache UUID, une base et des racines audio éphémères, sans
ouvrir `chuchote.db`. Le premier ouvre une base v2 synthétique avec le store v3
et vérifie que l'identifiant, le texte, le texte brut, la source et l'état final
sont conservés. Le second vérifie par SQLite que la réhabilitation conserve les
textes, l'état, le chemin absolu et les octets du WAV, prouve qu'aucune copie
n'apparaît sous l'autre racine, puis qu'une nouvelle ligne continue d'écrire un
chemin absolu compatible avec l'APK v3 précédent. Le troisième force un échec
SQLite de réhabilitation et vérifie que l'historique, le dictionnaire et la
récupération d'une capture interrompue restent disponibles. Le quatrième place
un WAV valide derrière 100 chemins invalides permanents, vérifie leurs champs à
chaque ouverture, puis prouve le retour circulaire vers un ID inférieur devenu
valide. Il contrôle aussi l'index et les plans des deux pages keyset. Le cinquième
prouve qu'un démarrage sans mutation ne relit pas tout l'historique une seconde
fois. Le sixième force l'échec de la seconde récupération interrompue et prouve
que la première mutation reste publiée tandis que la cause SQLite exacte est
restituée au test. Les six compilent; leur relance commune sur le Samsung
SM-S721W sous Android 16 attend la nouvelle autorisation ADB. Ils ne
remplacent pas encore un essai de mise à jour avec une copie anonymisée d'un
historique réel.

## Écarts avec le desktop

Les deux bases n'ont pas de contrat commun : identifiants locaux, timestamps en unités différentes, champs textuels de sens différent et politiques de rétention divergentes. La matrice inter-dépôts se trouve dans [CROSS_REPO_DATA_AND_SYNC.md](https://github.com/ReiiViilo/Chuchote-Flow/blob/ab0479f136bc3f6fc0d9dffc22ffa08a58fd4552/.docs/CROSS_REPO_DATA_AND_SYNC.md). Elle décrit le snapshot Android de départ; le présent document possède les faits de la candidate.

## Absences nécessaires à une future synchronisation

- UUID de dictée;
- `user_id` et `device_id`;
- timestamps normalisés et révisions;
- état `pending/synced/failed`;
- idempotency key;
- tombstone de suppression;
- table d'événements ou journal de mutation;
- contrainte d'unicité du dictionnaire;
- provenance structurée du modèle et de la langue.
