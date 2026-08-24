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

Les préférences générales utilisent DataStore, notamment thème, acceptation, retour au clavier, démarrage et envoi automatiques. L'acceptation est versionnée par `ACCEPTED_PRIVACY_POLICY_AND_LICENSE_2026_08_23`; la clé historique n'accorde aucun accès aux traitements de la politique courante.

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
