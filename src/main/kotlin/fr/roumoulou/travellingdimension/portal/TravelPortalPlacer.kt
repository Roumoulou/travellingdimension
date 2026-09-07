package fr.roumoulou.travellingdimension.portal

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.VerticalMode
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * La recherche d'un portail existant, et la pose d'un portail neuf.
 *
 * ## L'ordre des choses
 *
 * La transformation de coordonnées ([PortalCoordinates]) donne un **point idéal**. Le mod ne
 * bâtit pas dessus tout de suite : il cherche d'abord un portail déjà présent dans une
 * emprise autour de ce point, et **un portail existant l'emporte toujours**, même quand le
 * point idéal est libre et parfaitement constructible.
 *
 * Conséquence assumée, et c'est celle du NETHER : bâtir un portail change la destination de
 * ses voisins. Le colorant est la parade, et c'est sa raison d'être.
 *
 * ## L'emprise, et pourquoi elle doit être symétrique
 *
 * Le rayon vaut `searchRadiusOverworld` blocs dans l'OVERWORLD et `searchRadiusVoyage` blocs
 * dans VOYAGE. Ces deux nombres désignent le MÊME carré de monde, le second valant le premier
 * divisé par le ratio. L'invariant est vérifié et corrigé par [TravelConfig.sanitized] : sans
 * lui, un portail trouvé à l'aller ne retrouve pas son partenaire au retour.
 *
 * ## Le balayage ne lit pas les blocs un par un
 *
 * [forEachCompletePortal] passe par la **palette des sections de chunk**. Une emprise de 256
 * blocs de côté sur toute la hauteur du monde compte plus de vingt millions de positions :
 * les lire une par une coûterait plusieurs secondes. Une section de 16x16x16 répond en
 * quelques comparaisons si elle contient ou non un bloc donné, parce qu'elle n'inspecte que
 * sa palette. On ne descend au bloc que dans les rares sections qui portent un portail.
 *
 * ## Et il ne fabrique jamais de terrain
 *
 * Le balayage charge les chunks au statut d'entrée (`ChunkStatus.EMPTY`), jamais au statut
 * complet. Un chunk déjà sauvegardé revient du disque avec ses blocs, donc un portail endormi
 * reste trouvé ; un chunk jamais généré revient vide et se saute aussitôt. Charger au statut
 * complet ferait GÉNÉRER le terrain manquant sur le thread serveur, et l'emprise d'OVERWORLD
 * couvre 17 x 17 chunks : près de trois cents chunks fabriqués à chaque traversée et à chaque
 * allumage, de quoi figer une partie. Vanilla procède de la même façon pour chercher un portail
 * du NETHER.
 */
object TravelPortalPlacer {

    /** Un portail mesuré : son coin minimal, sa taille, son axe et sa couleur. */
    data class PortalRect(
        val minCorner: BlockPos,
        val width: Int,
        val height: Int,
        val axis: Direction.Axis,
        val tint: PortalTint,
    ) {
        /** L'ANCRE : rangée du bas, à `(largeur - 1) / 2` du coin minimal. */
        val centre: BlockPos
            get() = minCorner.relative(alongOf(axis), (width - 1) / 2)
    }

    /** Une emprise plate, bornes comprises. La hauteur se traite à part. */
    data class Box(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
        operator fun contains(pos: BlockPos): Boolean =
            pos.x in minX..maxX && pos.z in minZ..maxZ

        companion object {
            fun around(centre: BlockPos, radius: Int) =
                Box(centre.x - radius, centre.x + radius, centre.z - radius, centre.z + radius)
        }
    }

    /**
     * **Le rayon de recherche de cette dimension**, en blocs de cette dimension.
     *
     * 128 blocs dans l'OVERWORLD, 8 dans VOYAGE : les deux désignent le même carré de monde.
     * Une seule fonction pour tout le mod, sinon la recherche, les verrous et l'affichage
     * finiraient par ne plus parler du même territoire.
     */
    fun searchRadius(level: Level, config: TravelConfig): Int =
        if (level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL) config.searchRadiusVoyage
        else config.searchRadiusOverworld

    /** La direction des X ou des Z croissants, selon l'axe du portail. */
    private fun alongOf(axis: Direction.Axis): Direction =
        if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH

    /** Couleur du portail dont [pos] est un bloc (NONE si ce n'en est pas un). */
    fun tintAt(level: ServerLevel, pos: BlockPos): PortalTint =
        level.getBlockState(pos).getOptionalValue(TravelPortalBlock.COLOR).orElse(PortalTint.NONE)

