# Travelling Dimension, cahier des charges

Ce que le mod est, ce qu'il fait, et les règles auxquelles il se tient.

---

## 1. Résumé

Une **dimension de voyage** de type OVERWORLD, compressée au ratio configurable, 1:16 par
défaut. Un bloc parcouru dans VOYAGE vaut seize blocs d'OVERWORLD.

On y entre par un portail à cadre d'améthyste, allumé avec un item dédié. La destination est
calculée par conversion de coordonnées, puis résolue en cherchant un portail existant autour
du point obtenu. C'est le modèle du NETHER, avec un ratio plus agressif et des règles rendues
prévisibles.

Le mod applique aussi cette mécanique de portail repensée aux **portails ordinaires du NETHER**.
Chaque apport y est réglable séparément, et tout couper laisse le NETHER exactement tel que
vanilla le fait.

## 2. Environnement technique

| | |
|---|---|
| Minecraft | 26.2 |
| Chargeur | Fabric, loader 0.19.5 ou plus récent, plancher calculé depuis le catalogue |
| Dépendances | Fabric API, Fabric Language Kotlin |
| Facultatif | Mod Menu et Cloth Config, pour l'écran de configuration |
| Côté | client **et** serveur, les deux sont obligatoires |
| Langage | Kotlin, Java pour les mixins |

## 3. Le portail

### 3.1 Le cadre

Rectangle vertical, comme celui du NETHER :

| | |
|---|---|
| bloc du cadre | configurable, `minecraft:amethyst_block` par défaut |
| largeur intérieure | 1 à 21, paire ou impaire |
| hauteur intérieure | 3 à 21 |
| tailles hors vanilla | `portalFreeSize` ouvre le 1x1, `portalMaxSize` monte le maximum jusqu'à 41 |
| allumage | l'Amethyst Igniter, clic droit |
| dimensions autorisées | OVERWORLD et VOYAGE, nulle part ailleurs |

Le cadre n'accepte **qu'un seul bloc**, celui de la configuration : tout mélange le rend
invalide. Les coins ne sont pas exigés par la validation, mais les constructions automatiques
les posent.

Un cadre dont un bloc disparaît éteint le portail en cascade. Il se rallume à l'igniter une fois
réparé.

### 3.2 L'ancre

> **L'ancre est le bloc de la rangée du bas situé à `(largeur - 1) / 2` du bloc de plus petite
> coordonnée sur l'AXE, en division entière.**

C'est la position canonique du mod : c'est elle que la transformation divise ou multiplie, elle
sur laquelle se mesurent les distances, elle qu'on compare pour savoir si deux portails sont le
même.

L'ancre appartient au **portail**, pas au voyageur. Deux entités qui traversent le même portail
par ses deux bords calculent le même point idéal ; leur bord d'entrée ne décide que de leur
position dans le portail d'arrivée.

Elle est au centre et jamais à un bord, ce qui garantit que deux portails de tailles
différentes posés au même endroit ont la même destination.

### 3.3 Le cadre de 5x5 recommandé

Recommandation, pas obligation : un cadre de 5 par 5, en blocs d'améthyste pour VOYAGE, en
obsidienne pour le NETHER. Largeur impaire, l'ancre tombe au milieu exactement et un portail
et son image miroir ont la même ancre. C'est la plus petite taille confortable, vite bâtie et
bon marché. Et une taille commune sur un serveur aligne les ancres de tout le monde ; qui
veut faire passer de grosses créatures bâtit simplement plus large.

## 4. La transformation de coordonnées

```
OVERWORLD vers VOYAGE      X_idéal = floorDiv(X_ancre, ratio)
                           Z_idéal = floorDiv(Z_ancre, ratio)
                           Y_idéal = Y_ancre

VOYAGE vers OVERWORLD      X_idéal = X_ancre * ratio
                           Z_idéal = Z_ancre * ratio
                           Y_idéal = Y_ancre
```

**La division est plancher**, y compris en négatif : on garde la partie entière, jamais
d'arrondi au plus proche. C'est ce que fait le NETHER, et cela garantit que les cases font
exactement `ratio` blocs partout, y compris à cheval sur zéro.

**Le Y n'est jamais converti**, seulement ramené dans les limites du monde d'arrivée, avec la
place nécessaire pour la plateforme en dessous et la rangée de cadre au-dessus.

**La perte d'information est assumée.** Seize colonnes d'OVERWORLD tombent sur la même colonne
de VOYAGE, donc un aller-retour ne ramène pas au bloc de départ mais au premier bloc de la
case. La mémoire de trajet (section 8) le corrige pour qui l'active.

## 5. La résolution

Le point idéal est un **calcul**, l'arrivée est un **fait**. Quatre rangs, le premier qui
répond gagne :

