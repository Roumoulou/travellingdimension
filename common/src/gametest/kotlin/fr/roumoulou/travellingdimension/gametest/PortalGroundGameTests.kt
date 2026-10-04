// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gametest

import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.portal.PortalGround
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import fr.roumoulou.travellingdimension.registry.ModBlocks
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB

/**
 * Épargner ce qu'un joueur a bâti, dans un vrai serveur : le décalage devant une construction, le
 * veto de la redstone, le déménagement des coffres.
 *
 * Aucune traversée ici. Chaque test appelle [TravelPortalPlacer.build], la fonction que la
 * résolution appelle à son dernier rang, sur un point de l'OVERWORLD décoré au bloc, puis lit où le
 * portail est né. Le superflat de développement donne un décor sans hasard : de l'air en Y80, et
 * rien que les tests n'aient posé.
 *
 * ## L'emprise, de tête
 *
 * Pour un portail de 3x3 d'ancre (X, Y, Z) sur l'axe X, aux défauts : de X-4 à X+4, de Z-2 à Z+2, de
 * Y-1 à Y+5. Une marque posée 2 blocs au-dessus de l'ancre est donc dans l'emprise de sept
 * altitudes, de Y-3 à Y+3 : la première altitude libre est à 4 blocs, vers le haut comme vers le
 * bas, et à égalité le haut gagne.
 *
 * ## La fréquentation
 *
 * Le serveur GameTest n'a aucun joueur : le compteur de fréquentation de ses chunks reste à zéro, et
 * tout y passe pour de la génération naturelle. Un test qui veut un chunk fréquenté le dit lui-même,
 * en posant le compteur au-dessus du seuil.
 *
 * ## Les réglages
 *
 * `build` reçoit sa configuration : un test qui veut un autre réglage en passe une copie, et celle
 * du serveur, que tous les tests partagent, ne bouge pas.
 */
class PortalGroundGameTests {

    /** Une marque de construction décale le portail dans un chunk fréquenté ; dans un chunk vierge, ou au-delà de la limite, il naît sur place. */
    @GameTest(maxTicks = 200)
    fun playerBuildShiftsThePortal(helper: GameTestHelper) {
        val level = helper.level
        val config = ConfigManager.current
        val sector = Harness.sector(8)

        // Chunk fréquenté : la table est une marque, le portail monte de 4 blocs et l'épargne.
        val inhabited = spot(sector, 0)
        markInhabited(level, inhabited, config)
        val table = inhabited.above(2)
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL)
        helper.assertTrue(PortalGround.refuses(level, footprint(inhabited), config), "une table de craft dans un chunk fréquenté refuse le terrain")
        val shifted = TravelPortalPlacer.build(level, inhabited, Direction.Axis.X, 3, 3, config)
        helper.assertValueEqual(shifted.centre, inhabited.above(4), "le portail monte de 4 blocs : à égalité avec 4 blocs plus bas, le haut gagne")
        helper.assertTrue(level.getBlockState(table).`is`(Blocks.CRAFTING_TABLE), "la table est intacte")
        helper.assertTrue(TravelPortalPlacer.completePortalAt(level, shifted.centre) != null, "le portail décalé est complet")

        // Chunk vierge : la même table passe pour de la génération, le portail naît sur place et la remplace.
        val untouched = spot(sector, 1)
        level.setBlock(untouched.above(2), Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL)
        helper.assertTrue(PortalGround.untouched(level, footprint(untouched), config), "le chunk n'a vu aucun joueur")
        val inPlace = TravelPortalPlacer.build(level, untouched, Direction.Axis.X, 3, 3, config)
        helper.assertValueEqual(inPlace.centre, untouched, "dans un chunk vierge, le portail naît sur place")
        helper.assertTrue(level.getBlockState(untouched.above(2)).`is`(ModBlocks.TRAVEL_PORTAL), "la table a laissé la place au portail")

        // Chunk fréquenté, décalage borné à 1 bloc : l'altitude libre est à 4 blocs, hors d'atteinte, et le portail naît quand même.
        val bounded = spot(sector, 2)
        markInhabited(level, bounded, config)
        level.setBlock(bounded.above(2), Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL)
        val forced = TravelPortalPlacer.build(level, bounded, Direction.Axis.X, 3, 3, config.copy(buildShiftMaxOffset = 1))
        helper.assertValueEqual(forced.centre, bounded, "au-delà de la limite de décalage, le portail naît sur place")

