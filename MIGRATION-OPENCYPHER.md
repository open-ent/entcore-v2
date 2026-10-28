# Portage entcore vers OpenCypher / ArcadeDB

Branche `feature/opencypher-arcadedb`, en préparation d'une campagne de tests sur ArcadeDB.
Le travail porte sur **deux sous-modules**, qui portent la même branche :

| Sous-module | Rôle dans le portage |
|---|---|
| `libs/edifice-entcore-libs` | couche d'accès (client REST, transactions, sélection du moteur) **et** les requêtes du socle de session |
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
| `length()` → `size()` hors chemins | 38 |
| `exists((motif))` → `EXISTS { }` | 7 |
| `extract(x IN l \| e)` → `[x IN l \| e]` | 4 |
| `length((motif))` → `EXISTS { }` | 1 |
| DDL d'index et de contraintes en syntaxe Neo4j 5 | 52 |
| `START ... node_auto_index(...)` | 4 |
| détection de doublons portée sur un index full-text | 1 |

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

## Un seuil franchi : la branche ne tourne plus sur la 3.1.9

Vérifié contre un Neo4j 3.1.9 réel, en forçant `CYPHER 3.1` :

| Conversion | Neo4j 3.1.9 |
|---|---|
| `has()` → `IS NOT NULL`, `lower()` → `toLower()` | passe |
| `{param}` → `$param` | passe |
| variables de nœud parenthésées | passe |
| `filter(…)` → `[x IN … WHERE …]` | passe |
| `extract(…)` → `[x IN … \| …]` | passe |
| `length()` → `size()`, sur liste comme sur chaîne | passe |
| `CREATE UNIQUE` → `MERGE` | passe |
| `EXISTS { motif }` | **échoue** |
| `CREATE INDEX IF NOT EXISTS FOR …` et `… REQUIRE … IS UNIQUE` | **échoue** |

Autrement dit, tout le travail jusqu'au commit `0da54d01f0` inclus reste exécutable sur la
base de production actuelle, **à condition d'avoir retiré le pin `cypher.default_language_version=2.3`**.
C'est précisément l'étape 0, et elle peut donc réutiliser ce travail plutôt que de le refaire.

À partir du commit `ab105d0e79`, la branche vise Neo4j 5 ou ArcadeDB et n'est plus déployable
sur la 3.1.9 : `EXISTS { }` et la DDL en syntaxe 4/5 n'y existent pas.

## L'auto-indexation Lucene héritée est éliminée

Les cinq `START ... node_auto_index(...)` sont traités. Quatre étaient de simples recherches
par `externalId`, devenues des `MATCH (u:User {externalId: $externalId})` adossés à la
contrainte d'unicité déjà présente au schéma. L'un d'eux passait une requête Lucene
construite en Java — `externalId:A OR externalId:B` — qui exprimait en réalité une
appartenance : la liste est désormais passée telle quelle et testée par `IN`.

Le cinquième portait l'**appariement flou** de la détection de comptes en double
(`firstName:Jan~0.6 AND lastName:Dupond~0.6`), sans équivalent en Cypher courant. Il est
porté sur un index full-text nommé, en conservant `luceneAttribute()` inchangé :

```
CALL db.index.fulltext.queryNodes('User[firstName,lastName]', $luceneQuery) YIELD node AS u
```

Vérifié sur les deux moteurs : la requête, le nom d'index et la syntaxe floue qualifiée par
champ se comportent **à l'identique** sur Neo4j 5.21 et ArcadeDB 26.11, et retrouvent bien
« Jean Dupont » ; une combinaison non pertinente ne renvoie rien.

### La création de l'index, elle, n'est pas portable

ArcadeDB refuse `CREATE FULLTEXT INDEX` (« Only standard, RANGE and TEXT index types are
supported ») et demande sa propre DDL SQL, précédée de la déclaration des propriétés :

| Moteur | Création de l'index |
|---|---|
| Neo4j 5 | ``CREATE FULLTEXT INDEX `User[firstName,lastName]` IF NOT EXISTS FOR (n:User) ON EACH [n.firstName, n.lastName]`` — c'est le script `025-user-fulltext-index.cypher` |
| ArcadeDB | `CREATE PROPERTY User.firstName IF NOT EXISTS STRING` puis `CREATE PROPERTY User.lastName IF NOT EXISTS STRING`, enfin `CREATE INDEX IF NOT EXISTS ON User (firstName, lastName) FULL_TEXT`, le tout en langage `sql` |

