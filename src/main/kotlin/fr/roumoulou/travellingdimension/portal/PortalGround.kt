// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.TravelConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.storage.TagValueInput

/**
 * **Ne pas détruire ce qu'un joueur a bâti.**
 *
 * Un portail se crée à la coordonnée exacte, et il remplace ce qui s'y trouve. Tant qu'il
 * s'agit de pierre et de terre, personne ne s'en plaint. Quand il s'agit d'une maison, si.
 *
 * ## Le problème de fond, dit franchement
 *
 * **Minecraft n'enregistre nulle part qui a posé un bloc.** Il est donc impossible de savoir
 * qu'une pierre taillée vient d'un joueur plutôt que d'un village. Tout ce qui suit est une
 * PRÉSOMPTION, jamais une certitude, et elle est construite sur trois signaux que le jeu
 * tient vraiment.
 *
 * Le choix qui gouverne tout le reste : **un faux positif est bon marché**, le portail se
 * décale de quelques blocs pour rien ; un faux négatif coûte cher, un trou dans une base.
 * La règle penche donc systématiquement du côté prudent.
 *
 * ## Signal 1, le seul qui soit sûr : le chunk a-t-il vu un joueur ?
 *
 * `getInhabitedTime` est le compteur vanilla du temps que des joueurs ont passé dans un
 * chunk, celui qui sert à la difficulté locale. Un chunk que personne n'a jamais visité vaut
 * zéro. Si aucun chunk de l'emprise n'a été fréquenté, alors **tout ce qui s'y trouve est de
 * la génération naturelle**, y compris les coffres d'un donjon ou les lits d'un village, et
 * on bâtit sans état d'âme. C'est ce signal qui évite de décaler un portail pour une mine
 * abandonnée que personne n'a jamais vue.
 *
 * ## Signal 2, le plus fiable des marqueurs : les block entities
 *
 * Coffres, tonneaux, fourneaux, panneaux, lits, bannières, trémies, chaudrons de brassage :
 * ce sont eux qu'on tient à ne pas perdre, et le chunk en tient la liste, donc les trouver
 * ne coûte pas un seul balayage de blocs.
 *
 * ## Signal 3, le plus discutable : une liste de blocs
 *
 * Une table de craft ou une bibliothèque au milieu de nulle part trahit une main humaine.
 * Mais elles poussent aussi dans les villages, et un mur de pierre taillée est indétectable.
 * La liste est donc **configurable**, courte par défaut, et c'est le réglage à étendre selon
 * ce que l'on bâtit sur son serveur.
 */
object PortalGround {

    /** Une position de conteneur et l'endroit où il a été mis à l'abri. */
    data class Rescue(val from: BlockPos, val to: BlockPos)

    // ─────────────────────────────────────────────────────────────────────────
    // Lire le terrain
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Aucun joueur n'a jamais mis les pieds dans cette emprise.**
     *
     * Vrai quand tous les chunks touchés sont sous le seuil de fréquentation. Dans ce cas
     * tout ce qui s'y trouve vient de la génération du monde, et rien n'est à protéger.
     */
    fun untouched(level: ServerLevel, box: BoundingBox, config: TravelConfig): Boolean {
        for (chunkX in (box.minX() shr 4)..(box.maxX() shr 4)) {
            for (chunkZ in (box.minZ() shr 4)..(box.maxZ() shr 4)) {
                val chunk = level.getChunk(chunkX, chunkZ)
                if (chunk.inhabitedTime > config.inhabitedThreshold) return false
            }
        }
        return true
    }

