# Portage entcore vers OpenCypher / ArcadeDB

Branche `feature/opencypher-arcadedb`, en préparation d'une campagne de tests sur ArcadeDB.
Le travail porte sur **deux sous-modules**, qui portent la même branche :

| Sous-module | Rôle dans le portage |
|---|---|
| `libs/edifice-entcore-libs` | couche d'accès : client REST, transactions, sélection du moteur |
| `libs/entcore` | les requêtes : sortie du dialecte Cypher 2.3 |

## Pourquoi cette branche

La base du monolithe est en Neo4j 3.1.9 avec `cypher.default_language_version=2.3`, ce qui
fige tout le workload sur le planner de 2015. Sortir de ce verrou est utile en soi, quelle
que soit la base cible. ArcadeDB est évalué parce qu'il apporte le clustering et le backup
à chaud sous licence Apache 2.0, là où Neo4j les réserve à son édition Enterprise.

## État

### Fait — couche d'accès (`libs/edifice-entcore-libs`)

`ArcadeDbRest` implémente l'interface `GraphDatabase` existante, à côté de `Neo4jRest`.
Le moteur se choisit par configuration, sans toucher aux appelants :

```json
{
  "graph-engine": "arcadedb",
  "server-uris": ["http://arcadedb:2480"],
  "database": "openent",
  "username": "root",
  "password": "..."
}
```

`graph-engine` vaut `neo4j` par défaut : le comportement actuel est inchangé tant que la
clé n'est pas posée, ce qui permet de comparer les deux moteurs sur la même base de code.

Correspondance des API :

| entcore / Neo4j 3.x | ArcadeDB 26.x |
|---|---|
| `POST /cypher` | `POST /api/v1/query\|command/{db}` |
| `POST /transaction` | `POST /api/v1/begin/{db}` |
| `POST /transaction/{id}` | `POST /api/v1/command/{db}` + en-tête `arcadedb-session-id` |
| `POST /transaction/{id}/commit` | `POST /api/v1/commit/{db}` |
| `DELETE /transaction/{id}` | `POST /api/v1/rollback/{db}` |

Écarts assumés, à vérifier pendant les tests :

- entcore identifie ses transactions par un entier, ArcadeDB par un identifiant de session.
  La correspondance est tenue **en mémoire de l'instance**, là où le `transactionId` Neo4j
  était porté par le serveur. Une transaction ne survit donc pas à un redémarrage de module,
  ni ne traverse deux instances.
- `resetTransactionTimeout` est sans effet : ArcadeDB ne prolonge pas une transaction ouverte.
- `unmanagedExtension` échoue explicitement : mécanisme de plugin propre à Neo4j.
- Les index legacy Lucene (`legacy-indexes`) ne sont pas créés et sont signalés au démarrage.
- `executeBatch` enchaîne les requêtes séquentiellement : l'endpoint `/api/v1/batch` d'ArcadeDB
  est un import en masse, pas un lot d'instructions.

### Fait — requêtes (`libs/entcore`)

Première passe mécanique, vérifiable sans base :

| Conversion | Occurrences |
|---|---|
| `NOT(HAS(x.y))` → `x.y IS NULL` | 117 |
| `HAS(x.y)` → `x.y IS NOT NULL` | 139 |
| `lower(` → `toLower(` | 18 |

Cible retenue : `IS [NOT] NULL` directement, et non le palier `exists(prop)` — `has()` a été
retiré de Cypher en 3.0 et `exists(prop)` en 5.0. C'est aussi le choix fait dans la tentative
de portage Neo4j 5 de 2025 (`open-ent/modules`), dont la conversion est réutilisable.

Les six modules touchés (`feeder`, `auth`, `directory`, `infra`, `communication`, `app-registry`)
compilent.

Périmètre : code Java et `infra/src/main/resources/neo4j/*.cypher`. Les scripts `migration/*`
historiques sont laissés en l'état, ils ne sont pas rejoués sur une base neuve.

## Validation contre un serveur ArcadeDB réel

Faite le 28/10/2026 contre **ArcadeDB 26.11.1** (conteneur `arcadedata/arcadedb:latest`).

### L'adaptateur

`ArcadeDbRestProbe` (dans `libs/edifice-entcore-libs`) exerce `ArcadeDbRest` de bout en bout.
**9 sondes sur 9 passent** : lecture simple, écriture paramétrée, `executeBatch` avec index
préservé, transaction ouverte puis reprise et validée, persistance vérifiée après commit,
rollback effectif, et remontée d'erreur au contrat `{message}` attendu par `Neo4jResult`.