Le nom d'index produit par ArcadeDB, `User[firstName,lastName]`, a été repris tel quel côté
Neo4j (entre accents graves) : la **requête applicative reste donc identique** sur les deux
moteurs, seule la mise en place du schéma diverge.

### `dbms.auto_index.nodes.enabled` peut être retiré

Plus aucun `node_auto_index` dans le code actif — les 20 occurrences restantes sont dans les
scripts `migration/*` historiques, et deux lignes de test sont commentées. L'auto-indexation
Lucene sur cinq propriétés, qui taxait toutes les écritures, n'a donc plus de raison d'être.
C'est un gain à prendre dès l'étape 0, sur la 3.1.9 actuelle.

## Reste à faire

| Motif | Occurrences | Fichiers | Nature |
|---|---|---|---|
| `CREATE UNIQUE` dans `ManualFeeder` | 2 | 1 | **décision à prendre**, voir ci-dessous |

S'y ajoute le répertoire `scripts/`, hors périmètre, décrit plus bas.

Les requêtes complètes extractibles s'analysent à **130 sur 133** contre ArcadeDB 26.11 ;
les trois restantes sont tronquées par l'extracteur, pas fautives. Les 52 instructions de
DDL rejouées deux fois de suite passent intégralement aux deux passes.

Ce qui subsiste et ne doit **pas** être converti, vérifié un à un :

- deux `length(p)` sur des chemins issus de `MATCH p=(...)`, où `length()` reste correct ;
- un `EXTRACT(EPOCH FROM NOW())` dans `SqlConversationService`, qui est du SQL PostgreSQL ;
- cinq littéraux portant `{x}` : trois propriétés Unicode de regex Java, un gabarit d'URL,
  un préfixe de `startsWith()`.

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

## Exécution réelle : l'ENT local tourne sur ArcadeDB

Fait le 28/10/2026, sur la sauvegarde ArcadeDB de la production du jour (700 624 nœuds,
2 573 584 relations) restaurée en local. Connexion, session, annuaire, registre
d'applications et fil de nouveautés répondent. La marche à suivre est dans
`scripts/ops/arcadedb-local-setup.sh` (côté dépôt parent) et dans la documentation de
déploiement ; la bascule tient à deux lignes de `starter/.env.localhost` :

```
NEO4J_URI=http://localhost:2480
GRAPH_ENGINE=arcadedb
```

### Deux défauts que seule l'exécution a révélés

**1. Un paramètre qui absorbe le mot-clé suivant.** `OAuthDataHandler.getUserIdNeo4j`
terminait un fragment par `"WHERE n.login=$login"`, sans espace, avant de concaténer
`"OPTIONAL MATCH …"` plus bas. Avec `{login}`, l'accolade fermante bornait le nom du
paramètre et l'absence d'espace était sans conséquence ; avec `$login`, le moteur lit
`$loginOPTIONAL`. **La connexion échouait**, sans message exploitable côté client.

Le même motif a été cherché partout, par `scripts/ops/cypher-concat-check.py` : il
reconstruit les paires de littéraux réellement accolés (`"A" + "B"`, rien entre les deux)
et signale celles où `A` finit et `B` commence par un caractère identifiant. **Cinq autres
occurrences** sont ressorties, toutes nées de la conversion de `HAS()` en `IS [NOT] NULL`
— le moteur lisait `IS NULLMATCH` — dans `DefaultSchoolService`, `feeder/User`,
`AbstractTimetableImporter` et `DefaultUserPositionService`.

Le contrôle est à repasser après chaque module converti : le défaut ne se voit ni à la
relecture du diff, ni à la compilation, seulement à l'exécution de la requête touchée.

**2. Les colonnes nulles ne se comportaient pas comme chez Neo4j.** `Neo4jRest` emballe
chaque ligne dans `fr.wseduc.webutils.collections.JsonObject`, une sous-classe qui rend
la valeur par défaut quand la propriété vaut `null`, là où `io.vertx.core.json.JsonObject`
rend `null` dès que la clé existe. `ArcadeDbRest` rendait les lignes telles quelles : tout
`getBoolean("x", false)` d'entcore sur une colonne ramenée à `null` par un `OPTIONAL MATCH`
levait un NullPointerException — constaté sur `blockedProfile`, en plein contrôle de mot de
passe. L'adaptateur applique désormais le même emballage que `Neo4jRest`.

C'est le genre d'écart qu'aucune matrice de compatibilité ne voit : la requête passe, le
résultat est juste, c'est la *forme* du résultat qui diffère.

