# Travelling Dimension

Mod Fabric pour Minecraft 26.3, 26.2 et 26.1.2, un jar par version. Une dimension de voyage de
type OVERWORLD, compressée au ratio configurable (1:16 par défaut) : un bloc parcouru dans VOYAGE
vaut seize blocs d'OVERWORLD.

Le modèle est celui du NETHER, avec des règles rendues prévisibles : la destination se calcule
par conversion de coordonnées, puis se résout en cherchant un portail existant autour du point
obtenu. Le mod applique aussi cette mécanique repensée aux **portails ordinaires du NETHER**,
chaque apport étant réglable séparément.

| | |
|---|---|
| Minecraft | 26.3, 26.2 et 26.1.2, un jar par version : `travellingdimension-<version>+<version du jeu>.jar` |
| Fabric Loader | 0.19.5 ou plus récent, **calculé** : voir Build |
| Dépendances | Fabric API, celle contre laquelle chaque jar est compilé ou plus récente (0.161.0 en 26.3 et 26.2, 0.155.2 en 26.1.2), **calculée** : voir Build ; Fabric Language Kotlin 1.14.1 ou plus récent |
| Embarqué | Storify 0.4.0-SNAPSHOT, la bibliothèque des fichiers JSON du mod, avec tomlkt et json5, en jar-in-jar |
| Facultatif | Mod Menu et Cloth Config : 21.0.0 et 26.3.159 en 26.3, 20.0.3 et 26.2.155 en 26.2, 18.0.2 et 26.1.154 en 26.1.2 (l'écran n'est éprouvé en jeu qu'en 26.2) |
| Côté | client **et** serveur |
| Langage | Kotlin 2.4.20, Java 25 pour les mixins |
| Licence | LGPL-3.0-only, voir La licence |

**Le cahier des charges** est ici même : `Travelling Dimension.md`. La documentation joueur,
`comprendre-les-portails.md` et `configuration.md`, vit dans `01-docs\user-docs\` du classeur,
hors du dépôt.

---

## En jeu

**L'Amethyst Igniter**, durabilité 64, rangé avec les outils :

```
 A        A = minecraft:amethyst_block
DFD       D = minecraft:diamond      F = minecraft:flint_and_steel
 E        E = minecraft:echo_shard
```

**Le cadre** : rectangle vertical du bloc configuré, `minecraft:amethyst_block` par défaut.
Intérieur de 1 à 21 de large, paire ou impaire, et de 3 à 21 de haut. Réglage `portalFreeSize`
actif, le minimum tombe à 1x1 et le maximum devient `portalMaxSize`, jusqu'à 41. L'igniter ne fonctionne
que dans l'OVERWORLD et dans VOYAGE.

**Les commandes** : `/where`, `/where cible`, `/tdzones` et `/tdlock` pour tous les joueurs ;
`/tdtest` et `/tdnether` pour les opérateurs de niveau 2.

---

## Architecture

Le code partagé par toutes les versions du jeu vit dans le module `common`, compilé une fois
contre la dernière release servie (le code commun suit toujours la dernière release, jamais un
snapshot). Chaque module `mc-<version du jeu>` ne porte que ce que sa version ne partage pas, et
assemble le jar de cette version. Le découpage des modules et leur build sont décrits en tête de
`settings.gradle.kts`.

```
common/src/main/kotlin/fr/roumoulou/travellingdimension/
├── TravellingDimension.kt          point d'entrée, ordre d'initialisation
├── command/
│   ├── WhereCommand.kt             /where, /where cible
│   ├── LockCommand.kt              /tdlock
│   ├── ZonesCommand.kt             /tdzones
│   ├── ZoneHighlight.kt            le rideau de particules, un joueur à la fois
│   ├── Targeting.kt                la visée commune : le bloc regardé, l'ancre du portail visé
│   └── TravelTestCommand.kt        /tdtest scan|clear
├── config/
│   ├── TravelConfig.kt             les réglages, leurs défauts, le rayon de VOYAGE calculé
│   ├── TravelConfigValidator.kt    les bornes, refusées et jamais corrigées
│   ├── ConfigManager.kt            le store Storify de config.json : ouverture validée, application, écriture
│   └── ModJson.kt                  le JSON strict de config.json, disque et réseau, le JSON5 de dev.json, les options des stores
├── dimension/
│   ├── TravelDimensionKeys.kt      les clés de la dimension
│   ├── WorldgenSelector.kt         choix du générateur, seed, fallback
│   └── GeneratorSwapper.kt         remplacement du LevelStem
├── dev/DevWorld.kt                 monde plat de développement, jamais lu en production
├── gameversion/
│   ├── GameVersionBridge.kt        ce qui diffère d'une version du jeu à l'autre
│   └── GameVersion.kt              le pont de la version servie, chargé par META-INF/services
├── item/AmethystIgniterItem.kt     allumage, annonce de destination
├── nether/
│   ├── NetherPortalBuilder.kt      création au point idéal, taille recopiée
│   ├── NetherPortalGeometry.kt     mesure d'un portail vanilla
│   ├── NetherPortalLinks.kt        choix du partenaire coloré
│   ├── NetherPortalTints.kt        stockage de la couleur (chunk)
│   ├── NetherPortalDye.kt          le clic droit au colorant
│   ├── NetherPortalSizes.kt        les bornes de taille, vanilla ou config
│   └── NetherPortalCommand.kt      /tdnether
├── network/                        transport de la config, serveur autoritaire
├── portal/
│   ├── PortalCoordinates.kt        la transformation et la distance
│   ├── TravelPortalShape.kt        détection du cadre, l'ancre
│   ├── TravelPortalPlacer.kt       recherche et pose
│   ├── TravelPortalBlock.kt        le bloc, la résolution à 4 rangs
│   ├── PortalGround.kt             épargner ce qui est bâti
│   ├── PortalFrame.kt              le bloc du cadre, configurable
│   ├── PortalTint.kt               les 16 couleurs et leur rendu
│   ├── PortalLocks.kt              les verrous
│   ├── PortalMemory.kt             la mémoire de trajet
│   └── BlockVolumes.kt             le parcours d'un pavé, partagé avec le NETHER
└── registry/                       blocs et items

common/src/main/java/.../mixin/     5 mixins communs, voir plus bas
common/src/main/resources/travellingdimension/dev.json   la ressource commentée copiée en config/travellingdimension/dev.json
common/src/client/kotlin/fr/roumoulou/travellingdimension/client/
├── TravellingDimensionClient.kt    point d'entrée client
├── ModMenuIntegration.kt           le bouton dans Mod Menu
├── config/ClientTravelConfig.kt    la config vue du client, et le droit de la modifier
├── config/TravelConfigScreen.kt    l'écran Cloth Config
└── nether/NetherPortalTintRendering.kt  teinte et pack intégré du portail du NETHER

common/src/gametest/                l'étage 2 des tests : le mod travellingdimension-gametest, jamais publié
├── kotlin/.../gametest/Harness.kt              les secteurs, les cadres posés au bloc, le voyageur
├── kotlin/.../gametest/TravelPortalGameTests.kt  formes et ancre, les trois cas de référence, la couleur, le verrou
├── kotlin/.../gametest/NetherPortalGameTests.kt  le 1x1 par le mixin des tailles, la création au point idéal
├── kotlin/.../gametest/TravelDimensionGameTests.kt  la parité du type de VOYAGE avec celui de l'OVERWORLD, contre chaque version
└── java/.../gametest/mixin/GameTestServerDimensionsMixin.java  les dimensions des datapacks sur le serveur GameTest

mc-26.1/src/main/                   ce que la lignée 26.1 ne partage pas
├── kotlin/.../gameversion/GameVersionBridge261.kt          le pont de 26.1 : la réaction aux pistons, les seize colorants
├── java/.../gameversion/mixin/ServerChunkCacheMixin.java   le mixin de mobDensity, aux signatures de 26.1 et 26.2
└── resources/                      sa configuration de mixins, sa déclaration META-INF/services
mc-26.2/src/main/                   la même chose pour 26.2
mc-26.3/src/main/                   la même chose pour 26.3

build-logic/src/main/kotlin/        les deux plugins de convention, et la tâche de vérification de compatibilité
```

---

## Le portail

### L'ancre

```kotlin
fun centre(): BlockPos = minCorner().relative(along, (width - 1) / 2)
```

Le bloc de la rangée du bas à `(largeur - 1) / 2` du coin minimal sur l'AXE. C'est la position
canonique de tout le mod : la transformation la divise ou la multiplie, la distance se mesure
dessus, et deux portails sont « le même » quand leurs ancres coïncident.

Elle appartient au portail et non au voyageur : deux entités qui traversent le même 9x9 par ses
deux bords calculent le même point idéal.

Elle est au centre et jamais à un bord. Un 9 de large et un 3 de large posés au même endroit ont
la même ancre ; avec une ancre au bord ils seraient séparés de 3 blocs, soit 48 blocs
d'OVERWORLD après conversion.

### La forme

Port de `net.minecraft.world.level.portal.PortalShape`, avec le cadre restreint au bloc
configuré et l'intérieur accepté en air ou en blocs de portail déjà posés.

`updateShape` retire le bloc dès que le cadre n'est plus complet : le portail s'éteint en
cascade, sans crash ni entité orpheline, et se rallume à l'igniter une fois réparé.

---

## La transformation

```
OVERWORLD vers VOYAGE      floorDiv(coord, ratio)
VOYAGE vers OVERWORLD      multiplyExact(coord, ratio)
Y                          jamais converti, seulement clampé
```

Division **plancher**, y compris en négatif : les cases font `ratio` blocs partout, y compris à
cheval sur zéro. Une division tronquant vers zéro donnerait une case centrale deux fois plus
large.

---

## La résolution

`TravelPortalBlock.getPortalDestination` : quatre rangs, le premier qui répond gagne.

| Rang | Règle | Réglage | Défaut |
|---|---|---|---|
| 1 | la couleur du portail source filtre les candidats | `portalTints` | actif |
| 2 | la mémoire du trajet | `rememberEntryPortal` | inactif |
| 3 | le portail le plus proche du point idéal | | actif |
| 4 | la construction au point idéal | | actif |

Un rang coupé **saute**, il ne dégrade rien : `portalTints` à false ignore les couleurs posées
sans les effacer, et les rallumer les remet en service telles quelles.

### L'emprise

Carré centré sur le point idéal, bornes comprises. Rayon `searchRadiusOverworld` (128) dans
l'OVERWORLD, `searchRadiusVoyage` (8) dans VOYAGE.

> **Invariant : `searchRadiusVoyage * ratio = searchRadiusOverworld`.**

`searchRadiusVoyage` n'est pas dans le fichier : il se calcule depuis `searchRadiusOverworld` et
`ratio`, en division entière avec un plancher de 1. L'égalité produit n'est donc exacte que
lorsque le ratio divise le rayon. Sans elle, un portail trouvé à l'aller ne retrouve pas son
partenaire au retour. Le contre-exemple chiffré est dans `PortalCoordinates.symmetricTravelRadius`.

En hauteur, tout le monde en `full_height`, une fenêtre de `verticalRadius` en `bounded`.

### Le critère

```kotlin
val scale = if (inTravel) ratio else 1
val dx = (from.x - to.x) * scale
val dz = (from.z - to.z) * scale
val dy = (from.y - to.y) * verticalWeight
return dx * dx + dy * dy + dz * dz
```

> **La distance se mesure toujours en blocs d'OVERWORLD.**

Dans VOYAGE un bloc horizontal vaut `ratio` blocs d'OVERWORLD, un bloc vertical en vaut un. Les
additionner tels quels reviendrait à additionner des kilomètres et des centimètres. Le carré
suffit, seul l'ordre compte.

Conséquence recherchée : à colonne égale, seul le Y départage, donc l'étage le plus proche gagne
et les étages bâtis à la main se répondent dans les deux sens sans colorant.

Égalité stricte : X, puis Y, puis Z. Ordre total, résultat reproductible.

### Le balayage ne lit pas les blocs un par un

`forEachCompletePortal` passe par la **palette des sections de chunk**. Une emprise de 256 blocs
de côté sur toute la hauteur compte plus de vingt millions de positions ; une section de 16³
répond en quelques comparaisons si elle contient ou non un bloc donné.

```kotlin
if (section.hasOnlyAir()) continue
if (!section.maybeHas { it.`is`(ModBlocks.TRAVEL_PORTAL) }) continue
```

La **résolution** charge les chunks : un portail existe même dans un chunk endormi, et l'ignorer
ferait naître un doublon. Le **diagnostic** ne charge rien : mesurer ne doit pas générer du
terrain.

### Et il ne fabrique jamais de terrain

Le chargement se fait au **statut d'entrée**, `ChunkStatus.EMPTY`, jamais au statut complet :

```kotlin
val chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, true) ?: continue
```

Un chunk déjà sauvegardé revient du disque avec ses blocs, donc un portail endormi reste
trouvé. Un chunk jamais généré revient vide et se saute en une comparaison de section. Charger
au statut complet, ce que fait `getChunk(x, z)`, ferait **générer** le terrain manquant sur le
thread serveur, et l'emprise d'OVERWORLD couvre 17 x 17 chunks : près de trois cents chunks
fabriqués à chaque traversée et à chaque allumage, de quoi figer une partie et bloquer un arrêt
de serveur. Vanilla procède de la même façon pour chercher un portail du NETHER
(`PoiManager.ensureLoadedAndValid`).

Le niveau n'est sollicité qu'une fois un bloc de portail trouvé dans la palette, donc seuls les
rares chunks qui en portent un sont chargés pour de bon.

**Mesuré en jeu** : un balayage sur un carré vierge de 17 x 17 chunks écrit **zéro** chunk ;
une traversée vers un portail endormi, serveur fraîchement redémarré, le retrouve et n'en crée
aucun autre.

---

## La création

`TravelPortalPlacer.build`, exactement au point idéal, à la taille et sur l'AXE du portail
source. Aucune recherche d'emplacement convenable, aucun ajustement horizontal.

1. dégagement, `clearanceMargin` de côté et `clearanceHeight` au-dessus, fluides évacués ;
2. plateforme, `platformDepth` d'épaisseur débordant de `platformMargin`, **une seule couche et
   un seul bloc de débordement par défaut**, et **blocs remplaçables uniquement** ;
3. cadre, coins compris ;
4. blocs de portail, flag 18 comme vanilla.

```kotlin
fun isProtected(pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    return state.`is`(ModBlocks.TRAVEL_PORTAL) ||
            PortalFrame.matches(state) ||
            state.getDestroySpeed(level, pos) < 0f
}
```

Ce n'est pas une politesse : sans elle, deux portails voisins se mangent l'un l'autre.

**La position d'arrivée** reproduit l'offset relatif de l'entité, lu avec l'axe du portail source
et appliqué avec celui du portail de destination. Axes différents, le regard tourne de 90 degrés.

---

## Épargner ce qu'un joueur a bâti

`PortalGround`. Trois signaux, du plus sûr au plus discutable :

| Signal | Coût | Ce qu'il dit |
|---|---|---|
| `chunk.inhabitedTime` contre `inhabitedThreshold` | nul | personne n'y a jamais mis les pieds |
| `chunk.blockEntities` | nul, le chunk tient la liste | coffres, panneaux, lits |
| `playerMadeBlocks` | balayage borné, `firstOnly` court-circuite | table de craft, verre, redstone |

> **Le jeu n'enregistre nulle part qui a posé un bloc.** Tout ceci est une présomption.

Le choix qui gouverne le reste : un faux positif coûte un décalage, un faux négatif coûte un trou
dans une base.

`clearAltitude` essaie la hauteur voulue, puis +1, -1, +2, -2, jusqu'à `buildShiftMaxOffset`. **À
égalité le haut gagne.** X et Z ne bougent jamais.

`rescueContainers` déménage les conteneurs avec `saveWithFullMetadata`, retire x/y/z du tag,
repose, recharge par `loadWithComponents`, **puis vide la source** sinon le jeu sème les objets
au sol en cassant le bloc.

`underRoof` sert au NETHER : depuis le plafond logique, on descend tant que le **cadre** mordrait
dans de l'indestructible. C'est le rectangle du cadre qui est testé et non l'emprise entière, le
dégagement et la dalle contournant déjà l'indestructible alors que le cadre s'écrit sans
condition.

---

## Le colorant

`PortalTint`, enum en propriété d'état `color` du bloc, donc 34 états avec l'axe. La couleur vit
dans la sauvegarde du monde, il n'y a aucun registre parallèle à maintenir.

> **La couleur réordonne un choix, elle n'étend jamais une portée.**

Elle filtre les candidats de l'emprise. Rien n'est refusé : autant de portails d'une même couleur
que l'on veut. Le même colorant sur un portail déjà de cette couleur l'efface.

**Le rendu.** Un portail se teinte par multiplication, et la texture d'origine est franchement
violette (moyenne R=112 V=70 B=171) : aucune teinte n'en sortait un vert ou un jaune vif. La
texture est donc neutralisée en niveaux de gris, et `PortalTint.NONE` porte la teinte
`0xAF76FF` qui reconstruit l'améthyste d'origine.

Dans les trois versions servies, l'enregistrement passe par
`BlockColorRegistry.register(BlockTintsFactory { ... })` ; `ColorProviderRegistry` n'existe plus.
En 26.1, `BlockTintsFactory` n'est arrivée qu'en cours de lignée : c'est pourquoi le jar 26.1
exige 26.1.2 et sa Fabric API (voir Build).

Six couleurs sont marquées `recommended`, les six sommets saturés du cube RVB, séparables y
compris pour un daltonisme courant.

**Le système entier se coupe** par `portalTints` : le clic droit redevient un clic droit
ordinaire et le rang 1 de la résolution saute. Les états de bloc ne sont pas touchés, donc rien
n'est perdu et rallumer le réglage remet les liens en service. Réglage jumeau de
`netherPortalTints`, indépendants l'un de l'autre.

---

## L'affichage de l'emprise

```
/tdzones        vise un portail : allume son emprise, ou l'éteint si c'était déjà lui
/tdzones off    éteint l'affichage
```

Interrupteur, pas commande ponctuelle, et **ouverte à tous les joueurs** : c'est un affichage,
il ne modifie ni ne sonde rien. Viser un AUTRE portail déplace l'affichage au lieu de
l'éteindre, ce qui permet de comparer deux emprises en deux commandes.

`ZoneHighlight` sème des particules le long des quatre murs de l'emprise de recherche d'un
portail, et ne les envoie **qu'au joueur** qui a demandé l'affichage
(`level.sendParticles(player, ...)`). Aucun code client : ça marche en vanilla.

Pour un portail de VOYAGE, l'emprise vient de
`TravelPortalPlacer.Box.around(anchor, TravelPortalPlacer.searchRadius(...))` ; pour un portail
du NETHER, de `NetherPortalLinks.reach(level)`, soit 16 blocs dans le NETHER et 128 dans
l'OVERWORLD. Dans les deux cas c'est la **même source** que la recherche elle-même, donc
l'affichage ne peut pas annoncer un territoire que le mod ne consulterait pas.

Trois choix de lisibilité : la densité se resserre près du joueur (un point par bloc sous 28
blocs, un tous les 4 au-delà), le rideau a de la hauteur plutôt qu'un trait au sol, et il suit
l'altitude du joueur à chaque redessin. Redessin toutes les 4 ticks, les particules vivant
environ une seconde.

Les murs tombent sur les **bords physiques** : le bloc `maxX` étant dans l'emprise, le mur passe
en `maxX + 1`.

---

## Le verrou

`PortalLocks`, attachement de **chunk** persistant, `Map<BlockPos, Lock>` indexée sur l'ancre.

Portée = emprise de recherche de la dimension, donc 128 blocs (OVERWORLD) et 8 blocs (VOYAGE),
le même carré : c'est exactement le territoire où deux portails se disputeraient les mêmes
voyageurs.

Le refus a **un seul point d'entrée**, `AmethystIgniterItem.useOn`. La création automatique n'est
jamais bloquée : un voyageur doit atterrir quelque part.

Une entrée dont le portail n'existe plus est ignorée à la lecture et purgée à la première
écriture dans son chunk. Casser un portail libère donc le terrain.

Un verrou posé depuis la console appartient à `CONSOLE_OWNER`, un UUID nul : aucun joueur ne le
porte, donc seuls les opérateurs peuvent le retirer.

---

## La mémoire de trajet

`PortalMemory`, attachement **d'entité** persistant avec `copyOnDeath`. Il est écrit dans le NBT
de l'entité, survit au redémarrage et voyage avec elle. Rien n'est stocké à côté, il n'y a donc
pas de registre à purger.

Le rappel n'a lieu que si l'entité repart du portail exact sur lequel elle était arrivée, et que
ce portail est toujours complet à l'ancre exacte.

`Entity` reçoit `getAttached` / `setAttached` par injection d'interface Loom.

---

## Les portails du NETHER vanilla

Tout passe par **deux `@WrapOperation` dans le même mixin**, `NetherPortalBlockMixin`, autour de
deux appels de `NetherPortalBlock.getExitPortal`.

| Appel enveloppé | Ce qu'on substitue | Réglage |
|---|---|---|
| `PortalForcer.findClosestPortalPosition` | le partenaire coloré | `netherPortalTints` |
| `PortalForcer.createPortal` | une construction au point idéal | `netherPortalPlacement` |

Sans couleur posée, le premier rend `null` et l'appel d'origine part tel quel. Réglage coupé, le
second aussi. **Le jeu se comporte exactement comme sans le mod.**

Tout le reste du chemin de Mojang est intact : conversion, mesure du rectangle, placement de
l'entité, son, ticket de chunk.

**La portée est celle de vanilla** : `destIsNether ? 16 : 128` blocs, carré Chebyshev via
`PoiManager.getInSquare`. Ces deux nombres désignent le même carré de monde au ratio 8, donc la
réciprocité est gratuite (`|A/8 - B| <= 16` équivaut à `|A - 8B| <= 128`), contrairement à VOYAGE.

**Le stockage de la couleur.** Impossible d'ajouter un état à `minecraft:nether_portal` : le
remplacer le sortirait du point d'intérêt, et un portail teint deviendrait invisible aux
voyageurs sans couleur. La couleur vit donc dans un attachement de **chunk** persistant et
synchronisé. Comme l'état du bloc ne change pas, rien ne redessinerait la section : une mise à
jour de bloc à état inchangé est ignorée par le client. Le client écoute donc l'attachement de
chaque chunk chargé (`onAttachedSet`) et marque à redessiner les sections dont une couleur a
changé.

**La taille recopiée** passe par `NetherPortalGeometry.rectangleAt`, donc par
`BlockUtil.getLargestRectangleAround`, le calcul de Mojang, lu dans le niveau de l'entité qui n'a
pas encore bougé. Bornes 2 à 21 et 3 à 21 par défaut, et de 1 à `netherPortalMaxSize` dès que
`netherPortalFreeSize` est actif, donc exactement celles de la détection de forme. Tout ce qui
manque ramène au 2x3 : la taille est un confort, jamais une condition.

La texture neutralisée est livrée dans un pack intégré `nether_portal_tints`,
`PackActivationType.DEFAULT_ENABLED`, donc désactivable. Coupé, vanilla reprend au pixel près et
les liens marchent quand même.

---

## La génération du monde

Le JSON embarqué définit la génération par défaut, vanilla large biomes. Pour tout autre choix,
`WorldgenSelector` résout une cible au démarrage et `GeneratorSwapper` remplace le `LevelStem` à
la création des mondes, **jamais fatalement** : en cas de problème le JSON d'origine reste.

| Mode | Ce qui se passe |
|---|---|
| `terralith` | référence les identifiants que Terralith remplace, sans toucher à l'OVERWORLD |
| `vanilla` | le JSON par défaut quand `largeBiomes` est actif, sinon un swap vers `minecraft:overworld` |
| `tectonic` | hérite de l'OVERWORLD, que Tectonic remplace globalement |
| `william` | pack embarqué re-namespacé, généré par un outil du projet |
| `custom` | deux identifiants libres |

Le mod demandé mais absent bascule sur vanilla, avec un avertissement dans les logs et un message
aux opérateurs à la connexion.

**Le seed dédié** découple le terrain de VOYAGE de celui du monde. Il alimente `getSeed`
(`RandomState` plus structures via `ChunkMap`) et le seed de zoom des biomes.

---

## Les mixins

| Mixin | Cible | Rôle |
|---|---|---|
| `NetherPortalBlockMixin` | `NetherPortalBlock` | les deux `@WrapOperation` du NETHER |
| `MinecraftServerMixin` | `MinecraftServer` | remplacement du `LevelStem`, seed de zoom |
| `ServerLevelMixin` | `ServerLevel` | seed dédié via `getSeed` |
| `ChunkGeneratorMixin` | `ChunkGenerator` | `structures = false` dans VOYAGE |
| `ServerChunkCacheMixin` | `ServerChunkCache` | `mobDensity` dans VOYAGE ; un par module de version, ses deux cibles changeant de signature en 26.3 |
| `PortalShapeMixin` | `PortalShape` | les bornes de taille du NETHER, `netherPortalFreeSize` |

Tous vivent dans `common` (`travellingdimension.mixins.json`), sauf `ServerChunkCacheMixin`, qui
vit dans chaque module de version, sous `gameversion.mixin`, avec sa propre configuration
(`travellingdimension.gameversion.mixins.json`) : un mixin dont la cible change d'une version à
l'autre y déménage.

**Le portail de VOYAGE n'a aucun mixin** : `TravelPortalBlock` implémente l'interface `Portal`.

---

## La configuration

`config/travellingdimension/config.json`, JSON **strict** puisque l'écran le réécrit : ni
commentaire, ni virgule finale, ni clé inconnue ou déclarée deux fois ; seul le BOM est toléré.
`dev.json`, que le mod ne réécrit jamais, se lit en **JSON5** : ses commentaires, qui
documentent chaque réglage, et une virgule finale passent ; une clé inconnue ou déclarée deux
fois reste refusée.

**Le fichier est porté par un store Storify** (`ConfigManager`), et `dev.json` par un second,
en lecture seule : la création depuis les défauts au premier lancement, ou depuis la ressource
commentée du jar pour `dev.json` ; l'écriture atomique, jamais de fichier tronqué ; un décodage
qui nomme le fichier et la ligne fautive ; la validation au chargement, par
`TravelConfigValidator`, qui porte les bornes et refuse un fichier invalide avec la liste de ses
problèmes. Le store s'ouvre à l'initialisation du mod, et un échec, fichier illisible ou
invalide, remonte jusqu'au rapport de crash : le jeu ne démarre pas sur une configuration cassée.
Sans auto-save : c'est le mod qui écrit, au moment où la configuration change. La racine du store
est `ConfigManager.current` elle-même, une data class à `var`, modifiée propriété par propriété
sous son verrou ; le reste du mod la lit et ne l'écrit jamais. `searchRadiusVoyage` n'est pas
dans le fichier : il se calcule depuis `searchRadiusOverworld` et `ratio`.

Ce qui reste non fatal, parce que ce n'est pas le fichier : un bloc ou un identifiant bien formé
mais absent du jeu (repli sur le défaut, avertissement), un générateur absent (bascule vanilla),
et une demande invalide venue de l'écran, refusée entière avec ses raisons.

**Le serveur est autoritaire.** Deux paquets Fabric transportent la config en JSON : `config_sync`
serveur vers client, envoyé à la connexion et après chaque modification, et `config_update` client
vers serveur, qui n'est qu'une demande. Le droit de modifier est calculé par le serveur, hôte du
solo ou permission de niveau 4.

Cinq réglages ne sont lus qu'au chargement, listés par `needsRestartAgainst` : `worldgen`, `seed`,
`largeBiomes`, `customNoiseSettings`, `customBiomePreset`. L'écran porte `requireRestart()` sur
eux et le serveur redit la même chose en chat.

---

## Les invariants

1. Une configuration est entière et valide, ou le jeu ne démarre pas.
2. `searchRadiusVoyage * ratio = searchRadiusOverworld`.
3. La distance se mesure en blocs d'OVERWORLD, des deux côtés.
4. La couleur réordonne un choix, elle n'étend jamais une portée.
5. Tout se calcule sur l'ancre, jamais sur la position du voyageur.
6. Rien de ce qui a été bâti n'est amputé.
7. Le monde reste sans état : aucune table de liaison en sauvegarde.
8. Mesurer ne génère jamais de terrain.
9. Jamais de portail créé au-dessus du toit du NETHER.
10. Dans le NETHER, chaque intervention a son interrupteur, et toutes coupées le jeu se comporte
    exactement comme sans le mod.
11. VOYAGE a le type de dimension de l'OVERWORLD, `coordinate_scale` mis à part : mêmes champs,
    mêmes attributs à leur valeur effective, dans chaque version servie.

---

## Build

```powershell
.\gradlew.bat build --console=plain
```

Un jar par version du jeu, dans
`mc-<version>/build/libs/travellingdimension-<version du mod>+<version compilée>.jar` (par
exemple `mc-26.1/build/libs/travellingdimension-2.8.0+26.1.2.jar`), Storify, tomlkt et json5
embarqués sous `META-INF/jars/`. **`remapJar` n'existe plus en 26.x**, le jeu n'étant plus
obfusqué : c'est la tâche `jar` qui produit le livrable. `build` joue les **trois étages de
test** : la logique pure une fois, dans `common` (`:common:test`, 12 tests), puis, contre chaque
version du jeu, le jeu amorcé (`:mc-<version>:testMC`, 19 tests) et le serveur GameTest
(`:mc-<version>:runGameTest`, 9 tests, une vingtaine de secondes) ; leur partage vit dans
`01-docs/technical-docs/02-finalized/strategie-de-test.md`, hors du dépôt. Il joue aussi, contre
chaque version, la **vérification de compatibilité** (`:mc-<version>:checkCommonCompatibility`).

**Les modules.** `common` porte le code partagé et ses tests, compilés une fois contre la
dernière release servie : le code commun suit toujours la dernière release, jamais un snapshot.
Chaque module `mc-<version>` sert une lignée du jeu (`mc-26.1` sert 26.1.2, la dernière release
de la lignée 26.1), porte ce que sa version ne partage pas, assemble son jar et rejoue les étages
1 et 2 contre son jeu. Ce qu'ils partagent vit dans les plugins de convention de `build-logic`,
que le `build.gradle.kts` de la racine charge une fois pour tous. Les en-têtes de
`settings.gradle.kts`, des deux plugins et de chaque module disent le reste.

**La vérification de compatibilité.** `common` compilé contre la dernière release, le
compilateur ne voit pas ce qu'il emploie et qu'une version plus ancienne n'a pas. Les étages 1
et 2 ne le voient que là où un test passe. `checkCommonCompatibility` lit le bytecode de
`common` et cherche chaque classe, méthode et champ qu'il nomme dans le classpath du module de
version, jeu, Fabric API, Mod Menu et Cloth Config de sa version compris : ce qui manque fait
échouer `build`, et se règle par le pont de version (`GameVersionBridge`). Elle ne voit pas les
cibles des mixins, écrites en chaînes, que l'étage 2 éprouve.

Les environnements de développement, le serveur GameTest et les déploiements sont l'affaire du
plugin **Outfitter** (`S:\16\_V\Outfitter`, consommé en build composite : voir
`settings.gradle.kts`). Il tient le groupe de tâches `outfitter` ; chaque module de version
déclare ses environnements, son serveur GameTest, ses cibles et le panier de son serveur dédié.

| Tâche | Effet |
|---|---|
| `:mc-<version>:runClient`, `:mc-<version>:runServer` | client et serveur de dev, vanilla purs, dans `mc-<version>/run/client` et `mc-<version>/run/server` ; `prepare<Env>` d'Outfitter les prépare avant |
| `:mc-26.2:runClientModded`, `:mc-26.2:runServerModded` | les mêmes **avec le noyau MDTK**, en 26.2 seulement, MDTK n'existant qu'en 26.2 : dans `mc-26.2/run/client-modded` et `mc-26.2/run/server-modded` |
| `:mc-<version>:runGameTest` | l'étage 2 : un serveur GameTest sans fenêtre dans `mc-<version>/run/game-test`, remis à neuf avant chaque run par Outfitter (`freshGameTest`), rapport XML dans `mc-<version>/build/test-results/gameTest/`, branché sur `check` ; `"-Poutfitter.gametest_filter=<motif>"`, entre guillemets, n'en joue qu'une partie |
| `:mc-<version>:checkCommonCompatibility` | la vérification de compatibilité : `common` ne nomme que ce que ce jeu a ; compte rendu dans `mc-<version>/build/reports/common-compatibility.txt`, branchée sur `check` |
| `:mc-26.2:deployToPrism` | pousse le jar seul dans l'instance de référence (`outfitter.reference_instance_dir`), et avertit si Fabric API ou FLK y manquent. Pas de cible `prism` en 26.1 ni en 26.3 : l'instance de référence est en 26.2 |
| `:mc-<version>:deployToServerPur` | pousse le jar et le panier `serverPurBundle`, Fabric API et FLK aux versions du module, dans le serveur dédié « pur » de sa version (`05-instances/server-pur-<version>`) |
| `:mc-<version>:setupServerPur` | prépare le serveur dédié « pur » de sa version depuis le profil `dev` de l'entrepôt |
| `resetEnvironments` | retire les marqueurs et le dossier `config` des environnements de chaque module, pour forcer une re-synchronisation ; les mondes restent |
| `resetWorlds` | retire les mondes de dev et leur marqueur |
| `:<module>:listRepositories` | les dépôts de dépendances effectifs d'un module, ceux que Loom pose compris |

**`machine.properties`, à créer sur chaque machine**, à la racine. Un module de version peut
avoir le sien, qu'Outfitter lit après celui de la racine. Ce fichier n'est pas versionné, et le
build s'en passe : sans lui, Outfitter dégrade les environnements et le dit, `deployToPrism`
refuse. Il porte les chemins propres au poste, en barres obliques parce qu'un `.properties` lit
l'antislash comme un échappement, et le joueur des runs client, propre à la personne ; son nom
évite exprès `local.properties`, le marqueur des projets Android, qui poussait le plugin Android
d'IntelliJ à revendiquer le projet :

```properties
outfitter.profiles_dir=S:/18/00-my-minecraft-favorites-configs
outfitter.maps_dir=S:/18/05-maps
outfitter.reference_instance_dir=C:/chemin/vers/PrismLauncher/instances/<instance>/minecraft
outfitter.content_tool_dir=S:/17/TheModpackCreator/main-project/PackTool
outfitter.username=<ton pseudo>
```

Il peut aussi surcharger par poste les clés `outfitter.*` de `gradle.properties` (maps, monde
du serveur, exclusions, logs), que la doc d'environnement détaille.

**Les planchers ne s'écrivent pas à la main.** `fabric.mod.json` déclare
`"fabricloader": ">=${fabric_loader_version}"` et `"fabric-api": ">=${fabric_api_version}"`, que
le `processResources` de chaque module de version expanse : le chargeur depuis le catalogue
`mc`, la Fabric API depuis celle que le module déclare, sans son suffixe (`>=0.155.2` en
26.1.2). Chaque jar exige ainsi ce contre quoi il a été compilé et testé ; en 26.1.2, c'est ce
qui écarte les Fabric API d'avant `BlockTintsFactory`, arrivée en cours de lignée. Corollaire à
connaître : **monter le loader ou la Fabric API dans un catalogue durcit automatiquement
l'exigence annoncée aux joueurs.** Le sujet du loader a été instruit et clos, il n'y a pas de
plancher séparé à figer.

**Le style de ce build vient du gabarit `FabricTemplateMod`**
(`S:\16\_V\FabricDemoMod\fabric-mod-core\FabricTemplateMod`) : encadrés, listes alignées,
commentaires courts. C'est une référence de **forme, jamais de fond**, vérifié à la dure : son
`-Dcom.mojang.eula.agree=true` ne sert plus à rien en 26.2.

**La publication reste manuelle, et c'est provisoire.** Elle deviendra une section du plugin de
version (`build-logic`), un envoi par version du jeu, après comparaison de trois pistes
présélectionnées, **Minotaur, CurseForgeGradle et mod-publish-plugin**, sur la compatibilité
Gradle 9.7.1 / Loom 1.18 / Minecraft 26.x non obfusqué, l'état de maintenance et l'ergonomie
changelog-versions. Les jetons viendront de la chaîne bws, jamais du script, jamais commités, et
les premiers essais se feront en brouillon.

**Piège Gradle.** Les dépôts déclarés dans `settings.gradle.kts` sont ignorés : Loom ajoute les
siens à chaque module et `repositoriesMode = PREFER_PROJECT` fait gagner le module. Tout dépôt
supplémentaire se déclare dans le plugin de convention `travellingdimension.loom-module`.

**Piège `clientCompileOnly`.** La configuration n'existe qu'après `splitEnvironmentSourceSets()`,
que le plugin de convention appelle : un module peut s'en servir partout dans son build, après
son bloc `plugins`.

**Piège des plugins chargés deux fois.** Sans le `build.gradle.kts` de la racine, chaque module
chargerait Loom dans son propre classloader, et un module de version, qui touche au Loom de
`common` en réunissant leurs source sets, échouerait sur une `ClassCastException` entre les deux
copies. La racine charge donc les plugins des modules sans les appliquer (`apply false`), et un
module les applique sans version.

**Piège du dossier `build`.** Le motif `build/` du `.gitignore` ignore tout dossier de ce nom, à
toute profondeur : un package Kotlin nommé `build` ne serait pas suivi par Git. Le code de
`build-logic` vit donc sous `fr.roumoulou.travellingdimension.buildlogic`.

**Piège du serveur GameTest.** Le serveur de test de Mojang bâtit ses dimensions depuis le
préréglage plat et un registre de `LevelStem` vide : les dimensions des datapacks, VOYAGE
comprise, n'y existent pas. Le mod de test porte un mixin, `GameTestServerDimensionsMixin`, qui
lui donne le registre des dimensions chargées, comme le fait un serveur dédié. Sa cible est une
lambda de `GameTestServer.create`, nommée par le compilateur : une montée de version peut la
renommer, et c'est ce mixin qui le dira.

**Piège des `-SNAPSHOT` de Repsy.** Storify est prise en `0.4.0-SNAPSHOT` : Gradle ne rafraîchit
un snapshot qu'une fois par vingt-quatre heures, `--refresh-dependencies` force la reprise de la
dernière publication.

---

## Environnement de développement

**Quatre environnements en 26.2, deux par deux, et les deux vanilla en 26.3 et en 26.1.2**,
déclarés au plugin Outfitter par chaque module de version, dans son propre dossier `run/` : les
mondes d'une version ne se mélangent pas à ceux d'une autre.

`runClient` et `runServer` sont **vanilla purs**, sans aucun mod : Loom charge le mod depuis le
classpath et rien d'autre n'est présent. Ce sont eux la référence, celle qui dit ce que voit un
joueur n'ayant QUE ce mod.

`runClientModded` et `runServerModded` portent le **noyau MDTK**, copié depuis l'instance
PrismLauncher du poste (`outfitter.reference_instance_dir`) et filtré par le side lu dans chaque
jar. Ils servent à éprouver le mod au milieu de ceux qu'on utilise vraiment, sans quitter
Gradle. Trois mods ne sont jamais copiés : Fabric API et Fabric Language Kotlin, que Loom
fournit déjà au classpath, et le mod lui-même, que `deployToPrism` pousse dans cette même
instance. La clé `outfitter.mods_exclude` de `gradle.properties` écarte en plus, par
identifiant, les mods qui cassent les runs sans casser l'instance (surcharge par poste possible).
Les packs, les datapacks et les réglages de MDTK viennent de PackTool (`outfitter.content_tool_dir`,
projet `outfitter.content_project`) : les configs de mods naissent des **défauts du jeu** au
premier lancement, puis les réglages documentés de `mdtk-settings.json` s'appliquent par-dessus
au lancement suivant (convergence automatique, `mdtk-settings` est la source de vérité). Voir
`01-docs/technical-docs/02-finalized/environnement-de-developpement.md` pour le résultat,
environnement par environnement, et `01-docs/technical-docs/02-finalized/taches-de-developpement.md`
pour la déclaration, les clés et leurs valeurs ; le mécanisme de chaque tâche, sa condition, son
geste et son marqueur, est la doc d'Outfitter.

**Les configurations viennent de l'entrepôt** `S:\18` (`outfitter.profiles_dir`, `outfitter.maps_dir`),
pas du projet, rangées par version du jeu : 26.3 et 26.1.2 n'y ont pas encore de dossier.
Entrepôt absent, le lancement se fait quand même avec un message explicite en console, sauf pour
un serveur, qui refuse de démarrer sans `eula.txt`. Les maps que les clients de dev reçoivent dans
leurs `saves\` se choisissent par la clé `outfitter.maps` de `gradle.properties` (noms exacts de
l'entrepôt ; absente, toutes ; surcharge possible par poste dans `machine.properties`). Le monde
du serveur, lui, vient de la clé `outfitter.server_world` quand elle est posée, sinon du profil
`dev` de l'entrepôt.

**Monde plat de dev.** `dev/DevWorld.flattenOverworld` remplace le générateur de l'OVERWORLD par le
`FlatLevelSource` vanilla, uniquement en dev, réglé par `config/travellingdimension/dev.json`, que
le mod copie depuis sa ressource commentée au premier lancement où il manque.

**Les logs des runs** se règlent par trois clés de `gradle.properties`, indépendantes :
`outfitter.log_level` pour la console de tout le monde (vide = info), `outfitter.mod_log_level`
pour le seul logger du mod (son debug ou son trace, sans le bruit des autres), et
`outfitter.log_format` pour l'habillage de la console (vide = celui de Loom ; `compact` ou
`details`, hérités d'Enhanced Terminal Logging). `logs/debug.log` reçoit toujours tout ; les
montages sont des ressources d'Outfitter, extraites dans `build\outfitter\log4j\` avant chaque run
et fusionnées avec la config que Loom génère.

**Le joueur des runs client** vit dans `machine.properties`, parce qu'il est propre à la
personne : un clone n'hérite pas du pseudo de l'auteur. `outfitter.username` donne un pseudo fixe
hors ligne aux runs client, au lieu du « Player » à trois chiffres que Minecraft invente à chaque
lancement. `outfitter.login=true` branche à la place le compte Microsoft enregistré par
`gradlew microsoftLogin` (Loom 1.18, flux « device code », connexion dans le navigateur) ; le
jeton chiffré vit dans le cache Loom du Gradle user home, jamais dans le projet, et
`microsoftLogout` l'efface.

**Le serveur GameTest** est créé par le plugin de version et déclaré à Outfitter par chaque
module, à part des environnements (`gameTests { }`) ; le plugin de version refuse un module qui
oublie cette déclaration. `runGameTest` tourne dans `mc-<version>/run/game-test`, hors de
`build/`, et `freshGameTest` y retire le monde et la configuration avant chaque run : chaque run
rejoue le premier lancement du mod. Il accepte l'EULA par Fabric API, s'arrête seul, et ne reçoit
rien de l'entrepôt ni de l'instance : un clone le rejoue tel quel. Il porte le superflat de
`dev.json` et les dimensions des datapacks (voir le piège plus haut). Son rapport sort au format
XML de JUnit dans `mc-<version>/build/test-results/gameTest/TEST-gameTest.xml`. Pour ne jouer
qu'une partie des tests, un motif à jokers sur leur identifiant, `<mod>:<classe>_<méthode>` en
snake_case. **L'argument se cite** : sans guillemets, PowerShell le coupe au point, et Gradle
cherche une tâche nommée `.gametest_filter=...` (mesuré sous PowerShell 7.6).

```powershell
.\gradlew.bat :mc-26.3:runGameTest "-Poutfitter.gametest_filter=travellingdimension-gametest:nether_portal_*" --console=plain
```

**Tests en jeu par RCON.** Le serveur de test est celui de `gradlew :mc-<version>:runServer`. Mettre
`pause-when-empty-seconds=0` dans son `server.properties` : sans joueur connecté, le serveur se met
en pause au bout de soixante secondes, plus rien ne tick, et aucune traversée n'a lieu.

Deux pièges de test qui reviennent :

- un portail survivant d'un essai précédent capte les traversées et fausse toute la campagne,
  changer de secteur à chaque fois ;
- `forceload add` plafonne à 256 chunks et échoue **en entier** au-delà, tous les `fill` suivants
  échouant alors en silence.

---

## Le dépôt

**Le périmètre est le projet Gradle seul**, ce dossier et rien d'autre. Le classeur qui
l'entoure reste sur le disque, où la sauvegarde restic le couvre : `00-ai`, `01-docs`,
`04-releases`, `05-instances`, `07-tools-and-scripts` et les archives datées. Un dépôt à
l'échelle du classeur a été pesé et écarté ; Git ne porte que le code.

Conséquence à garder en tête en lisant ce README : **un renvoi vers `01-docs` pointe
hors du dépôt.** Le cahier des charges, lui, a été déplacé ici exprès pour qu'un clone
l'emporte : `Travelling Dimension.md`.

### Les messages de commit

```
<zone> : <ce qui change, à l'infinitif, en minuscule, sans point final>

