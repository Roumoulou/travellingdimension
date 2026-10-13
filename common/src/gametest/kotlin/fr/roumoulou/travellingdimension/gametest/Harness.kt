// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.portal.PortalTint
import fr.roumoulou.travellingdimension.portal.TravelPortalBlock
import fr.roumoulou.travellingdimension.portal.TravelPortalShape
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Le harnais commun de l'étage 2 : des secteurs qui ne se voient pas, des cadres posés au bloc,
 * un voyageur, et les dimensions du serveur.
 *
 * ## Les secteurs
 *
 * Le framework GameTest place ses structures côte à côte dans l'OVERWORLD, à quelques blocs les
 * unes des autres : deux tests de traversée y auraient des points idéaux voisins dans VOYAGE, et
 * le portail créé par l'un capterait le voyageur de l'autre, parce qu'un portail existant l'emporte
 * toujours. Chaque test vit donc dans un SECTEUR à lui, loin des structures, et les secteurs sont
 * espacés de 4096 blocs (OVERWORLD), soit 256 blocs (VOYAGE), très au-delà des 8 blocs (VOYAGE) de
 * portée. Le piège est celui du vocabulaire du projet : un secteur de test ne sert qu'une fois.
 *
 * ## Le voyageur
 *
 * Un cochon : l'entité la plus simple qui ait une gravité et une IA. Un mob `NoAI` ne se déplace
 * pas, donc ne rencontre jamais l'intérieur d'un portail. Le chunk du secteur est forcé pour que le
 * cochon tique : les chunks que les tests posent au bloc ne le sont pas d'eux-mêmes.
 *
 * ## Le monde
 *
 * L'OVERWORLD du serveur GameTest est le monde plat de Mojang, et les cadres se posent en Y80, en
 * l'air. VOYAGE et le NETHER se génèrent à la demande, chunk par chunk, quand un portail y est
 * bâti.
 */
internal object Harness {

    /** L'altitude des essais : au-dessus du monde plat du serveur GameTest. */
    const val Y = 80

    private const val ORIGIN_X = 100_000
    private const val ORIGIN_Z = 100_000
    private const val SECTOR_SPACING = 4096

    /** Le coin d'un secteur : un multiple de 8 et de 16 en X et en Z, donc un coin de case dans VOYAGE comme dans le NETHER. */
    fun sector(index: Int): BlockPos = BlockPos(ORIGIN_X + index * SECTOR_SPACING, Y, ORIGIN_Z)

    fun travel(helper: GameTestHelper): ServerLevel =
        helper.level.server.getLevel(TravelDimensionKeys.TRAVEL_LEVEL)
            ?: throw helper.assertionException("VOYAGE n'est pas chargée sur le serveur GameTest")

    fun nether(helper: GameTestHelper): ServerLevel =
        helper.level.server.getLevel(Level.NETHER)
            ?: throw helper.assertionException("le NETHER n'est pas chargé sur le serveur GameTest")

    /** Force ou libère les chunks autour de [pos], [radius] chunks de chaque côté : forcés, ils tiquent, entités comprises. */
    fun forceAround(level: ServerLevel, pos: BlockPos, radius: Int, force: Boolean) {
        val chunkX = SectionPos.blockToSectionCoord(pos.x)
        val chunkZ = SectionPos.blockToSectionCoord(pos.z)
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                level.setChunkForced(chunkX + dx, chunkZ + dz, force)
            }
        }
    }

    /**
     * Pose un cadre du bloc [frame] autour d'un intérieur de [width] x [height] dont le coin
     * minimal est [corner], sur [axis], coins compris ; l'intérieur est vidé.
     */
    fun buildFrame(level: ServerLevel, corner: BlockPos, axis: Direction.Axis, width: Int, height: Int, frame: BlockState) {
        val along = alongOf(axis)
        val low = corner.relative(along, -1).below()
        val high = corner.relative(along, width).above(height)
        for (pos in BlockPos.betweenClosed(low, high)) {
            val da = (pos.x - corner.x) * along.stepX + (pos.z - corner.z) * along.stepZ
            val dy = pos.y - corner.y
            val isFrame = da == -1 || da == width || dy == -1 || dy == height
            level.setBlock(pos.immutable(), if (isFrame) frame else Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        }
    }

    /** La direction des X ou des Z croissants, selon l'axe du portail. */
    fun alongOf(axis: Direction.Axis): Direction = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH

    /** Allume le cadre de VOYAGE dont [inside] est une case intérieure, et rend sa forme. */
    fun lightTravelPortal(helper: GameTestHelper, level: ServerLevel, inside: BlockPos, axis: Direction.Axis): TravelPortalShape {
        val shape = TravelPortalShape.findEmptyPortalShape(level, inside, axis)
            .orElseThrow { helper.assertionException("aucun cadre de VOYAGE vide et valide en ${inside.toShortString()}") }
        shape.createPortalBlocks(level)
        return shape
    }

    /** Pose la couleur [tint] sur tous les blocs du portail [shape], le geste du colorant. */
    fun paint(level: ServerLevel, shape: TravelPortalShape, tint: PortalTint) {
        val along = alongOf(shape.axis)
        val min = shape.minCorner()
        for (pos in BlockPos.betweenClosed(min, min.relative(along, shape.width - 1).above(shape.height - 1))) {
            val state = level.getBlockState(pos)
            if (state.`is`(ModBlocks.TRAVEL_PORTAL)) {
                level.setBlock(pos.immutable(), state.setValue(TravelPortalBlock.COLOR, tint), Block.UPDATE_CLIENTS)
            }
        }
    }

    /** Un cochon posé en [pos], prêt à tiquer. */
    fun spawnPig(helper: GameTestHelper, level: ServerLevel, pos: BlockPos): Entity {
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse("minecraft:pig")).orElseThrow()
        val pig = type.create(level, EntitySpawnReason.TRIGGERED)
            ?: throw helper.assertionException("le cochon n'a pas pu être créé")
        pig.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        level.addFreshEntity(pig)
        return pig
    }

    /** L'entité [uuid] si elle est dans [level] : un changement de dimension recrée l'entité et garde son UUID. */
    fun entityIn(level: ServerLevel, uuid: UUID): Entity? = level.allEntities.firstOrNull { it.uuid == uuid }
}
