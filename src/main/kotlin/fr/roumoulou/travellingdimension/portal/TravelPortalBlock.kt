// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.Relative
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.portal.PortalShape
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Bloc de portail de VOYAGE, l'équivalent du bloc de portail du NETHER.
 *
 * La transformation de coordonnées donne un **point idéal** ; la destination effective est
 * le portail existant le plus proche de ce point, et la construction n'arrive qu'en dernier
 * recours. La transformation est pure, la destination ne l'est pas : elle dépend de ce qui
 * est déjà bâti, et c'est assumé.
 *
 * Trajets : OVERWORLD et VOYAGE, dans les deux sens. Dans toute autre dimension le portail
 * reste inerte, l'allume-portail refusant de toute façon de s'y allumer.
 */
class TravelPortalBlock(properties: BlockBehaviour.Properties) : Block(properties), Portal {

    companion object {
        val AXIS: EnumProperty<Direction.Axis> = BlockStateProperties.HORIZONTAL_AXIS


        /** Couleur posée au colorant : le lien explicite entre deux portails. */
        val COLOR: EnumProperty<PortalTint> = EnumProperty.create("color", PortalTint::class.java)

        private val SHAPES: Map<Direction.Axis, VoxelShape> =
            Shapes.rotateHorizontalAxis(Block.column(4.0, 16.0, 0.0, 16.0))
    }

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(AXIS, Direction.Axis.X)
                .setValue(COLOR, PortalTint.NONE)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AXIS, COLOR)
    }

    /**
     * Clic droit avec un colorant : le portail entier prend cette couleur. Le même
     * colorant sur un portail déjà de cette couleur l'efface (un seul geste sert aux
     * deux, pas d'objet supplémentaire à trouver pour défaire).
     *
     * La couleur est posée sur TOUS les blocs du portail : un portail est un objet,
     * pas une collection de blocs, et une moitié teinte n'aurait aucun sens.
     *
     * **Rien n'est refusé.** Autant de portails d'une même couleur que l'on veut, des deux
     * côtés : la couleur est un FILTRE sur les candidats, pas une réservation. À la
     * traversée, c'est le portail le plus proche PARMI ceux de cette couleur qui gagne, et
     * si aucun n'est à portée on retombe sur la règle ordinaire.
     */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val wanted = PortalTint.ofItem(stack.item) ?: return super.useItemOn(stack, state, level, pos, player, hand, hit)

        // Réglage coupé : le colorant redevient un colorant ordinaire sur ce bloc, et les
        // couleurs déjà posées restent dans la sauvegarde sans plus rien décider.
        if (!ConfigManager.current.portalTints) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit)
        }

        val target = if (state.getValue(COLOR) == wanted) PortalTint.NONE else wanted

        // La forme se mesure des deux côtés : le client ne fait pas le geste sur un portail
        // incomplet, et c'est le serveur qui le dit au joueur.
        val shape = TravelPortalShape.findAnyShape(level, pos, state.getValue(AXIS))
        if (!shape.isComplete()) {
            if (!level.isClientSide) {
                player.sendOverlayMessage(
                    Component.translatable("block.travellingdimension.travel_portal.incomplete")
                )
            }
            return InteractionResult.FAIL
        }
        if (level.isClientSide) return InteractionResult.SUCCESS

        paintPortal(level, shape, target)
        if (!player.abilities.instabuild) stack.consume(1, player)
        level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0f, 1.0f)

        player.sendOverlayMessage(
            if (target.isLink) {
                Component.translatable(
                    "block.travellingdimension.travel_portal.tinted",
                    Component.translatable("color.minecraft.${target.serializedName}")
                )
            } else {
                Component.translatable("block.travellingdimension.travel_portal.untinted")
            }
        )
        return InteractionResult.SUCCESS
    }

    /** Applique [tint] à tout l'intérieur du portail décrit par [shape]. */
    private fun paintPortal(level: Level, shape: TravelPortalShape, tint: PortalTint) {
        val min = shape.minCorner()
        val along = if (shape.axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        BlockPos.betweenClosed(min, min.relative(along, shape.width - 1).above(shape.height - 1))
            .forEach { pos ->
                val current = level.getBlockState(pos)
                // Flag 18 comme la pose : on prévient les clients sans déclencher de
                // mise à jour de voisinage, qui re-validerait la forme pour rien.
                if (current.`is`(this)) level.setBlock(pos, current.setValue(COLOR, tint), 18)
            }
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(AXIS))

    /**
     * Cassage propre : si un voisin change et que le cadre n'est plus complet,
     * le bloc de portail disparaît (cascade -> tout le portail s'éteint, pas de crash,
     * pas d'entité orpheline). Réactivation possible à l'igniter sur cadre re-validé.
     */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        val updateAxis = directionToNeighbour.axis
        val axis = state.getValue(AXIS)
        val wrongAxis = axis != updateAxis && updateAxis.isHorizontal
        return if (!wrongAxis && !neighbourState.`is`(this) && !TravelPortalShape.findAnyShape(level, pos, axis).isComplete()) {
            Blocks.AIR.defaultBlockState()
        } else {
            super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
    }

    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        // Cooldown standard type Nether : canUsePortal le vérifie (anti boucle de TP).
        if (entity.canUsePortal(false)) {
            entity.setAsInsidePortal(this, pos)
        }
    }

    /** Même temporisation que le portail du Nether (gamerules, instantané en créatif). */
    override fun getPortalTransitionTime(level: ServerLevel, entity: Entity): Int =
        if (entity is Player) {
            maxOf(
                0,
                level.gameRules.get(
                    if (entity.abilities.invulnerable) GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY
                    else GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY
                )
            )
        } else 0

    /**
     * **Le cœur du mod** : où mène ce portail ?
     *
     * Quatre rangs, dans cet ordre, et le premier qui répond gagne :
     *
     * 1. **la couleur**, si le portail de départ en porte une. Elle filtre les candidats, et
     *    c'est le plus proche de cette couleur qui gagne. C'est le seul choix que le joueur
     *    pose lui-même, il passe donc devant tout automatisme ;
     * 2. **la mémoire du trajet** (`rememberEntryPortal`, désactivée par défaut), qui ramène
     *    par le portail exact emprunté à l'aller ;
     * 3. **le portail le plus proche** du point idéal, dans l'emprise de recherche ;
     * 4. **la construction**, à la case exacte, à la taille et sur l'axe du portail source.
     *
     * Un portail existant l'emporte donc toujours sur la création, même quand le point idéal
     * est libre. C'est le modèle du NETHER, et sa contrepartie est connue : bâtir un portail
     * change la destination de ses voisins. Le colorant est la parade.
     */
    override fun getPortalDestination(currentLevel: ServerLevel, entity: Entity, portalEntryPos: BlockPos): TeleportTransition? {
        val config = ConfigManager.current
        val ratio = config.ratio

        val destKey = when (currentLevel.dimension()) {
            Level.OVERWORLD -> TravelDimensionKeys.TRAVEL_LEVEL
            TravelDimensionKeys.TRAVEL_LEVEL -> Level.OVERWORLD
            else -> {
                TravellingDimension.LOGGER.debug(
                    "Portail de voyage utilisé dans {} : ignoré (seuls l'OVERWORLD et VOYAGE sont reliés)",
                    currentLevel.dimension().identifier()
                )
                return null
            }
        }
        val destLevel = currentLevel.server.getLevel(destKey) ?: run {
            TravellingDimension.LOGGER.error("Dimension de destination {} introuvable", destKey.identifier())
            return null
        }

        /** La destination est-elle VOYAGE ? C'est elle qui fixe l'échelle des distances. */
        val toTravel = destKey == TravelDimensionKeys.TRAVEL_LEVEL

        // Mesure du portail SOURCE : son ancre, sa taille et son axe, qui seront reproduits.
        val sourceState = currentLevel.getBlockState(portalEntryPos)
        val sourceAxis = sourceState.getOptionalValue(AXIS).orElse(Direction.Axis.X)
        val sourceTint = sourceState.getOptionalValue(COLOR).orElse(PortalTint.NONE)
        val sourceShape = TravelPortalShape.findAnyShape(currentLevel, portalEntryPos, sourceAxis)
        if (!sourceShape.isValid()) {
            TravellingDimension.LOGGER.warn(
                "Portail de voyage : forme source invalide en {} ({}), cadre incomplet ou mélangé ?",
                portalEntryPos.toShortString(), currentLevel.dimension().identifier()
            )
            return null
        }

        // TOUT se lit sur l'ANCRE, jamais sur la position du voyageur : deux entités qui
        // traversent le même portail par ses deux bords calculent le même point idéal.
        val sourceCentre = sourceShape.centre()

        val convert: (Int) -> Int =
            if (toTravel) { coord -> PortalCoordinates.overworldToTravel(coord, ratio) }
            else { coord -> PortalCoordinates.travelToOverworld(coord, ratio) }

        val ideal = TravelPortalPlacer.idealPoint(
            destLevel, sourceCentre, sourceShape.height, config.platformDepth, convert
        )

        TravellingDimension.LOGGER.debug(
            "Traversée {} : ancre {} -> point idéal {} dans {} (intérieur {}x{}, axe {})",
            if (toTravel) "OVERWORLD vers VOYAGE" else "VOYAGE vers OVERWORLD",
            sourceCentre.toShortString(), ideal.toShortString(), destLevel.dimension().identifier(),
            sourceShape.width, sourceShape.height, sourceAxis
        )

        /** L'arrivée, quel que soit le rang qui l'a désignée : on retient puis on part. */
        fun arriveAt(rect: TravelPortalPlacer.PortalRect): TeleportTransition {
            if (config.rememberEntryPortal) {
                PortalMemory.remember(entity, sourceCentre, rect.centre)
            }
            return createTransition(destLevel, rect, sourceShape, entity, portalEntryPos)
        }

        // ── Rang 1 : LA COULEUR. Elle filtre les candidats, elle n'étend jamais l'emprise :
        //    deux portails de la même couleur hors de portée l'un de l'autre ne sont pas
        //    reliés. Sans partenaire de cette couleur, on retombe sur la règle ordinaire.
        //    Réglage `portalTints` coupé : le rang saute, les couleurs posées sont ignorées.
        if (config.portalTints && sourceTint.isLink) {
            val linked = TravelPortalPlacer.findNearest(destLevel, ideal, toTravel, config, sourceTint)
            if (linked != null) {
                TravellingDimension.LOGGER.debug(
                    "Lien de couleur {} : portail d'ancre {}", sourceTint, linked.centre.toShortString()
                )
                return arriveAt(linked)
            }
        }

        // ── Rang 2 : LA MÉMOIRE DU TRAJET. La transformation divise, donc tous les portails
        //    d'un carré de `ratio` blocs mènent au même endroit : l'information « lequel des
        //    trois ? » est mathématiquement perdue, et seule la mémoire peut la rendre. On ne
        //    s'en sert que si l'entité repart du portail EXACT sur lequel elle était arrivée.
        if (config.rememberEntryPortal) {
            val remembered = PortalMemory.recall(entity, sourceCentre)
                ?.let { TravelPortalPlacer.completePortalAt(destLevel, it) }
            if (remembered != null) {
                TravellingDimension.LOGGER.debug(
                    "Mémoire du trajet : retour par le portail d'ancre {}", remembered.centre.toShortString()
                )
                return arriveAt(remembered)
            }
        }

        // ── Rang 3 : LE PLUS PROCHE. La distance se mesure en blocs d'OVERWORLD, donc à
        //    colonne égale c'est l'étage le plus proche qui gagne.
        val nearest = TravelPortalPlacer.findNearest(destLevel, ideal, toTravel, config)
        if (nearest != null) {
            TravellingDimension.LOGGER.debug(
                "Portail existant rejoint, ancre {} (point idéal {})",
                nearest.centre.toShortString(), ideal.toShortString()
            )
            return arriveAt(nearest)
        }

        // ── Rang 4 : LA CONSTRUCTION, exactement au point idéal, à la taille et sur l'axe du
        //    portail source. Aucun ajustement vertical : c'est le terrain qui s'adapte.
        val built = TravelPortalPlacer.build(
            destLevel, ideal, sourceAxis, sourceShape.width, sourceShape.height, config
        )
        return arriveAt(built)
    }

    /**
     * Position d'arrivée dans le portail de destination : l'offset relatif de l'entité
     * dans le portail source est reproduit (comme vanilla). Les deux portails n'ont pas
     * forcément le même axe (rotation préventive) : l'offset se LIT avec l'axe du portail
     * source et s'APPLIQUE avec celui du portail de destination — entrer au milieu fait
     * sortir au milieu, tourné ou pas.
     */
    private fun createTransition(
        destLevel: ServerLevel,
        destRect: TravelPortalPlacer.PortalRect,
        sourceShape: TravelPortalShape,
        entity: Entity,
        portalEntryPos: BlockPos,
    ): TeleportTransition {
        val axis = destRect.axis

        // Offset relatif (droite, haut, avant) de l'entité dans le portail source.
        val sourceRect = net.minecraft.util.BlockUtil.FoundRectangle(
            sourceShape.minCorner(), sourceShape.width, sourceShape.height
        )
        val offset = entity.getRelativePortalPosition(sourceShape.axis, sourceRect)

        val dimensions: EntityDimensions = entity.getDimensions(entity.pose)
        val offsetRight = dimensions.width() / 2.0 + (destRect.width - dimensions.width()) * offset.x()
        val offsetUp = (destRect.height - dimensions.height()) * offset.y()
        val offsetForward = 0.5 + offset.z()
        val xAligned = axis == Direction.Axis.X

        val targetPos = Vec3(
            destRect.minCorner.x + if (xAligned) offsetRight else offsetForward,
            destRect.minCorner.y + offsetUp,
            destRect.minCorner.z + if (xAligned) offsetForward else offsetRight,
        )
        val collisionFree = PortalShape.findCollisionFreePosition(targetPos, destLevel, entity, dimensions)

        val post = TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)

        // Même axe -> vitesse et regard préservés. Portail TOURNÉ (rotation préventive) ->
        // le regard tourne de 90 degrés avec lui, pour ne pas ressortir face au cadre.
        return if (sourceShape.axis == axis) {
            TeleportTransition(
                destLevel, collisionFree, Vec3.ZERO, 0.0f, 0.0f,
                Relative.union(Relative.DELTA, Relative.ROTATION), post
            )
        } else {
            TeleportTransition(
                destLevel, collisionFree, Vec3.ZERO, entity.yRot + 90.0f, entity.xRot,
                Relative.DELTA, post
            )
        }
    }

    override fun getLocalTransition(): Portal.Transition = Portal.Transition.CONFUSION

    /** Particules et sons d'ambiance (comme le portail du Nether, teinte améthyste côté texture). */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(100) == 0) {
            level.playLocalSound(
                pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
                SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS,
                0.5f, random.nextFloat() * 0.4f + 0.8f, false
            )
        }

        repeat(4) {
            var x = pos.x + random.nextDouble()
            val y = pos.y + random.nextDouble()
            var z = pos.z + random.nextDouble()
            var xa = (random.nextFloat() - 0.5) * 0.5
            val ya = (random.nextFloat() - 0.5) * 0.5
            var za = (random.nextFloat() - 0.5) * 0.5
            val flip = random.nextInt(2) * 2 - 1
            if (!level.getBlockState(pos.west()).`is`(this) && !level.getBlockState(pos.east()).`is`(this)) {
                x = pos.x + 0.5 + 0.25 * flip
                xa = (random.nextFloat() * 2.0f * flip).toDouble()
            } else {
                z = pos.z + 0.5 + 0.25 * flip
                za = (random.nextFloat() * 2.0f * flip).toDouble()
            }
            level.addParticle(ParticleTypes.PORTAL, x, y, z, xa, ya, za)
        }
    }

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        ItemStack.EMPTY

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = when (rotation) {
        Rotation.COUNTERCLOCKWISE_90, Rotation.CLOCKWISE_90 -> when (state.getValue(AXIS)) {
            Direction.Axis.X -> state.setValue(AXIS, Direction.Axis.Z)
            Direction.Axis.Z -> state.setValue(AXIS, Direction.Axis.X)
            else -> state
        }
        else -> state
    }
}
