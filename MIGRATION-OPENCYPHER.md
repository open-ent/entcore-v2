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
| `{param}` → `$param` | 1790 |
| variables de nœud parenthésées (`u-[:IN]->` → `(u)-[:IN]->`) | 488 |
| `exists(prop)` → `IS [NOT] NULL` | 44 |
| `filter(x IN l WHERE p)` → `[x IN l WHERE p]` | 76 |
| `CREATE UNIQUE` → `MERGE` | 53 |

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

## Comparaison de performance

Banc dans `libs/edifice-entcore-libs/common/src/test/resources/benchmark`, mesuré le
28/10/2026 sur 100 000 utilisateurs / 100 structures, conteneurs à 3 Go et 4 cœurs chacun.
Latence médiane en ms.

| | Neo4j 3.1.9 C2.3 | Neo4j 3.1.9 C3.1 | Neo4j 5.21 | ArcadeDB 26.11 |
|---|---|---|---|---|
| plancher protocolaire | 0.55 | 0.54 | 1.69 | 0.41 |
| agrégat par structure | 103.34 | 73.44 | **17.08** | 20.54 |
| parcours global + agrégat | 133.80 | 108.39 | **19.36** | 31.10 |
| parcours lourd (paires) | 21863 | 12396 | **1214** | 7819 |
| 20 000 lignes rendues | 53.85 | 57.26 | 135.87 | **19.34** |
| écriture simple | 5.69 | 5.51 | 3.13 | **0.76** |
| écriture en lot (1000) | 9.69 | 10.04 | 7.34 | **3.25** |

Trois conclusions, dans l'ordre d'importance pour la décision :

1. **ArcadeDB n'est pas un gain de performance face à Neo4j 5.** Il est 6,4 fois plus lent
   sur le parcours de graphe profond, qui est précisément ce qu'on attend d'une base de
   graphe. Son intérêt reste donc entier mais il est ailleurs : licence Apache 2.0,
   clustering et backup à chaud sans édition commerciale.
2. **Le pin Cypher 2.3 coûte 29 à 43 %**, à base et données identiques. C'est l'« étape 0 »
   chiffrée, et elle ne demande aucun changement d'infrastructure.
3. **Les requêtes légères ne départagent rien** : sur un lookup indexé, le coût moteur est
   de 0,02 à 0,04 ms pour les trois serveurs, le reste étant du round-trip HTTP. Le plancher
   plus élevé de Neo4j 5.21 (1,69 ms) tient à son endpoint REST ; open-ent passant par Bolt,
   cette pénalité ne le concerne pas.

ArcadeDB reste très supérieur à la 3.1.9 actuelle sur tous les postes, et meilleur que
Neo4j 5 en écriture et en restitution.

## Reste à faire

| Motif | Occurrences | Fichiers | Nature |
|---|---|---|---|
| `length()` | 37 | 15 | à arbitrer : valide sur un chemin, à passer en `size()` sur liste/chaîne |
| `START ... node_auto_index(...)` | 5 | 3 | à reprendre sur index de schéma (`feeder`) |
| `exists((motif))` → `EXISTS { }` | 3 | 2 | réécriture |
| `extract(...)` → `[x IN ...]` | 2 | 2 | réécriture |
| `CREATE UNIQUE` dans `ManualFeeder` | 2 | 1 | **décision à prendre**, voir ci-dessous |

S'ajoutent les 52 occurrences de DDL d'index et de contrainte, à passer en syntaxe Neo4j 4/5.

### La décision en suspens : création d'utilisateur dans ManualFeeder

```
CREATE UNIQUE (pg)<-[:IN]-(u:User $props)
CREATE UNIQUE (pg)<-[:IN]-(u:User $props), (cpg)<-[:IN]-(u)
```

`MERGE` n'accepte pas de map de paramètres — vérifié contre ArcadeDB, qui refuse
explicitement : *« Parameters cannot be used as predicates in MERGE patterns »*. Deux issues,
qui ne sont pas équivalentes :

| Conversion | Comportement |
|---|---|
| `CREATE (u:User $props)` | crée toujours. **Change le comportement** : `CREATE UNIQUE` réutilisait l'utilisateur existant si toutes ses propriétés correspondaient. |
| `MERGE (u:User {externalId: $props.externalId}) ON CREATE SET u = $props` | idempotent, mais sur la **seule** clé `externalId` au lieu de l'ensemble des propriétés. |

Le choix n'est pas mécanique. `ManualFeeder.createUser` ne génère un `externalId` que s'il
est absent : un appelant peut donc en fournir un, et l'écriture actuelle est alors idempotente.
Passer en `CREATE` créerait un doublon d'utilisateur dans l'annuaire ; passer en `MERGE` sur
`externalId` dédoublonnerait sur cette seule clé, ce qui est sans doute l'intention d'origine
mais reste un changement de comportement à assumer.

Les deux occurrences sont donc laissées en l'état, en attente d'arbitrage.

## Étape suivante conseillée

Retirer `cypher.default_language_version=2.3` et mesurer sur Neo4j 3.1.9 avant de changer
quoi que ce soit d'autre : le banc chiffre ce gain entre 29 % et 43 %, il est indépendant
du choix de base, et il valide la conversion déjà faite.
