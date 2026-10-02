// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.registry

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.gameversion.GameVersion
import fr.roumoulou.travellingdimension.portal.TravelPortalBlock
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * Blocs du mod. Le bloc de portail n'a volontairement pas de BlockItem
 * (comme le portail du Nether : non obtenable, non posable à la main).
 */
object ModBlocks {

    private val TRAVEL_PORTAL_KEY: ResourceKey<Block> = key("travel_portal")

    /** Bloc de portail de voyage : incassable, lumineux, sans collision ni loot. */
    val TRAVEL_PORTAL: TravelPortalBlock = register(
        TRAVEL_PORTAL_KEY,
        TravelPortalBlock(
            BlockBehaviour.Properties.of()
                .noCollision()
                .strength(-1.0f)
                .sound(SoundType.GLASS)
                .lightLevel { 11 }
                .pushReaction(GameVersion.bridge.immovablePushReaction)
                .noLootTable()
                .setId(TRAVEL_PORTAL_KEY)
        )
    )

    private fun key(path: String): ResourceKey<Block> =
        ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, path))

    private fun <T : Block> register(key: ResourceKey<Block>, block: T): T =
        Registry.register(BuiltInRegistries.BLOCK, key, block)

    /** Force l'initialisation de l'objet (et donc l'enregistrement) au bon moment. */
    fun init() {
        TravellingDimension.LOGGER.debug("Blocs enregistrés : {}", TRAVEL_PORTAL_KEY.identifier())
    }
}