    private fun rectOf(level: ServerLevel, shape: TravelPortalShape): PortalRect =
        PortalRect(shape.minCorner(), shape.width, shape.height, shape.axis, tintAt(level, shape.centre()))

    private fun rectFromCentre(centre: BlockPos, width: Int, height: Int, axis: Direction.Axis): PortalRect =
        PortalRect(centre.relative(alongOf(axis), -((width - 1) / 2)), width, height, axis, PortalTint.NONE)

    // ─────────────────────────────────────────────────────────────────────────
    // Le point idéal
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Le point idéal** : l'ancre du portail source, transformée, puis ramenée dans les
     * limites du monde de destination.
     *
     * Le Y n'est pas converti, seulement clampé : il faut de la place pour la plateforme en
     * dessous et pour la rangée de cadre au-dessus.
     */
    fun idealPoint(
        destLevel: ServerLevel,
        sourceCentre: BlockPos,
        height: Int,
        platformDepth: Int,
        convert: (Int) -> Int,
    ): BlockPos {
        val x = convert(sourceCentre.x)
        val z = convert(sourceCentre.z)
        val y = Mth.clamp(
            sourceCentre.y,
            destLevel.minY + platformDepth + 1,
            destLevel.maxY - height - 1,
        )
        val clamped = destLevel.worldBorder.clampToBounds(x.toDouble(), y.toDouble(), z.toDouble())
        return BlockPos(clamped.x, y, clamped.z)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // La recherche
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Le portail le plus proche du point idéal**, ou `null` si l'emprise est vide.
     *
     * La distance se mesure en blocs d'OVERWORLD ([PortalCoordinates.distanceSquared]) : dans
     * VOYAGE, les écarts horizontaux sont multipliés par le ratio, l'écart vertical ne l'est
     * pas. C'est ce qui empêche un portail à la bonne altitude mais à des centaines de blocs
     * de sa cible de battre un portail bien placé, et ce qui fait qu'à colonne égale c'est
     * l'étage le plus proche qui gagne.
     *
     * [tint] filtre les candidats quand elle est un lien : seuls les portails de cette
     * couleur sont considérés, et c'est le plus proche d'entre eux qui gagne. La couleur
     * réordonne un choix, elle n'étend jamais l'emprise.
     *
     * En cas d'égalité stricte, priorité aux coordonnées les plus faibles, dans l'ordre X,
     * puis Y, puis Z : l'ordre est total, donc le résultat est reproductible.
     */
    fun findNearest(
        level: ServerLevel,
        ideal: BlockPos,
        inTravel: Boolean,
        config: TravelConfig,
        tint: PortalTint = PortalTint.NONE,
    ): PortalRect? {
        val radius = if (inTravel) config.searchRadiusVoyage else config.searchRadiusOverworld
        if (radius < 1) return null

        val (minY, maxY) = when (config.verticalMode) {
            VerticalMode.FULL_HEIGHT -> level.minY to level.maxY
            VerticalMode.BOUNDED -> (ideal.y - config.verticalRadius) to (ideal.y + config.verticalRadius)
        }

        var best: PortalRect? = null
        var bestDistance = Double.MAX_VALUE

        forEachCompletePortal(level, Box.around(ideal, radius), minY, maxY) { shape, centre ->
            val rect = rectOf(level, shape)
            if (!tint.isLink || rect.tint == tint) {
                val distance = PortalCoordinates.distanceSquared(
                    centre, ideal, inTravel, config.ratio, config.verticalWeight
                )
                if (isBetter(distance, centre, bestDistance, best?.centre)) {
                    best = rect
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /** Ordre total : la distance, puis X, puis Y, puis Z. */
    private fun isBetter(distance: Double, centre: BlockPos, bestDistance: Double, best: BlockPos?): Boolean {
        if (best == null) return true
        if (distance != bestDistance) return distance < bestDistance
        if (centre.x != best.x) return centre.x < best.x
        if (centre.y != best.y) return centre.y < best.y
        return centre.z < best.z
    }

    /**
     * Le portail complet dont l'ancre est exactement [centre], ou `null`.
     *
     * Sert à la mémoire de trajet : un portail cassé depuis, ou reconstruit décalé, doit
     * rendre la main au calcul plutôt que de téléporter dans le vide.
     */
    fun completePortalAt(level: ServerLevel, centre: BlockPos): PortalRect? {
        val state = level.getBlockState(centre)
        if (!state.`is`(ModBlocks.TRAVEL_PORTAL)) return null

        val axis = state.getOptionalValue(TravelPortalBlock.AXIS).orElse(Direction.Axis.X)
        val shape = TravelPortalShape.findAnyShape(level, centre, axis)
        if (!shape.isComplete() || shape.centre() != centre) return null

        return rectOf(level, shape)
    }

    /**
     * Tous les portails complets d'une emprise, pour les commandes de diagnostic.
     *
     * Ne charge que les chunks déjà chargés : mesurer ne doit pas générer du terrain, sans
     * quoi un simple `/tdtest scan` peuplerait la carte en la lisant.
     */
    fun portalsIn(
        level: ServerLevel,
        box: Box,
        minY: Int,
        maxY: Int,
        loadedOnly: Boolean = true,
    ): List<PortalRect> {
        val found = ArrayList<PortalRect>()
        forEachCompletePortal(level, box, minY, maxY, loadedOnly) { shape, _ ->
            found.add(rectOf(level, shape))
        }
        return found
    }

    /**
     * Parcourt les portails COMPLETS dont l'ancre tombe dans [box] et entre [minY] et [maxY],
     * une seule fois chacun. Voir la note de classe sur le balayage par palette.
     */
    private inline fun forEachCompletePortal(
        level: ServerLevel,
        box: Box,
        minY: Int,
        maxY: Int,
        loadedOnly: Boolean = false,
        action: (TravelPortalShape, BlockPos) -> Unit,
    ) {
        val seen = HashSet<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()

        for (chunkX in (box.minX shr 4)..(box.maxX shr 4)) {
            for (chunkZ in (box.minZ shr 4)..(box.maxZ shr 4)) {
                // La RÉSOLUTION doit charger : un portail existe même dans un chunk endormi,
                // et l'ignorer ferait naître un doublon. Le DIAGNOSTIC, lui, ne charge rien :
                // mesurer ne doit pas générer du terrain.
                if (loadedOnly && !level.chunkSource.hasChunk(chunkX, chunkZ)) continue

                // JAMAIS DE GÉNÉRATION DE TERRAIN. `ChunkStatus.EMPTY` est le statut d'entrée :
                // un chunk déjà sauvegardé est relu du disque avec ses blocs, un chunk jamais
                // généré rend une coquille vide que la boucle saute en une comparaison de
                // section. Demander le statut complet, comme le fait `getChunk(x, z)`, ferait
                // FABRIQUER le terrain manquant, sur le thread serveur : côté OVERWORLD
                // l'emprise couvre 17 x 17 chunks, soit près de trois cents chunks générés à
                // chaque traversée et à chaque allumage. C'est aussi ce que fait vanilla pour
                // chercher un portail du NETHER (`PoiManager.ensureLoadedAndValid`).
                val chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, true) ?: continue
                val sections = chunk.sections

                val x0 = maxOf(box.minX, chunkX shl 4)
                val x1 = minOf(box.maxX, (chunkX shl 4) + 15)
                val z0 = maxOf(box.minZ, chunkZ shl 4)
                val z1 = minOf(box.maxZ, (chunkZ shl 4) + 15)

                for (index in sections.indices) {
                    val section = sections[index]
                    if (section.hasOnlyAir()) continue
                    if (!section.maybeHas { state -> state.`is`(ModBlocks.TRAVEL_PORTAL) }) continue

                    val baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(index))
                    if (baseY + 15 < minY || baseY > maxY) continue

                    for (y in maxOf(baseY, minY)..minOf(baseY + 15, maxY)) {
                        for (x in x0..x1) {
                            for (z in z0..z1) {
                                // L'axe se lit sur la section déjà en main : inutile de
                                // repasser par le niveau, qui forcerait le chargement complet
                                // du chunk avant même de savoir s'il y a une forme valide.
                                val state = section.getBlockState(x and 15, y and 15, z and 15)
                                if (!state.`is`(ModBlocks.TRAVEL_PORTAL)) continue

                                cursor.set(x, y, z)
                                val axis = state.getOptionalValue(TravelPortalBlock.AXIS)
                                    .orElse(Direction.Axis.X)
                                // À partir d'ici seulement, le niveau est sollicité, donc le
                                // chunk chargé pour de bon : uniquement pour les rares chunks
                                // qui portent réellement un bloc de portail.
                                val shape = TravelPortalShape.findAnyShape(level, cursor.immutable(), axis)
                                if (!shape.isComplete()) continue

                                val centre = shape.centre()
                                if (centre !in box || centre.y !in minY..maxY) continue
                                if (!seen.add(centre)) continue

                                action(shape, centre)
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // La pose
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Bâtit un portail exactement au point demandé**, à la taille et sur l'axe du portail
     * source. Aucun ajustement vertical, aucune recherche d'un endroit convenable : c'est le
     * terrain qui s'adapte au portail.
     *
     * [centre] est l'ANCRE du portail à créer : rangée du bas, à `(largeur - 1) / 2` du coin
     * minimal.
     *
     * Quatre temps, dans cet ordre :
     * 1. le **dégagement**, qui retire les solides autour et au-dessus ;
     * 2. la **plateforme**, coulée en dessous, qui ne remplit QUE les blocs remplaçables ;
     * 3. le **cadre** ;
     * 4. les **blocs de portail**.
     *
     * Rien de ce qui a été bâti n'est amputé : ni portail, ni cadre, ni bloc indestructible.
     * La protection des portails n'est pas une politesse, sans elle deux portails voisins se
     * mangent l'un l'autre.
     */
    fun build(
        level: ServerLevel,
        centre: BlockPos,
        axis: Direction.Axis,
        width: Int,
        height: Int,
        config: TravelConfig,
    ): PortalRect {
        val along = alongOf(axis)
        val perpendicular = if (axis == Direction.Axis.X) Direction.SOUTH else Direction.EAST

        /** L'emprise que ce portail occuperait à une altitude donnée : tout ce qu'il touche. */
        fun footprintAt(y: Int): BoundingBox {
            val at = BlockPos(centre.x, y, centre.z).relative(along, -((width - 1) / 2))
            val margin = maxOf(config.clearanceMargin, config.platformMargin)
            val low = at.relative(along, -1 - margin).relative(perpendicular, -margin)
                .below(maxOf(config.platformDepth, 1))
            val high = at.relative(along, width + margin).relative(perpendicular, margin)
                .above(height - 1 + config.clearanceHeight)
            return BoundingBox(low.x, low.y, low.z, high.x, high.y, high.z).let { box ->
                BoundingBox(
                    minOf(box.minX(), box.maxX()), minOf(box.minY(), box.maxY()), minOf(box.minZ(), box.maxZ()),
                    maxOf(box.minX(), box.maxX()), maxOf(box.minY(), box.maxY()), maxOf(box.minZ(), box.maxZ()),
                )
            }
        }

        // ── 0. ÉPARGNER CE QUI EST BÂTI. Le portail cherche une altitude qui ne détruit
        //       rien, au plus près de la sienne. Voir PortalGround pour ce que « bâti »
        //       veut dire, et pourquoi ce n'est qu'une présomption.
        //
        //       Une INSTALLATION DE REDSTONE a droit à un traitement à part : elle refuse le
        //       terrain sans regarder la fréquentation du chunk, et le portail cherche alors
        //       une altitude libre sur TOUTE la hauteur du monde. Une machine ne se répare pas
        //       en reposant les blocs, un portail perché se contourne.
        var anchor = centre
        val redstone = PortalGround.hasRedstoneWorks(level, footprintAt(centre.y), config)
        if (PortalGround.refuses(level, footprintAt(centre.y), config)) {
            val floor = level.minY + config.platformDepth + 1
            val ceiling = level.maxY - height - 1
            val reach = if (redstone) ceiling - floor else config.buildShiftMaxOffset
            val free = PortalGround.clearAltitude(level, config, centre.y, floor, ceiling, reach, ::footprintAt)

            if (free != null && free != centre.y) {
                anchor = BlockPos(centre.x, free, centre.z)
                TravellingDimension.LOGGER.info(
                    "{} repérée en {} : portail décalé en Y={} ({} blocs)",
                    if (redstone) "Installation de redstone" else "Construction",
                    centre.toShortString(), free, free - centre.y
                )
            } else if (free == null) {
                TravellingDimension.LOGGER.warn(
                    "{} repérée en {} et aucune altitude libre à {} blocs : on bâtit sur place",
                    if (redstone) "Installation de redstone" else "Construction",
                    centre.toShortString(), reach
                )
            }
        }

        // ── 0 bis. Les conteneurs de l'emprise retenue partent à l'abri, contenu compris.
        PortalGround.rescueContainers(level, footprintAt(anchor.y), anchor, config)

        TravellingDimension.LOGGER.info(
            "Portail créé, ancre en {} (axe {}, intérieur {}x{}) dans {}",
            anchor.toShortString(), axis, width, height, level.dimension().identifier()
        )

        val corner = anchor.relative(along, -((width - 1) / 2))

        val air = Blocks.AIR.defaultBlockState()
        val frame = PortalFrame.state
        val platform = platformState(config)
        val portal = ModBlocks.TRAVEL_PORTAL.defaultBlockState().setValue(TravelPortalBlock.AXIS, axis)

        /** Jamais touché : un portail voisin, son cadre, ou l'indestructible. */
        fun isProtected(pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            return state.`is`(ModBlocks.TRAVEL_PORTAL) ||
                    PortalFrame.matches(state) ||
                    state.getDestroySpeed(level, pos) < 0f
        }

        // ── 1. Le dégagement : le portail doit être praticable en pleine roche comme en
        //       plein océan. Les fluides partent avec les solides, mettre de l'air suffit.
        val cm = config.clearanceMargin
        forEachInBox(
            corner.relative(along, -1 - cm).relative(perpendicular, -cm),
            corner.relative(along, width + cm).relative(perpendicular, cm)
                .above(height - 1 + config.clearanceHeight),
        ) { pos ->
            val state = level.getBlockState(pos)
            val solid = !state.isAir
            val fluid = !state.fluidState.isEmpty
            if (!isProtected(pos) && (solid || (fluid && config.removeFluids))) {
                level.setBlock(pos, air, 2)
            }
        }

        // ── 2. La plateforme : elle ne remplace QUE les blocs remplaçables, donc une
        //       construction préexistante est préservée, quitte à ce que la dalle soit
        //       partielle sur un flanc de colline. C'est le choix : mieux vaut une dalle
        //       trouée qu'un mur de joueur percé.
        if (config.platformDepth > 0) {
            val pm = config.platformMargin
            forEachInBox(
                corner.relative(along, -1 - pm).relative(perpendicular, -pm).below(config.platformDepth),
                corner.relative(along, width + pm).relative(perpendicular, pm).below(1),
            ) { pos ->
                val state = level.getBlockState(pos)
                if (state.canBeReplaced() && !isProtected(pos)) {
                    level.setBlock(pos, platform, 2)
                }
            }
        }

        // ── 3. Le cadre, coins compris : plus propre à l'œil, la validation ne les exige pas.
        forEachInBox(corner.relative(along, -1).below(), corner.relative(along, width).above(height)) { pos ->
            if (isFrameCell(pos, corner, along, width, height)) level.setBlock(pos, frame, 2)
        }

        // ── 4. Les blocs de portail (flag 18 comme vanilla : pas de re-validation de forme
        //       pendant la pose).
        forEachInBox(corner, corner.relative(along, width - 1).above(height - 1)) { pos ->
            level.setBlock(pos, portal, 18)
        }

        return rectFromCentre(anchor, width, height, axis)
    }

    /** Le bloc de la plateforme, résolu depuis la config, avec repli sur la calcite. */
    private fun platformState(config: TravelConfig): BlockState {
        val id = Identifier.tryParse(config.platformBlock)
        val block = id?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        if (block == null) {
            TravellingDimension.LOGGER.warn(
                "platformBlock=\"{}\" : aucun bloc de ce nom (mod absent ?), retour à minecraft:calcite",
                config.platformBlock
            )
            return Blocks.CALCITE.defaultBlockState()
        }
        return block.defaultBlockState()
    }

    /** Une case appartient-elle au cadre, c'est-à-dire au pourtour du rectangle intérieur ? */
    private fun isFrameCell(pos: BlockPos, corner: BlockPos, along: Direction, width: Int, height: Int): Boolean {
        val da = (pos.x - corner.x) * along.stepX + (pos.z - corner.z) * along.stepZ
        val dy = pos.y - corner.y
        return da == -1 || da == width || dy == -1 || dy == height
    }

    /** Itère toutes les positions du pavé délimité par deux coins, bornes comprises. */
    private inline fun forEachInBox(a: BlockPos, b: BlockPos, action: (BlockPos) -> Unit) {
        BlockPos.betweenClosed(
            minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
            maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z),
        ).forEach { pos -> action(pos.immutable()) }
    }
}