Trois hypothèses de conception sont confirmées par l'exécution :

- `opencypher` est bien l'identifiant de langage à employer ;
- `POST /api/v1/begin` répond **204 sans corps**, l'identifiant de session étant porté par
  l'en-tête `arcadedb-session-id` ;
- la réponse de `/command` expose déjà `result` comme une liste d'objets `{colonne: valeur}`,
  il n'y a donc pas de transposition `columns`/`data` à faire, et `stats` reprend la forme
  des compteurs Neo4j (`nodesCreated`, `propertiesSet`, …).

### Le dialecte

`compat-matrix.py` interroge le moteur sur les constructions effectivement utilisées par
entcore : **44 sur 49 passent**. Les 5 échecs recoupent exactement l'inventaire ci-dessous,
ce qui est plutôt rassurant : aucune mauvaise surprise hors de ce qui était déjà identifié.

| Construction | Verdict | Remplacement validé |
|---|---|---|
| `length()` sur une liste | échec | `size()` — `length()` reste valide sur un chemin |
| `filter(x IN l WHERE …)` | échec | `[x IN l WHERE …]` |
| `extract(x IN l \| …)` | échec | `[x IN l \| …]` |
| `CREATE INDEX ON :L(p)` | échec | `CREATE INDEX FOR (n:L) ON (n.p)` |
| `CREATE CONSTRAINT ON … ASSERT` | échec | `CREATE CONSTRAINT FOR (n:L) REQUIRE n.p IS UNIQUE` |

Passent notamment sans réserve : `OPTIONAL MATCH`, `MERGE` avec `ON CREATE` / `ON MATCH`,
`UNWIND`, `UNION`, `FOREACH`, `DETACH DELETE`, `CASE WHEN`, `STARTS WITH` / `ENDS WITH` /
`CONTAINS`, les chemins de longueur variable `[:R*1..3]`, les projections de map,
`collect`, `head`, `last`, `coalesce`, `labels`, `id`, `type`, `split`, `substring`,
`replace`, `timestamp`, `any`, `all`, et `EXISTS { }`.

Deux remarques utiles pour la suite :

- `exists(prop)` fonctionne encore chez ArcadeDB alors que Neo4j l'a retiré en 5.0. La
  conversion déjà faite vers `IS [NOT] NULL` reste donc le bon choix : elle vaut pour les
  deux moteurs.
- Les syntaxes d'index et de contrainte attendues sont celles de Neo4j 4/5. Les 52
  occurrences concernées sont donc à reprendre de toute façon, quelle que soit la base visée.

## Reste à faire

Comptage restreint aux chaînes effectivement Cypher (les appels Java `.filter(` sont exclus) :

| Motif | Occurrences | Fichiers | Nature |
|---|---|---|---|
| `{param}` → `$param` | 1492 | 121 | mécanique mais massif, dans des requêtes concaténées |
| `filter(...)` → `[x IN ... WHERE ...]` | 76 | 18 | réécriture, pas un remplacement |
| `CREATE UNIQUE` → `MERGE` | 54 | 13 | sémantique proche, à relire une à une |
| `length()` | 37 | 15 | à arbitrer : valide sur un chemin, à passer en `size()` sur liste/chaîne |
| `START ... node_auto_index(...)` | 5 | 3 | à reprendre sur index de schéma (`feeder`) |
| `extract(...)` → `[x IN ...]` | 2 | 2 | réécriture |
| `exists((pattern))` → `EXISTS { }` | 2 | 1 | réécriture |

Le gros morceau reste `{param}` → `$param`. Un simple remplacement global est dangereux :
ces requêtes sont construites par concaténation de chaînes Java, et les accolades servent
aussi aux littéraux de map Cypher (`{name: 'x'}`) et aux chaînes JSON embarquées.

Deux points non couverts par cette liste, à traiter avant toute campagne :

- `dbms.auto_index.nodes.enabled` (auto-indexation Lucene héritée) n'a de raison d'être que
  pour les 5 requêtes `START` du feeder. À retirer une fois celles-ci reprises.
- Le portage ne dit rien de la conformité réelle du moteur OpenCypher d'ArcadeDB, annoncée
  à 97,8 % du TCK par l'éditeur. C'est précisément ce que la campagne doit mesurer.

## Étape suivante conseillée

Retirer `cypher.default_language_version=2.3` et mesurer sur Neo4j 3.1.9 avant de changer
quoi que ce soit d'autre : c'est le gain de performance le plus accessible, il est
indépendant du choix de base, et il valide la conversion déjà faite.