**3. Deux paramètres que la conversion ne pouvait pas convertir.** `{1d}`,
`{1stLoeWidgets}` et `{2ndLoeWidgets}` commencent par un chiffre : `$1d` serait lu comme
un paramètre positionnel, la conversion mécanique les avait donc laissés tels quels — et
ArcadeDB lisait `{1d}` comme une map, d'où un `extraneous input` sur la liste des widgets
et sur les groupes de direction. Les trois sont renommés, liaison Java comprise.

**4. Une projection agrégeante qu'ArcadeDB refuse.** Dans `DefaultSchoolService.userList`,
un `WITH` portait `CASE WHEN … ELSE COLLECT(…) END + dupsDet as duplicates` où `dupsDet`
n'était ni clé de regroupement ni agrégé. Neo4j 3.1 l'acceptait ; ArcadeDB répond
`AmbiguousAggregationExpression`, faute de pouvoir déterminer les clés — et c'est toute la
liste des utilisateurs d'un établissement qui tombait. `dupsDet` devient clé de
regroupement (il est constant pour un utilisateur, venant lui-même d'un agrégat) et la
concaténation est reportée sur un `WITH` suivant.

À noter : les seize autres `CASE WHEN … THEN [] ELSE COLLECT(…) END` du code passent sans
réserve. Ce n'est pas la forme qui gêne, c'est la variable hors clé.

### Les requêtes du socle de session étaient restées en Cypher 2.3

La première passe n'avait porté que `libs/entcore`. Or `generateSessionInfos` — la requête
qui construit la session à chaque connexion — vit dans `libs/edifice-entcore-libs/session`,
et `common` porte les filtres de droits, le partage, les quotas et les postes. Converti
depuis : 17 fichiers, `{param}` → `$param`, `HAS`/`exists` → `IS [NOT] NULL`,
`FILTER(…)` → `[… WHERE …]`, `LENGTH` → `size`, variables de nœud parenthésées, et les deux
prédicats de motif de la requête des widgets portés sur `EXISTS { }`.

Un `CREATE UNIQUE` y restait, dans la création de poste utilisateur. Il est converti en
`CREATE` sans changement de comportement, contrairement au cas de `ManualFeeder` laissé en
suspens : l'identifiant y est un UUID tout juste tiré et l'absence du poste est vérifiée
juste avant, si bien que `CREATE UNIQUE` ne pouvait réutiliser aucun nœud.

### Ce qui n'est pas porté, et le reste visiblement

Seuls les 15 modules d'`org.entcore` embarquent l'adaptateur. Les **29 autres jars** présents
en local (`calendar`, `presences`, `formulaire`, `rbs`, `support`, `portal-mui`, `video`,
`schoolbook`, `workflowhub`, `edt`, `diary`…) embarquent chacun leur propre copie d'une
ancienne `entcore-common` : ils instancient `Neo4jRest`, interrogent les chemins REST de
Neo4j sur le port d'ArcadeDB et reçoivent des 404 — y compris leur sonde de santé
périodique, d'où un flot de `Neo4j Read check failed with status: 404` dans le journal.
Leurs propres requêtes sont par ailleurs toujours en dialecte 2.3.

Les porter demande de republier chaque module contre les libs converties : c'est le volume
réel qui sépare « le socle tourne sur ArcadeDB » de « l'ENT tourne sur ArcadeDB ».

### Une seule divergence de schéma subsiste au démarrage

`CREATE FULLTEXT INDEX` est refusé par ArcadeDB (« Only standard, RANGE and TEXT index
types are supported »). L'index est posé par la DDL SQL d'ArcadeDB, que
`arcadedb-local-setup.sh` applique : 514 645 entrées indexées, et la requête applicative
`db.index.fulltext.queryNodes('User[firstName,lastName]', …)` est inchangée.

### Dimensionnement

ArcadeDB est tombé en `OutOfMemoryError` au premier démarrage complet de l'ENT avec 3 Go de
tas : le graphe pèse 2,2 Go sur disque et les requêtes d'annuaire ramènent des milliers de
lignes. Le tas est porté à 8 Go, le conteneur à 10 Go.

## Étape suivante conseillée

Retirer `cypher.default_language_version=2.3` et mesurer sur Neo4j 3.1.9 avant de changer
quoi que ce soit d'autre : le banc chiffre ce gain entre 29 % et 43 %, il est indépendant
du choix de base, et il valide la conversion déjà faite.