<Le pourquoi, si ce n'est pas évident. Jamais le comment : le diff le dit déjà.>
```

| Zone | Ce qu'elle couvre |
|---|---|
| `build` | `settings.gradle.kts`, les `build.gradle.kts` de la racine et des modules, `build-logic`, les catalogues, le wrapper, `gradle.properties` |
| `mod` | le code du mod : `common/src/main`, `common/src/client` et les `src` des modules de version |
| `test` | `common/src/test`, `common/src/testMC` et `common/src/gametest` |
| `modpack` | ce que les environnements moddés reçoivent de MDTK : les clés `outfitter.*` de `gradle.properties` (maps, monde du serveur, exclusions, projet de contenu) |
| `doc` | ce README et le cahier des charges |
| `dépôt` | le `.gitignore` et la structure du dépôt lui-même |

Dès qu'un commit touche au comportement des portails, il emprunte le vocabulaire de
`00-ai/01-context/vocabulaire-et-patterns.md` : VOYAGE en majuscules,
l'**ancre** et non « la position », le **point idéal** distingué de l'**arrivée**, et toute
distance écrite avec sa dimension. Ce fichier vit hors du dépôt, un clone ne le porte pas.

### L'identité

Les commits portent le pseudonyme `roumoulou` et une adresse **noreply** de GitHub, jamais un
nom civil ni une adresse personnelle. La raison n'est pas une coquetterie : une adresse de
commit est gravée dans l'historique et part avec le dépôt, un dépôt privé peut devenir public,
et l'historique ne se nettoie pas sans réécriture. La même règle vaut hors de Git, pour une
métadonnée de mod, un `pom`, une page de publication.

Pas de ligne `Co-Authored-By` dans les messages.

La branche est `master`, **par choix** et non par défaut subi.

## La licence

Travelling Dimension est distribué sous la GNU Lesser General Public License, version 3
seulement (SPDX `LGPL-3.0-only`) ; copyright (c) 2026 Roumoulou. Le texte fait foi : `LICENSE`,
et `LICENSE.GPL` pour la GPL v3 qu'il incorpore ; les deux voyagent dans le jar et dans le jar de
sources, suffixés du nom de l'artefact. Storify, embarquée en jar-in-jar, est sous la même
licence et porte la sienne dans son propre jar. En résumé :

- un modpack, un serveur ou un lanceur embarquent le mod tel quel, à condition de conserver sa
  licence et ses mentions ;
- une version modifiée se redistribue sous la même licence, sources comprises.

## Hors scope

Liaison manuelle par coordonnées, table de portails persistante, blocs ou structures propres à
VOYAGE, rendu client particulier, portails vers le NETHER ou l'END depuis VOYAGE, interface de
gestion.