| Rang | Règle | Réglage |
|---|---|---|
| 1 | la **couleur** du portail de départ, si elle est posée | `portalTints`, actif |
| 2 | la **mémoire du trajet** | `rememberEntryPortal`, inactif |
| 3 | le **portail le plus proche** du point idéal, dans l'emprise | |
| 4 | la **construction**, au point idéal exact | |

Un rang coupé **saute**, il ne dégrade rien : les couleurs déjà posées restent dans la
sauvegarde et reprennent leur rôle si le réglage est rallumé.

### 5.1 L'emprise de recherche

Un carré centré sur le point idéal, bornes comprises :

| Dimension | Rayon |
|---|---|
| OVERWORLD | `searchRadiusOverworld`, 128 blocs par défaut |
| VOYAGE | `searchRadiusVoyage`, 8 blocs, **calculé** |

> **Invariant : `searchRadiusVoyage = searchRadiusOverworld / ratio`, en division entière et au
> minimum 1.**

Les deux nombres désignent le même carré de monde. Ce n'est pas une élégance mais une condition
de bon fonctionnement : sans elle, un portail trouvé à l'aller ne retrouve pas son partenaire au
retour. Toute valeur qui la brise est corrigée d'office avec une ligne dans les logs.

En hauteur, `verticalMode` décide : tout le monde par défaut, ou une fenêtre allant de
`verticalRadius` blocs au-dessous à `verticalRadius` blocs au-dessus du point idéal en mode
borné. C'est donc une demi-hauteur, pas la hauteur de la fenêtre.

### 5.2 Le critère de sélection

> **La distance se mesure toujours en blocs d'OVERWORLD.**

Dans VOYAGE, un bloc horizontal vaut `ratio` blocs d'OVERWORLD et un bloc vertical en vaut un,
le Y n'étant pas compressé. Les additionner tels quels reviendrait à additionner des kilomètres
et des centimètres. Tous les écarts sont donc ramenés à l'unité commune avant comparaison,
c'est-à-dire au trajet que le voyageur devra vraiment faire en arrivant.

Conséquence recherchée : **à colonne égale, l'étage le plus proche gagne**. Des étages bâtis à
la main aux mêmes X et Z se répondent dans les deux sens, sans colorant ni mémoire.

En cas d'égalité stricte, priorité aux coordonnées les plus faibles, dans l'ordre X, Y, Z.
L'ordre est total, donc le résultat est reproductible.

### 5.3 La pondération verticale

`verticalWeight` multiplie l'écart vertical après la mise à l'unité commune. Neutre à 1.0. Son
seul usage réel est côté OVERWORLD, pour qu'un portail au fond d'une caverne ne batte pas un
portail de surface un peu plus loin.

### 5.4 Un portail existant est toujours prioritaire

Même quand le point idéal est libre et parfaitement constructible.

Contrepartie assumée, et c'est celle du NETHER : **bâtir un portail change la destination de ses
voisins**. Le colorant est la parade, et c'est sa raison d'être.

## 6. La création d'un portail

Elle a lieu **exactement au point idéal**. Aucune recherche d'un endroit convenable, aucun
ajustement horizontal : c'est le terrain qui s'adapte.

Le portail créé est la **copie du portail source** : même largeur, même hauteur, même AXE. Un
portail large peut donc déborder sur les cases voisines et répondre aussi à leurs voyageurs,
c'est accepté.

Quatre temps, dans cet ordre :

1. **le dégagement**, `clearanceMargin` blocs de chaque côté et `clearanceHeight` au-dessus,
   fluides évacués si `removeFluids` ;
2. **la plateforme**, `platformDepth` blocs d'épaisseur débordant de `platformMargin`, soit
   **une seule couche et un seul bloc tout autour** par défaut, qui ne remplit **que les blocs
   remplaçables** ;
3. **le cadre**, coins compris ;
4. **les blocs de portail**.

> **Rien de ce qui a été bâti n'est amputé** : ni portail voisin, ni cadre, ni bloc
> indestructible.

**La position d'arrivée** reproduit l'offset relatif de l'entité dans le portail source, comme
vanilla : entrer au milieu fait sortir au milieu. Quand les deux portails n'ont pas le même axe,
le regard tourne de 90 degrés pour ne pas ressortir face au cadre.

## 7. Épargner ce qu'un joueur a bâti

> **Minecraft n'enregistre nulle part qui a posé un bloc.** Tout ce mécanisme est une
> présomption, jamais une certitude.

Le choix qui gouverne le reste : un faux positif est bon marché, le portail se décale pour rien ;
un faux négatif coûte cher, un trou dans une base. La règle penche toujours du côté prudent.

Trois signaux, du plus sûr au plus discutable :