        helper.succeed()
    }

    /** Huit blocs de redstone refusent le terrain quoi qu'il arrive : chunk vierge, protection coupée, limite de décalage dépassée. */
    @GameTest(maxTicks = 200)
    fun redstoneVetoOverridesTheOtherRules(helper: GameTestHelper) {
        val level = helper.level
        val config = ConfigManager.current
        val sector = Harness.sector(9)

        // Sept blocs, sous le seuil de huit : pas d'installation, et dans un chunk vierge le portail naît sur place.
        val below = spot(sector, 0)
        redstoneRow(level, below, 7)
        helper.assertFalse(PortalGround.hasRedstoneWorks(level, footprint(below), config), "sept blocs de redstone ne font pas une installation")
        val inPlace = TravelPortalPlacer.build(level, below, Direction.Axis.X, 3, 3, config)
        helper.assertValueEqual(inPlace.centre, below, "sous le seuil, le portail naît sur place")

        // Huit blocs, chunk vierge : le veto ignore la fréquentation, le portail monte de 4 blocs et la machine reste entière.
        val machine = spot(sector, 1)
        val row = redstoneRow(level, machine, 8)
        helper.assertTrue(PortalGround.untouched(level, footprint(machine), config), "le chunk n'a vu aucun joueur")
        helper.assertTrue(PortalGround.hasRedstoneWorks(level, footprint(machine), config), "huit blocs de redstone font une installation")
        val shifted = TravelPortalPlacer.build(level, machine, Direction.Axis.X, 3, 3, config)
        helper.assertValueEqual(shifted.centre, machine.above(4), "le veto ignore la fréquentation : le portail monte de 4 blocs")
        helper.assertTrue(row.all { level.getBlockState(it).`is`(Blocks.REDSTONE_BLOCK) }, "la machine est intacte")

        // Protection générale coupée, décalage borné à 1 bloc : le veto ignore les deux, et cherche sur toute la hauteur.
        val stubborn = spot(sector, 2)
        redstoneRow(level, stubborn, 8)
        val strict = config.copy(protectPlayerBuilds = false, buildShiftMaxOffset = 1)
        val beyond = TravelPortalPlacer.build(level, stubborn, Direction.Axis.X, 3, 3, strict)
        helper.assertValueEqual(beyond.centre, stubborn.above(4), "le veto ignore protectPlayerBuilds et buildShiftMaxOffset")

        helper.succeed()
    }

    /** Un coffre de l'emprise part à l'abri avant la pose : hors de l'emprise, dans le rayon, son contenu avec lui, et rien au sol. */
    @GameTest(maxTicks = 200)
    fun containersMoveToShelterBeforeBuilding(helper: GameTestHelper) {
        val level = helper.level
        val config = ConfigManager.current
        val anchor = spot(Harness.sector(10), 0)

        // Un sol de pierre sous l'ancre : un abri se pose sur un bloc qui porte, et l'air du superflat n'en offre pas.
        for (pos in BlockPos.betweenClosed(anchor.offset(-10, -1, -10), anchor.offset(10, -1, 10))) {
            level.setBlock(pos.immutable(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS)
        }
        level.setBlock(anchor, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL)
        val chest = level.getBlockEntity(anchor) as? Container ?: throw helper.assertionException("le coffre n'a pas de block entity")
        chest.setItem(0, ItemStack(Items.DIAMOND, 5))

        val built = TravelPortalPlacer.build(level, anchor, Direction.Axis.X, 3, 3, config)
        helper.assertValueEqual(built.centre, anchor, "chunk vierge : le coffre ne décale pas le portail")
        helper.assertTrue(level.getBlockState(anchor).`is`(ModBlocks.TRAVEL_PORTAL), "le coffre a laissé la place au portail")

        val radius = config.rescueRadius
        val shelters = BlockPos.betweenClosed(anchor.offset(-radius, -radius, -radius), anchor.offset(radius, radius, radius))
            .map { it.immutable() }
            .filter { level.getBlockEntity(it) is Container }
        helper.assertValueEqual(shelters.size, 1, "un coffre, et un seul, dans le rayon de $radius blocs")
        val shelter = shelters.single()
        helper.assertFalse(footprint(anchor).isInside(shelter), "l'abri est hors de l'emprise du portail, en ${shelter.toShortString()}")
        val moved = (level.getBlockEntity(shelter) as Container).getItem(0)
        helper.assertTrue(moved.`is`(Items.DIAMOND) && moved.count == 5, "les cinq diamants ont suivi le coffre")
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity::class.java, AABB(anchor).inflate(16.0)).isEmpty(), "rien n'est tombé au sol")

        helper.succeed()
    }

    /** Un point d'essai du secteur : au milieu d'un chunk, pour que l'emprise tienne dans un seul, et à deux chunks du précédent. */
    private fun spot(sector: BlockPos, index: Int): BlockPos = sector.offset(8, 0, 8 + index * 32)

    /** L'emprise d'un portail de 3x3 d'ancre [anchor] sur l'axe X, aux défauts, recalculée de tête comme le dit la note de classe. */
    private fun footprint(anchor: BlockPos): BoundingBox =
        BoundingBox(anchor.x - 4, anchor.y - 1, anchor.z - 2, anchor.x + 4, anchor.y + 5, anchor.z + 2)

    /** Pose le compteur de fréquentation du chunk de [pos] juste au-dessus du seuil : aucun joueur n'est là pour le faire. */
    private fun markInhabited(level: ServerLevel, pos: BlockPos, config: TravelConfig) {
        level.getChunk(pos).inhabitedTime = config.inhabitedThreshold + 1
    }

    /** Une rangée de [count] blocs de redstone dans l'emprise du portail d'ancre [anchor], 2 blocs au-dessus de l'ancre et 2 blocs sur le côté. */
    private fun redstoneRow(level: ServerLevel, anchor: BlockPos, count: Int): List<BlockPos> {
        val row = (0 until count).map { anchor.offset(-4 + it, 2, 2) }
        row.forEach { level.setBlock(it, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL) }
        return row
    }
}
