// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Générateur de terrain utilisé par la dimension de voyage.
 *
 * - [TERRALITH] : DÉFAUT. Avec `largeBiomes`, utilise la variante Large Biomes que
 *                 Terralith embarque lui-même (`minecraft:large_biomes`) : biomes agrandis,
 *                 réservée en pratique à la dimension de voyage (l'OVERWORLD reste sur les
 *                 réglages `minecraft:overworld`, y compris un Terralith modifié par
 *                 l'utilisateur : aucune interférence, pure référence). Terralith absent ->
 *                 mêmes ids résolus en vanilla (large biomes), jamais de crash.
 * - [VANILLA]   : génération vanilla, Large Biomes selon `largeBiomes` (fallback universel).
 * - [TECTONIC]  : reprend la génération de l'OVERWORLD (Tectonic la remplace globalement).
 * - [WILLIAM]   : biomes de William Wythers' Overhauled Overworld, UNIQUEMENT dans la
 *                 dimension de voyage (pack embarqué re-namespacé, l'OVERWORLD n'est pas touché).
 * - [CUSTOM]    : réglages libres via `customNoiseSettings` + `customBiomePreset`.
 */
@Serializable
enum class WorldgenMode {
    @SerialName("vanilla")
    VANILLA,

    @SerialName("terralith")
    TERRALITH,

    @SerialName("tectonic")
    TECTONIC,

    @SerialName("william")
    WILLIAM,

    @SerialName("custom")
    CUSTOM
}

/**
 * Comment l'emprise de recherche se comporte en hauteur.
 *
 * - [FULL_HEIGHT] : DÉFAUT. L'emprise est un prisme qui va du fond du monde au plafond. Un
 *                   portail est donc candidat quelle que soit son altitude, ce qui rend les
 *                   étages bâtis à la main utilisables, mais rend aussi éligible un portail
 *                   perdu au fond d'une caverne (voir `verticalWeight`).
 * - [BOUNDED]     : l'emprise est bornée à `verticalRadius` blocs au-dessus et en dessous du
 *                   point idéal. Plus sûr, mais les étages hors de la fenêtre deviennent
 *                   invisibles.
 */
@Serializable
enum class VerticalMode {
    @SerialName("full_height")
    FULL_HEIGHT,

    @SerialName("bounded")
    BOUNDED
}

/**
 * Configuration du mod, chargée depuis `config/travellingdimension/config.json`.
 * Le fichier est du JSON nu, la lecture reste tolérante aux commentaires ; le détail de
 * chaque réglage vit dans `01-docs/user-docs/02-finalized/configuration.md`.
 *
 * Ses propriétés sont des `var` : la racine du store Storify se modifie propriété par
 * propriété, sous son verrou ([ConfigManager]). Le reste du mod la lit par
 * `ConfigManager.current` et ne l'écrit jamais lui-même ; [copy] reste la façon de dériver
 * une variante, comme le fait l'écran de configuration.
 *
 * Invariant : une erreur de configuration est loggée, jamais fatale.
 */
@Serializable
data class TravelConfig(

    /** Générateur de la dimension : vanilla | terralith | tectonic | william | custom. */
    var worldgen: WorldgenMode = WorldgenMode.TERRALITH,

    /** Ratio de conversion : 1 bloc de VOYAGE = `ratio` blocs d'OVERWORLD (défaut 16). */
    var ratio: Int = 16,

    /**
     * Seed dédié de la dimension de voyage (sémantique vanilla : nombre, ou texte haché ;
     * vide = utiliser le seed du monde). Permet un terrain connu et navigable autour de
     * (0,0) quel que soit le monde. À définir AVANT la première visite de la dimension :
     * en changer ensuite crée des bordures de chunks (anciens chunks conservés).
     */
    var seed: String = "424242",

    /** Large Biomes (défaut true, cohérent avec le ratio compressé). */
    var largeBiomes: Boolean = true,

    /** Mode custom : id des noise settings (ex: "minecraft:amplified", "terralith:overworld"). */
    var customNoiseSettings: String = "minecraft:large_biomes",

    /** Mode custom : id du multi-noise biome preset (ex: "minecraft:overworld"). */
    var customBiomePreset: String = "minecraft:overworld",

    /** Multiplicateur de densité des mobs dans la dimension de voyage. */
    var mobDensity: Double = 1.0,

    /** Génération des structures (villages, donjons...) dans la dimension. */
    var structures: Boolean = true,

    /**
     * **Rayon horizontal de recherche côté OVERWORLD**, en blocs (défaut 128).
     *
     * C'est l'emprise dans laquelle on cherche un portail d'OVERWORLD à rejoindre quand on
     * sort de VOYAGE. Il vaut aussi la portée d'un lien de couleur dans ce sens.
     */
    var searchRadiusOverworld: Int = 128,

    /**
     * **Rayon horizontal de recherche côté VOYAGE**, en blocs (défaut 8).
     *
     * DOIT valoir `searchRadiusOverworld / ratio`, sinon la portée n'est plus symétrique et
     * un portail trouvé à l'aller ne retrouve pas son partenaire au retour : on ressort loin
     * de chez soi et un portail parasite naît. [sanitized] corrige la valeur et le dit dans
     * les logs plutôt que de laisser passer une configuration qui casse les allers-retours.
     *
     * Voir [PortalCoordinates.symmetricTravelRadius], qui porte le contre-exemple chiffré.
     */
    var searchRadiusVoyage: Int = 8,

    /** Comportement de l'emprise en hauteur (défaut pleine hauteur). */
    var verticalMode: VerticalMode = VerticalMode.FULL_HEIGHT,

    /** Demi-hauteur de l'emprise en mode [VerticalMode.BOUNDED], ignoré en pleine hauteur. */
    var verticalRadius: Int = 16,

    /**
     * **Poids de l'écart vertical** dans la distance, appliqué APRÈS la mise à l'unité
     * commune (défaut 1.0, neutre).
     *
     * Son seul usage réel est côté OVERWORLD : en pleine hauteur, un portail cinquante blocs
     * plus bas dans une caverne reste éligible, et l'on y atterrit sans moyen évident de
     * remonter. À 2.0 ou 3.0 il compte double ou triple, donc il reste atteignable en dernier
     * recours mais ne bat plus un portail de surface raisonnablement proche.
     *
     * Côté VOYAGE il ne change presque rien : la mise à l'unité commune a déjà réduit le Y à
     * sa juste part.
     */
    var verticalWeight: Double = 1.0,

    /**
     * Au retour, ressortir par le portail EXACT emprunté à l'aller (défaut **false**).
     *
     * L'aller divise les coordonnées, donc tous les portails d'un carré de `ratio` blocs
     * mènent au même endroit et l'information « lequel des trois ? » est mathématiquement
     * perdue. La mémoire est portée par l'entité elle-même (attachement persistant) : elle
     * survit au redémarrage et voyage avec elle. Le monde, lui, reste sans état.
     */
    var rememberEntryPortal: Boolean = false,

    /**
     * **Le bloc du cadre**, par identifiant (défaut `minecraft:amethyst_block`).
     *
     * Le bloc d'améthyste ordinaire et non l'améthyste bourgeonnante : la bourgeonnante ne se
     * ramasse pas en vanilla, même à la pioche en Soie, si bien qu'on ne pourrait bâtir un
     * portail qu'à l'intérieur d'une géode.
     *
     * N'importe quel identifiant convient, y compris celui d'un autre mod. Un identifiant
     * inconnu retombe sur le défaut avec un avertissement dans les logs.
     *
     * **À choisir avant de bâtir** : en changer rend INVALIDES les cadres déjà posés.
     */
    var frameBlock: String = "minecraft:amethyst_block",

    /**
     * **Le bloc de la plateforme** coulée sous un portail créé (défaut `minecraft:calcite`).
     *
     * La calcite plutôt que la pierre : c'est le bloc compagnon de la géode d'améthyste, elle
     * s'accorde au cadre et se reconnaît au premier coup d'œil comme l'ouvrage du mod.
     */
    var platformBlock: String = "minecraft:calcite",

    /**
     * Débordement horizontal de la plateforme, en blocs (défaut 1).
     *
     * Un seul bloc tout autour : de quoi poser le pied en sortant, sans transformer chaque
     * arrivée en esplanade.
     */
    var platformMargin: Int = 1,

    /**
     * Épaisseur de la plateforme, en blocs (défaut 1). **0 supprime la plateforme.**
     *
     * Une seule couche : elle sert à ne pas tomber en sortant, pas à bâtir un socle.
     */
    var platformDepth: Int = 1,

    /** Marge latérale du dégagement creusé autour du portail (défaut 2). */
    var clearanceMargin: Int = 2,

    /** Marge verticale du dégagement, au-dessus du portail (défaut 3). */
    var clearanceHeight: Int = 3,

    /** Évacuer les fluides du volume dégagé (défaut true). */
    var removeFluids: Boolean = true,

    /**
     * **Ne pas détruire ce qu'un joueur a bâti** (défaut true).
     *
     * Quand l'emprise d'un portail à créer porte des marques de construction, le portail est
     * décalé en hauteur pour les épargner. Voir
     * [fr.roumoulou.travellingdimension.portal.PortalGround], qui explique pourquoi tout ceci
     * est une présomption et jamais une certitude : le jeu n'enregistre nulle part qui a posé
     * un bloc.
     */
    var protectPlayerBuilds: Boolean = true,

    /**
     * De combien de blocs, au maximum, un portail peut monter ou descendre pour épargner une
     * construction (défaut 32 ; **0 désactive le décalage**).
     *
     * On essaie la hauteur voulue, puis un bloc au-dessus, un en dessous, et ainsi de suite :
     * le portail s'éloigne le moins possible de son point idéal. Au-delà de cette limite, on
     * bâtit quand même, et ce sont les conteneurs mis à l'abri qui limitent la casse.
     */
    var buildShiftMaxOffset: Int = 32,

    /**
     * **Le seuil de fréquentation d'un chunk**, en ticks (défaut 1200, soit une minute).
     *
     * `getInhabitedTime` est le compteur vanilla du temps que des joueurs ont passé dans un
     * chunk. En dessous de ce seuil, on considère que personne n'y a mis les pieds : tout ce
     * qui s'y trouve vient donc de la génération du monde, et rien n'est à protéger. C'est ce
     * qui évite de décaler un portail pour un village ou une mine abandonnée que personne n'a
     * jamais vus.
     *
     * Une minute de présence cumulée, et non dix secondes : dix secondes s'accumulent en
     * traversant un chunk au galop, ce qui faisait passer pour habité un terrain que personne
     * n'avait jamais touché. Une minute demande d'y avoir vraiment séjourné.
     *
     * Monter le seuil rend le mod moins prudent, le descendre à 0 protège dès le premier
     * passage d'un joueur.
     */
    var inhabitedThreshold: Long = 1200,

    /**
     * **Mettre les conteneurs à l'abri** avant de bâtir par-dessus (défaut true).
     *
     * Vaut quoi qu'il arrive, même quand le décalage a échoué : mieux vaut un coffre déplacé
     * de quelques blocs qu'un coffre effacé. Le contenu, le nom et le reste suivent.
     */
    var rescueContainers: Boolean = true,

    /** Rayon, en blocs, dans lequel un conteneur déménagé est reposé (défaut 8). */
    var rescueRadius: Int = 8,

    /**
     * **Les blocs qui trahissent une main humaine**, en plus des block entities.
     *
     * Les block entities (coffres, fourneaux, panneaux, lits...) sont détectées de toute
     * façon et gratuitement, le chunk en tenant la liste. Celle-ci ajoute des blocs ordinaires
     * qui poussent rarement tout seuls. Elle est **volontairement courte** : un mur de pierre
     * taillée est indétectable, et une liste trop large ferait fuir le portail à chaque
     * village. C'est le réglage à étendre selon ce que l'on bâtit sur son serveur.
     */
    var playerMadeBlocks: List<String> = listOf(
        "minecraft:crafting_table",
        "minecraft:enchanting_table",
        "minecraft:anvil",
        "minecraft:chipped_anvil",
        "minecraft:damaged_anvil",
        "minecraft:grindstone",
        "minecraft:smithing_table",
        "minecraft:loom",
        "minecraft:stonecutter",
        "minecraft:cartography_table",
        "minecraft:fletching_table",
        "minecraft:bookshelf",
        "minecraft:glass",
        "minecraft:glass_pane",
        "minecraft:ladder",
        "minecraft:scaffolding",
        "minecraft:tnt",
        "minecraft:redstone_wire",
        "minecraft:repeater",
        "minecraft:comparator",
        "minecraft:piston",
        "minecraft:sticky_piston",
        "minecraft:observer",
    ),

    /**
     * **Les tailles de portail hors vanilla dans VOYAGE** (défaut false).
     *
     * Coupé, un portail de voyage mesure de 1 à 21 de large et de 3 à 21 de haut, ce qui reste
     * proche des habitudes de vanilla. Activé, la hauteur minimale tombe à **1** : le portail
     * **1x1** devient possible, et le maximum passe à [portalMaxSize].
     *
     * Rien d'autre ne change. La transformation, le point idéal, la portée, l'ordre des rangs
     * et la distance en blocs d'OVERWORLD ignorent complètement la taille d'un portail. Seule
     * l'ancre se déplace, puisqu'elle vaut toujours `(largeur - 1) / 2` depuis le bord minimal.
     *
     * **À décider avant de bâtir.** Les bornes servent aussi à REVALIDER un portail quand un
     * bloc voisin change : couper le réglage ensuite éteint les portails devenus hors bornes.
     */
    var portalFreeSize: Boolean = false,

    /**
     * **La taille maximale d'un portail de VOYAGE**, largeur et hauteur (défaut 21, jusqu'à 41).
     *
     * N'a d'effet que lorsque [portalFreeSize] est actif. Un portail de 41 de large est un
     * ouvrage considérable : à la création, le dégagement et la dalle suivent la taille, donc
     * l'arrivée creuse d'autant.
     */
    var portalMaxSize: Int = 21,

    /**
     * **Les liens de couleur sur les portails de VOYAGE** (défaut true).
     *
     * Coupé, le colorant n'a plus aucun effet sur un portail de voyage et la couleur ne
     * départage plus rien : la résolution se réduit à la mémoire du trajet, au portail le plus
     * proche, puis à la construction.
     *
     * Les couleurs déjà posées **restent dans la sauvegarde** et restent visibles, elles sont
     * simplement ignorées. Rallumer le réglage les remet en service telles quelles, rien n'est
     * perdu.
     *
     * Réglage jumeau de [netherPortalTints], qui fait la même chose pour les portails du
     * NETHER : les deux se coupent séparément.
     */
    var portalTints: Boolean = true,

    /**
     * **Le veto de la redstone** : à partir de combien de blocs de redstone dans l'emprise le
     * terrain est refusé (défaut 8 ; **0 désactive le veto**).
     *
     * ## Pourquoi la redstone a sa propre règle
     *
     * Elle est le seul marqueur qui ne trompe pas. Un coffre ou une table de craft poussent dans
     * les villages, une pierre taillée ne dit rien de son poseur, mais **une installation de
     * redstone est une machine, et une machine a un auteur**. Percer un mur se répare ; percer
     * une horloge ou un trieur, c'est détruire un ouvrage qui a demandé des heures et qui ne se
     * répare pas en reposant les blocs.
     *
     * ## Ce qu'elle contourne
     *
     * Ce veto **ignore [inhabitedThreshold]**, contrairement à tout le reste de
     * [fr.roumoulou.travellingdimension.portal.PortalGround]. C'est tout son intérêt : une
     * machine bâtie dans un chunk que le compteur de fréquentation croit encore vierge était
     * jusque-là parfaitement invisible.
     *
     * Il ignore aussi [protectPlayerBuilds] : couper la protection générale ne doit pas ouvrir
     * les machines. Pour se passer du veto, il faut le mettre à 0, explicitement.
     *
     * ## Ce qu'il fait
     *
     * Le portail cherche une altitude sans redstone **sur toute la hauteur du monde**, et non
     * dans les seuls [buildShiftMaxOffset] blocs habituels : refuser de percer une machine vaut
     * bien un portail perché. En dernier recours, si la colonne entière est occupée, on bâtit
     * quand même, parce qu'un voyageur doit atterrir quelque part, et les logs le disent.
     */
    var redstoneVeto: Int = 8,

    /**
     * **Les blocs qui comptent pour le veto de la redstone.**
     *
     * Volontairement plus large que [playerMadeBlocks] : on cherche ici les composants d'une
     * installation, pas les indices d'un passage. Les rails ordinaires n'y sont pas, les
     * mineshafts en sont pleins ; les rails alimentés, si.
     */
    var redstoneBlocks: List<String> = listOf(
        "minecraft:redstone_wire",
        "minecraft:repeater",
        "minecraft:comparator",
        "minecraft:redstone_torch",
        "minecraft:redstone_wall_torch",
        "minecraft:redstone_block",
        "minecraft:redstone_lamp",
        "minecraft:piston",
        "minecraft:sticky_piston",
        "minecraft:piston_head",
        "minecraft:moving_piston",
        "minecraft:slime_block",
        "minecraft:honey_block",
        "minecraft:observer",
        "minecraft:dropper",
        "minecraft:dispenser",
        "minecraft:hopper",
        "minecraft:crafter",
        "minecraft:lever",
        "minecraft:tripwire_hook",
        "minecraft:tripwire",
        "minecraft:daylight_detector",
        "minecraft:target",
        "minecraft:note_block",
        "minecraft:sculk_sensor",
        "minecraft:calibrated_sculk_sensor",
        "minecraft:powered_rail",
        "minecraft:detector_rail",
        "minecraft:activator_rail",
    ),

    /**
     * **Les verrous de portail** (défaut true).
     *
     * Un joueur peut verrouiller un portail avec `/tdlock`, et un portail verrouillé interdit
     * d'ALLUMER un autre portail dans son emprise de recherche, c'est-à-dire dans le
     * territoire où deux portails se disputeraient les mêmes voyageurs. Le joueur qui essaie
     * reçoit un message qui nomme le propriétaire et donne les coordonnées.
     *
     * Coupé, les verrous déjà posés restent dans la sauvegarde mais ne sont plus consultés,
     * et la commande refuse d'en poser de nouveaux.
     */
    var portalLocks: Boolean = true,

    /**
     * **Le portail du NETHER est créé au POINT IDÉAL** (défaut true).
     *
     * Le jeu ne bâtit pas là où le calcul l'envoie : il balaie une large zone à la recherche
     * d'un endroit jugé convenable, et se rabat sur un emplacement approximatif s'il n'en
     * trouve pas. Le portail peut donc naître très loin du point calculé, sans qu'on puisse
     * le prévoir, ce qui est la principale raison de la réputation d'imprévisibilité du
     * NETHER.
     *
     * Activé, le portail naît à la coordonnée exacte, et c'est le terrain qui s'adapte :
     * dégagement autour, plateforme dessous, avec les mêmes réglages que dans VOYAGE. Le
     * cadre reste en obsidienne, la taille reste celle de vanilla, l'axe reste celui du
     * portail d'où l'on part : c'est un portail du NETHER ordinaire, seul son EMPLACEMENT
     * change.
     *
     * Coupé, le jeu reprend exactement son comportement habituel.
     */
    var netherPortalPlacement: Boolean = true,

    /**
     * **Le portail du NETHER créé recopie la taille du portail d'où l'on part** (défaut true).
     *
     * Vanilla crée toujours un 2x3, la taille minimale, quelle que soit la porte que l'on a
     * franchie : une belle arche de 9 de large répond par une fente de 2. C'est la règle de
     * VOYAGE qui est appliquée ici, un portail étant un élément entier.
     *
     * Coupé, la taille reste celle de vanilla, 2 de large et 3 de haut, et `netherPortalPlacement`
     * ne change plus alors que l'EMPLACEMENT du portail.
     *
     * Sans effet quand [netherPortalPlacement] est coupé : c'est le jeu qui bâtit, et il bâtit
     * en 2x3.
     */
    var netherPortalCopySize: Boolean = true,

    /**
     * **Les tailles de portail hors vanilla dans le NETHER** (défaut false).
     *
     * Coupé, la règle de Mojang s'applique telle quelle : de 2 à 21 de large, de 3 à 21 de
     * haut. Activé, un cadre d'obsidienne s'allume au briquet dès **1x1**, et jusqu'à
     * [netherPortalMaxSize].
     *
     * C'est le seul réglage du mod qui modifie la **détection de forme** de vanilla, donc la
     * règle vaut pour TOUS les portails du NETHER de la partie et pas seulement pour ceux que
     * le mod crée. Il porte aussi sur la taille recopiée à l'arrivée : une arche 1x1 répond par
     * une arche 1x1, exactement comme dans VOYAGE.
     *
     * **À décider avant de bâtir.** Ces bornes servent aussi à REVALIDER un portail quand un
     * bloc voisin change : couper le réglage ensuite éteint les portails devenus hors bornes.
     */
    var netherPortalFreeSize: Boolean = false,

    /**
     * **La taille maximale d'un portail du NETHER**, largeur et hauteur (défaut 21, jusqu'à 41).
     *
     * N'a d'effet que lorsque [netherPortalFreeSize] est actif.
     */
    var netherPortalMaxSize: Int = 21,

    /**
     * **Les liens de couleur sur les portails du NETHER vanilla** (défaut true).
     *
     * Mécanique indépendante de VOYAGE, portée par le package `nether`. Elle ne modifie pas
     * l'algorithme de Mojang : elle dit seulement lequel des portails que le jeu aurait de
     * toute façon trouvés est retenu, quand deux portails de la même couleur se répondent.
     */
    var netherPortalTints: Boolean = true,

    /** Logger la bascule automatique vers vanilla quand le générateur demandé est absent. */
    var logFallback: Boolean = true,
) {

    /**
     * Les réglages de génération ne sont lus qu'une fois : au chargement du mod
     * (`WorldgenSelector.apply`) et à la création des mondes (`GeneratorSwapper`).
     * Les modifier en jeu ne change donc rien avant relance, et il faut le dire au
     * joueur au lieu de le laisser croire que ça a pris.
     *
     * Tous les autres réglages sont relus à chaque usage via `ConfigManager.current` :
     * ils s'appliquent immédiatement.
     */
    fun needsRestartAgainst(previous: TravelConfig): Boolean =
        worldgen != previous.worldgen ||
                seed != previous.seed ||
                largeBiomes != previous.largeBiomes ||
                customNoiseSettings != previous.customNoiseSettings ||
                customBiomePreset != previous.customBiomePreset

    /** Valeurs corrigées pour rester dans des bornes saines, sans jamais crasher. */
    fun sanitized(onProblem: (String) -> Unit): TravelConfig {
        var fixed = this

        // Des bornes au moins aussi larges que celles de l'écran en jeu, qui resserre les
        // siennes. Sans borne ici, une valeur tapée à la main dans le fichier passait là où
        // l'écran l'aurait refusée, et la documentation ne pouvait pas dire la vérité sur
        // les deux chemins à la fois.
        if (ratio < 2) {
            onProblem("ratio=$ratio invalide (< 2), retour à 16")
            fixed = fixed.copy(ratio = 16)
        }
        if (fixed.ratio > 64) {
            onProblem("ratio=${fixed.ratio} trop grand (> 64), ramené à 64")
            fixed = fixed.copy(ratio = 64)
        }
        if (mobDensity < 0.0) {
            onProblem("mobDensity=$mobDensity invalide (< 0), retour à 1.0")
            fixed = fixed.copy(mobDensity = 1.0)
        }
        if (fixed.mobDensity > 10.0) {
            onProblem("mobDensity=${fixed.mobDensity} trop grand (> 10.0), ramené à 10.0")
            fixed = fixed.copy(mobDensity = 10.0)
        }
        if (frameBlock.isBlank()) {
            onProblem("frameBlock vide, retour à minecraft:amethyst_block")
            fixed = fixed.copy(frameBlock = "minecraft:amethyst_block")
        }
        if (platformBlock.isBlank()) {
            onProblem("platformBlock vide, retour à minecraft:calcite")
            fixed = fixed.copy(platformBlock = "minecraft:calcite")
        }

        fixed = fixed.copy(
            searchRadiusOverworld = fixed.searchRadiusOverworld.clampReporting(
                1, 4096, "searchRadiusOverworld", onProblem
            ),
            // 41 est la borne haute assumée : au-delà, un portail créé dévaste son arrivée.
            // 3 est la borne basse pour qu'un portail reste franchissable réglage coupé.
            portalMaxSize = fixed.portalMaxSize.clampReporting(3, 41, "portalMaxSize", onProblem),
            netherPortalMaxSize = fixed.netherPortalMaxSize.clampReporting(
                3, 41, "netherPortalMaxSize", onProblem
            ),
            // 0 désactive le veto, une valeur négative n'aurait aucun sens.
            redstoneVeto = fixed.redstoneVeto.clampReporting(0, 4096, "redstoneVeto", onProblem),
            verticalRadius = fixed.verticalRadius.clampReporting(1, 512, "verticalRadius", onProblem),
            platformMargin = fixed.platformMargin.clampReporting(0, 8, "platformMargin", onProblem),
            platformDepth = fixed.platformDepth.clampReporting(0, 8, "platformDepth", onProblem),
            clearanceMargin = fixed.clearanceMargin.clampReporting(0, 8, "clearanceMargin", onProblem),
            clearanceHeight = fixed.clearanceHeight.clampReporting(0, 16, "clearanceHeight", onProblem),
            // 0 désactive le décalage ; au-delà de la hauteur du monde, la borne ne change rien.
            buildShiftMaxOffset = fixed.buildShiftMaxOffset.clampReporting(0, 512, "buildShiftMaxOffset", onProblem),
            // Un rayon négatif ferait planter le tirage d'un abri : c'est la seule valeur du
            // fichier qui pouvait rendre la création d'un portail fatale.
            rescueRadius = fixed.rescueRadius.clampReporting(1, 16, "rescueRadius", onProblem),
        )

        if (fixed.verticalWeight < 0.0) {
            onProblem("verticalWeight=${fixed.verticalWeight} invalide (< 0), retour à 1.0")
            fixed = fixed.copy(verticalWeight = 1.0)
        }
        if (fixed.inhabitedThreshold < 0L) {
            onProblem("inhabitedThreshold=${fixed.inhabitedThreshold} invalide (< 0), ramené à 0")
            fixed = fixed.copy(inhabitedThreshold = 0L)
        }

        // LA SYMÉTRIE DE LA PORTÉE, corrigée d'office. Ce n'est pas une coquetterie : un
        // rayon de VOYAGE qui ne vaut pas searchRadiusOverworld / ratio casse les
        // allers-retours et fait naître des portails parasites. Mieux vaut une valeur
        // corrigée et annoncée qu'une configuration qui a l'air de marcher.
        val symmetric = PortalCoordinates.symmetricTravelRadius(fixed.searchRadiusOverworld, fixed.ratio)
        if (fixed.searchRadiusVoyage != symmetric) {
            onProblem(
                "searchRadiusVoyage=${fixed.searchRadiusVoyage} brise la symétrie de la portée " +
                        "(searchRadiusOverworld=${fixed.searchRadiusOverworld} / ratio=${fixed.ratio} = $symmetric) : " +
                        "corrigé à $symmetric, sinon un portail trouvé à l'aller ne retrouve pas son partenaire au retour"
            )
            fixed = fixed.copy(searchRadiusVoyage = symmetric)
        }

        return fixed
    }

    private fun Int.clampReporting(min: Int, max: Int, name: String, onProblem: (String) -> Unit): Int = when {
        this < min -> { onProblem("$name=$this trop petit (< $min), ramené à $min"); min }
        this > max -> { onProblem("$name=$this trop grand (> $max), ramené à $max"); max }
        else -> this
    }
}