1. **la fréquentation du chunk** (`inhabitedThreshold`, 1200 ticks) : sous le seuil, tout vient
   de la génération et rien n'est protégé ;
2. **les block entities**, lues dans la liste que le chunk tient déjà, donc sans balayage ;
3. **`playerMadeBlocks`**, liste courte et configurable de blocs qui poussent rarement seuls.

**Le décalage** cherche l'altitude libre la plus proche : la hauteur voulue, puis un bloc
au-dessus, un en dessous, et ainsi de suite jusqu'à `buildShiftMaxOffset`. À égalité, le haut
gagne. X et Z ne bougent jamais.

**Les conteneurs** de l'emprise sont déménagés dans `rescueRadius` blocs avec leur contenu et
leur nom, quoi qu'il arrive, même quand le décalage a échoué.

## 8. Le colorant

Clic droit avec un colorant sur un portail allumé : le portail entier prend la couleur. Le même
colorant l'efface.

> **La couleur réordonne un choix, elle n'étend jamais une portée.**

Elle **filtre** les candidats de l'emprise : seuls les portails de cette couleur sont
considérés, et c'est le plus proche d'entre eux qui gagne. Sans partenaire de cette couleur à
portée, la règle ordinaire reprend la main.

Rien n'est refusé : autant de portails d'une même couleur que l'on veut, des deux côtés.

La couleur vit dans l'état du bloc, donc dans la sauvegarde du monde. Les seize colorants du
jeu sont acceptés ; six sont marquées comme franchement distinctes.

Le système entier se coupe par `portalTints`, sans rien effacer : le colorant redevient
ordinaire, le rang 1 saute, et les couleurs posées attendent qu'on rallume le réglage.

## 8 bis. Voir l'emprise d'un portail

`/tdzones` en visant un portail allumé, **ouverte à tous les joueurs**. Les quatre murs de son
emprise de recherche s'affichent en particules, envoyées au seul joueur concerné, donc sans
aucun code client. `/tdzones off` éteint.

L'emprise affichée est prise à la **même source** que celle de la recherche : l'affichage ne
peut pas annoncer un territoire que le mod ne consulterait pas.

## 9. Le verrou de portail

`/tdlock` en visant un portail allumé, **ouverte à tous les joueurs**, réglage `portalLocks`.

Un portail verrouillé **interdit d'ALLUMER** un autre portail dans son territoire, c'est-à-dire
dans l'emprise de recherche de la dimension : 128 blocs dans l'OVERWORLD, 8 blocs dans VOYAGE,
le même carré de monde. C'est exactement l'emprise où deux portails se disputeraient les mêmes
voyageurs.

**Ce qu'un verrou ne fait pas** : il ne bloque jamais la création automatique. Un voyageur doit
atterrir quelque part.

| Geste | Qui |
|---|---|
| verrouiller un portail libre | n'importe quel joueur |
| retirer un verrou | le propriétaire, ou un opérateur |
| `check <position>` | n'importe quel joueur |
| `at <position>` | opérateurs |

Le verrou vit dans un attachement de chunk persistant, indexé sur l'ancre. Une entrée dont le
portail n'existe plus est ignorée et purgée : casser un portail libère le terrain.

## 10. La mémoire de trajet

`rememberEntryPortal`, désactivée par défaut. Retenir le couple portail quitté et portail
atteint, pour ressortir par le portail exact emprunté à l'aller.

Elle vit dans un attachement **porté par l'entité**, persistant : il survit au redémarrage, à la
déconnexion, au changement de dimension, et disparaît avec l'entité. Rien n'est écrit dans la
sauvegarde du niveau, aucun portail n'est créé par elle.

Le rappel n'a lieu que si l'entité repart du portail exact sur lequel elle était arrivée, et que
ce portail est toujours complet.

## 11. Les portails du NETHER vanilla

La même mécanique de portail, appliquée au NETHER. **Quatre** apports indépendants, chacun
réglable séparément. Trois ne touchent à aucun calcul de Mojang ; le quatrième,
`netherPortalFreeSize`, remplace ses bornes de taille.

**`netherPortalPlacement`** : le portail naît à la coordonnée exacte au lieu de l'endroit
approximatif que vanilla retient après balayage. Cadre d'obsidienne, axe du portail source,
indexation en point d'intérêt : tout le reste est vanilla, seul l'EMPLACEMENT change. Le mod ne
crée jamais rien au-dessus du toit du NETHER, et cette règle n'a pas de réglage.

**`netherPortalCopySize`** : le portail créé recopie la largeur et la hauteur du portail source,
au lieu du 2x3 systématique de vanilla. La taille est un confort, jamais une condition : tout ce
qui manque ramène au 2x3.

