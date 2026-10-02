// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.registry

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.item.AmethystIgniterItem
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item

/**
 * Items du mod.
 */
object ModItems {

    private val AMETHYST_IGNITER_KEY: ResourceKey<Item> = key("amethyst_igniter")

    /** Allume-portail : durabilité type briquet, progression mid/late game via la recette. */
    val AMETHYST_IGNITER: AmethystIgniterItem = register(
        AMETHYST_IGNITER_KEY,
        AmethystIgniterItem(
            Item.Properties()
                .durability(64)
                .setId(AMETHYST_IGNITER_KEY)
        )
    )

    private fun key(path: String): ResourceKey<Item> =
        ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, path))

    private fun <T : Item> register(key: ResourceKey<Item>, item: T): T =
        Registry.register(BuiltInRegistries.ITEM, key, item)

    fun init() {
        // Rangé avec les outils, juste après le briquet dans l'esprit.
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register { output ->
            output.accept(AMETHYST_IGNITER)
        }
        TravellingDimension.LOGGER.debug("Items enregistrés : {}", AMETHYST_IGNITER_KEY.identifier())
    }
}