    /**
     * Les marques de construction trouvées dans l'emprise, block entities d'abord.
     *
     * S'arrête au premier marqueur quand [firstOnly] : chercher si le terrain est libre ne
     * demande pas d'inventorier ce qui l'occupe.
     */
    fun playerMade(
        level: ServerLevel,
        box: BoundingBox,
        config: TravelConfig,
        firstOnly: Boolean = false,
    ): List<BlockPos> {
        val found = ArrayList<BlockPos>()

        // Les block entities, sans balayer un seul bloc : le chunk en tient la liste.
        for (chunkX in (box.minX() shr 4)..(box.maxX() shr 4)) {
            for (chunkZ in (box.minZ() shr 4)..(box.maxZ() shr 4)) {
                for (pos in level.getChunk(chunkX, chunkZ).blockEntities.keys) {
                    if (box.isInside(pos)) {
                        found.add(pos.immutable())
                        if (firstOnly) return found
                    }
                }
            }
        }

        // Puis la liste de blocs, qui demande un balayage. L'emprise d'un portail compte un
        // millier de positions, c'est négligeable.
        val watched = watchedBlocks(config)
        if (watched.isEmpty()) return found

        for (pos in BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
        )) {
            if (level.getBlockState(pos).block in watched) {
                found.add(pos.immutable())
                if (firstOnly) return found
            }
        }
        return found
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Le veto de la redstone
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Combien de blocs de redstone dans l'emprise**, au plus [stopAt].
     *
     * On s'arrête dès le seuil atteint : savoir qu'il y a « au moins huit » suffit à refuser,
     * inutile d'inventorier une usine entière.
     */
    fun redstoneCount(level: ServerLevel, box: BoundingBox, config: TravelConfig, stopAt: Int): Int {
        if (stopAt <= 0) return 0
        val watched = redstoneSet(config)
        if (watched.isEmpty()) return 0

        var found = 0
        for (pos in BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
        )) {
            if (level.getBlockState(pos).block in watched) {
                found++
                if (found >= stopAt) return found
            }
        }
        return found
    }

    /**
     * **L'emprise porte-t-elle une installation de redstone ?**
     *
     * Le seul marqueur qui ne trompe pas : un coffre pousse dans un village, une machine a un
     * auteur. Ce veto **ignore le seuil de fréquentation et `protectPlayerBuilds`** : une machine
     * bâtie dans un chunk que le compteur croit vierge était sinon invisible.
     */
    fun hasRedstoneWorks(level: ServerLevel, box: BoundingBox, config: TravelConfig): Boolean {
        val threshold = config.redstoneVeto
        return threshold > 0 && redstoneCount(level, box, config, threshold) >= threshold
    }

    /**
     * **Le terrain refuse-t-il ce portail ?**
     *
     * Deux raisons possibles, et elles ne suivent pas la même règle :
     * - une **installation de redstone**, qui refuse toujours, sans regarder la fréquentation ;
     * - des **marques de construction ordinaires**, qui ne comptent que dans un chunk fréquenté
     *   et que `protectPlayerBuilds` peut désactiver.
     */
    fun refuses(level: ServerLevel, box: BoundingBox, config: TravelConfig): Boolean {
        if (hasRedstoneWorks(level, box, config)) return true
        if (!config.protectPlayerBuilds) return false
        if (untouched(level, box, config)) return false
        return playerMade(level, box, config, firstOnly = true).isNotEmpty()
    }

    /**
     * **L'altitude libre la plus proche de celle voulue**, ou `null` si aucune ne convient.
     *
     * On essaie la hauteur voulue, puis un bloc au-dessus, un en dessous, deux au-dessus, et
     * ainsi de suite : le portail s'éloigne le moins possible de son point idéal. À égalité
     * de distance, le HAUT gagne, parce qu'un portail perché reste accessible alors qu'un
     * portail enterré sous une maison demande de creuser.
     *
     * [footprintAt] rend l'emprise que le portail occuperait à une altitude donnée.
     *
     * [maxOffset] borne l'écart. L'appelant passe [TravelConfig.buildShiftMaxOffset] dans le cas
     * ordinaire, et **toute la hauteur du monde** quand c'est une installation de redstone qui
     * refuse le terrain : percer une machine coûte trop cher pour renoncer après trente-deux
     * blocs.
     */
    fun clearAltitude(
        level: ServerLevel,
        config: TravelConfig,
        wantedY: Int,
        minY: Int,
        maxY: Int,
        maxOffset: Int,
        footprintAt: (Int) -> BoundingBox,
    ): Int? {
        fun clear(y: Int): Boolean = y in minY..maxY && !refuses(level, footprintAt(y), config)

        if (clear(wantedY)) return wantedY
        if (maxOffset <= 0) return null

        for (offset in 1..maxOffset) {
            if (clear(wantedY + offset)) return wantedY + offset
            if (clear(wantedY - offset)) return wantedY - offset
        }
        return null
    }

    /** Les blocs de redstone de la config, résolus une fois puis gardés. */
    private var cachedRedstoneFor: List<String>? = null
    private var cachedRedstone: Set<Block> = emptySet()

    private fun redstoneSet(config: TravelConfig): Set<Block> {
        if (cachedRedstoneFor == config.redstoneBlocks) return cachedRedstone
        val blocks = LinkedHashSet<Block>()
        for (id in config.redstoneBlocks) {
            val parsed = Identifier.tryParse(id.trim())
            val block = parsed?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
            if (block == null) {
                TravellingDimension.LOGGER.warn("redstoneBlocks : \"{}\" n'est aucun bloc connu, ignoré", id)
            } else {
                blocks.add(block)
            }
        }
        cachedRedstoneFor = config.redstoneBlocks
        cachedRedstone = blocks
        return blocks
    }

    /**
     * **Le plus haut portail qui tienne SOUS le toit**, en partant de [top] et en descendant.
     *
     * Une dimension à plafond, le NETHER en l'occurrence, porte une couche de bedrock au-dessus
     * de laquelle il y a de la place, et cette place est un terrain de jeu de joueur : on y bâtit
     * une autoroute, on y pose son portail à la main. **Le mod n'y bâtit jamais.** Un portail
     * créé tout seul sur le toit serait une surprise désagréable, et y arriver en voyageant
     * serait pire encore.
     *
     * Le toit n'est pas une altitude ronde : la bedrock du NETHER est bruitée sur quelques
     * couches. On part donc du plafond LOGIQUE de la dimension, celui que vanilla respecte
     * aussi, et on descend tant que le cadre mordrait dans de l'indestructible. [frameBoxAt]
     * rend le rectangle du CADRE à une altitude donnée : ce qui doit rester libre, ce n'est pas
     * l'emprise entière, dont le dégagement et la dalle contournent déjà l'indestructible.
     *
     * Dans une dimension sans plafond, il n'y a rien à faire et [top] est rendu tel quel.
     */
    fun underRoof(
        level: ServerLevel,
        top: Int,
        floor: Int,
        frameBoxAt: (Int) -> BoundingBox,
    ): Int {
        if (!level.dimensionType().hasCeiling()) return top

        for (y in top downTo floor) {
            if (!holdsIndestructible(level, frameBoxAt(y))) return y
        }
        TravellingDimension.LOGGER.warn(
            "Aucune altitude sans indestructible entre {} et {} : on bâtit au plancher", floor, top
        )
        return floor
    }

    /** Vrai dès qu'un bloc de l'emprise est incassable : bedrock du toit, du plancher, barrière. */
    private fun holdsIndestructible(level: ServerLevel, box: BoundingBox): Boolean {
        for (pos in BlockPos.betweenClosed(
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()
        )) {
            if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0f) return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mettre les coffres à l'abri
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * **Déménage les conteneurs de l'emprise**, contenu compris, à un endroit tiré au sort
     * dans un rayon autour du portail.
     *
     * Vaut quoi qu'il arrive, même quand le décalage a échoué et qu'on bâtit sur la
     * construction : mieux vaut un coffre déplacé de six blocs qu'un coffre effacé.
     *
     * Le déménagement recopie l'état du bloc ET la sauvegarde complète de son contenu, donc
     * un coffre garde ses objets, son nom et le reste. La source est vidée avant d'être
     * remplacée, sinon le jeu ferait tomber les objets au sol en la cassant.
     */
    fun rescueContainers(
        level: ServerLevel,
        box: BoundingBox,
        around: BlockPos,
        config: TravelConfig,
    ): List<Rescue> {
        if (!config.rescueContainers) return emptyList()

        val containers = ArrayList<BlockPos>()
        for (chunkX in (box.minX() shr 4)..(box.maxX() shr 4)) {
            for (chunkZ in (box.minZ() shr 4)..(box.maxZ() shr 4)) {
                for ((pos, blockEntity) in level.getChunk(chunkX, chunkZ).blockEntities) {
                    if (blockEntity is Container && box.isInside(pos)) containers.add(pos.immutable())
                }
            }
        }
        if (containers.isEmpty()) return emptyList()

        val taken = HashSet<BlockPos>()
        val moved = ArrayList<Rescue>()
        for (from in containers) {
            val to = shelter(level, box, around, config, taken) ?: run {
                TravellingDimension.LOGGER.warn(
                    "Conteneur en {} : aucun abri libre à {} blocs, il sera remplacé",
                    from.toShortString(), config.rescueRadius
                )
                null
            } ?: continue

            if (move(level, from, to)) {
                taken.add(to)
                moved.add(Rescue(from, to))
                TravellingDimension.LOGGER.info(
                    "Conteneur déménagé de {} vers {}", from.toShortString(), to.toShortString()
                )
            }
        }
        return moved
    }

    /**
     * Un abri : hors de l'emprise du portail, dans le rayon, sur un sol qui porte, et à une
     * position encore libre.
     *
     * On tire au sort, comme demandé, mais un tirage peut ne rien trouver : après quelques
     * essais infructueux on balaie, pour ne jamais perdre un coffre par malchance.
     */
    private fun shelter(
        level: ServerLevel,
        box: BoundingBox,
        around: BlockPos,
        config: TravelConfig,
        taken: Set<BlockPos>,
    ): BlockPos? {
        val radius = config.rescueRadius
        val random = level.random

        fun suitable(pos: BlockPos): Boolean {
            if (box.isInside(pos) || pos in taken) return false
            if (pos.y <= level.minY || pos.y >= level.maxY) return false
            if (!level.getBlockState(pos).canBeReplaced()) return false
            return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
        }

        repeat(64) {
            val candidate = BlockPos(
                around.x + random.nextInt(radius * 2 + 1) - radius,
                around.y + random.nextInt(radius + 1) - radius / 2,
                around.z + random.nextInt(radius * 2 + 1) - radius,
            )
            if (suitable(candidate)) return candidate
        }

        for (pos in BlockPos.betweenClosed(
            around.offset(-radius, -radius, -radius),
            around.offset(radius, radius, radius),
        )) {
            if (suitable(pos)) return pos.immutable()
        }
        return null
    }

    /** Recopie le bloc et sa sauvegarde complète, puis vide la source. */
    private fun move(level: ServerLevel, from: BlockPos, to: BlockPos): Boolean {
        val state = level.getBlockState(from)
        val source = level.getBlockEntity(from) ?: return false

        val tag = try {
            source.saveWithFullMetadata(level.registryAccess())
        } catch (e: Exception) {
            TravellingDimension.LOGGER.warn("Conteneur en {} illisible : {}", from.toShortString(), e.message)
            return false
        }
        // La position est réécrite par le jeu à la pose : la garder ferait revenir le bloc
        // sur son ancienne case.
        tag.remove("x"); tag.remove("y"); tag.remove("z")

        level.setBlock(to, state, Block.UPDATE_ALL)
        val target = level.getBlockEntity(to)
        if (target == null) {
            TravellingDimension.LOGGER.warn("Abri en {} sans block entity, déménagement annulé", to.toShortString())
            return false
        }
        target.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        target.setChanged()

        // Vider la source AVANT que la construction ne la remplace, sinon le jeu sème les
        // objets au sol en cassant le bloc.
        (source as? Container)?.let { container ->
            for (slot in 0 until container.containerSize) {
                container.setItem(slot, ItemStack.EMPTY)
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // La liste de blocs surveillés
    // ─────────────────────────────────────────────────────────────────────────

    private var cachedFor: List<String>? = null
    private var cached: Set<Block> = emptySet()

    /** Les blocs de la config, résolus une fois puis gardés tant que la liste ne change pas. */
    private fun watchedBlocks(config: TravelConfig): Set<Block> {
        if (cachedFor == config.playerMadeBlocks) return cached

        val blocks = LinkedHashSet<Block>()
        for (id in config.playerMadeBlocks) {
            val parsed = Identifier.tryParse(id.trim())
            val block = parsed?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
            if (block == null) {
                TravellingDimension.LOGGER.warn("playerMadeBlocks : \"{}\" n'est aucun bloc connu, ignoré", id)
            } else {
                blocks.add(block)
            }
        }
        cachedFor = config.playerMadeBlocks
        cached = blocks
        return blocks
    }
}