**`netherPortalTints`** : le colorant s'applique aux portails ordinaires. Le mod dit seulement
lequel des portails que vanilla aurait trouvés est retenu. La portée reste celle de vanilla, 16
blocs dans le NETHER et 128 dans l'OVERWORLD, qui désignent le même carré au ratio 8, donc la
réciprocité est gratuite. La couleur vit dans un attachement de chunk synchronisé, le bloc de
Mojang n'étant pas touché.

**`netherPortalFreeSize`**, coupé par défaut : les tailles hors vanilla, du **1x1** jusqu'à
`netherPortalMaxSize`, jusqu'à 41. C'est le **seul apport du mod qui modifie un calcul de
Mojang** : il remplace les bornes de taille de sa détection de forme, donc la règle vaut pour
tous les portails du NETHER de la partie et pas seulement pour ceux que le mod crée. Il gouverne
aussi la taille recopiée à l'arrivée.

## 12. La génération du monde

Type OVERWORLD : ciel, cycle jour-nuit, pas de plafond, hauteur 384 de Y-64 à Y319.

| Réglage | Effet |
|---|---|
| `worldgen` | terralith (défaut), vanilla, william, tectonic, custom |
| `seed` | seed propre à la dimension, `424242` par défaut |
| `largeBiomes` | biomes agrandis, cohérents avec la compression |

Ce que chaque valeur de `worldgen` promet :

| Valeur | Promesse |
|---|---|
| `terralith` | VOYAGE suit l'OVERWORLD : le terrain de Terralith quand son mod est installé, le terrain vanilla sinon |
| `vanilla` | le terrain vanilla, quoi qui soit installé |
| `william` | le relief vanilla et les biomes de William Wythers, dans VOYAGE seule : ceux de WWOO quand il est installé, sinon ceux que le mod tire du jar officiel de WWOO déposé dans `travellingdimension/worldgen/` |
| `tectonic` | VOYAGE suit l'OVERWORLD, que Tectonic remplace |
| `custom` | un réglage de bruit et un preset de biomes, par leurs identifiants |

Le terrain ne fait jamais échouer le jeu. Quand la source d'une valeur manque, VOYAGE se replie
sur le terrain vanilla, puis suit l'OVERWORLD en large biomes, avec un message aux opérateurs à
chaque connexion et un avertissement dans les logs. `terralith` sans Terralith n'est pas un repli.

## 13. Le gameplay

VOYAGE est un raccourci, pas une destination : rien d'unique ne s'y trouve, aucun minerai
exclusif, aucune structure propre.

| Réglage | Effet |
|---|---|
| `structures` | villages et donjons, chunks à générer seulement |
| `mobDensity` | multiplicateur des plafonds d'apparition, dans VOYAGE uniquement |

## 14. Les invariants

Ce à quoi le mod se tient, sans exception :

1. **Une erreur de configuration est signalée, jamais fatale.**
2. **La portée est symétrique** : `searchRadiusVoyage * ratio = searchRadiusOverworld`.
3. **La distance se mesure en blocs d'OVERWORLD**, des deux côtés.
4. **La couleur réordonne un choix, elle n'étend jamais une portée.**
5. **Tout se calcule sur l'ancre**, jamais sur la position du voyageur.
6. **Dans VOYAGE, rien de ce qui a été bâti n'est amputé** : portails, cadres, indestructible.
   Dans le NETHER, seuls les portails allumés et l'indestructible sont épargnés, l'obsidienne
   d'un cadre non allumé peut être effacée par le dégagement.
7. **Aucune liaison de portails n'est écrite en sauvegarde** : la destination se recalcule
   toujours. Ce que le monde garde se compte sur les doigts, et ce sont des choix de joueur :
   les verrous et les couleurs des portails du NETHER, chacun dans le chunk qui les porte.
8. **Mesurer ne génère jamais de terrain.** La recherche relit les chunks sauvegardés au statut
   d'entrée, `/tdtest` se limite aux chunks déjà chargés, et rien ne fabrique de terrain.
9. **Le mod ne crée jamais de portail au-dessus du toit du NETHER.**
10. **Chaque intervention dans le NETHER a son interrupteur**, et les quatre coupées, le jeu se
    comporte exactement comme sans le mod.

## 15. Hors scope

Ce que le mod ne fait pas, et n'a pas vocation à faire :

- une liaison manuelle par coordonnées entre deux portails : le colorant couvre le besoin ;
- une table de portails persistante en sauvegarde ;
- des blocs, minerais ou structures propres à VOYAGE ;
- un rendu client lourd : la dimension se joue avec un client vanilla, et le peu de code client
  du mod se limite à la teinte des portails et à l'écran de configuration ;
- des portails vers le NETHER ou l'END depuis VOYAGE ;
- une interface de gestion des portails ; les commandes de diagnostic suffisent.
